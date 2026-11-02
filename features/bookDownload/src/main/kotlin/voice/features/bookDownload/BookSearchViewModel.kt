package voice.features.bookDownload

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import voice.core.booksources.BookSource
import voice.core.booksources.BookSourceRegistry
import voice.core.booksources.SearchResult
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.navigation.Destination
import voice.navigation.Navigator

@Inject
class BookSearchViewModel(
  private val registry: BookSourceRegistry,
  private val navigator: Navigator,
  dispatcherProvider: DispatcherProvider,
) {

  private val scope = MainScope(dispatcherProvider)

  val sources: List<BookSource> = registry.all.toList()

  internal var query by mutableStateOf("")
  internal var searching by mutableStateOf(false)
    private set

  internal var searched by mutableStateOf(false)
    private set

  internal var results by mutableStateOf<List<SearchResult>>(emptyList())
    private set

  internal var failedSources by mutableStateOf<Set<String>>(emptySet())
    private set

  private var pages = mutableMapOf<String, Int>()
  private var morePages = mutableMapOf<String, Boolean>()
  private var searchJob: Job? = null

  fun onQueryChange(value: String) {
    query = value
  }

  fun onBackClick() {
    navigator.goBack()
  }

  fun onOpenDownloads() {
    navigator.goTo(Destination.BookDownloads)
  }

  fun onResultClick(result: SearchResult) {
    navigator.goTo(Destination.BookDetails(result))
  }

  fun search() {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return
    searchJob?.cancel()
    results = emptyList()
    failedSources = emptySet()
    pages = mutableMapOf()
    morePages = mutableMapOf()
    searching = true
    searched = true
    searchJob = scope.launch {
      coroutineScope {
        sources.map { source ->
          launch {
            try {
              val page = source.search(trimmed, 0)
              results = results + page.results
              pages[source.id] = 1
              morePages[source.id] = page.hasNextPage
            } catch (exception: CancellationException) {
              throw exception
            } catch (_: Exception) {
              failedSources = failedSources + source.id
            }
          }
        }
      }
      searching = false
    }
  }

  internal fun hasMore(sourceId: String): Boolean = morePages[sourceId] == true

  fun loadMore(sourceId: String) {
    if (searching) return
    val source = registry.byId(sourceId) ?: return
    val nextPage = pages[sourceId] ?: return
    // tracked in the same slot as the search, so starting a new search cancels a running load
    searchJob?.cancel()
    searchJob = scope.launch {
      searching = true
      try {
        val page = source.search(query.trim(), nextPage)
        results = results + page.results
        pages[sourceId] = nextPage + 1
        morePages[sourceId] = page.hasNextPage
        failedSources = failedSources - sourceId
      } catch (exception: CancellationException) {
        throw exception
      } catch (_: Exception) {
        // leave the button so the listener can try the next page again
        morePages[sourceId] = true
      }
      searching = false
    }
  }
}
