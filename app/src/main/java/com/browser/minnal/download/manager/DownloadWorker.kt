package com.browser.minnal.download.manager

import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.browser.minnal.BrowserApp
import com.browser.minnal.database.downloads.DownloadStatus
import com.browser.minnal.log.Logger

/**
 * WorkManager fallback when the in-process runner is killed. Runs as a foreground worker
 * so Android keeps network access after the browser task is closed.
 */
class DownloadWorker(
    context: android.content.Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val component get() = (applicationContext as BrowserApp).applicationComponent
    private val downloadRunner get() = component.downloadRunner()
    private val notifier get() = component.downloadNotifier()
    private val repository get() = component.downloadsRepository()
    private val registry get() = component.activeDownloadRegistry()
    private val logger: Logger get() = component.logger()

    override suspend fun doWork(): Result {
        val url = inputData.getString(INPUT_URL)
        if (url.isNullOrBlank()) {
            logger.log(TAG, "DownloadWorker started without an INPUT_URL; refusing to run.")
            return Result.failure()
        }

        val entry = repository.findDownloadForUrl(url).blockingGet()
        if (entry == null) {
            registry.markInactive(url)
            return Result.failure()
        }
        when (DownloadStatus.fromName(entry.status)) {
            DownloadStatus.COMPLETED,
            DownloadStatus.CANCELLED -> {
                registry.markInactive(url)
                return Result.success()
            }
            else -> Unit
        }

        if (downloadRunner.isActive(url)) {
            logger.log(TAG, "DownloadRunner already active for $url; deferring worker.")
            return Result.retry()
        }

        registry.markActive(url)
        val snapshot = component.downloadStateBus().snapshot(url)
        val notification = notifier.buildOngoingNotification(
            url = url,
            title = entry.title,
            bytesDownloaded = snapshot?.bytesDownloaded ?: entry.bytesDownloaded,
            totalBytes = snapshot?.totalBytes ?: entry.totalBytes,
            bytesPerSecond = snapshot?.bytesPerSecond ?: 0L,
            finalizing = snapshot?.finalizing == true,
        )
        val notificationId = notifier.notificationIdFor(url)
        setForeground(foregroundInfo(notificationId, notification))

        return downloadRunner.runForWorker(url, runAttemptCount)
    }

    private fun foregroundInfo(notificationId: Int, notification: android.app.Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }

    companion object {
        const val INPUT_URL = "minnal.download.input.url"
        const val WORK_TAG_PREFIX = "minnal-download:"

        private const val TAG = "DownloadWorker"

        fun workTagFor(url: String): String = WORK_TAG_PREFIX + url
    }
}
