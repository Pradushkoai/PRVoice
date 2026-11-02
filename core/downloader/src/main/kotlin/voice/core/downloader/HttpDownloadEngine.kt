package voice.core.downloader

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import voice.core.booksources.SourceHttpClient

/** Downloads the files of a book one after another into its folder. */
interface DownloadEngine {

  /**
   * Downloads the files from [BookDownload.completedFiles] on. Files before that are skipped, so a
   * paused download resumes at its current file. Reports each finished file and the bytes of the
   * file it currently writes. Throws when the download can't continue.
   */
  suspend fun download(
    download: BookDownload,
    folder: BookFolderWriter,
    onFileFinished: suspend (completedFiles: Int) -> Unit,
    onFileProgress: (bytes: Long, totalBytes: Long) -> Unit,
  )
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class HttpDownloadEngine(
  @SourceHttpClient private val client: OkHttpClient,
) : DownloadEngine {

  override suspend fun download(
    download: BookDownload,
    folder: BookFolderWriter,
    onFileFinished: suspend (completedFiles: Int) -> Unit,
    onFileProgress: (bytes: Long, totalBytes: Long) -> Unit,
  ) =
    withContext(Dispatchers.IO) {
      val files = download.files
      for (index in download.completedFiles until files.size) {
        coroutineContext.ensureActive()
        val file = files[index]
        val request = Request.Builder().url(file.url).build()
        try {
          client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected status ${response.code}")
            val body = response.body
            val totalBytes = body.contentLength()
            var written = 0L
            var lastReport = 0L
            val output = folder.outputStream(file.fileName)
            body.byteStream().use { input ->
              output.use {
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                  val read = input.read(buffer)
                  if (read == -1) break
                  it.write(buffer, 0, read)
                  written += read
                  if (written - lastReport >= REPORT_EVERY_BYTES) {
                    lastReport = written
                    onFileProgress(written, totalBytes)
                  }
                }
                it.flush()
              }
            }
          }
        } catch (exception: IOException) {
          // half a file would look complete to the library, so drop it before failing
          folder.delete(file.fileName)
          throw exception
        }
        onFileFinished(index + 1)
      }
    }

  private companion object {
    const val BUFFER_SIZE = 64 * 1024
    const val REPORT_EVERY_BYTES = 512 * 1024L
  }
}
