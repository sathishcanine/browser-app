package com.browser.minnal.download.manager

import android.app.ForegroundServiceStartNotAllowedException
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
 *
 * On Android 14+ the [ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC] quota can be
 * exhausted while the app is backgrounded; all [startForeground] entry points must fail
 * gracefully and keep [DownloadRunner] / WorkManager alive instead of crashing.
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

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
    }

    override fun onDestroy() {
        if (runningInstance === this) {
            runningInstance = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_UPDATE -> {
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, DEFAULT_NOTIFICATION_ID)
                val notification = readNotification(intent) ?: return START_STICKY
                if (intent.action == ACTION_START) {
                    notifier.cancelLegacySummaryNotification()
                    if (!startForegroundSafely(notificationId, notification)) {
                        notifier.notifyNow(notificationId, notification)
                        stopSelf()
                    }
                } else {
                    notifier.postOngoing(notificationId, notification)
                }
            }
            ACTION_RESURRECT -> resurrectActiveDownloads()
            ACTION_STOP -> stopForegroundAndSelf()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!registry.hasActive()) {
            return
        }
        // Do not startForegroundService(RESURRECT) here — Android 14+ dataSync FGS time limits
        // cause ForegroundServiceStartNotAllowedException when the task is swiped away.
        logger.log(TAG, "Task removed with active downloads; keeping in-process runner alive.")
        keepDownloadsRunning(registry.activeUrls())
        postBestEffortNotification(registry.activeUrls())
    }

    private fun resurrectActiveDownloads() {
        val urls = registry.activeUrls()
        if (urls.isEmpty()) {
            stopSelf()
            return
        }

        val firstUrl = urls.first()
        val notification = buildSummaryNotification(urls)
        if (notification == null) {
            keepDownloadsRunning(urls)
            stopSelf()
            return
        }

        val notificationId = notifier.notificationIdFor(firstUrl)
        notifier.cancelLegacySummaryNotification()
        if (!startForegroundSafely(notificationId, notification)) {
            logger.log(TAG, "FGS resurrect blocked; continuing downloads without foreground promotion.")
            notifier.notifyNow(notificationId, notification)
            keepDownloadsRunning(urls)
            stopSelf()
            return
        }

        keepDownloadsRunning(urls)
    }

    private fun keepDownloadsRunning(urls: Set<String>) {
        for (url in urls) {
            downloadRunner.start(url)
        }
    }

    private fun postBestEffortNotification(urls: Set<String>) {
        val notification = buildSummaryNotification(urls) ?: return
        val notificationId = notifier.notificationIdFor(urls.first())
        runCatching { notifier.notifyNow(notificationId, notification) }
            .onFailure { logger.log(TAG, "Failed to post download notification after task removal", it) }
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

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundSafely(notificationId: Int, notification: Notification): Boolean {
        return try {
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
            true
        } catch (t: Throwable) {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    t is ForegroundServiceStartNotAllowedException ->
                    logger.log(TAG, "dataSync foreground service quota exhausted or not allowed", t)
                else ->
                    logger.log(TAG, "Failed to promote download service to foreground", t)
            }
            false
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

        @Volatile
        private var runningInstance: DownloadForegroundService? = null

        /**
         * Stops the running service without [android.content.Context.startService], which throws
         * [android.app.BackgroundServiceStartNotAllowedException] on API 31+ when the app is backgrounded.
         */
        fun stopIfRunning(): Boolean {
            val service = runningInstance ?: return false
            service.stopForegroundAndSelf()
            return true
        }

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
