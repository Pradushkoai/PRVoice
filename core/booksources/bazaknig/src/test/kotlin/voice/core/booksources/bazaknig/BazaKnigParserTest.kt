package voice.core.booksources.bazaknig

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import voice.core.booksources.SourceChanged

class BazaKnigParserTest {

  private val baseUrl = BazaKnigSource.BASE_URL

  private fun resource(name: String): String =
    javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().decodeToString()

  @Test
  fun `the search page is parsed into results`() {
    val page = BazaKnigParser.parseSearch(resource("search.html"), baseUrl, page = 0)

    assertEquals(55, page.results.size)
    assertTrue(page.hasNextPage)

    val first = page.results.first()
    assertEquals("bazaknig", first.sourceId)
    assertEquals("Портрет", first.title)
    assertEquals("Гоголь Николай", first.author)
    assertEquals("Антоник Владимир", first.narrator)
    assertEquals("https://baza-knig.top/klassika/182311-portret-nikolaj-gogol.html", first.url)
    assertEquals(
      "https://baza-knig.top/uploads/posts/2026-10/logo_68499.webp",
      first.coverUrl,
    )
    assertTrue(first.annotation.orEmpty().startsWith("Мистическая история"))
  }

  @Test
  fun `the last page has no next page`() {
    // the fixture search found 106 hits, 55 per page, so there are two pages and the second
    // one (zero based page 1) is the last
    val page = BazaKnigParser.parseSearch(resource("search.html"), baseUrl, page = 0)
    assertTrue(page.hasNextPage)
    val lastPage = BazaKnigParser.parseSearch(resource("search.html"), baseUrl, page = 1)
    assertFalse(lastPage.hasNextPage)
  }

  @Test
  fun `a page without results and without a hit count is a changed site`() {
    assertTrue(
      runCatching {
        BazaKnigParser.parseSearch("<html><body>maintenance</body></html>", baseUrl, page = 0)
      }
        .exceptionOrNull() is SourceChanged,
    )
  }

  @Test
  fun `the book page is parsed`() {
    val page =
      BazaKnigParser.parseBookPage(
        resource("book.html"),
        "https://baza-knig.top/fantastika-fentezii/182986-vozdushnaja-razvedka-kniga-pervaja-vladimir-poseljagin.html",
      )

    assertEquals("Поселягин Владимир", page.author)
    assertEquals("Андрианов Григорий", page.narrator)
    assertEquals("2026", page.year)
    assertEquals("12:23:08", page.duration)
    assertNotNull(page.series)
    assertNotNull(page.coverUrl)
    assertNotNull(page.annotation)
    assertNotNull(page.encodedPlaylist)
  }

  @Test
  fun `the playlist is parsed into tracks`() = runTest {
    val decoded = PlaylistDecoder { resource("decode.js") }.decode(resource("encoded_playlist.txt"))

    val tracks = BazaKnigParser.parsePlaylist(decoded)

    assertEquals(42, tracks.size)
    val first = tracks.first()
    assertEquals("01 - Глава_01", first.name)
    assertEquals("https://archive.org/download/04_20261030_202610/01.mp3", first.url)
    val last = tracks.last()
    assertEquals("42 - Глава_42", last.name)
    assertEquals("https://archive.org/download/04_20261030_202610/42.mp3", last.url)
  }

  @Test
  fun `track names are sanitized`() {
    val tracks =
      BazaKnigParser.parsePlaylist(
        """[{"title": "Глава: 1/2", "file": "https://example.com/a.mp3"}]""",
      )
    assertEquals("01 - Глава 12", tracks.single().name)
  }

  @Test
  fun `a book page without a playlist reports a changed site`() {
    val page = BazaKnigParser.parseBookPage("<html><body></body></html>", baseUrl)
    assertNull(page.encodedPlaylist)
  }
}
