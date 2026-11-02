package voice.core.booksources.bazaknig

import java.net.URLEncoder
import okhttp3.OkHttpClient
import voice.core.booksources.BookInfo
import voice.core.booksources.BookSource
import voice.core.booksources.DownloadSpec
import voice.core.booksources.SearchPage
import voice.core.booksources.SearchResult
import voice.core.booksources.SourceChanged
import voice.core.booksources.fetch

/**
 * baza-knig.top is a DataLife Engine site. Search goes through the DLE search endpoint, the book
 * page contains an encoded playlist whose files are hosted on archive.org.
 */
class BazaKnigSource(
  baseUrl: String,
  private val client: OkHttpClient,
) : BookSource {

  constructor(client: OkHttpClient) : this(BASE_URL, client)

  private val baseUrl: String = baseUrl.trimEnd('/')

  override val id = ID
  override val name = "Baza-knig"

  private val decoder = PlaylistDecoder {
    client.fetch("$baseUrl/player/decode.js")
  }

  override suspend fun search(query: String, page: Int): SearchPage {
    val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8)
    // DLE numbers its result pages starting at one, so the zero based page 0 leaves the
    // parameter out and page N requests search_start = N + 1
    val searchStart = if (page <= 0) "" else "&search_start=${page + 1}"
    val html =
      client.fetch(
        "$baseUrl/index.php?do=search&subaction=search&story=$encodedQuery$searchStart",
        referer = baseUrl,
      )
    return BazaKnigParser.parseSearch(html, baseUrl, page)
  }

  override suspend fun details(result: SearchResult): BookInfo {
    if (result.sourceId != id) throw IllegalArgumentException("Not a result of this source")
    val html = client.fetch(result.url, referer = baseUrl)
    val page = BazaKnigParser.parseBookPage(html, result.url)
    val encoded = page.encodedPlaylist ?: throw SourceChanged("No playlist on the book page")
    val tracks = BazaKnigParser.parsePlaylist(decoder.decode(encoded))
    if (tracks.isEmpty()) throw SourceChanged("The playlist is empty")
    return BookInfo(
      result =
        result.copy(
          author = result.author ?: page.author,
          narrator = result.narrator ?: page.narrator,
          coverUrl = result.coverUrl ?: page.coverUrl,
          annotation = result.annotation ?: page.annotation,
        ),
      description = page.annotation,
      duration = page.duration,
      series = page.series,
      year = page.year,
      files = DownloadSpec.HttpFiles(tracks = tracks),
    )
  }

  companion object {
    const val ID = "bazaknig"
    const val BASE_URL = "https://baza-knig.top"
  }
}
