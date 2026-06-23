package com.browser.minnal.download.manager

import android.app.Application
import android.text.format.Formatter
import androidx.work.ListenableWorker
import com.browser.minnal.database.downloads.DownloadEntry
import com.browser.minnal.database.downloads.DownloadStatus
import com.browser.minnal.database.downloads.DownloadsRepository
import com.browser.minnal.log.Logger
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs HTTP transfers immediately in-process (Chrome-style). WorkManager should not be on the
 * critical path for new enqueues — users see [DownloadStatus.RUNNING] and live progress right away.
 * [DownloadWorker] delegates here for retries and process-death recovery.
 */
@Singleton
class DownloadRunner @Inject constructor(
    private val application: Application,
    private val repository: DownloadsRepository,
    private val engine: DownloadEngine,
    private val storage: DownloadStorage,
    private val notifier: DownloadNotifier,
    private val notificationRefresher: DownloadNotificationRefresher,
    private val bus: DownloadStateBus,
    private val foregroundCoordinator: DownloadForegroundCoordinator,
    private val workScheduler: DownloadWorkScheduler,
    private val activeRegistry: ActiveDownloadRegistry,
    private val logger: Logger,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val progressPersistLocks = ConcurrentHashMap<String, Any>()

    fun isActive(url: String): Boolean = activeJobs[url]?.isActive == true

    /** Start now if not already running for [url] and the DB row is resumable. */
    fun start(url: String) {
        if (!shouldStartTransfer(url)) {
            return
        }
        synchronized(activeJobs) {
            if (activeJobs[url]?.isActive == true) {
                return
            }
            if (!shouldStartTransfer(url)) {
                return
            }
            val job = scope.launch {
                try {
                    run(url, attempt = 1)
                } finally {
                    foregroundCoordinator.detach(url)
                    activeJobs.remove(url)
                    progressPersistLocks.remove(url)
                }
            }
            activeJobs[url] = job
        }
    }

    /** Cancel an in-flight coroutine (pause / user cancel). */
    fun cancelActive(url: String) {
        activeJobs.remove(url)?.cancel()
    }

    /**
     * Re-post the live notification for in-process transfers (e.g. after the user dismissed
     * the shade or returned to the app) without creating a second notification id.
     */
    fun refreshOngoingNotifications() {
        for ((url, job) in activeJobs) {
            if (!job.isActive) continue
            val entry = repository.findDownloadForUrl(url).blockingGet() ?: continue
            val snapshot = bus.snapshot(url)
            val notificationId = notifier.notificationIdFor(url)
            val notification = notifier.buildOngoingNotification(
                url = url,
                title = entry.title,
                bytesDownloaded = snapshot?.bytesDownloaded ?: entry.bytesDownloaded,
                totalBytes = snapshot?.totalBytes ?: entry.totalBytes,
                bytesPerSecond = snapshot?.bytesPerSecond ?: 0L,
                finalizing = snapshot?.finalizing == true,
            )
            notifier.postOngoing(notificationId, notification)
            notificationRefresher.flushNow(
                notificationSnapshot(
                    entry,
                    snapshot?.bytesDownloaded ?: entry.bytesDownloaded,
                    snapshot?.totalBytes ?: entry.totalBytes,
                    bytesPerSecond = snapshot?.bytesPerSecond ?: 0L,
                    finalizing = snapshot?.finalizing == true,
                ),
            )
            foregroundCoordinator.rebind(url, notificationId, notification)
        }
    }

    private fun notificationSnapshot(
        entry: DownloadEntry,
        bytesDownloaded: Long,
        totalBytes: Long,
        bytesPerSecond: Long = 0L,
        finalizing: Boolean = false,
    ) = DownloadNotificationRefresher.Snapshot(
        url = entry.url,
        title = entry.title,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        bytesPerSecond = bytesPerSecond,
        finalizing = finalizing,
    )

    private fun shouldStartTransfer(url: String): Boolean {
        val entry = repository.findDownloadForUrl(url).blockingGet() ?: return false
        return isTransferableStatus(entry)
    }

    private fun isTransferableStatus(entry: DownloadEntry): Boolean =
        when (DownloadStatus.fromName(entry.status)) {
            DownloadStatus.PENDING,
            DownloadStatus.RUNNING,
            DownloadStatus.RETRYING -> true
            else -> false
        }

    /** WorkManager fallback for legacy queued work or process-death recovery. */
    suspend fun runForWorker(url: String, runAttemptCount: Int): ListenableWorker.Result =
        withContext(Dispatchers.IO) {
            when (run(url, attempt = runAttemptCount + 1)) {
                RunOutcome.Success -> ListenableWorker.Result.success()
                RunOutcome.Retry -> ListenableWorker.Result.retry()
                RunOutcome.Failure -> ListenableWorker.Result.failure()
                RunOutcome.Cancelled -> ListenableWorker.Result.failure()
            }
        }

    private suspend fun run(url: String, attempt: Int): RunOutcome {
        var entry = repository.findDownloadForUrl(url).blockingGet()
        if (entry == null) {
            logger.log(TAG, "No DB row for $url; nothing to download.")
            return RunOutcome.Success
        }
        if (!isTransferableStatus(entry)) {
            return when (DownloadStatus.fromName(entry.status)) {
                DownloadStatus.COMPLETED -> RunOutcome.Success
                else -> RunOutcome.Cancelled
            }
        }

        val staging = storage.stagingFile(entry.url, entry.title)

        val latest = repository.findDownloadForUrl(entry.url).blockingGet()
        if (latest == null || !isTransferableStatus(latest)) {
            return RunOutcome.Cancelled
        }
        entry = latest

        workScheduler.schedule(entry.url)
        activeRegistry.markActive(entry.url)
        val notificationId = notifier.notificationIdFor(entry.url)
        val notification = notifier.buildOngoingNotification(
            entry.url,
            entry.title,
            entry.bytesDownloaded,
            entry.totalBytes,
        )
        notifier.postOngoing(notificationId, notification)
        foregroundCoordinator.attach(entry.url, notificationId, notification)
        notificationRefresher.flushNow(
            notificationSnapshot(entry, entry.bytesDownloaded, entry.totalBytes),
        )

        emit(entry, DownloadStatus.RUNNING, entry.bytesDownloaded, entry.totalBytes, speed = 0L)
        repository.updateProgress(
            entry.url,
            entry.bytesDownloaded,
            entry.totalBytes,
            DownloadStatus.RUNNING,
        ).blockingAwait()

        val sampleWindow = ArrayDeque<Pair<Long, Long>>()
        sampleWindow.addLast(System.currentTimeMillis() to entry.bytesDownloaded)
        val progressPersistLock = progressPersistLocks.getOrPut(entry.url) { Any() }
        val lastProgressDbPersistMs = AtomicLong(0L)

        val transferResult = try {
            engine.download(
                url = entry.url,
                stagingFile = staging,
                userAgent = entry.userAgent,
                existingETag = entry.eTag,
                existingLastModified = entry.lastModified,
                parallelConnections = DownloadEngine.PARALLEL_AUTO,
                onProgress = { written, total ->
                    runCatching {
                        // Parallel parts report progress from multiple threads; guard the speed
                        // sample window and downstream UI/DB updates.
                        val bytesPerSecond = synchronized(sampleWindow) {
                            val nowMs = System.currentTimeMillis()
                            sampleWindow.addLast(nowMs to written)
                            while (sampleWindow.size > 1) {
                                val head = sampleWindow.firstOrNull() ?: break
                                if (nowMs - head.first <= 3_000L) break
                                sampleWindow.removeFirst()
                            }
                            val oldest = sampleWindow.firstOrNull() ?: return@synchronized 0L
                            val elapsedMs = (nowMs - oldest.first).coerceAtLeast(1L)
                            val deltaBytes = (written - oldest.second).coerceAtLeast(0L)
                            (deltaBytes * 1000L) / elapsedMs
                        }

                        emit(entry, DownloadStatus.RUNNING, written, total, speed = bytesPerSecond, finalizing = false)
                        notificationRefresher.update(
                            notificationSnapshot(
                                entry,
                                written,
                                total,
                                bytesPerSecond = bytesPerSecond,
                                finalizing = false,
                            ),
                        )

                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastProgressDbPersistMs.get() >= PROGRESS_DB_INTERVAL_MS) {
                            synchronized(progressPersistLock) {
                                if (nowMs - lastProgressDbPersistMs.get() >= PROGRESS_DB_INTERVAL_MS) {
                                    lastProgressDbPersistMs.set(nowMs)
                                    repository.updateProgress(
                                        entry.url,
                                        written,
                                        total,
                                        DownloadStatus.RUNNING,
                                    ).blockingAwait()
                                }
                            }
                        }
                    }.onFailure { logger.log(TAG, "Progress update failed for ${entry.url}", it) }
                },
                onFinalizing = {
                    runCatching {
                        val total = entry.totalBytes.coerceAtLeast(1L)
                        emit(entry, DownloadStatus.RUNNING, total, total, finalizing = true)
                        notificationRefresher.flushNow(
                            notificationSnapshot(entry, total, total, finalizing = true),
                        )
                    }.onFailure { logger.log(TAG, "Finalizing update failed for ${entry.url}", it) }
                },
            )
        } catch (ce: CancellationException) {
            onCancelled(entry, staging)
            return RunOutcome.Cancelled
        } catch (t: Throwable) {
            logger.log(TAG, "Unexpected error transferring $url", t)
            return failTerminal(entry, t, staging)
        }

        return when (transferResult) {
            is DownloadEngine.Result.Success -> finalize(entry, staging, transferResult)
            is DownloadEngine.Result.Retry -> retryWithBackoff(entry, transferResult.cause, attempt, staging)
            is DownloadEngine.Result.Failure -> {
                if (isRecoverableTransferError(transferResult.cause)) {
                    retryWithBackoff(entry, transferResult.cause, attempt, staging)
                } else {
                    failTerminal(entry, transferResult.cause, staging)
                }
            }
        }
    }

    private suspend fun finalize(
        entry: DownloadEntry,
        staging: File,
        result: DownloadEngine.Result.Success,
    ): RunOutcome {
        val total = if (result.totalBytes > 0) result.totalBytes else entry.totalBytes.coerceAtLeast(1L)
        runCatching {
            emit(entry, DownloadStatus.RUNNING, total, total, finalizing = true)
            notificationRefresher.flushNow(
                notificationSnapshot(entry, total, total, finalizing = true),
            )
        }.onFailure { logger.log(TAG, "Finalizing update failed for ${entry.url}", it) }

        val committed: String = try {
            storage.commit(
                staging,
                entry.title,
                result.mimeType ?: entry.mimeType,
            )
        } catch (t: Throwable) {
            logger.log(TAG, "Commit failed for ${entry.url}", t)
            return failTerminal(entry, t, staging)
        }

        val finalBytes = if (result.totalBytes > 0) result.totalBytes else staging.length()
        val withValidators = entry.copy(
            eTag = result.eTag ?: entry.eTag,
            lastModified = result.lastModified ?: entry.lastModified,
            mimeType = result.mimeType ?: entry.mimeType,
            totalBytes = if (result.totalBytes > 0) result.totalBytes else entry.totalBytes,
            bytesDownloaded = finalBytes,
            contentSize = if (result.totalBytes > 0) {
                Formatter.formatShortFileSize(application, result.totalBytes)
            } else {
                entry.contentSize
            },
            status = DownloadStatus.COMPLETED.name,
            localPath = committed,
            errorMessage = null,
        )
        repository.upsertDownload(withValidators).blockingAwait()

        bus.update(
            DownloadState(
                url = entry.url,
                title = entry.title,
                status = DownloadStatus.COMPLETED,
                bytesDownloaded = withValidators.bytesDownloaded,
                totalBytes = withValidators.totalBytes,
                localPath = committed,
                mimeType = withValidators.mimeType,
                finalizing = false,
            ),
        )
        notificationRefresher.remove(entry.url)
        notifier.showTerminal(
            url = entry.url,
            title = entry.title,
            status = DownloadStatus.COMPLETED,
            localPath = committed,
            mimeType = withValidators.mimeType,
        )
        workScheduler.cancel(entry.url)
        activeRegistry.markInactive(entry.url)
        return RunOutcome.Success
    }

    private suspend fun retryWithBackoff(
        entry: DownloadEntry,
        cause: Throwable,
        attempt: Int,
        staging: File,
    ): RunOutcome {
        if (attempt >= maxAttemptsFor(cause)) {
            if (isRecoverableTransferError(cause)) {
                return interruptAndPreserve(entry, staging, cause)
            }
            return failTerminal(entry, cause, staging)
        }
        val latest = repository.findDownloadForUrl(entry.url).blockingGet() ?: return RunOutcome.Cancelled
        if (!isTransferableStatus(latest)) {
            return RunOutcome.Cancelled
        }
        emit(latest, DownloadStatus.RETRYING, latest.bytesDownloaded, latest.totalBytes, cause.message)
        repository.updateStatus(
            url = latest.url,
            status = DownloadStatus.RETRYING,
            errorMessage = cause.message,
        ).blockingAwait()
        postOngoingNotification(latest, latest.bytesDownloaded, latest.totalBytes)

        val backoffMs = retryBackoffMs(attempt, cause)
        try {
            delay(backoffMs)
        } catch (ce: CancellationException) {
            onCancelled(latest, storage.stagingFile(latest.url, latest.title))
            return RunOutcome.Cancelled
        }

        val afterWait = repository.findDownloadForUrl(entry.url).blockingGet() ?: return RunOutcome.Cancelled
        if (!isTransferableStatus(afterWait)) {
            return RunOutcome.Cancelled
        }
        return run(entry.url, attempt + 1)
    }

    /**
     * Near-complete downloads should not surface as permanent failures — keep staged bytes and
     * auto-resume the remaining tail in the background.
     */
    private suspend fun interruptAndPreserve(
        entry: DownloadEntry,
        staging: File,
        cause: Throwable,
    ): RunOutcome {
        val stagedBytes = stagedBytesFor(entry, staging)
        val message = interruptedMessage(stagedBytes, entry.totalBytes)
        logger.log(TAG, "Preserving interrupted download for ${entry.url}: $message", cause)

        repository.updateProgress(
            url = entry.url,
            bytesDownloaded = stagedBytes,
            totalBytes = entry.totalBytes,
            status = DownloadStatus.PAUSED,
        ).blockingAwait()
        repository.updateStatus(
            url = entry.url,
            status = DownloadStatus.PAUSED,
            errorMessage = message,
        ).blockingAwait()

        bus.update(
            DownloadState(
                url = entry.url,
                title = entry.title,
                status = DownloadStatus.PAUSED,
                bytesDownloaded = stagedBytes,
                totalBytes = entry.totalBytes,
                errorMessage = message,
                mimeType = entry.mimeType,
                finalizing = false,
            ),
        )
        postOngoingNotification(entry, stagedBytes, entry.totalBytes)
        workScheduler.schedule(entry.url)
        activeRegistry.markActive(entry.url)

        scope.launch {
            delay(AUTO_RESUME_DELAY_MS)
            if (!isActive(entry.url)) {
                start(entry.url)
            }
        }
        return RunOutcome.Retry
    }

    private suspend fun failTerminal(
        entry: DownloadEntry,
        cause: Throwable,
        staging: File,
    ): RunOutcome {
        val stagedBytes = stagedBytesFor(entry, staging)
        val message = if (isRecoverableTransferError(cause) && stagedBytes > 0L) {
            interruptedMessage(stagedBytes, entry.totalBytes)
        } else {
            cause.message ?: cause::class.java.simpleName
        }
        repository.updateStatus(
            url = entry.url,
            status = DownloadStatus.FAILED,
            errorMessage = message,
        ).blockingAwait()
        repository.updateProgress(
            url = entry.url,
            bytesDownloaded = stagedBytes.coerceAtLeast(entry.bytesDownloaded),
            totalBytes = entry.totalBytes,
            status = DownloadStatus.FAILED,
        ).blockingAwait()
        bus.update(
            DownloadState(
                url = entry.url,
                title = entry.title,
                status = DownloadStatus.FAILED,
                bytesDownloaded = stagedBytes.coerceAtLeast(entry.bytesDownloaded),
                totalBytes = entry.totalBytes,
                errorMessage = message,
                mimeType = entry.mimeType,
            ),
        )
        notificationRefresher.remove(entry.url)
        notifier.showTerminal(
            url = entry.url,
            title = entry.title,
            status = DownloadStatus.FAILED,
            localPath = null,
            mimeType = entry.mimeType,
        )
        workScheduler.cancel(entry.url)
        activeRegistry.markInactive(entry.url)
        return RunOutcome.Failure
    }

    private fun onCancelled(entry: DownloadEntry, staging: File) {
        val latest = repository.findDownloadForUrl(entry.url).blockingGet() ?: return
        val status = DownloadStatus.fromName(latest.status)
        val pausedByUser = bus.consumePauseRequested(entry.url) || status == DownloadStatus.PAUSED

        if (pausedByUser) {
            persistPausedState(latest, staging)
            return
        }

        if (status == DownloadStatus.CANCELLED) {
            runCatching { storage.deleteStaging(entry.url) }
            runCatching { notificationRefresher.remove(entry.url) }
            runCatching { notifier.cancel(entry.url) }
            return
        }

        when (status) {
            DownloadStatus.PENDING,
            DownloadStatus.RUNNING,
            DownloadStatus.RETRYING -> {
                if (!pausedByUser) {
                    persistRunningProgress(latest, staging)
                    workScheduler.schedule(entry.url)
                }
            }
            else -> {
                runCatching { notificationRefresher.remove(entry.url) }
                runCatching { notifier.cancel(entry.url) }
            }
        }
    }

    private fun persistRunningProgress(latest: DownloadEntry, staging: File) {
        val bytes = runCatching {
            storage.stagedBytesDownloaded(latest.url, latest.title)
        }.getOrDefault(0L).let { staged ->
            when {
                staged > 0L -> staged
                staging.exists() -> staging.length().coerceAtLeast(0L)
                else -> latest.bytesDownloaded.coerceAtLeast(0L)
            }
        }
        runCatching {
            repository.updateProgress(
                url = latest.url,
                bytesDownloaded = bytes,
                totalBytes = latest.totalBytes,
                status = DownloadStatus.RUNNING,
            ).blockingAwait()
        }
    }

    private fun persistPausedState(latest: DownloadEntry, staging: File) {
        val bytes = runCatching {
            storage.stagedBytesDownloaded(latest.url, latest.title)
        }.getOrDefault(0L).let { staged ->
            when {
                staged > 0L -> staged
                staging.exists() -> staging.length().coerceAtLeast(0L)
                else -> latest.bytesDownloaded.coerceAtLeast(0L)
            }
        }
        runCatching {
            repository.updateProgress(
                url = latest.url,
                bytesDownloaded = bytes,
                totalBytes = latest.totalBytes,
                status = DownloadStatus.PAUSED,
            ).blockingAwait()
        }
        runCatching {
            bus.update(
                DownloadState(
                    url = latest.url,
                    title = latest.title,
                    status = DownloadStatus.PAUSED,
                    bytesDownloaded = bytes,
                    totalBytes = latest.totalBytes,
                    mimeType = latest.mimeType,
                    finalizing = false,
                ),
            )
        }
        runCatching { notificationRefresher.remove(latest.url) }
        runCatching { notifier.cancel(latest.url) }
    }

    private fun postOngoingNotification(
        entry: DownloadEntry,
        bytesDownloaded: Long,
        totalBytes: Long,
        bytesPerSecond: Long = 0L,
        finalizing: Boolean = false,
    ) {
        notificationRefresher.flushNow(
            notificationSnapshot(entry, bytesDownloaded, totalBytes, bytesPerSecond, finalizing),
        )
    }

    private fun stagedBytesFor(entry: DownloadEntry, staging: File): Long =
        runCatching { storage.stagedBytesDownloaded(entry.url, entry.title) }
            .getOrDefault(0L)
            .let { staged ->
                when {
                    staged > 0L -> staged
                    staging.exists() -> staging.length().coerceAtLeast(0L)
                    else -> entry.bytesDownloaded.coerceAtLeast(0L)
                }
            }

    private fun interruptedMessage(stagedBytes: Long, totalBytes: Long): String {
        val saved = Formatter.formatShortFileSize(application, stagedBytes.coerceAtLeast(0L))
        return if (totalBytes > 0L && stagedBytes >= totalBytes * 99 / 100) {
            "Almost done — $saved saved. Tap Resume to finish."
        } else {
            "Download interrupted — $saved saved. Tap Resume to continue."
        }
    }

    private fun maxAttemptsFor(cause: Throwable): Int =
        when {
            isRecoverableTransferError(cause) -> MAX_PART_RESUME_ATTEMPTS
            isTransientNetworkError(cause) -> MAX_TRANSIENT_ATTEMPTS
            else -> MAX_ATTEMPTS
        }

    private fun isRecoverableTransferError(cause: Throwable): Boolean {
        var current: Throwable? = cause
        while (current != null) {
            val message = current.message.orEmpty()
            if (message.contains("Part incomplete", ignoreCase = true) ||
                message.contains("Part size mismatch", ignoreCase = true) ||
                message.contains("incomplete", ignoreCase = true) ||
                message.contains("truncated", ignoreCase = true) ||
                message.contains("Range not satisfiable", ignoreCase = true) ||
                message.contains("Resuming parallel transfer", ignoreCase = true) ||
                message.contains("Parallel part metadata", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun retryBackoffMs(attempt: Int, cause: Throwable): Long {
        val base = if (isTransientNetworkError(cause)) 2_000L else 1_000L
        return (base shl (attempt - 1).coerceAtMost(4)).coerceAtMost(30_000L)
    }

    private fun isTransientNetworkError(cause: Throwable): Boolean {
        var current: Throwable? = cause
        while (current != null) {
            when (current) {
                is UnknownHostException,
                is ConnectException,
                is SocketTimeoutException -> return true
                is IOException -> {
                    val message = current.message.orEmpty()
                    if (message.contains("Unable to resolve host", ignoreCase = true) ||
                        message.contains("ENETUNREACH", ignoreCase = true) ||
                        message.contains("ECONNRESET", ignoreCase = true) ||
                        message.contains("EHOSTUNREACH", ignoreCase = true)
                    ) {
                        return true
                    }
                }
            }
            current = current.cause
        }
        return false
    }

    private fun emit(
        entry: DownloadEntry,
        status: DownloadStatus,
        bytes: Long,
        total: Long,
        errorMessage: String? = null,
        speed: Long = 0L,
        finalizing: Boolean = false,
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
                bytesPerSecond = speed,
                finalizing = finalizing,
            ),
        )
    }

    private enum class RunOutcome {
        Success,
        Retry,
        Failure,
        Cancelled,
    }

    companion object {
        private const val TAG = "DownloadRunner"
        private const val MAX_ATTEMPTS = 5
        private const val MAX_TRANSIENT_ATTEMPTS = 15
        private const val MAX_PART_RESUME_ATTEMPTS = 50
        private const val AUTO_RESUME_DELAY_MS = 3_000L
        private const val PROGRESS_DB_INTERVAL_MS = 2_000L
    }
}
