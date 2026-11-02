package voice.core.booksources.rutor

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.booksources.BookSource

@BindingContainer
@ContributesTo(AppScope::class)
object RutorModule {

  @Provides
  @IntoSet
  fun rutorSource(client: okhttp3.OkHttpClient): BookSource =
    RutorSource(RutorSource.MIRRORS, client)
}
