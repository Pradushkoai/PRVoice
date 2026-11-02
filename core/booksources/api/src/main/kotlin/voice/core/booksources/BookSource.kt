package voice.core.booksources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

interface BookSource {

  /** Stable identifier, e.g. `bazaknig`. Used to find the source of a search result again. */
  val id: String

  /** Human readable name for the UI. */
  val name: String

  /** @param page zero based page index. */
  suspend fun search(query: String, page: Int): SearchPage

  /** Loads the full book information, including the files to download. */
  suspend fun details(result: SearchResult): BookInfo
}

data class SearchPage(
  val results: List<SearchResult>,
  val hasNextPage: Boolean,
)

@Serializable
data class SearchResult(
  val sourceId: String,
  val title: String,
  val author: String? = null,
  val narrator: String? = null,
  val annotation: String? = null,
  val coverUrl: String? = null,
  /** Absolute url of the book page at the source. */
  val url: String,
  /** Torrent summary, when the source already knows it from its search listing. */
  val torrent: TorrentSummary? = null,
)

@Serializable
data class TorrentSummary(
  val magnetUri: String? = null,
  val torrentUrl: String? = null,
  val sizeText: String? = null,
  val seeders: Int? = null,
  val leechers: Int? = null,
)

data class BookInfo(
  val result: SearchResult,
  val description: String? = null,
  val duration: String? = null,
  val series: String? = null,
  val year: String? = null,
  val files: DownloadSpec,
)

/** Fetches a url and returns the response body, or throws a [SourceUnavailable]. */
suspend fun OkHttpClient.fetch(
  url: String,
  referer: String? = null,
): String =
  withContext(Dispatchers.IO) {
    val request =
      Request.Builder()
        .url(url)
        .apply {
          if (referer != null) header("Referer", referer)
        }
        .build()
    try {
      newCall(request).execute().use { response: Response ->
        if (!response.isSuccessful) throw SourceUnavailable("HTTP ${response.code}")
        response.body.string()
      }
    } catch (exception: java.io.IOException) {
      throw SourceUnavailable(cause = exception)
    }
  }
