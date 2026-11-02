package voice.core.downloader

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import voice.core.booksources.BookInfo
import voice.core.booksources.DownloadSpec
import voice.core.booksources.SourceChanged
import voice.core.booksources.SourceUnavailable
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
  private val mediaScanTrigger: MediaScanTrigger,
  private val notifications: DownloadNotifications,
) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  private val persisted = MutableStateFlow<List<BookDownload>>(emptyList())

  /** Bytes and total of the file each download currently writes. */
  private val fileProgress = MutableStateFlow<Map<String, Pair<Long, Long>>>(emptyMap())

  val downloads: StateFlow<List<DownloadWithProgress>> = run {
    val flow = MutableStateFlow<List<DownloadWithProgress>>(emptyList())
    scope.launch {
      combine(persisted, fileProgress) { downloads, progress ->
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
    val files = info.files as? DownloadSpec.HttpFiles ?: return
    val result = info.result
    val download =
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
            it.id == id && (it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING)
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
        return@launch
      }
      runningIds.update { it + next.id }
      var current = next.copy(state = DownloadState.RUNNING, error = null)
      try {
        val folder = folderProvider.openFolder(current)
        if (current.folderUri == null) {
          current = current.copy(folderUri = folder.uri)
        }
        persist(current)
        notifications.showProgress(current)
        engine.download(
          download = current,
          folder = folder.writer,
          onFileFinished = { completedFiles ->
            current = current.copy(completedFiles = completedFiles)
            persist(current)
            notifications.showProgress(current)
          },
          onFileProgress = { bytes, total ->
            fileProgress.update { it + (current.id to (bytes to total)) }
          },
        )
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
        runningIds.update { it - current.id }
        // the worker is still active inside its own finally block, so the next one starts detached
        scope.launch { startNext() }
      }
    }
  }

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
      is IOException -> DownloadError.NETWORK
      else -> DownloadError.UNKNOWN
    }

  companion object {
    /** The stable id of the download of a search result. */
    fun downloadIdOf(result: voice.core.booksources.SearchResult): String =
      "${result.sourceId}|${result.url}"
  }
}
