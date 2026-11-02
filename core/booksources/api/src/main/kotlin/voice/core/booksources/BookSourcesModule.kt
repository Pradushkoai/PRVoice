package voice.core.booksources

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

@BindingContainer
@ContributesTo(AppScope::class)
object BookSourcesModule {

  /**
   * The book sites expect a browser user agent: some reject or degrade answers for unknown clients.
   */
  private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
      "Chrome/126.0.0.0 Safari/537.36"

  @Provides
  @SingleIn(AppScope::class)
  fun sourceHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .followRedirects(true)
      .addInterceptor { chain ->
        chain.proceed(
          chain.request().newBuilder().header("User-Agent", USER_AGENT).build(),
        )
      }
      .build()

  @Provides
  fun bookSourceRegistry(sources: Set<BookSource>): BookSourceRegistry = BookSourceRegistry(sources)
}
