package voice.core.booksources.bazaknig

import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.select.Elements
import voice.core.booksources.SearchPage
import voice.core.booksources.SearchResult
import voice.core.booksources.SourceChanged
import voice.core.booksources.Track

internal data class BookPage(
  val coverUrl: String? = null,
  val author: String? = null,
  val narrator: String? = null,
  val duration: String? = null,
  val series: String? = null,
  val year: String? = null,
  val annotation: String? = null,
  /** The encoded playlist of the first player on the page. */
  val encodedPlaylist: String? = null,
)

internal object BazaKnigParser {

  /**
   * The site prints the number of hits of a search, e.g. `По Вашему запросу найдено 106 ответов`.
   */
  private val foundRegex = Regex("""найден(?:о)?\s+(\d+)\s+ответ""", RegexOption.IGNORE_CASE)

  private val playlistRegex = Regex("""var\s+\w+\s*=\s*strDecode\("([^"]+)"\)""")

  private const val resultsPerPage = 55

  fun parseSearch(
    html: String,
    baseUrl: String,
    page: Int,
  ): SearchPage {
    val document = Jsoup.parse(html, baseUrl)
    val results =
      document.select("div.short").mapNotNull { card ->
        val link = card.selectFirst(".short-title a") ?: return@mapNotNull null
        val url = link.absUrl("href").ifEmpty { link.attr("href") }
        val title = link.text().trim()
        if (title.isEmpty() || url.isEmpty()) return@mapNotNull null
        val items = card.select("ul.short-items li")
        SearchResult(
          sourceId = BazaKnigSource.ID,
          title = title,
          author = items.labeled("Автор"),
          narrator = items.labeled("Читает"),
          annotation = card.selectFirst(".short-text")?.text()?.trim()?.ifEmpty { null },
          coverUrl = card.selectFirst(".short-img img")?.absUrl("src")?.ifEmpty { null },
          url = url,
        )
      }
    if (results.isEmpty() && foundRegex.find(html) == null) {
      // either the site changed, or it answered with something unexpected
      throw SourceChanged("No search results and no hit count on the page")
    }
    val total = foundRegex.find(html)?.groupValues?.get(1)?.toIntOrNull()
    val hasNextPage = total != null && (page + 1) * resultsPerPage < total
    return SearchPage(results = results, hasNextPage = hasNextPage)
  }

  fun parseBookPage(
    html: String,
    baseUrl: String,
  ): BookPage {
    val document = Jsoup.parse(html, baseUrl)
    val items = document.select("ul.full-items li")
    return BookPage(
      coverUrl = document.selectFirst("div.full-img img")?.absUrl("src")?.ifEmpty { null },
      author = items.labeled("Автор"),
      narrator = items.labeled("Читает"),
      duration = items.labeled("Длительность"),
      series = items.labeled("Цикл"),
      year = items.labeled("Год"),
      annotation = document.selectFirst(".short-text")?.text()?.trim()?.ifEmpty { null },
      encodedPlaylist = playlistRegex.find(html)?.groupValues?.get(1),
    )
  }

  /** Parses the decoded playlist, a json array of `{"title": …, "file": …}` objects. */
  fun parsePlaylist(decoded: String): List<Track> {
    val json = Json { ignoreUnknownKeys = true }
    val entries = json.decodeFromString<List<PlaylistEntry>>(decoded)
    return entries.mapIndexed { index, entry ->
      val name = sanitizeFileName(entry.title).ifEmpty { (index + 1).toString() }
      Track(
        url = entry.file,
        name = "${(index + 1).toString().padStart(2, '0')} - $name",
      )
    }
  }

  private fun Elements.labeled(label: String): String? {
    val element = firstOrNull { it.text().startsWith("$label:") } ?: return null
    return element.text().removePrefix("$label:").trim().ifEmpty { null }
  }

  private fun sanitizeFileName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|]"""), "").trim()

  @kotlinx.serialization.Serializable
  private data class PlaylistEntry(
    val title: String,
    val file: String,
  )
}
