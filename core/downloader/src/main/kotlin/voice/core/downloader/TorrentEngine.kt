package voice.core.downloader

import java.io.File

/** A file inside a torrent, addressed by the index libtorrent knows it under. */
data class TorrentFile(
  val index: Int,
  /** Path inside the torrent, like `Book/Chapter 01.mp3`. */
  val path: String,
  val sizeBytes: Long,
)

/** A torrent whose metadata is known: its name and the files it carries. */
class ResolvedTorrent(
  val infoHash: String,
  val magnetUri: String,
  val files: List<TorrentFile>,
  /** The .torrent file the metadata was cached to, for fast resumes. */
  val metadataFile: File,
) {
  /** The audio files of the torrent, junk like nfo files and covers left out. */
  val audioFiles: List<TorrentFile> = files.filter { it.path.isAudioFile() }

  val audioBytes: Long = audioFiles.sumOf { it.sizeBytes }
}

data class TorrentStats(
  val doneBytes: Long,
  val totalBytes: Long,
  val downloadRateBytes: Long,
  val seeds: Int,
  val peers: Int,
)

/** Resolves torrent metadata and downloads torrents into a folder of the file system. */
interface TorrentEngine {

  /**
   * Fetches the metadata of the magnet or .torrent file. Uses the cached metadata file when it
   * exists, so a resume doesn't ask the swarm again. Throws when no peer offers the metadata.
   */
  suspend fun resolve(
    ref: TorrentRef,
    cacheDir: File,
  ): ResolvedTorrent

  /**
   * Downloads the given files of the torrent into [saveDir]. Reports stats until all files are
   * there. Throws when the download can't continue; cooperative cancellation stops the download and
   * leaves the files it already wrote, so a resume rechecks and continues.
   */
  suspend fun download(
    torrent: ResolvedTorrent,
    saveDir: File,
    files: List<TorrentFile>,
    onStats: suspend (TorrentStats) -> Unit,
  )
}

/** Audiobooks ship with junk like .nfo, .txt and .jpg files; only the audio is wanted. */
internal fun String.isAudioFile(): Boolean =
  when (substringAfterLast('.', "").lowercase()) {
    "mp3",
    "m4a",
    "m4b",
    "ogg",
    "oga",
    "opus",
    "flac",
    "wav",
    "aac",
    "wma" -> true
    else -> false
  }
