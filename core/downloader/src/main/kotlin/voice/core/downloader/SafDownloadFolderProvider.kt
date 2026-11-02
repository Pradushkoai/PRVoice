package voice.core.downloader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import voice.core.data.folders.AudiobookFolders
import voice.core.data.folders.FolderType
import voice.core.logging.api.Logger

/**
 * Writes the downloads into a folder of the listener's library through the storage access
 * framework. Root and author folders group the books by author, single book folders hold the book
 * folder directly.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class SafDownloadFolderProvider(
  private val audiobookFolders: AudiobookFolders,
  private val context: Context,
) : DownloadFolderProvider {

  override suspend fun openFolder(download: BookDownload): BookDownloadFolder =
    withContext(Dispatchers.IO) {
      download.folderUri?.let { persisted ->
        val writer = SafBookFolderWriter(context, Uri.parse(persisted))
        if (writer.stillExists()) {
          return@withContext BookDownloadFolder(persisted, writer)
        }
        Logger.i("The folder of a download vanished, creating a new one")
      }
      val root = libraryRoot() ?: throw NoWritableLibraryFolder()
      val (rootUri, folderType) = root
      val folderUri = SafDownloadTarget(context, rootUri, folderType).createBookFolder(download)
      BookDownloadFolder(folderUri.toString(), SafBookFolderWriter(context, folderUri))
    }

  /** Downloads go into the first root, author or single book folder of the library. */
  private suspend fun libraryRoot(): Pair<Uri, FolderType>? {
    val folders = audiobookFolders.all().first()
    for (folderType in listOf(FolderType.Root, FolderType.Author, FolderType.SingleFolder)) {
      folders[folderType]?.firstOrNull()?.let {
        return it.uri to folderType
      }
    }
    return null
  }
}

/** Creates the folder structure of one book below a library folder. */
internal class SafDownloadTarget(
  private val context: Context,
  treeUri: Uri,
  private val folderType: FolderType,
) {

  private val rootDocumentUri: Uri =
    DocumentsContract.buildDocumentUriUsingTree(
      treeUri,
      DocumentsContract.getTreeDocumentId(treeUri),
    )

  fun createBookFolder(download: BookDownload): Uri {
    var directory = rootDocumentUri
    if (folderType == FolderType.Root || folderType == FolderType.Author) {
      directory =
        findOrCreateDirectory(directory, folderName(download.author ?: download.sourceName))
    }
    return findOrCreateDirectory(directory, folderName(download.title))
  }

  private fun findOrCreateDirectory(parent: Uri, name: String): Uri {
    findChild(parent, name)?.let {
      return it
    }
    return DocumentsContract.createDocument(
      context.contentResolver,
      parent,
      DocumentsContract.Document.MIME_TYPE_DIR,
      name,
    ) ?: throw IOException("Could not create the download folder")
  }

  private fun findChild(parent: Uri, name: String): Uri? {
    val childrenUri =
      DocumentsContract.buildChildDocumentsUriUsingTree(
        parent,
        DocumentsContract.getDocumentId(parent),
      )
    context.contentResolver
      .query(
        childrenUri,
        arrayOf(
          DocumentsContract.Document.COLUMN_DOCUMENT_ID,
          DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        ),
        null,
        null,
        null,
      )
      ?.use { cursor ->
        while (cursor.moveToNext()) {
          if (cursor.getString(1) == name) {
            val documentId = cursor.getString(0)
            return DocumentsContract.buildDocumentUriUsingTree(parent, documentId)
          }
        }
      }
    return null
  }

  private fun folderName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|]"""), "").trim().take(MAX_FOLDER_NAME_LENGTH).ifEmpty {
      "book"
    }

  private companion object {
    const val MAX_FOLDER_NAME_LENGTH = 100
  }
}

/** Writes the audio files of a book through the storage access framework. */
internal class SafBookFolderWriter(
  private val context: Context,
  private val folderUri: Uri,
) : BookFolderWriter {

  override fun stillExists(): Boolean = documentUri() != null

  override fun exists(fileName: String): Boolean = fileUri(fileName) != null

  override fun size(fileName: String): Long {
    val uri = fileUri(fileName) ?: return 0
    return context.contentResolver
      .query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
      ?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else 0
      } ?: 0
  }

  override fun delete(fileName: String) {
    val uri = fileUri(fileName) ?: return
    try {
      DocumentsContract.deleteDocument(context.contentResolver, uri)
    } catch (exception: Exception) {
      // the throwable of a SAF call can carry the document uri, so only log its type
      Logger.w("Could not delete a partially downloaded file: ${exception::class.simpleName}")
    }
  }

  override fun outputStream(fileName: String): java.io.OutputStream {
    // a failed attempt may have left half a file behind, so rewrite it from scratch
    delete(fileName)
    val uri =
      DocumentsContract.createDocument(
        context.contentResolver,
        folderUri,
        mimeOf(fileName),
        fileName,
      ) ?: throw IOException("Could not create a file for the download")
    return context.contentResolver.openOutputStream(uri, "w")
      ?: throw IOException("Could not open a file for the download")
  }

  private fun documentUri(): Uri? =
    try {
      context.contentResolver
        .query(
          folderUri,
          arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
          null,
          null,
          null,
        )
        ?.use { cursor ->
          if (cursor.moveToFirst()) {
            DocumentsContract.buildDocumentUriUsingTree(folderUri, cursor.getString(0))
          } else {
            null
          }
        }
    } catch (_: Exception) {
      null
    }

  private fun fileUri(fileName: String): Uri? {
    val childrenUri =
      DocumentsContract.buildChildDocumentsUriUsingTree(
        folderUri,
        DocumentsContract.getDocumentId(folderUri),
      )
    return try {
      context.contentResolver
        .query(
          childrenUri,
          arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
          ),
          null,
          null,
          null,
        )
        ?.use { cursor ->
          while (cursor.moveToNext()) {
            if (cursor.getString(1) == fileName) {
              return DocumentsContract.buildDocumentUriUsingTree(folderUri, cursor.getString(0))
            }
          }
          null
        }
    } catch (_: Exception) {
      null
    }
  }

  private fun mimeOf(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase()) {
      "mp3" -> "audio/mpeg"
      "m4a",
      "m4b" -> "audio/mp4"
      "ogg" -> "audio/ogg"
      "opus" -> "audio/opus"
      "flac" -> "audio/flac"
      "wav" -> "audio/x-wav"
      else -> "application/octet-stream"
    }
}
