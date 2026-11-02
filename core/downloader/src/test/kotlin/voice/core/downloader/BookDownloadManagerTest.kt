package voice.core.downloader

import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import voice.core.booksources.BookInfo
import voice.core.booksources.DownloadSpec
import voice.core.booksources.SearchResult
import voice.core.booksources.Track
import voice.core.scanner.MediaScanTrigger

class BookDownloadManagerTest {

  private val store = BookDownloadStore(MemoryDataStore<List<BookDownload>>(emptyList()))

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  /** The engine of the tests: records calls, can fail once and can wait until released. */
  private class RecordingEngine : DownloadEngine {
    val downloads = mutableListOf<BookDownload>()
    var failOnce: Exception? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun download(
      download: BookDownload,
      folder: BookFolderWriter,
      onFileFinished: suspend (completedFiles: Int) -> Unit,
      onFileProgress: (bytes: Long, totalBytes: Long) -> Unit,
    ) {
      downloads.add(download)
      gate?.await()
      failOnce?.let {
        failOnce = null
        throw it
      }
      for (index in download.completedFiles until download.files.size) {
        onFileFinished(index + 1)
      }
    }
  }

  private class TestFolderProvider : DownloadFolderProvider {
    val opened = mutableListOf<BookDownload>()
    var failWith: Exception? = null

    override suspend fun openFolder(download: BookDownload): BookDownloadFolder {
      opened.add(download)
      failWith?.let { throw it }
      return BookDownloadFolder(uri = "folder://${download.id}", writer = FakeWriter)
    }
  }

  private object FakeWriter : BookFolderWriter {
    override fun stillExists(): Boolean = true

    override fun exists(fileName: String): Boolean = false

    override fun size(fileName: String): Long = 0

    override fun delete(fileName: String) {}

    override fun outputStream(fileName: String): java.io.OutputStream =
      java.io.ByteArrayOutputStream()
  }

  private fun book(title: String): BookInfo =
    BookInfo(
      result =
        SearchResult(
          sourceId = "bazaknig",
          title = title,
          url = "https://example.com/$title",
        ),
      files =
        DownloadSpec.HttpFiles(
          tracks =
            listOf(
              Track(url = "https://example.com/$title/1.mp3", name = "01"),
              Track(url = "https://example.com/$title/2.mp3", name = "02"),
            ),
        ),
    )

  @Test
  fun `a download runs and finishes`() = runTest {
    val engine = RecordingEngine()
    val manager = manager(engine)

    manager.start()
    manager.enqueue(book("Гоголь"), sourceName = "Baza-knig")
    advanceUntilIdle()

    val downloads = manager.downloads.value
    assertEquals(1, downloads.size)
    assertEquals(DownloadState.DONE, downloads.single().download.state)
    assertEquals(2, downloads.single().download.completedFiles)
    assertTrue(store.data.first().single().folderUri != null)
  }

  @Test
  fun `downloads run one after another`() = runTest {
    val engine = RecordingEngine()
    val gate = CompletableDeferred<Unit>()
    engine.gate = gate
    val manager = manager(engine)

    manager.start()
    manager.enqueue(book("Гоголь"), sourceName = "Baza-knig")
    manager.enqueue(book("Пушкин"), sourceName = "Baza-knig")
    advanceUntilIdle()

    // the first download blocks on the gate, so the second never started
    assertEquals(listOf("Гоголь"), engine.downloads.map { it.title })
    assertEquals(DownloadState.QUEUED, manager.downloads.value[1].download.state)

    gate.complete(Unit)
    advanceUntilIdle()

    assertEquals(listOf("Гоголь", "Пушкин"), engine.downloads.map { it.title })
    assertTrue(manager.downloads.value.all { it.download.state == DownloadState.DONE })
  }

