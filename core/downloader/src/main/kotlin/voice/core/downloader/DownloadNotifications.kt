package voice.core.downloader

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import voice.core.common.rootGraphAs
import voice.core.strings.R as StringsR

/**
 * The progress of the running download as a quiet notification, plus a notification when a book
 * finished or failed. While a download runs, a foreground service holds the same notification, so
 * downloads continue with the app in the background.
 */
@SingleIn(AppScope::class)
@Inject
class DownloadNotifications(private val context: Context) {

  private val notificationManager = NotificationManagerCompat.from(context)

  private var channelCreated = false

  fun showProgress(download: BookDownload) {
    notify(PROGRESS_NOTIFICATION_ID, progress(download))
  }

  fun progress(download: BookDownload): Notification =
    builder()
      .setContentTitle(download.title)
      .setProgress(
        if (download.torrent != null) 0 else download.files.size,
        if (download.torrent != null) 0 else download.completedFiles,
        download.torrent != null,
      )
      .setOngoing(true)
      .build()

  /** The notification the foreground service starts with before any download reported in. */
  fun active(): Notification =
    builder()
      .setContentTitle(context.getString(StringsR.string.bookdownload_notification_active))
      .setOngoing(true)
      .build()

  fun finished(download: BookDownload) {
    notify(
      download.id.hashCode(),
      builder()
        .setContentTitle(download.title)
        .setContentText(context.getString(StringsR.string.bookdownload_notification_done))
        .setAutoCancel(true)
        .build(),
    )
  }

  fun failed(download: BookDownload) {
    notify(
      download.id.hashCode(),
      builder()
        .setContentTitle(download.title)
        .setContentText(context.getString(StringsR.string.bookdownload_notification_failed))
        .setAutoCancel(true)
        .build(),
    )
  }

  fun hideProgress() {
    if (notificationManager.areNotificationsEnabled()) {
      notificationManager.cancel(PROGRESS_NOTIFICATION_ID)
    }
  }

  private fun builder(): NotificationCompat.Builder {
    createChannel()
    return NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
      .setSilent(true)
      .setContentIntent(contentIntent())
  }

  private fun createChannel() {
    if (channelCreated) return
    notificationManager.createNotificationChannel(
      NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
        .setName(context.getString(StringsR.string.bookdownload_notification_channel))
        .build(),
    )
    channelCreated = true
  }

  private fun contentIntent(): PendingIntent? {
    val intent =
      context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
    return PendingIntent.getActivity(
      context,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun notify(
    id: Int,
    notification: Notification,
  ) {
    if (notificationManager.areNotificationsEnabled()) {
      try {
        notificationManager.notify(id, notification)
      } catch (_: SecurityException) {
        // the permission was revoked between the check and the notify
      }
    }
  }

  companion object {
    const val CHANNEL_ID = "bookDownloads"
    const val PROGRESS_NOTIFICATION_ID = 4342
  }
}

/** The pieces of the download feature the foreground service reaches through the app graph. */
@ContributesTo(AppScope::class)
interface DownloaderGraph {
  val downloadNotifications: DownloadNotifications
  val bookDownloadManager: BookDownloadManager
}

/**
 * Keeps the process alive while books download, so the queue keeps running with the app in the
 * background. The [BookDownloadManager] starts and stops it with the queue.
 */
class BookDownloadService : android.app.Service() {

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    val graph = rootGraphAs<DownloaderGraph>()
    val running =
      graph.bookDownloadManager.downloads.value
        .firstOrNull {
          it.download.state == DownloadState.RUNNING || it.download.state == DownloadState.RESOLVING
        }
        ?.download
    startForeground(
      DownloadNotifications.PROGRESS_NOTIFICATION_ID,
      running?.let { graph.downloadNotifications.progress(it) }
        ?: graph.downloadNotifications.active(),
    )
    return START_STICKY
  }

  companion object {

    fun start(context: Context) {
      try {
        ContextCompat.startForegroundService(
          context,
          Intent(context, BookDownloadService::class.java),
        )
      } catch (_: Exception) {
        // the system may refuse to start a foreground service from the background; the download
        // then continues with a plain notification until the app comes forward again
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, BookDownloadService::class.java))
    }
  }
}
