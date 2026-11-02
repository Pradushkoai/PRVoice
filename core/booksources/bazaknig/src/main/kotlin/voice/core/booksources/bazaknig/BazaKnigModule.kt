package voice.core.booksources.bazaknig

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.booksources.BookSource

@BindingContainer
@ContributesTo(AppScope::class)
object BazaKnigModule {

  @Provides
  @IntoSet
  fun bazaKnigSource(client: okhttp3.OkHttpClient): BookSource =
    BazaKnigSource(BazaKnigSource.BASE_URL, client)
}
