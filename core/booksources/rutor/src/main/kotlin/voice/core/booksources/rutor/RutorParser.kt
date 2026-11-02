package voice.core.booksources.rutor

import org.jsoup.Jsoup
import voice.core.booksources.SearchPage
import voice.core.booksources.SearchResult
import voice.core.booksources.SourceChanged
import voice.core.booksources.TorrentSummary

internal object RutorParser {

  /** The search lists the book category of rutor, which contains audiobooks and text books. */
  private const val bookCategory = 11

  fun searchUrl(
    query: String,
    page: Int,
  ): String = "/search/$page/$bookCategory/000/0/${urlEncode(query)}"

  fun parseSearch(
    html: String,
    baseUrl: String,
  ): SearchPage {
    val document = Jsoup.parse(html, baseUrl)
    if (document.select("tr.gai, tr.tum").isEmpty()) {
      throw SourceChanged("No result table on the page")
    }
    val results =
      document
        .select("tr.gai, tr.tum")
        .mapNotNull { row ->
          val titleLink = row.selectFirst("a[href^=/torrent/]") ?: return@mapNotNull null
          val magnet = row.selectFirst("a[href^=magnet:]")?.attr("href")
          val torrentPath = row.selectFirst("a.downgif")?.attr("href")
          if (magnet == null && torrentPath == null) return@mapNotNull null
          SearchResult(
            sourceId = RutorSource.ID,
            title = titleLink.text().trim(),
            url = titleLink.absUrl("href").ifEmpty { baseUrl + titleLink.attr("href") },
            torrent =
              TorrentSummary(
                magnetUri = magnet,
                torrentUrl =
                  torrentPath?.let { path ->
                    val absolute = if (path.startsWith("//")) "https:$path" else baseUrl + path
                    absolute
                  },
                sizeText = row.select("td[align=right]").firstOrNull()?.text()?.trim(),
                seeders = row.selectFirst("span.green")?.text()?.trim()?.toIntOrNull(),
                leechers = row.selectFirst("span.red")?.text()?.trim()?.toIntOrNull(),
              ),
          )
        }
        .distinctBy { it.url }
    // rutor serves the next page until the search is exhausted, so an empty page is the end
    return SearchPage(results = results, hasNextPage = results.isNotEmpty())
  }

  private fun urlEncode(query: String): String =
    java.net.URLEncoder.encode(query, Charsets.UTF_8).replace("+", "%20")
}
