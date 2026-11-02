package voice.core.downloader

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.builtins.ListSerializer
import voice.core.datastore.VoiceDataStoreFactory

/** Persists the download queue, so downloads survive restarts and can be resumed. */
@SingleIn(AppScope::class)
class BookDownloadStore(store: DataStore<List<BookDownload>>) :
  DataStore<List<BookDownload>> by store {

  @Inject
  constructor(
    factory: VoiceDataStoreFactory,
  ) : this(
    factory.create(
      serializer = ListSerializer(BookDownload.serializer()),
      fileName = "bookDownloads",
      defaultValue = emptyList(),
    ),
  )
}
