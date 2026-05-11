package com.browser.minnal.download.manager

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import com.browser.minnal.DefaultBrowserActivity
import com.browser.minnal.R
import com.browser.minnal.database.downloads.DownloadStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the "Downloads" notification channel and renders one notification per active download.
 *
 * Notification ids are derived deterministically from the download URL so the same download
 * always updates its existing notification rather than spawning a stack of stale ones.
 */
@Singleton
class DownloadNotifier @Inject constructor(
    private val application: Application,
    private val notificationManager: NotificationManager
) {

    init {
        createChannel()
    }

    private fun createChannel() {
        val name = application.getString(R.string.download_channel_name)
        val description = application.getString(R.string.download_channel_description)
        val channel = NotificationChannel(
            CHANNEL_ID,
            name,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            this.description = description
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /** Notification id for a given URL. Stable across the process lifetime of a download. */
    fun notificationIdFor(url: String): Int =
        // Reserve negative ids for our own use; mask to a safe positive int.
        (NOTIFICATION_ID_OFFSET + (url.hashCode() and 0x00FFFFFF))

    /**
     * Build (but do not post) a "downloading…" notification suitable for use as a
     * foreground-service notification. The worker calls this once per download and reuses
     * the [NotificationCompat.Builder] for ongoing progress updates.
     */
    fun buildOngoing(
        url: String,
        title: String,
        bytesDownloaded: Long,
        totalBytes: Long
    ): NotificationCompat.Builder {
        val percent = percentOf(bytesDownloaded, totalBytes)
        val builder = NotificationCompat.Builder(application, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(progressText(bytesDownloaded, totalBytes))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openDownloadsPagePendingIntent())
            .addAction(
                0,
                application.getString(R.string.download_action_cancel),
                actionPendingIntent(url, DownloadActionReceiver.ACTION_CANCEL)
            )

        if (percent >= 0) {
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder
    }

    /** Updates the visible notification for [url] to reflect new progress. */
    fun updateProgress(
        url: String,
        title: String,
        bytesDownloaded: Long,
        totalBytes: Long
    ) {
        val notification = buildOngoing(url, title, bytesDownloaded, totalBytes).build()
        notificationManager.notify(notificationIdFor(url), notification)
    }

    /** Replaces the in-progress notification with a terminal one. */
    fun showTerminal(
        url: String,
        title: String,
        status: DownloadStatus,
        localPath: String?,
        mimeType: String?
    ) {
        val (titleResId, smallIcon) = when (status) {
            DownloadStatus.COMPLETED -> R.string.download_complete to android.R.drawable.stat_sys_download_done
            DownloadStatus.FAILED -> R.string.download_failed to android.R.drawable.stat_notify_error
            DownloadStatus.CANCELLED -> R.string.download_cancelled to android.R.drawable.stat_notify_error
            else -> R.string.download_complete to android.R.drawable.stat_sys_download_done
        }
        val builder = NotificationCompat.Builder(application, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(application.getString(titleResId))
            .setContentText(title)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(
                if (status == DownloadStatus.COMPLETED && localPath != null) {
                    openFilePendingIntent(localPath, mimeType) ?: openDownloadsPagePendingIntent()
                } else {
                    openDownloadsPagePendingIntent()
                }
            )

        notificationManager.notify(notificationIdFor(url), builder.build())
    }

    /** Removes the notification for [url] without leaving a terminal state behind. */
    fun cancel(url: String) {
        notificationManager.cancel(notificationIdFor(url))
    }

    private fun progressText(bytesDownloaded: Long, totalBytes: Long): String {
        val written = Formatter.formatShortFileSize(application, bytesDownloaded.coerceAtLeast(0))
        return if (totalBytes > 0) {
            val total = Formatter.formatShortFileSize(application, totalBytes)
            application.getString(R.string.download_progress_known, written, total)
        } else {
            application.getString(R.string.download_progress_unknown, written)
        }
    }

    private fun percentOf(bytesDownloaded: Long, totalBytes: Long): Int =
        if (totalBytes <= 0) -1
        else ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)

    private fun openDownloadsPagePendingIntent(): PendingIntent {
        val intent = Intent(application, DefaultBrowserActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_OPEN_DOWNLOADS, true)
        }
        return PendingIntent.getActivity(
            application,
            REQ_OPEN_DOWNLOADS,
            intent,
            pendingIntentFlags(mutable = false)
        )
    }

    private fun openFilePendingIntent(localPath: String, mimeType: String?): PendingIntent? {
        // Best-effort "tap to open" for the completed file. We support both content:// (MediaStore)
        // and file paths; a file:// URI on API 24+ would crash with FileUriExposedException, so
        // we only build the intent for content URIs to keep this safe.
        val parsed = runCatching { Uri.parse(localPath) }.getOrNull() ?: return null
        if (parsed.scheme != "content") return null
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(parsed, mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            application,
            localPath.hashCode(),
            intent,
            pendingIntentFlags(mutable = false)
        )
    }

    private fun actionPendingIntent(url: String, action: String): PendingIntent {
        val intent = Intent(application, DownloadActionReceiver::class.java).apply {
            this.action = action
            putExtra(DownloadActionReceiver.EXTRA_URL, url)
        }
        return PendingIntent.getBroadcast(
            application,
            (action + url).hashCode(),
            intent,
            pendingIntentFlags(mutable = false)
        )
    }

    private fun pendingIntentFlags(mutable: Boolean): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = if (mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags or PendingIntent.FLAG_MUTABLE
            } else {
                flags or PendingIntent.FLAG_IMMUTABLE
            }
        }
        return flags
    }

    companion object {
        const val CHANNEL_ID = "channel_downloads"
        const val EXTRA_OPEN_DOWNLOADS = "minnal.extra.open_downloads"
        private const val NOTIFICATION_ID_OFFSET = 0x10_00_00_00
        private const val REQ_OPEN_DOWNLOADS = 0x52_00_00
    }
}
