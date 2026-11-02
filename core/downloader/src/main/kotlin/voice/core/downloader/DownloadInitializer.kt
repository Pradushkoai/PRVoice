package voice.core.downloader

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import voice.core.initializer.AppInitializer

/** Starts the download queue when the app comes up. */
@ContributesIntoSet(AppScope::class)
@Inject
class DownloadInitializer(
  private val manager: BookDownloadManager,
) : AppInitializer {
  override fun onAppStart(application: Application) {
    manager.start()
  }
}
