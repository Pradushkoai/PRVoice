package voice.core.downloader

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** A [BookFolderWriter] over a plain directory, for tests. */
open class FileBookFolderWriter(private val directory: File) : BookFolderWriter {

  override fun stillExists(): Boolean = directory.isDirectory

  override fun exists(fileName: String): Boolean = File(directory, fileName).isFile

  override fun size(fileName: String): Long = File(directory, fileName).length()

  override fun delete(fileName: String) {
    File(directory, fileName).delete()
  }

  override fun outputStream(fileName: String): OutputStream =
    FileOutputStream(File(directory, fileName))

  fun fileContent(fileName: String): String = File(directory, fileName).readText()

  fun files(): List<String> = directory.listFiles()?.map { it.name } ?: emptyList()
}
