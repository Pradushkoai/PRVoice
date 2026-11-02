package voice.core.downloader

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import voice.core.strings.R as StringsR

/**
 * The progress of the running download as a quiet notification, plus a notification when a book
 * finished or failed. Not a foreground service: the queue keeps running while the app lives and
 * resumes on the next start.
 */
@SingleIn(AppScope::class)
@Inject
class DownloadNotifications(private val context: Context) {

  private val notificationManager = NotificationManagerCompat.from(context)

  private var channelCreated = false

  fun showProgress(download: BookDownload) {
    val title = download.title
    notify(
      PROGRESS_NOTIFICATION_ID,
      builder()
        .setContentTitle(title)
        .setProgress(download.files.size, download.completedFiles, false)
        .setOngoing(true)
        .build(),
    )
  }

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
    notification: android.app.Notification,
  ) {
    if (notificationManager.areNotificationsEnabled()) {
      try {
        notificationManager.notify(id, notification)
      } catch (_: SecurityException) {
        // the permission was revoked between the check and the notify
      }
    }
  }

  private companion object {
    const val CHANNEL_ID = "bookDownloads"
    const val PROGRESS_NOTIFICATION_ID = 4342
  }
}
