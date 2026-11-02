package voice.features.bookDownload

import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.navigation.Destination
import voice.navigation.NavEntryProvider

@ContributesTo(AppScope::class)
interface Graph {
  val bookSearchViewModel: BookSearchViewModel
  val downloadsViewModel: DownloadsViewModel
  val bookDetailsViewModelFactory: BookDetailsViewModel.Factory
}

@BindingContainer
@ContributesTo(AppScope::class)
object BookDownloadProvider {

  @Provides
  @IntoSet
  fun bookSearch(): NavEntryProvider<*> =
    NavEntryProvider<Destination.BookSearch> { key ->
      NavEntry(key) { BookSearchScreen() }
    }

  @Provides
  @IntoSet
  fun bookDetails(): NavEntryProvider<*> =
    NavEntryProvider<Destination.BookDetails> { key ->
      NavEntry(key) { BookDetailsScreen(key.result) }
    }

  @Provides
  @IntoSet
  fun bookDownloads(): NavEntryProvider<*> =
    NavEntryProvider<Destination.BookDownloads> { key ->
      NavEntry(key) { DownloadsScreen() }
    }
}
