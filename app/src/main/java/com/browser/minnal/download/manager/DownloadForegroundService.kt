package com.browser.minnal.download.manager

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.browser.minnal.BrowserApp
import com.browser.minnal.database.downloads.DownloadsRepository
import com.browser.minnal.log.Logger

/**
 * Hosts active downloads in a foreground service so transfers continue when the user
 * leaves, closes, or swipes the browser away from recents.
 */
class DownloadForegroundService : Service() {

    private val app: BrowserApp
        get() = application as BrowserApp

    private val registry: ActiveDownloadRegistry
        get() = app.applicationComponent.activeDownloadRegistry()

    private val downloadRunner: DownloadRunner
        get() = app.applicationComponent.downloadRunner()

    private val notifier: DownloadNotifier
        get() = app.applicationComponent.downloadNotifier()

    private val repository: DownloadsRepository
        get() = app.applicationComponent.downloadsRepository()

    private val logger: Logger
        get() = app.applicationComponent.logger()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_UPDATE -> {
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, DEFAULT_NOTIFICATION_ID)
                val notification = readNotification(intent) ?: return START_STICKY
                if (intent.action == ACTION_START) {
                    notifier.cancelLegacySummaryNotification()
                    startAsForeground(notificationId, notification)
                } else {
                    notifier.postOngoing(notificationId, notification)
                }
            }
            ACTION_RESURRECT -> resurrectActiveDownloads()
            ACTION_STOP -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!registry.hasActive()) {
            return
        }
        logger.log(TAG, "Task removed with active downloads; resurrecting foreground service.")
        val restart = Intent(applicationContext, DownloadForegroundService::class.java).apply {
            action = ACTION_RESURRECT
        }
        try {
            androidx.core.content.ContextCompat.startForegroundService(applicationContext, restart)
        } catch (t: Throwable) {
            logger.log(TAG, "Failed to resurrect download service after task removal", t)
        }
    }

    private fun resurrectActiveDownloads() {
        val urls = registry.activeUrls()
        if (urls.isEmpty()) {
            stopSelf()
            return
        }

        val firstUrl = urls.first()
        val notification = buildSummaryNotification(urls) ?: return
        val notificationId = notifier.notificationIdFor(firstUrl)
        notifier.cancelLegacySummaryNotification()
        startAsForeground(notificationId, notification)

        for (url in urls) {
            downloadRunner.start(url)
        }
    }

    private fun buildSummaryNotification(urls: Set<String>): Notification? {
        val firstUrl = urls.firstOrNull() ?: return null
        val entry = repository.findDownloadForUrl(firstUrl).blockingGet() ?: return null
        val snapshot = app.applicationComponent.downloadStateBus().snapshot(firstUrl)
        return notifier.buildOngoingNotification(
            url = firstUrl,
            title = entry.title,
            bytesDownloaded = snapshot?.bytesDownloaded ?: entry.bytesDownloaded,
            totalBytes = snapshot?.totalBytes ?: entry.totalBytes,
            bytesPerSecond = snapshot?.bytesPerSecond ?: 0L,
            finalizing = snapshot?.finalizing == true,
        )
    }

    private fun startAsForeground(notificationId: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun readNotification(intent: Intent): Notification? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_NOTIFICATION, Notification::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_NOTIFICATION)
        }

    companion object {
        private const val TAG = "DownloadForegroundService"

        const val ACTION_START = "com.browser.minnal.download.fgs.START"
        const val ACTION_UPDATE = "com.browser.minnal.download.fgs.UPDATE"
        const val ACTION_RESURRECT = "com.browser.minnal.download.fgs.RESURRECT"
        const val ACTION_STOP = "com.browser.minnal.download.fgs.STOP"
        const val EXTRA_NOTIFICATION_ID = "minnal.extra.notification_id"
        const val EXTRA_NOTIFICATION = "minnal.extra.notification"

        /** @deprecated Resurrect used this id; kept for cleanup via [DownloadNotifier.cancelLegacySummaryNotification]. */
        const val DEFAULT_NOTIFICATION_ID = 0xD0_00_01
    }
}
