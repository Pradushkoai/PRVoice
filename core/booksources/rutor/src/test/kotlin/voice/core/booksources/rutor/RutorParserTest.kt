package voice.core.booksources.rutor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import voice.core.booksources.DownloadSpec
import voice.core.booksources.SourceChanged

class RutorParserTest {

  private val baseUrl = "https://rutor.info"

  private fun resource(name: String): String =
    javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().decodeToString()

  @Test
  fun `the search results are parsed`() {
    val page = RutorParser.parseSearch(resource("search.html"), baseUrl)

    assertEquals(54, page.results.size)
    assertTrue(page.hasNextPage)

    val first = page.results.first()
    assertEquals("rutor", first.sourceId)
    assertEquals("Николай Гоголь - Портрет (1834) МР3", first.title)
    assertEquals("https://rutor.info/torrent/1112294/nikolaj-gogol-portret-1834-mr3", first.url)
    val torrent = first.torrent!!
    assertTrue(torrent.magnetUri.orEmpty().startsWith("magnet:?xt=urn:btih:b260523c"))
    assertEquals("https://d.rutor.info/download/1112294", torrent.torrentUrl)
    assertEquals("68.80 MB", torrent.sizeText)
    assertEquals(4, torrent.seeders)
    assertEquals(0, torrent.leechers)
  }

  @Test
  fun `the search url targets the book category`() {
    assertEquals(
      "/search/0/11/000/0/%D0%B3%D0%BE%D0%B3%D0%BE%D0%BB%D1%8C",
      RutorParser.searchUrl("гоголь", page = 0),
    )
    assertEquals(
      "/search/3/11/000/0/%D0%B3%D0%BE%D0%B3%D0%BE%D0%BB%D1%8C",
      RutorParser.searchUrl("гоголь", page = 3),
    )
  }

  @Test
  fun `a page without the result table is a changed site`() {
    assertFailsWith<SourceChanged> {
      RutorParser.parseSearch("<html><body>blocked</body></html>", baseUrl)
    }
  }
}

class RutorSourceTest {

  private fun resource(name: String): String =
    javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().decodeToString()

  @Test
  fun `the details are built from the torrent of the search result`() = runTest {
    val source = RutorSource(listOf("https://rutor.info"), okhttp3.OkHttpClient())
    val result =
      voice.core.booksources.SearchResult(
        sourceId = RutorSource.ID,
        title = "Николай Гоголь - Портрет (1834) МР3",
        url = "https://rutor.info/torrent/1112294/nikolaj-gogol-portret-1834-mr3",
        torrent =
          voice.core.booksources.TorrentSummary(
            magnetUri = "magnet:?xt=urn:btih:b260523c",
            torrentUrl = "https://d.rutor.info/download/1112294",
            seeders = 4,
            leechers = 0,
          ),
      )

    val info = source.details(result)

    val files = assertIs<DownloadSpec.Torrent>(info.files)
    assertEquals("magnet:?xt=urn:btih:b260523c", files.magnetUri)
    assertEquals("https://d.rutor.info/download/1112294", files.torrentUrl)
    assertEquals(4, files.seeders)
    assertNull(files.sizeBytes)
  }

  @Test
  fun `a failing mirror falls back to the next one`() = runTest {
    val server = mockwebserver3.MockWebServer()
    server.start()
    try {
      server.enqueue(
        mockwebserver3.MockResponse(code = 200, body = resource("search.html")),
      )
      val mirror = server.url("/").toString().trimEnd('/')
      // nothing listens on port 1, so the first mirror always refuses the connection
      val source = RutorSource(listOf("http://localhost:1", mirror), okhttp3.OkHttpClient())

      val page = source.search("гоголь", page = 0)

      assertEquals(54, page.results.size)
      // the urls of the results point at the mirror that actually answered
      assertTrue(page.results.first().url.startsWith(mirror))
      assertEquals(
        "/search/0/11/000/0/%D0%B3%D0%BE%D0%B3%D0%BE%D0%BB%D1%8C",
        server.takeRequest().target,
      )
    } finally {
      server.close()
    }
  }
}
