package com.browser.minnal.download.manager

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.Formatter
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.browser.minnal.BrowserApp
import com.browser.minnal.database.downloads.DownloadEntry
import com.browser.minnal.database.downloads.DownloadStatus
import com.browser.minnal.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * WorkManager worker that owns one download from start to finish (with retries).
 *
 * Lifecycle:
 *  1. Read the persisted [DownloadEntry] for [INPUT_URL] (the manager has already inserted it).
 *  2. Promote the worker to foreground so the OS doesn't kill it; show a progress notification.
 *  3. Hand off to [DownloadEngine] to actually pump bytes, updating progress on the bus
 *     + repository + notification roughly every 256 KB.
 *  4. On success: commit bytes via [DownloadStorage], persist the final path and emit COMPLETED.
 *  5. On retryable failure: return [Result.retry] (WorkManager exponential backoff).
 *  6. On cancellation (user tapped the notification's Cancel button or removed the row):
 *     mark CANCELLED, delete staging bytes.
 *
 * Dependencies are pulled from the app's Dagger graph because WorkManager instantiates workers
 * via reflection. We avoid a custom WorkerFactory to keep startup paths unchanged.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val component get() = (applicationContext as BrowserApp).applicationComponent

    private val repository get() = component.downloadsRepository()
    private val engine get() = component.downloadEngine()
    private val storage get() = component.downloadStorage()
    private val notifier get() = component.downloadNotifier()
    private val bus get() = component.downloadStateBus()
    private val logger: Logger get() = component.logger()

    override suspend fun doWork(): androidx.work.ListenableWorker.Result =
        withContext(Dispatchers.IO) {
            val url = inputData.getString(INPUT_URL) ?: return@withContext failureMissingInput()

            val entry = repository.findDownloadForUrl(url).blockingGet()
            if (entry == null) {
                logger.log(TAG, "No DB row for $url; nothing to download.")
                return@withContext androidx.work.ListenableWorker.Result.success()
            }

            try {
                setForeground(buildForegroundInfo(entry, entry.bytesDownloaded, entry.totalBytes))
            } catch (t: Throwable) {
                logger.log(TAG, "setForeground failed; will continue without FGS for $url", t)
            }

            val staging = storage.stagingFile(entry.url, entry.title)

            // Reflect "we're trying" both in the UI bus and in SQLite.
            emit(entry, DownloadStatus.RUNNING, entry.bytesDownloaded, entry.totalBytes, speed = 0L)
            repository.updateProgress(
                entry.url,
                entry.bytesDownloaded,
                entry.totalBytes,
                DownloadStatus.RUNNING
            ).blockingAwait()

            // Speed sampling: we keep a small sliding window so the rate doesn't bounce wildly
            // between 256KB callbacks. Sample window holds (timestampMillis, bytesAtThatMoment).
            val sampleWindow = ArrayDeque<Pair<Long, Long>>()
            sampleWindow.addLast(System.currentTimeMillis() to entry.bytesDownloaded)

            val transferResult = try {
                engine.download(
                    url = entry.url,
                    stagingFile = staging,
                    userAgent = entry.userAgent,
                    existingETag = entry.eTag,
                    existingLastModified = entry.lastModified,
                    onProgress = { written, total ->
                        val nowMs = System.currentTimeMillis()
                        sampleWindow.addLast(nowMs to written)
                        // Drop samples older than ~3s to keep the rate responsive yet stable.
                        while (sampleWindow.size > 1 && nowMs - sampleWindow.first().first > 3_000L) {
                            sampleWindow.removeFirst()
                        }
                        val oldest = sampleWindow.first()
                        val elapsedMs = (nowMs - oldest.first).coerceAtLeast(1L)
                        val deltaBytes = (written - oldest.second).coerceAtLeast(0L)
                        val bytesPerSecond = (deltaBytes * 1000L) / elapsedMs

                        // Three sinks: in-memory bus (cheap), notification (cheap), SQLite (costlier
                        // but fine because the engine throttles us to ~once per 256 KB).
                        emit(entry, DownloadStatus.RUNNING, written, total, speed = bytesPerSecond)
                        notifier.updateProgress(entry.url, entry.title, written, total)
                        // Persist progress in a fire-and-forget way so SQLite contention doesn't
                        // block the byte loop; we reconcile authoritatively at the end anyway.
                        repository.updateProgress(entry.url, written, total, DownloadStatus.RUNNING)
                            .subscribe({}, { logger.log(TAG, "progress persist failed", it) })
                    }
                )
            } catch (ce: CancellationException) {
                onCancelled(entry, staging)
                throw ce
            } catch (t: Throwable) {
                logger.log(TAG, "Unexpected error transferring $url", t)
                return@withContext failTerminal(entry, t)
            }

            return@withContext when (transferResult) {
                is DownloadEngine.Result.Success -> finalize(entry, staging, transferResult)
                is DownloadEngine.Result.Retry -> retryWithBackoff(entry, transferResult.cause)
                is DownloadEngine.Result.Failure -> failTerminal(entry, transferResult.cause)
            }
        }

    private suspend fun finalize(
        entry: DownloadEntry,
        staging: File,
        result: DownloadEngine.Result.Success
    ): androidx.work.ListenableWorker.Result {
        // Persist the validators we just learned about so the next session can resume properly.
        val withValidators = entry.copy(
            eTag = result.eTag ?: entry.eTag,
            lastModified = result.lastModified ?: entry.lastModified,
            mimeType = result.mimeType ?: entry.mimeType,
            totalBytes = if (result.totalBytes > 0) result.totalBytes else entry.totalBytes,
            bytesDownloaded = if (result.totalBytes > 0) result.totalBytes else staging.length(),
            contentSize = if (result.totalBytes > 0)
                Formatter.formatShortFileSize(applicationContext, result.totalBytes)
            else entry.contentSize
        )
        repository.upsertDownload(withValidators).blockingAwait()

        val committed: String = try {
            storage.commit(staging, entry.title, withValidators.mimeType)
        } catch (io: IOException) {
            logger.log(TAG, "Commit failed for ${entry.url}", io)
            return failTerminal(entry, io)
        }

        repository.updateStatus(
            url = entry.url,
            status = DownloadStatus.COMPLETED,
            localPath = committed,
            errorMessage = null
        ).blockingAwait()

        val finalState = DownloadState(
            url = entry.url,
            title = entry.title,
            status = DownloadStatus.COMPLETED,
            bytesDownloaded = withValidators.bytesDownloaded,
            totalBytes = withValidators.totalBytes,
            localPath = committed,
            mimeType = withValidators.mimeType
        )
        bus.update(finalState)
        notifier.showTerminal(
            url = entry.url,
            title = entry.title,
            status = DownloadStatus.COMPLETED,
            localPath = committed,
            mimeType = withValidators.mimeType
        )
        return androidx.work.ListenableWorker.Result.success()
    }

    private suspend fun retryWithBackoff(
        entry: DownloadEntry,
        cause: Throwable
    ): androidx.work.ListenableWorker.Result {
        if (runAttemptCount >= MAX_ATTEMPTS) {
            return failTerminal(entry, cause)
        }
        emit(entry, DownloadStatus.RETRYING, entry.bytesDownloaded, entry.totalBytes, cause.message)
        repository.updateStatus(
            url = entry.url,
            status = DownloadStatus.RETRYING,
            errorMessage = cause.message
        ).blockingAwait()
        // Notification stays "ongoing" with the last known progress so the user sees we're
        // still trying; WorkManager will resurrect us with exponential backoff per the
        // policy set by the manager when enqueueing.
        return androidx.work.ListenableWorker.Result.retry()
    }

    private suspend fun failTerminal(
        entry: DownloadEntry,
        cause: Throwable
    ): androidx.work.ListenableWorker.Result {
        repository.updateStatus(
            url = entry.url,
            status = DownloadStatus.FAILED,
            errorMessage = cause.message ?: cause::class.java.simpleName
        ).blockingAwait()
        bus.update(
            DownloadState(
                url = entry.url,
                title = entry.title,
                status = DownloadStatus.FAILED,
                bytesDownloaded = entry.bytesDownloaded,
                totalBytes = entry.totalBytes,
                errorMessage = cause.message,
                mimeType = entry.mimeType
            )
        )
        notifier.showTerminal(
            url = entry.url,
            title = entry.title,
            status = DownloadStatus.FAILED,
            localPath = null,
            mimeType = entry.mimeType
        )
        return androidx.work.ListenableWorker.Result.failure()
    }

    private fun onCancelled(entry: DownloadEntry, staging: File) {
        runCatching {
            repository.updateStatus(
                url = entry.url,
                status = DownloadStatus.CANCELLED,
                errorMessage = null
            ).blockingAwait()
        }
        runCatching { storage.deleteStaging(entry.url) }
        runCatching {
            bus.update(
                DownloadState(
                    url = entry.url,
                    title = entry.title,
                    status = DownloadStatus.CANCELLED,
                    bytesDownloaded = staging.length().coerceAtLeast(0L),
                    totalBytes = entry.totalBytes,
                    mimeType = entry.mimeType
                )
            )
        }
        runCatching { notifier.cancel(entry.url) }
    }

    private fun emit(
        entry: DownloadEntry,
        status: DownloadStatus,
        bytes: Long,
        total: Long,
        errorMessage: String? = null,
        speed: Long = 0L
    ) {
        bus.update(
            DownloadState(
                url = entry.url,
                title = entry.title,
                status = status,
                bytesDownloaded = bytes,
                totalBytes = total,
                errorMessage = errorMessage,
                mimeType = entry.mimeType,
                bytesPerSecond = speed
            )
        )
    }

    private fun buildForegroundInfo(
        entry: DownloadEntry,
        bytesDownloaded: Long,
        totalBytes: Long
    ): ForegroundInfo {
        val notif = notifier.buildOngoing(entry.url, entry.title, bytesDownloaded, totalBytes).build()
        val notifId = notifier.notificationIdFor(entry.url)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires an explicit foregroundServiceType.
            ForegroundInfo(notifId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, notif)
        }
    }

    private fun failureMissingInput(): androidx.work.ListenableWorker.Result {
        logger.log(TAG, "DownloadWorker started without an INPUT_URL; refusing to run.")
        return androidx.work.ListenableWorker.Result.failure()
    }

    companion object {
        const val INPUT_URL = "minnal.download.input.url"
        const val WORK_TAG_PREFIX = "minnal-download:"

        private const val TAG = "DownloadWorker"
        private const val MAX_ATTEMPTS = 5

        fun workTagFor(url: String): String = WORK_TAG_PREFIX + url
    }
}
