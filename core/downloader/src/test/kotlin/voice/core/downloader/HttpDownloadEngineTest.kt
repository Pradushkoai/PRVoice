package voice.core.downloader

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class HttpDownloadEngineTest {

  private val server = MockWebServer()
  private val engine = HttpDownloadEngine(OkHttpClient())
  private lateinit var directory: java.io.File

  @BeforeTest
  fun setUp() {
    server.start()
    directory = kotlin.io.path.createTempDirectory("downloads").toFile()
  }

  @AfterTest
  fun tearDown() {
    server.close()
    directory.deleteRecursively()
  }

  private fun url(path: String): String = server.url(path).toString()

  private fun download(vararg files: DownloadFile): BookDownload =
    BookDownload(
      id = "test",
      sourceId = "test",
      sourceName = "Test",
      title = "A book",
      files = files.toList(),
      addedAtEpochMillis = 0,
    )

  @Test
  fun `the files are downloaded and reported one by one`() = runTest {
    server.enqueue(MockResponse(code = 200, body = "one"))
    server.enqueue(MockResponse(code = 200, body = "two"))
    val folder = FileBookFolderWriter(directory)
    val finished = mutableListOf<Int>()

    engine.download(
      download =
        download(
          DownloadFile(url = url("01.mp3"), name = "01 - First"),
          DownloadFile(url = url("02.mp3"), name = "02 - Second"),
        ),
      folder = folder,
      onFileFinished = { finished.add(it) },
      onFileProgress = { _, _ -> },
    )

    assertEquals(listOf(1, 2), finished)
    assertEquals("one", folder.fileContent("01 - First.mp3"))
    assertEquals("two", folder.fileContent("02 - Second.mp3"))
  }

  @Test
  fun `completed files are skipped, so a paused download resumes`() = runTest {
    server.enqueue(MockResponse(code = 200, body = "two"))
    val folder = FileBookFolderWriter(directory)
    val finished = mutableListOf<Int>()

    engine.download(
      download =
        download(
            DownloadFile(url = url("01.mp3"), name = "01 - First"),
            DownloadFile(url = url("02.mp3"), name = "02 - Second"),
          )
          .copy(completedFiles = 1),
      folder = folder,
      onFileFinished = { finished.add(it) },
      onFileProgress = { _, _ -> },
    )

    assertEquals(listOf(2), finished)
    assertEquals("two", folder.fileContent("02 - Second.mp3"))
    // the first file was never requested again
    assertEquals(1, server.requestCount)
  }

  @Test
  fun `a failing file drops its remains and throws`() = runTest {
    server.enqueue(MockResponse(code = 200, body = "1234567890"))
    // the writer dies mid file, as when the disk is full or a SAF grant lapses
    val folder =
      object : FileBookFolderWriter(directory) {
        override fun outputStream(fileName: String): java.io.OutputStream =
          object : java.io.FilterOutputStream(super.outputStream(fileName)) {
            private var written = 0

            override fun write(byte: Int) {
              if (++written > 5) throw java.io.IOException("The disk is full")
              out.write(byte)
            }
          }
      }

    val exception = runCatching {
      engine.download(
        download = download(DownloadFile(url = url("01.mp3"), name = "01 - First")),
        folder = folder,
        onFileFinished = { fail("no file finished") },
        onFileProgress = { _, _ -> },
      )
    }
      .exceptionOrNull()

    assertTrue(exception is java.io.IOException, "The download should have failed with $exception")
    assertTrue(folder.files().isEmpty(), "The partial file should be gone: ${folder.files()}")
  }

  @Test
  fun `a missing file throws`() = runTest {
    server.enqueue(MockResponse(code = 404))
    val folder = FileBookFolderWriter(directory)

    assertFailsWith<java.io.IOException> {
      engine.download(
        download = download(DownloadFile(url = url("gone.mp3"), name = "01 - First")),
        folder = folder,
        onFileFinished = {},
        onFileProgress = { _, _ -> },
      )
    }
  }
}
