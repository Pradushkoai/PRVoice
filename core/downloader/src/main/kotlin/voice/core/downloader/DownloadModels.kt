package voice.core.downloader

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadState {
  QUEUED,
  /** A torrent whose file list is still being fetched from the swarm. */
  RESOLVING,
  RUNNING,
  PAUSED,
  FAILED,
  DONE,
}

/** One audio file of a book, fetched from the url and stored under [name]. */
@Serializable
data class DownloadFile(
  val url: String,
  /** File name without extension, e.g. `01 - Глава_01`. */
  val name: String,
) {
  val fileName: String
    get() {
      val clean = url.substringBefore('?').substringBefore('#')
      val extension =
        clean.substringAfterLast('.', "").takeIf { it.length in 2..4 }?.lowercase() ?: "mp3"
      return "$name.$extension"
    }
}

/** How a torrent download is addressed: by magnet uri or by the url of a .torrent file. */
@Serializable
data class TorrentRef(
  val magnetUri: String? = null,
  val torrentUrl: String? = null,
) {
  init {
    require(magnetUri != null || torrentUrl != null) { "A torrent needs a magnet or a torrent url" }
  }
}

/** Why a download failed. Stored as a code so it can be localized and never contains a url. */
@Serializable
enum class DownloadError {
  NETWORK,
  CHANGED,
  NO_FOLDER,
  NO_METADATA,
  UNKNOWN,
}

@Serializable
data class BookDownload(
  val id: String,
  val sourceId: String,
  val sourceName: String,
  val title: String,
  val author: String? = null,
  val narrator: String? = null,
  val coverUrl: String? = null,
  val files: List<DownloadFile> = emptyList(),
  /** The torrent of this download, when it comes from a tracker instead of plain http files. */
  val torrent: TorrentRef? = null,
  /** The relative paths inside the torrent, known once the metadata was fetched. */
  val torrentFiles: List<String> = emptyList(),
  val state: DownloadState = DownloadState.QUEUED,
  val completedFiles: Int = 0,
  /** Bytes of a torrent download; known once the metadata was fetched. */
  val totalBytes: Long? = null,
  /** Bytes of a torrent download that are already there. */
  val progressBytes: Long = 0,
  /** Uri of the folder the files are written to, once the download started. */
  val folderUri: String? = null,
  val error: DownloadError? = null,
  val addedAtEpochMillis: Long,
) {
  val finishedFraction: Float
    get() =
      when {
        torrent != null -> totalBytes?.takeIf { it > 0 }?.let { progressBytes.toFloat() / it } ?: 0F
        files.isEmpty() -> 0F
        else -> completedFiles.toFloat() / files.size
      }
}

/** A download together with the ephemeral progress of the file it currently writes. */
data class DownloadWithProgress(
  val download: BookDownload,
  /** 0..1 progress of the current file, or null while no file is being written. */
  val currentFileFraction: Float? = null,
  /** Live peers and seeds of a running torrent download. */
  val seeds: Int? = null,
  val peers: Int? = null,
) {
  val fraction: Float
    get() {
      if (download.torrent != null) return download.finishedFraction
      val files = download.files.size
      if (files == 0) return 0F
      return (download.completedFiles + (currentFileFraction ?: 0F)) / files
    }
}
