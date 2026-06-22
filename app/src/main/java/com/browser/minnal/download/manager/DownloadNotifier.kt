package com.browser.minnal.download.manager

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.browser.minnal.DefaultBrowserActivity
import com.browser.minnal.R
import com.browser.minnal.database.downloads.DownloadStatus
import com.browser.minnal.utils.VideoViewerIntent
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Owns the "Downloads" notification channel and renders one notification per active download.
 *
 * Notification ids are derived deterministically from the download URL so the same download
 * always updates its existing notification rather than spawning a stack of stale ones.
 */
@Singleton
class DownloadNotifier @Inject constructor(
    private val application: Application,
    private val notificationManager: NotificationManager,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingOngoing = ConcurrentHashMap<Int, android.app.Notification>()
    private val flushScheduled = AtomicBoolean(false)

    init {
        createChannel()
    }

    private fun createChannel() {
        val name = application.getString(R.string.download_channel_name)
        val description = application.getString(R.string.download_channel_description)
        val channel = NotificationChannel(
            CHANNEL_ID,
            name,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            this.description = description
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun notificationIdFor(url: String): Int =
        (NOTIFICATION_ID_OFFSET + (url.hashCode() and 0x00FFFFFF))

    fun buildOngoing(
        url: String,
        title: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        bytesPerSecond: Long = 0L,
        finalizing: Boolean = false,
    ): NotificationCompat.Builder =
        buildProgressNotification(
            url = url,
            title = title,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            bytesPerSecond = bytesPerSecond,
            finalizing = finalizing,
        )

    fun updateProgress(
        url: String,
        title: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        bytesPerSecond: Long = 0L,
        finalizing: Boolean = false,
    ) {
        val notificationId = notificationIdFor(url)
        val notification = buildOngoingNotification(
            url = url,
            title = title,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            bytesPerSecond = bytesPerSecond,
            finalizing = finalizing,
        )
        postOngoing(notificationId, notification)
    }

    fun buildOngoingNotification(
        url: String,
        title: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        bytesPerSecond: Long = 0L,
        finalizing: Boolean = false,
    ): android.app.Notification =
        buildProgressNotification(
            url = url,
            title = title,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            bytesPerSecond = bytesPerSecond,
            finalizing = finalizing,
        ).build()

    fun notifyNow(notificationId: Int, notification: android.app.Notification) {
        cancelLegacySummaryNotification()
        notificationManager.notify(notificationId, notification)
    }

    fun postOngoing(notificationId: Int, notification: android.app.Notification) {
        cancelLegacySummaryNotification()
        pendingOngoing[notificationId] = notification
        scheduleOngoingFlush()
    }

    /** Removes the legacy resurrect summary notification so only per-URL ids remain. */
    fun cancelLegacySummaryNotification() {
        notificationManager.cancel(LEGACY_SUMMARY_NOTIFICATION_ID)
    }

    private fun scheduleOngoingFlush() {
        if (!flushScheduled.compareAndSet(false, true)) {
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            flushPendingOngoing()
        } else {
            mainHandler.post { flushPendingOngoing() }
        }
    }

    private fun flushPendingOngoing() {
        flushScheduled.set(false)
        val snapshot = pendingOngoing.toMap()
        pendingOngoing.clear()
        for ((id, notification) in snapshot) {
            notificationManager.notify(id, notification)
        }
        if (pendingOngoing.isNotEmpty()) {
            scheduleOngoingFlush()
        }
    }

    fun showTerminal(
        url: String,
        title: String,
        status: DownloadStatus,
        localPath: String?,
        mimeType: String?,
    ) {
        val (titleText, subtitleText, statusIcon) = when (status) {
            DownloadStatus.COMPLETED -> Triple(
                application.getString(R.string.download_complete),
                title,
                R.drawable.ic_download_completed,
            )
            DownloadStatus.FAILED -> Triple(
                application.getString(R.string.download_failed),
                title,
                android.R.drawable.stat_notify_error,
            )
            DownloadStatus.CANCELLED -> Triple(
                application.getString(R.string.download_cancelled),
                title,
                android.R.drawable.stat_notify_error,
            )
            else -> Triple(
                application.getString(R.string.download_complete),
                title,
                R.drawable.ic_download_completed,
            )
        }

        val remoteViews = RemoteViews(application.packageName, R.layout.notification_download_terminal).apply {
            setTextViewText(R.id.download_notification_title, titleText)
            setTextViewText(R.id.download_notification_subtitle, subtitleText)
            setImageViewResource(R.id.download_notification_status_icon, statusIcon)
        }

        val smallIcon = when (status) {
            DownloadStatus.COMPLETED -> R.drawable.ic_stat_download
            else -> android.R.drawable.stat_notify_error
        }

        val builder = NotificationCompat.Builder(application, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(remoteViews)
            .setCustomBigContentView(remoteViews)
            .setColor(ContextCompat.getColor(application, R.color.download_notification_accent))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(
                if (status == DownloadStatus.COMPLETED && localPath != null) {
                    openFilePendingIntent(localPath, mimeType) ?: openDownloadsPagePendingIntent()
                } else {
                    openDownloadsPagePendingIntent()
                },
            )

        notificationManager.notify(notificationIdFor(url), builder.build())
    }

    fun cancel(url: String) {
        notificationManager.cancel(notificationIdFor(url))
    }

    private fun buildProgressNotification(
        url: String,
        title: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        bytesPerSecond: Long,
        finalizing: Boolean,
    ): NotificationCompat.Builder {
        val percent = percentOf(bytesDownloaded, totalBytes, finalizing)
        val collapsed = buildProgressRemoteViews(
            layoutId = R.layout.notification_download_progress,
            title = title,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            bytesPerSecond = bytesPerSecond,
            finalizing = finalizing,
            percent = percent,
        )
        val expanded = buildProgressRemoteViews(
            layoutId = R.layout.notification_download_progress_expanded,
            title = title,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            bytesPerSecond = bytesPerSecond,
            finalizing = finalizing,
            percent = percent,
            url = url,
        )

        return NotificationCompat.Builder(application, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setColor(ContextCompat.getColor(application, R.color.download_notification_accent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openDownloadsPagePendingIntent())
            .also { builder ->
                val (progress, max) = scaledProgress(bytesDownloaded, totalBytes, finalizing)
                if (max > 0) {
                    builder.setProgress(max, progress, false)
                } else {
                    builder.setProgress(0, 0, true)
                }
            }
    }

    private fun scaledProgress(bytesDownloaded: Long, totalBytes: Long, finalizing: Boolean): Pair<Int, Int> {
        if (finalizing || totalBytes <= 0L) {
            return 0 to 0
        }
        val max = 10_000
        val progress = ((bytesDownloaded * max) / totalBytes).toInt().coerceIn(0, max)
        return progress to max
    }

    private fun buildProgressRemoteViews(
        layoutId: Int,
        title: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        bytesPerSecond: Long,
        finalizing: Boolean,
        percent: Int,
        url: String? = null,
    ): RemoteViews {
        val views = RemoteViews(application.packageName, layoutId)
        views.setTextViewText(R.id.download_notification_title, title)
        views.setTextViewText(R.id.download_notification_speed, formatSpeed(bytesPerSecond, finalizing))
        views.setTextViewText(R.id.download_notification_size, formatSize(bytesDownloaded, totalBytes, finalizing))
        bindEta(views, bytesDownloaded, totalBytes, bytesPerSecond, finalizing)
        bindProgressSegments(views, percent)

        if (url != null) {
            views.setOnClickPendingIntent(
                R.id.download_notification_pause,
                actionPendingIntent(url, DownloadActionReceiver.ACTION_PAUSE),
            )
            views.setOnClickPendingIntent(
                R.id.download_notification_cancel,
                actionPendingIntent(url, DownloadActionReceiver.ACTION_CANCEL),
            )
        }
        return views
    }

    private fun bindProgressSegments(views: RemoteViews, percent: Int) {
        val filled = when {
            percent < 0 -> 0
            percent >= 100 -> PROGRESS_SEGMENT_IDS.size
            else -> ((percent * PROGRESS_SEGMENT_IDS.size) + 99) / 100
        }
        PROGRESS_SEGMENT_IDS.forEachIndexed { index, viewId ->
            val drawable = if (index < filled) {
                R.drawable.notification_download_segment_active
            } else {
                R.drawable.notification_download_segment_inactive
            }
            views.setImageViewResource(viewId, drawable)
        }
    }

    private fun bindEta(
        views: RemoteViews,
        bytesDownloaded: Long,
        totalBytes: Long,
        bytesPerSecond: Long,
        finalizing: Boolean,
    ) {
        val etaText = formatEta(bytesDownloaded, totalBytes, bytesPerSecond, finalizing)
        if (etaText.isNullOrBlank()) {
            views.setViewVisibility(R.id.download_notification_eta, View.GONE)
        } else {
            views.setViewVisibility(R.id.download_notification_eta, View.VISIBLE)
            views.setTextViewText(R.id.download_notification_eta, etaText)
        }
    }

    private fun formatSpeed(bytesPerSecond: Long, finalizing: Boolean): String {
        if (finalizing) {
            return application.getString(R.string.download_status_finishing)
        }
        if (bytesPerSecond <= 0L) {
            return "—"
        }
        val megabytesPerSecond = bytesPerSecond / (1024.0 * 1024.0)
        return if (megabytesPerSecond >= 0.1) {
            String.format(Locale.getDefault(), "%.2f MB/s", megabytesPerSecond)
        } else {
            val kilobytesPerSecond = bytesPerSecond / 1024.0
            String.format(Locale.getDefault(), "%.1f KB/s", kilobytesPerSecond)
        }
    }

    private fun formatSize(bytesDownloaded: Long, totalBytes: Long, finalizing: Boolean): String {
        val written = formatDetailedSize(bytesDownloaded.coerceAtLeast(0L))
        return if (totalBytes > 0L) {
            val total = formatDetailedSize(totalBytes)
            if (finalizing) {
                "$total / $total"
            } else {
                "$written / $total"
            }
        } else {
            application.getString(R.string.download_progress_unknown, written)
        }
    }

    private fun formatDetailedSize(bytes: Long): String {
        if (bytes < 1024L) {
            return "$bytes B"
        }
        val megabytes = bytes / (1024.0 * 1024.0)
        if (megabytes >= 1.0) {
            return String.format(Locale.getDefault(), "%.2f MB", megabytes)
        }
        val kilobytes = bytes / 1024.0
        return String.format(Locale.getDefault(), "%.1f KB", kilobytes)
    }

    private fun formatEta(
        bytesDownloaded: Long,
        totalBytes: Long,
        bytesPerSecond: Long,
        finalizing: Boolean,
    ): String? {
        if (finalizing || totalBytes <= 0L || bytesPerSecond <= 0L || bytesDownloaded >= totalBytes) {
            return null
        }
        val remainingSeconds = max(1L, (totalBytes - bytesDownloaded) / bytesPerSecond)
        return when {
            remainingSeconds < 60L ->
                application.getString(R.string.download_notification_eta_seconds, remainingSeconds.toInt())
            remainingSeconds < 3_600L ->
                application.getString(R.string.download_notification_eta_minutes, (remainingSeconds / 60L).toInt())
            else ->
                application.getString(R.string.download_notification_eta_hours, (remainingSeconds / 3_600L).toInt())
        }
    }

    private fun percentOf(bytesDownloaded: Long, totalBytes: Long, finalizing: Boolean): Int =
        when {
            totalBytes <= 0 -> -1
            finalizing -> 100
            bytesDownloaded >= totalBytes -> 99
            else -> ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 99)
        }

    private fun openDownloadsPagePendingIntent(): PendingIntent {
        val intent = Intent(application, DefaultBrowserActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_OPEN_DOWNLOADS, true)
        }
        return PendingIntent.getActivity(
            application,
            REQ_OPEN_DOWNLOADS,
            intent,
            pendingIntentFlags(mutable = false),
        )
    }

    private fun openFilePendingIntent(localPath: String, mimeType: String?): PendingIntent? {
        val parsed = runCatching { Uri.parse(localPath) }.getOrNull() ?: return null
        if (parsed.scheme != "content") return null
        val intent = VideoViewerIntent.buildViewIntent(application, parsed, mimeType)
        return PendingIntent.getActivity(
            application,
            localPath.hashCode(),
            intent,
            pendingIntentFlags(mutable = false),
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
            pendingIntentFlags(mutable = false),
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
        const val CHANNEL_ID = "channel_downloads_v3"
        const val EXTRA_OPEN_DOWNLOADS = "minnal.extra.open_downloads"
        private const val NOTIFICATION_ID_OFFSET = 0x10_00_00_00
        private const val LEGACY_SUMMARY_NOTIFICATION_ID = 0xD0_00_01
        private const val REQ_OPEN_DOWNLOADS = 0x52_00_00

        private val PROGRESS_SEGMENT_IDS = intArrayOf(
            R.id.download_notification_segment_0,
            R.id.download_notification_segment_1,
            R.id.download_notification_segment_2,
            R.id.download_notification_segment_3,
            R.id.download_notification_segment_4,
            R.id.download_notification_segment_5,
            R.id.download_notification_segment_6,
            R.id.download_notification_segment_7,
        )
    }
}
