package voice.core.downloader

import java.io.OutputStream

/** Writes the files of one book into its folder of the listener's library. */
interface BookFolderWriter {

  /** The folder was deleted or its grant vanished. */
  fun stillExists(): Boolean

  fun exists(fileName: String): Boolean

  fun size(fileName: String): Long

  fun delete(fileName: String)

  fun outputStream(fileName: String): OutputStream
}

/** The folder a book downloads into, addressed by the uri the SAF provider handed out. */
data class BookDownloadFolder(
  val uri: String,
  val writer: BookFolderWriter,
)

/** Opens or creates the folder of a book download inside the library. */
interface DownloadFolderProvider {

  /**
   * Reuses the persisted folder of the download when it still exists, otherwise creates a new
   * folder for the book. Throws a [NoWritableLibraryFolder] when the library has no folder the
   * downloads could be written to.
   */
  suspend fun openFolder(download: BookDownload): BookDownloadFolder
}

class NoWritableLibraryFolder : Exception()
