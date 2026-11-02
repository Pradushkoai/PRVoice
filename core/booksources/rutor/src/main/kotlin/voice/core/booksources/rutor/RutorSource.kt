package voice.core.booksources.rutor

import voice.core.booksources.BookInfo
import voice.core.booksources.BookSource
import voice.core.booksources.DownloadSpec
import voice.core.booksources.SearchPage
import voice.core.booksources.SearchResult
import voice.core.booksources.SourceChanged
import voice.core.booksources.SourceUnavailable
import voice.core.booksources.fetch

/**
 * rutor is a public tracker that is reachable through a couple of mirrors. Search results already
 * carry the magnet uri and the .torrent download, so the details are built from what the search
 * found.
 */
class RutorSource(
  /** Mirrors are tried in order until one answers. */
  private val mirrors: List<String>,
  private val client: okhttp3.OkHttpClient,
) : BookSource {

  constructor(client: okhttp3.OkHttpClient) : this(MIRRORS, client)

  override val id = ID
  override val name = "Rutor"

  override suspend fun search(query: String, page: Int): SearchPage {
    val (mirror, html) =
      fetchFromAnyMirror { mirror ->
        RutorParser.searchUrl(query, page).let { mirror + it }
      }
    return RutorParser.parseSearch(html, mirror)
  }

  override suspend fun details(result: SearchResult): BookInfo {
    val summary = result.torrent ?: throw SourceChanged("No torrent in the result")
    if (summary.magnetUri == null && summary.torrentUrl == null) {
      throw SourceChanged("Neither a magnet nor a torrent url")
    }
    return BookInfo(result = result, files = DownloadSpec.from(summary))
  }

  /** Returns the mirror that answered together with its page, to resolve relative urls. */
  private suspend fun fetchFromAnyMirror(url: (String) -> String): Pair<String, String> {
    var lastException: SourceUnavailable? = null
    for (mirror in mirrors) {
      try {
        val html = client.fetch(url(mirror), referer = mirror)
        return mirror to html
      } catch (exception: SourceUnavailable) {
        lastException = exception
      }
    }
    throw lastException ?: SourceUnavailable("All mirrors failed")
  }

  companion object {
    const val ID = "rutor"
    val MIRRORS =
      listOf(
        "https://rutor.info",
        "https://rutor.is",
        "https://rutor.lib",
      )
  }
}
