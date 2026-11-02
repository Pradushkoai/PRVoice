package voice.features.bookDownload

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import voice.core.booksources.BookInfo
import voice.core.booksources.BookSourceRegistry
import voice.core.booksources.SearchResult
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.downloader.BookDownloadManager
import voice.core.downloader.DownloadWithProgress
import voice.navigation.Navigator

@AssistedInject
class BookDetailsViewModel(
  private val registry: BookSourceRegistry,
  private val downloadManager: BookDownloadManager,
  private val navigator: Navigator,
  dispatcherProvider: DispatcherProvider,
  @Assisted val result: SearchResult,
) {

  private val scope = MainScope(dispatcherProvider)

  internal var loading by mutableStateOf(true)
    private set

  internal var content by mutableStateOf<BookInfo?>(null)
    private set

  internal var failed by mutableStateOf(false)
    private set

  init {
    load()
  }

  fun onBackClick() {
    navigator.goBack()
  }

  fun load() {
    loading = true
    failed = false
    scope.launch {
      try {
        content = registry.byIdOrThrow(result.sourceId).details(result)
      } catch (exception: CancellationException) {
        throw exception
      } catch (_: Exception) {
        failed = true
      }
      loading = false
    }
  }

  /** The download of this book, when it is queued, running or done. */
  @Composable
  internal fun download(): DownloadWithProgress? {
    val downloads by downloadManager.downloads.collectAsState()
    return remember(downloads) {
      downloads.firstOrNull { it.download.id == BookDownloadManager.downloadIdOf(result) }
    }
  }

  fun onDownloadClick() {
    val info = content ?: return
    val source = registry.byId(result.sourceId) ?: return
    downloadManager.enqueue(info, source.name)
  }

  fun onPauseClick() {
    downloadManager.pause(BookDownloadManager.downloadIdOf(result))
  }

  fun onResumeClick() {
    downloadManager.resume(BookDownloadManager.downloadIdOf(result))
  }

  @AssistedFactory
  interface Factory {
    fun create(result: SearchResult): BookDetailsViewModel
  }
}
