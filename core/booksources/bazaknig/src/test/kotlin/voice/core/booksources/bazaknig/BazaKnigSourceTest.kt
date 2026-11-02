package voice.core.booksources.bazaknig

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import voice.core.booksources.fetch

class BazaKnigSourceTest {

  private val server = MockWebServer()
  private val client = OkHttpClient()

  private fun resource(name: String): String =
    javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().decodeToString()

  @BeforeTest
  fun setUp() {
    server.start()
  }

  @AfterTest
  fun tearDown() {
    server.close()
  }

  private fun source() = BazaKnigSource(server.url("/").toString(), client)

  @Test
  fun `search queries the DLE search endpoint`() = runTest {
    server.enqueue(MockResponse(code = 200, body = resource("search.html")))

    val page = source().search("гоголь", page = 0)

    val request = server.takeRequest()
    assertEquals("GET", request.method)
    val target = request.target
    assertTrue(target.startsWith("/index.php?do=search&subaction=search&story="), target)
    assertTrue("search_start" !in target, "the first page has no search_start")

    assertEquals(55, page.results.size)
  }

  @Test
  fun `higher pages add the search_start parameter`() = runTest {
    server.enqueue(MockResponse(code = 200, body = resource("search.html")))

    val page = source().search("гоголь", page = 2)

    assertTrue(page.results.isNotEmpty())
    val target = server.takeRequest().target
    assertTrue("search_start=3" in target, target)
  }

  @Test
  fun `the decoder script is fetched from the site and executed`() = runTest {
    server.enqueue(MockResponse(code = 200, body = resource("decode.js")))

    val encoded = resource("encoded_playlist.txt")
    val decoder = PlaylistDecoder { client.fetch(server.url("/player/decode.js").toString()) }
    val decoded = decoder.decode(encoded)

    assertTrue("https://archive.org/download/" in decoded, decoded.take(100))
    assertEquals("/player/decode.js", server.takeRequest().target)
  }
}
