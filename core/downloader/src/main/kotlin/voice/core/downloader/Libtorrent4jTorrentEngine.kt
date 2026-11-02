package voice.core.downloader

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import voice.core.booksources.SourceHttpClient
import voice.core.logging.api.Logger

/** Raised when no peer offered the torrent metadata, or the torrent holds no audio files. */
class NoMetadata(cause: Throwable? = null) : Exception(cause)

/**
 * Downloads torrents through libtorrent4j. One session for the whole app; each download goes to a
 * directory of the app, from where the manager copies the files into the library.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class Libtorrent4jTorrentEngine(
  @SourceHttpClient private val httpClient: OkHttpClient,
) : TorrentEngine {

  private val session: SessionManager =
    SessionManager().apply {
      start()
    }

  override suspend fun resolve(
    ref: TorrentRef,
    cacheDir: File,
  ): ResolvedTorrent =
    withContext(Dispatchers.IO) {
      val metadataFile = File(cacheDir, METADATA_FILE)
      val bytes =
        when {
          metadataFile.isFile -> metadataFile.readBytes()
          ref.torrentUrl != null ->
            fetchTorrentFile(ref.torrentUrl)?.also { cacheMetadata(cacheDir, it) }
          else -> fetchMetadataFromSwarm(ref.magnetUri!!, cacheDir)
        } ?: throw NoMetadata()
      torrentOf(ref, cacheDir, bytes)
    }

  override suspend fun download(
    torrent: ResolvedTorrent,
    saveDir: File,
    files: List<TorrentFile>,
    onStats: suspend (TorrentStats) -> Unit,
  ) =
    withContext(Dispatchers.IO) {
      val info = TorrentInfo.bdecode(torrent.metadataFile.readBytes())
      session.download(info, saveDir.absoluteFile)
      val handle: TorrentHandle? = session.find(Sha1Hash.parseHex(torrent.infoHash))
      if (handle == null || !handle.isValid) {
        throw IOException("The torrent did not start")
      }
      try {
        // only the selected files are wanted, the rest of the torrent stays out
        val selected = files.map { it.index }.toSet()
        val priorities =
          Array(info.files().numFiles()) { index ->
            if (index in selected) Priority.DEFAULT else Priority.IGNORE
          }
        handle.prioritizeFiles(priorities)
        // a resume rechecks what earlier runs already wrote, so it continues instead of restarting
        handle.forceRecheck()
        // the first chapter arrives first, so listening can start while the rest downloads
        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)

        while (true) {
          coroutineContext.ensureActive()
          delay(POLL_MILLIS)
          val status = handle.status()
          onStats(
            TorrentStats(
              doneBytes = status.totalWantedDone(),
              totalBytes = status.totalWanted(),
              downloadRateBytes = status.downloadPayloadRate().toLong(),
              seeds = status.numSeeds(),
              peers = status.numPeers(),
            ),
          )
          if (status.isSeeding) break
        }
      } finally {
        // keeps the files written so far; a resume rechecks them
        runCatching { session.remove(handle) }
          .onFailure {
            Logger.w("Could not remove the torrent from the session: ${it::class.simpleName}")
          }
      }
    }

  private fun cacheMetadata(
    cacheDir: File,
    bytes: ByteArray,
  ) {
    try {
      cacheDir.mkdirs()
      File(cacheDir, METADATA_FILE).writeBytes(bytes)
    } catch (exception: IOException) {
      Logger.w("Could not cache the torrent metadata: ${exception::class.simpleName}")
    }
  }

  private fun torrentOf(
    ref: TorrentRef,
    cacheDir: File,
    bytes: ByteArray,
  ): ResolvedTorrent {
    val info =
      try {
        TorrentInfo.bdecode(bytes)
      } catch (exception: Exception) {
        throw NoMetadata(exception)
      }
    val files = info.files()
    val torrentFiles =
      (0 until files.numFiles()).map { index ->
        TorrentFile(
          index = index,
          path = files.filePath(index),
          sizeBytes = files.fileSize(index),
        )
      }
    return ResolvedTorrent(
      infoHash = info.infoHash().toHex(),
      magnetUri = ref.magnetUri ?: "",
      files = torrentFiles,
      metadataFile = File(cacheDir, METADATA_FILE),
    )
  }

  /** Downloads the .torrent file from its url. */
  private fun fetchTorrentFile(url: String): ByteArray? =
    try {
      httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (response.isSuccessful) {
          response.body.bytes()
        } else {
          null
        }
      }
    } catch (exception: IOException) {
      Logger.w("Fetching the torrent file failed with ${exception::class.simpleName}")
      null
    }

  /**
   * Asks the swarm for the metadata of the magnet uri, waiting for peers to offer it. libtorrent
   * writes the metadata it received to the cache file itself.
   */
  private fun fetchMetadataFromSwarm(
    magnetUri: String,
    cacheDir: File,
  ): ByteArray? =
    try {
      cacheDir.mkdirs()
      session.fetchMagnet(magnetUri, MAGNET_TIMEOUT_SECONDS, File(cacheDir, METADATA_FILE))
    } catch (exception: Exception) {
      Logger.w("Fetching the magnet metadata failed with ${exception::class.simpleName}")
      null
    }

  private companion object {
    const val METADATA_FILE = "metadata.torrent"
    const val MAGNET_TIMEOUT_SECONDS = 60
    const val POLL_MILLIS = 500L
  }
}