  @Test
  fun `a failing download is marked failed and the next one runs`() = runTest {
    val engine = RecordingEngine()
    engine.failOnce = RuntimeException("boom")
    val manager = manager(engine)

    manager.start()
    manager.enqueue(book("Гоголь"), sourceName = "Baza-knig")
    manager.enqueue(book("Пушкин"), sourceName = "Baza-knig")
    advanceUntilIdle()

    val states = manager.downloads.value.associate { it.download.title to it.download.state }
    assertEquals(DownloadState.FAILED, states["Гоголь"])
    assertEquals(DownloadState.DONE, states["Пушкин"])
    assertEquals(DownloadError.UNKNOWN, manager.downloads.value[0].download.error)
  }

  @Test
  fun `a download without a writable folder fails with no folder`() = runTest {
    val engine = RecordingEngine()
    val manager =
      manager(
        engine,
        folderProvider = TestFolderProvider().apply { failWith = NoWritableLibraryFolder() },
      )

    manager.start()
    manager.enqueue(book("Гоголь"), sourceName = "Baza-knig")
    advanceUntilIdle()

    val download = manager.downloads.value.single().download
    assertEquals(DownloadState.FAILED, download.state)
    assertEquals(DownloadError.NO_FOLDER, download.error)
  }

  @Test
  fun `a RUNNING download of a previous run is queued again on start`() = runTest {
    val engine = RecordingEngine()
    store.updateData {
      listOf(
        BookDownload(
          id = "bazaknig|https://example.com/Гоголь",
          sourceId = "bazaknig",
          sourceName = "Baza-knig",
          title = "Гоголь",
          files = listOf(DownloadFile(url = "https://example.com/1.mp3", name = "01")),
          state = DownloadState.RUNNING,
          completedFiles = 1,
          addedAtEpochMillis = 0,
        ),
      )
    }
    val manager = manager(engine)

    manager.start()
    advanceUntilIdle()

    // the engine resumed after the one file that already finished
    assertEquals(1, engine.downloads.single().completedFiles)
    assertEquals(DownloadState.DONE, manager.downloads.value.single().download.state)
  }

  @Test
  fun `pausing the running download continues with the next one`() = runTest {
    val engine = RecordingEngine()
    val gate = CompletableDeferred<Unit>()
    engine.gate = gate
    val manager = manager(engine)

    manager.start()
    manager.enqueue(book("Гоголь"), sourceName = "Baza-knig")
    manager.enqueue(book("Пушкин"), sourceName = "Baza-knig")
    advanceUntilIdle()
    assertEquals(DownloadState.RUNNING, manager.downloads.value[0].download.state)

    manager.pause(manager.downloads.value[0].download.id)
    advanceUntilIdle()

    // the paused book steps aside, the next one runs
    assertEquals(DownloadState.PAUSED, manager.downloads.value[0].download.state)
    assertEquals(listOf("Гоголь", "Пушкин"), engine.downloads.map { it.title })

    manager.resume(manager.downloads.value[0].download.id)
    gate.complete(Unit)
    advanceUntilIdle()
    assertTrue(manager.downloads.value.all { it.download.state == DownloadState.DONE })
  }

  @Test
  fun `enqueueing the same book twice keeps one download`() = runTest {
    val engine = RecordingEngine()
    val manager = manager(engine)

    manager.start()
    manager.enqueue(book("Гоголь"), sourceName = "Baza-knig")
    advanceUntilIdle()
    manager.enqueue(book("Гоголь"), sourceName = "Baza-knig")
    advanceUntilIdle()

    assertEquals(1, manager.downloads.value.size)
    // the finished download is not picked up again
    assertEquals(1, engine.downloads.size)
  }

  private fun TestScope.manager(
    engine: RecordingEngine,
    folderProvider: TestFolderProvider = TestFolderProvider(),
  ): BookDownloadManager {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    return BookDownloadManager(
      store = store,
      folderProvider = folderProvider,
      engine = engine,
      mediaScanTrigger = mockk<MediaScanTrigger>(relaxed = true),
      notifications = mockk<DownloadNotifications>(relaxed = true),
    )
  }
}
