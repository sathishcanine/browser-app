package com.browser.minnal.download.manager

import android.app.Application
import android.text.format.Formatter
import androidx.work.ListenableWorker
import com.browser.minnal.database.downloads.DownloadEntry
import com.browser.minnal.database.downloads.DownloadStatus
import com.browser.minnal.database.downloads.DownloadsRepository
import com.browser.minnal.log.Logger
import com.browser.minnal.preference.UserPreferences
import java.io.File
import java.io.IOException
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
    private val bus: DownloadStateBus,
    private val userPreferences: UserPreferences,
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

        emit(entry, DownloadStatus.RUNNING, entry.bytesDownloaded, entry.totalBytes, speed = 0L)
        repository.updateProgress(
            entry.url,
            entry.bytesDownloaded,
            entry.totalBytes,
            DownloadStatus.RUNNING,
        ).blockingAwait()
        notifier.updateProgress(entry.url, entry.title, entry.bytesDownloaded, entry.totalBytes)

        val sampleWindow = ArrayDeque<Pair<Long, Long>>()
        sampleWindow.addLast(System.currentTimeMillis() to entry.bytesDownloaded)
        val progressPersistLock = progressPersistLocks.getOrPut(entry.url) { Any() }
        val lastProgressDbPersistMs = AtomicLong(0L)

        val parallelConnections = if (userPreferences.downloadParallelAccelerated) {
            DownloadEngine.PARALLEL_AUTO
        } else {
            DownloadEngine.SINGLE_CONNECTION
        }

        val transferResult = try {
            engine.download(
                url = entry.url,
                stagingFile = staging,
                userAgent = entry.userAgent,
                existingETag = entry.eTag,
                existingLastModified = entry.lastModified,
                parallelConnections = parallelConnections,
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

                        emit(entry, DownloadStatus.RUNNING, written, total, speed = bytesPerSecond)
                        notifier.updateProgress(entry.url, entry.title, written, total)

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
            )
        } catch (ce: CancellationException) {
            onCancelled(entry, staging)
            return RunOutcome.Cancelled
        } catch (t: Throwable) {
            logger.log(TAG, "Unexpected error transferring $url", t)
            return failTerminal(entry, t)
        }

        return when (transferResult) {
            is DownloadEngine.Result.Success -> finalize(entry, staging, transferResult)
            is DownloadEngine.Result.Retry -> retryWithBackoff(entry, transferResult.cause, attempt)
            is DownloadEngine.Result.Failure -> failTerminal(entry, transferResult.cause)
        }
    }

    private suspend fun finalize(
        entry: DownloadEntry,
        staging: File,
        result: DownloadEngine.Result.Success,
    ): RunOutcome {
        val committed: String = try {
            storage.commit(
                staging,
                entry.title,
                result.mimeType ?: entry.mimeType,
            )
        } catch (io: IOException) {
            logger.log(TAG, "Commit failed for ${entry.url}", io)
            return failTerminal(entry, io)
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
            ),
        )
        notifier.showTerminal(
            url = entry.url,
            title = entry.title,
            status = DownloadStatus.COMPLETED,
            localPath = committed,
            mimeType = withValidators.mimeType,
        )
        return RunOutcome.Success
    }

    private suspend fun retryWithBackoff(
        entry: DownloadEntry,
        cause: Throwable,
        attempt: Int,
    ): RunOutcome {
        if (attempt >= MAX_ATTEMPTS) {
            return failTerminal(entry, cause)
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
        notifier.updateProgress(latest.url, latest.title, latest.bytesDownloaded, latest.totalBytes)

        val backoffMs = (1_000L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(30_000L)
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

    private suspend fun failTerminal(entry: DownloadEntry, cause: Throwable): RunOutcome {
        repository.updateStatus(
            url = entry.url,
            status = DownloadStatus.FAILED,
            errorMessage = cause.message ?: cause::class.java.simpleName,
        ).blockingAwait()
        bus.update(
            DownloadState(
                url = entry.url,
                title = entry.title,
                status = DownloadStatus.FAILED,
                bytesDownloaded = entry.bytesDownloaded,
                totalBytes = entry.totalBytes,
                errorMessage = cause.message,
                mimeType = entry.mimeType,
            ),
        )
        notifier.showTerminal(
            url = entry.url,
            title = entry.title,
            status = DownloadStatus.FAILED,
            localPath = null,
            mimeType = entry.mimeType,
        )
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
            runCatching { notifier.cancel(entry.url) }
            return
        }

        when (status) {
            DownloadStatus.PENDING,
            DownloadStatus.RUNNING,
            DownloadStatus.RETRYING -> return
            else -> runCatching { notifier.cancel(entry.url) }
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
                ),
            )
        }
        runCatching { notifier.cancel(latest.url) }
    }

    private fun emit(
        entry: DownloadEntry,
        status: DownloadStatus,
        bytes: Long,
        total: Long,
        errorMessage: String? = null,
        speed: Long = 0L,
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
        private const val PROGRESS_DB_INTERVAL_MS = 2_000L
    }
}
