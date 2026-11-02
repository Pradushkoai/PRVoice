package voice.core.downloader

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadState {
  QUEUED,
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

/** Why a download failed. Stored as a code so it can be localized and never contains a url. */
@Serializable
enum class DownloadError {
  NETWORK,
  CHANGED,
  NO_FOLDER,
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
  val files: List<DownloadFile>,
  val state: DownloadState = DownloadState.QUEUED,
  val completedFiles: Int = 0,
  /** Uri of the folder the files are written to, once the download started. */
  val folderUri: String? = null,
  val error: DownloadError? = null,
  val addedAtEpochMillis: Long,
) {
  val finishedFraction: Float
    get() = if (files.isEmpty()) 0F else completedFiles.toFloat() / files.size
}

/** A download together with the ephemeral progress of the file it currently writes. */
data class DownloadWithProgress(
  val download: BookDownload,
  /** 0..1 progress of the current file, or null while no file is being written. */
  val currentFileFraction: Float? = null,
) {
  val fraction: Float
    get() {
      val files = download.files.size
      if (files == 0) return 0F
      return (download.completedFiles + (currentFileFraction ?: 0F)) / files
    }
}
