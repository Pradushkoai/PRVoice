package voice.core.downloader

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import voice.core.booksources.BookInfo
import voice.core.booksources.DownloadSpec
import voice.core.booksources.SourceChanged
import voice.core.booksources.SourceUnavailable
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.logging.api.Logger
import voice.core.scanner.MediaScanTrigger

/**
 * Runs the download queue: one book at a time, sequentially file by file. The queue is persisted,
 * so it survives restarts; a download that ran when the app died is queued again on start.
 */
@SingleIn(AppScope::class)
@Inject
class BookDownloadManager(
  private val store: BookDownloadStore,
  private val folderProvider: DownloadFolderProvider,
  private val engine: DownloadEngine,
  private val torrentEngine: TorrentEngine,
  private val mediaScanTrigger: MediaScanTrigger,
  private val notifications: DownloadNotifications,
  private val context: Application,
  dispatcherProvider: DispatcherProvider,
) {

  private val scope = MainScope(dispatcherProvider)
  private val ioContext = dispatcherProvider.io

  private val persisted = MutableStateFlow<List<BookDownload>>(emptyList())

  /** Bytes and total of the file each download currently writes. */
  private val fileProgress = MutableStateFlow<Map<String, Pair<Long, Long>>>(emptyMap())

  /** Live peers and seeds of the running torrent download. */
  private val torrentStats = MutableStateFlow<Map<String, TorrentStats>>(emptyMap())

  val downloads: StateFlow<List<DownloadWithProgress>> = run {
    val flow = MutableStateFlow<List<DownloadWithProgress>>(emptyList())
    scope.launch {
      combine(persisted, fileProgress, torrentStats) { downloads, progress, stats ->
          downloads.map { download ->
            DownloadWithProgress(
              download = download,
              currentFileFraction =
                progress[download.id]?.let { (bytes, total) ->
                  if (total > 0) {
                    (bytes.toDouble() / total).toFloat().coerceIn(0F, 1F)
                  } else {
                    null
                  }
                },
              seeds = stats[download.id]?.seeds,
              peers = stats[download.id]?.peers,
            )
          }
        }
        .collect { flow.value = it }
    }
    flow
  }

  private val runningIds = MutableStateFlow<Set<String>>(emptySet())

  private var worker: Job? = null

  private var started = false

  /** Called on app start; re-queues downloads that ran when the app died and starts the pump. */
  fun start() {
    if (started) return
    started = true
    scope.launch {
      store.updateData { downloads ->
        downloads.map {
          if (it.state == DownloadState.RUNNING) it.copy(state = DownloadState.QUEUED) else it
        }
      }
      var firstValue = true
      store.data.collect { value ->
        persisted.value = value
        if (firstValue) {
          firstValue = false
          startNext()
        }
      }
    }
  }

  fun enqueue(
    info: BookInfo,
    sourceName: String,
  ) {
    val result = info.result
    val download =
      when (val files = info.files) {
        is DownloadSpec.HttpFiles ->
          BookDownload(
            id = downloadIdOf(result),
            sourceId = result.sourceId,
            sourceName = sourceName,
            title = result.title,
            author = result.author,
            narrator = result.narrator,
            coverUrl = result.coverUrl,
            files = files.tracks.map { DownloadFile(url = it.url, name = it.name) },
            addedAtEpochMillis = System.currentTimeMillis(),
          )
        is DownloadSpec.Torrent ->
          BookDownload(
            id = downloadIdOf(result),
            sourceId = result.sourceId,
            sourceName = sourceName,
            title = result.title,
            author = result.author,
            narrator = result.narrator,
            coverUrl = result.coverUrl,
            torrent = TorrentRef(magnetUri = files.magnetUri, torrentUrl = files.torrentUrl),
            addedAtEpochMillis = System.currentTimeMillis(),
          )
      }
    scope.launch {
      store.updateData { downloads ->
        if (downloads.any { it.id == download.id }) {
          downloads
        } else {
          downloads + download
        }
      }
      startNext()
    }
  }

  fun pause(id: String) {
    if (id in runningIds.value) {
      worker?.cancel()
    }
    scope.launch {
      store.updateData { downloads ->
        downloads.map {
          if (
            it.id == id &&
              (it.state == DownloadState.QUEUED ||
                it.state == DownloadState.RUNNING ||
                it.state == DownloadState.RESOLVING)
          ) {
            it.copy(state = DownloadState.PAUSED)
          } else {
            it
          }
        }
      }
      startNext()
    }
  }

  fun resume(id: String) {
    scope.launch {
      store.updateData { downloads ->
        downloads.map {
          if (
            it.id == id && (it.state == DownloadState.PAUSED || it.state == DownloadState.FAILED)
          ) {
            it.copy(state = DownloadState.QUEUED, error = null)
          } else {
            it
          }
        }
      }
      startNext()
    }
  }

  fun delete(id: String) {
    if (id in runningIds.value) {
      worker?.cancel()
    }
    scope.launch {
      fileProgress.update { it - id }
      store.updateData { downloads -> downloads.filterNot { it.id == id } }
      startNext()
    }
  }

  private fun startNext() {
    if (worker?.isActive == true) return
    worker = scope.launch {
      // read the store instead of the mirrored state: a just enqueued download may not have
      // reached the mirror yet
      val next = store.data.first().firstOrNull { it.state == DownloadState.QUEUED }
      if (next == null) {
        notifications.hideProgress()
        BookDownloadService.stop(context)
        return@launch
      }
      BookDownloadService.start(context)
      runningIds.update { it + next.id }
      var current = next.copy(state = DownloadState.RUNNING, error = null)
      try {
        if (current.torrent != null) {
          current = downloadTorrent(current)
        } else {
          current = downloadHttp(current)
        }
        current = current.copy(state = DownloadState.DONE)
        persist(current)
        notifications.finished(current)
        // the library only knows the book once it scanned the new folder
        mediaScanTrigger.scan(restartIfScanning = true)
      } catch (exception: CancellationException) {
        throw exception
      } catch (exception: Exception) {
        // the message of an http or SAF exception can contain urls, so only its type is logged
        Logger.w("A book download failed with ${exception::class.simpleName}")
        current = current.copy(state = DownloadState.FAILED, error = errorOf(exception))
        persist(current)
        notifications.failed(current)
      } finally {
        fileProgress.update { it - current.id }
        torrentStats.update { it - current.id }
        runningIds.update { it - current.id }
        // the worker is still active inside its own finally block, so the next one starts detached
        scope.launch { startNext() }
      }
    }
  }

  /** Downloads the files of a book one by one straight into its library folder. */
  private suspend fun downloadHttp(current: BookDownload): BookDownload {
    var download = current
    val folder = folderProvider.openFolder(download)
    if (download.folderUri == null) {
      download = download.copy(folderUri = folder.uri)
    }
    persist(download)
    notifications.showProgress(download)
    engine.download(
      download = download,
      folder = folder.writer,
      onFileFinished = { completedFiles ->
        download = download.copy(completedFiles = completedFiles)
        persist(download)
        notifications.showProgress(download)
      },
      onFileProgress = { bytes, total ->
        fileProgress.update { it + (download.id to (bytes to total)) }
      },
    )
    return download.copy(completedFiles = download.files.size)
  }

  /**
   * Resolves the torrent metadata, downloads the audio files into a directory of the app and copies
   * them into the library folder from there.
   */
  private suspend fun downloadTorrent(current: BookDownload): BookDownload {
    var download = current.copy(state = DownloadState.RESOLVING)
    persist(download)
    notifications.showProgress(download)

    val cacheDir = torrentCacheDir(download.id)
    val resolved = torrentEngine.resolve(download.torrent!!, cacheDir)
    if (resolved.audioFiles.isEmpty()) throw NoMetadata()

    download =
      download.copy(
        torrentFiles = resolved.audioFiles.map { it.path },
        totalBytes = resolved.audioBytes,
        progressBytes = 0,
      )
    persist(download)

    download = download.copy(state = DownloadState.RUNNING)
    persist(download)

    val saveDir = torrentSaveDir(download.id)
    var lastPersist = 0L
    torrentEngine.download(
      torrent = resolved,
      saveDir = saveDir,
      files = resolved.audioFiles,
      onStats = { stats ->
        torrentStats.update { it + (download.id to stats) }
        // the byte progress is only persisted occasionally; the flow carries it live
        val now = System.currentTimeMillis()
        if (now - lastPersist >= STATS_PERSIST_MILLIS) {
          lastPersist = now
          download = download.copy(progressBytes = stats.doneBytes)
          persist(download)
          notifications.showProgress(download)
        }
      },
    )
    torrentStats.update { it - download.id }

    // the library folder is a SAF document, so the finished files move over stream by stream
    val folder = folderProvider.openFolder(download)
    if (download.folderUri == null) {
      download = download.copy(folderUri = folder.uri)
    }
    withContext(ioContext) {
      resolved.audioFiles.forEach { file ->
        val source = File(saveDir, file.path)
        // torrents carry their files in folders, while the library wants one folder per book
        val fileName = file.path.flattenedFileName()
        folder.writer.outputStream(fileName).use { output ->
          source.inputStream().use { input -> input.copyTo(output) }
        }
      }
    }
    saveDir.deleteRecursively()
    cacheDir.deleteRecursively()
    return download.copy(
      progressBytes = download.totalBytes ?: 0,
      completedFiles = resolved.audioFiles.size,
    )
  }

  /** A directory per download, derived from the id so resumes find their files again. */
  private fun torrentDir(
    id: String,
    kind: String,
  ): File = File(File(context.filesDir, "torrentDownloads/$kind"), abs(id.hashCode()).toString())

  private fun torrentCacheDir(id: String): File = torrentDir(id, "metadata")

  private fun torrentSaveDir(id: String): File = torrentDir(id, "files")

  private suspend fun persist(download: BookDownload) {
    store.updateData { downloads ->
      downloads.map { if (it.id == download.id) download else it }
    }
  }

  private fun errorOf(exception: Exception): DownloadError =
    when (exception) {
      is SourceUnavailable -> DownloadError.NETWORK
      is SourceChanged -> DownloadError.CHANGED
      is NoWritableLibraryFolder -> DownloadError.NO_FOLDER
      is NoMetadata -> DownloadError.NO_METADATA
      is IOException -> DownloadError.NETWORK
      else -> DownloadError.UNKNOWN
    }

  companion object {
    /** The stable id of the download of a search result. */
    fun downloadIdOf(result: voice.core.booksources.SearchResult): String =
      "${result.sourceId}|${result.url}"

    /** How often the byte progress of a torrent lands in the store. */
    private const val STATS_PERSIST_MILLIS = 3000L
  }
}

/** `Book/Chapter 01.mp3` becomes `Book - Chapter 01.mp3`: one folder per book in the library. */
internal fun String.flattenedFileName(): String = replace("/", " - ").replace("\\", " - ")
