package com.browser.minnal.download.manager

import android.app.Application
import com.browser.minnal.utils.VideoViewerIntent
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import androidx.work.WorkManager
import com.browser.minnal.BuildConfig
import com.browser.minnal.database.downloads.DownloadEntry
import com.browser.minnal.database.downloads.DownloadStatus
import com.browser.minnal.database.downloads.DownloadsRepository
import com.browser.minnal.log.Logger
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public-facing API for enqueueing / cancelling downloads. The browser only ever talks to
 * this class; everything else (engine, runner, storage, notifications) is plumbing that this
 * coordinator wires together.
 *
 * Downloads start immediately via [DownloadRunner] (Chrome-style). WorkManager is only used to
 * cancel legacy work requests; new enqueues never sit in [DownloadStatus.PENDING].
 */
@Singleton
class MinnalDownloadManager @Inject constructor(
    private val application: Application,
    private val repository: DownloadsRepository,
    private val stateBus: DownloadStateBus,
    private val notifier: DownloadNotifier,
    private val storage: DownloadStorage,
    private val downloadRunner: DownloadRunner,
    private val logger: Logger,
) {

    /**
     * Live stream of [DownloadState] changes. Subscribe on the main scheduler in UI code.
     */
    fun changes(): Observable<DownloadState> = stateBus.changes()

    /** Latest in-memory snapshot for [url], or null if we have nothing cached. */
    fun snapshot(url: String): DownloadState? = stateBus.snapshot(url)

    /** Snapshots for every download tracked since the process started. */
    fun snapshots(): Map<String, DownloadState> = stateBus.all()

    /**
     * Restart any downloads that were interrupted by process death. Call from [Application.onCreate].
     */
    fun resumeActiveDownloads() {
        repository.getAllDownloads()
            .subscribeOn(Schedulers.io())
            .subscribe(
                { entries ->
                    for (entry in entries) {
                        when (DownloadStatus.fromName(entry.status)) {
                            DownloadStatus.PENDING,
                            DownloadStatus.RUNNING,
                            DownloadStatus.RETRYING -> {
                                if (!downloadRunner.isActive(entry.url)) {
                                    downloadRunner.start(entry.url)
                                }
                            }
                            else -> Unit
                        }
                    }
                },
                { logger.log(TAG, "resumeActiveDownloads failed", it) },
            )
    }

    /**
     * Enqueue a new download. Transfers begin immediately in-process; the UI shows RUNNING
     * (not Queued) from the first frame.
     */
    fun enqueue(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ): Disposable {
        val resolvedFileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val resolvedMimeType = mimeType?.takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(resolvedFileName.substringAfterLast('.', ""))
        val readableSize = if (contentLength > 0) {
            Formatter.formatShortFileSize(application, contentLength)
        } else {
            "?"
        }
        val now = System.currentTimeMillis()
        val totalBytes = if (contentLength > 0) contentLength else -1L
        val entry = DownloadEntry(
            url = url,
            title = resolvedFileName,
            contentSize = readableSize,
            status = DownloadStatus.RUNNING.name,
            mimeType = resolvedMimeType,
            userAgent = userAgent,
            cookies = null,
            totalBytes = totalBytes,
            bytesDownloaded = 0L,
            localPath = null,
            eTag = null,
            lastModified = null,
            createdAt = now,
            updatedAt = now,
            errorMessage = null,
        )

        stateBus.update(
            DownloadState(
                url = entry.url,
                title = entry.title,
                status = DownloadStatus.RUNNING,
                bytesDownloaded = 0L,
                totalBytes = entry.totalBytes,
            ),
        )
        notifier.updateProgress(entry.url, entry.title, 0L, entry.totalBytes)

        return repository.upsertDownload(entry).subscribe(
            {
                downloadRunner.start(url)
                logger.log(TAG, "Started download: ${entry.title} ($url)")
            },
            { logger.log(TAG, "Failed to upsert download row for $url", it) },
        )
    }

    /**
     * Cancel a download. Stops the in-process runner, marks the row CANCELLED, deletes any
     * partial bytes and clears the notification.
     */
    fun cancel(url: String) {
        stateBus.clearPauseRequested(url)
        runCatching {
            repository.updateStatus(
                url = url,
                status = DownloadStatus.CANCELLED,
                errorMessage = null,
            ).blockingAwait()
            stateBus.snapshot(url)?.let {
                stateBus.update(
                    it.copy(
                        status = DownloadStatus.CANCELLED,
                        errorMessage = null,
                        bytesPerSecond = 0L,
                    ),
                )
            }
            downloadRunner.cancelActive(url)
            WorkManager.getInstance(application).cancelUniqueWork(workName(url))
            runCatching { storage.deleteStaging(url) }
            notifier.cancel(url)
        }.onFailure { logger.log(TAG, "cancel failed for $url", it) }
    }

    /**
     * Pause a download. Cancels the in-process runner; bytes on disk are kept for resume.
     */
    fun pause(url: String) {
        stateBus.markPauseRequested(url)
        runCatching {
            val snapshot = stateBus.snapshot(url)
            val entry = repository.findDownloadForUrl(url).blockingGet()
            val bytesDownloaded = when {
                snapshot != null && snapshot.bytesDownloaded > 0L -> snapshot.bytesDownloaded
                entry != null -> storage.stagedBytesDownloaded(url, entry.title)
                    .takeIf { it > 0L } ?: entry.bytesDownloaded
                else -> 0L
            }
            val totalBytes = snapshot?.totalBytes ?: entry?.totalBytes ?: -1L
            repository.updateProgress(
                url = url,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                status = DownloadStatus.PAUSED,
            ).blockingAwait()
            if (snapshot != null) {
                stateBus.update(
                    snapshot.copy(
                        status = DownloadStatus.PAUSED,
                        bytesDownloaded = bytesDownloaded,
                        totalBytes = totalBytes,
                        errorMessage = null,
                        bytesPerSecond = 0L,
                    ),
                )
            }
            downloadRunner.cancelActive(url)
            WorkManager.getInstance(application).cancelUniqueWork(workName(url))
        }.onFailure {
            stateBus.clearPauseRequested(url)
            logger.log(TAG, "pause failed for $url", it)
        }
    }

    /** Resume (or restart) a previously paused/failed download. */
    fun resume(url: String) {
        stateBus.clearPauseRequested(url)
        runCatching {
            repository.updateStatus(
                url = url,
                status = DownloadStatus.RUNNING,
                errorMessage = null,
            ).blockingAwait()
            val snapshot = stateBus.snapshot(url)
            if (snapshot != null) {
                stateBus.update(
                    snapshot.copy(
                        status = DownloadStatus.RUNNING,
                        errorMessage = null,
                    ),
                )
            }
            downloadRunner.start(url)
        }.onFailure { logger.log(TAG, "resume failed for $url", it) }
    }

    /** Alias for [resume] surfaced as "Retry" in the UI for terminal-failed entries. */
    fun retry(url: String): Unit = resume(url)

    /**
     * Remove a download from the manager entirely.
     */
    fun deleteEntry(url: String, alsoDeleteFile: Boolean) {
        downloadRunner.cancelActive(url)
        WorkManager.getInstance(application).cancelUniqueWork(workName(url))
        runCatching { storage.deleteStaging(url) }
        if (alsoDeleteFile) {
            repository.findDownloadForUrl(url).subscribe(
                { entry -> storage.deleteCommitted(entry.localPath) },
                { logger.log(TAG, "deleteEntry: lookup failed", it) },
            )
        }
        repository.deleteDownload(url).subscribe(
            { stateBus.forget(url); notifier.cancel(url) },
            { logger.log(TAG, "deleteEntry: db delete failed", it) },
        )
    }

    /**
     * Returns true if Android successfully launched a viewer for the previously committed file.
     */
    fun openCommittedFile(localPath: String?, mimeType: String?): Boolean {
        if (localPath.isNullOrBlank()) return false
        val uri: Uri = try {
            when {
                localPath.startsWith("content://") -> Uri.parse(localPath)
                localPath.startsWith("file://") -> {
                    val file = File(Uri.parse(localPath).path ?: return false)
                    if (!file.exists()) return false
                    fileProviderUri(file)
                }
                else -> {
                    val file = File(localPath)
                    if (!file.exists()) return false
                    fileProviderUri(file)
                }
            }
        } catch (_: Throwable) {
            return false
        }

        return runCatching {
            VideoViewerIntent.launch(application, uri, mimeType)
        }.getOrElse {
            logger.log(TAG, "openCommittedFile: no app could view $localPath", it)
            false
        }
    }

    private fun fileProviderUri(file: File): Uri = FileProvider.getUriForFile(
        application,
        "${BuildConfig.APPLICATION_ID}.fileprovider",
        file,
    )

    /**
     * Build a JSON snapshot of every known download (DB rows + live bus state merged).
     */
    fun getDownloadsJson(): String = runCatching {
        val persisted = repository.getAllDownloads().blockingGet().orEmpty()
        val live = stateBus.all()
        val merged = LinkedHashMap<String, JSONObject>(persisted.size + live.size)
        for (entry in persisted) {
            merged[entry.url] = entry.toJson()
        }
        for ((url, state) in live) {
            val base = merged[url] ?: JSONObject().also { merged[url] = it }
            val persistedStatus = base.optString("status")
            if (isTerminalPersistedStatus(persistedStatus)) {
                state.applyEphemeralOnto(base)
            } else {
                state.applyOnto(base)
            }
        }
        val arr = JSONArray()
        for (json in merged.values) arr.put(json)
        arr.toString()
    }.getOrElse {
        logger.log(TAG, "getDownloadsJson failed", it)
        "[]"
    }

    private fun DownloadEntry.toJson(): JSONObject = JSONObject().apply {
        put("url", url)
        put("title", title)
        put("contentSize", contentSize)
        put("status", status)
        putOpt("mimeType", mimeType)
        put("totalBytes", totalBytes)
        put("bytesDownloaded", bytesDownloaded)
        putOpt("localPath", localPath)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        putOpt("errorMessage", errorMessage)
        put("bytesPerSecond", 0)
    }

    private fun DownloadState.applyOnto(target: JSONObject) {
        target.put("url", url)
        target.put("title", title)
        target.put("status", status.name)
        target.put("totalBytes", totalBytes)
        target.put("bytesDownloaded", bytesDownloaded)
        target.put("bytesPerSecond", bytesPerSecond)
        target.put("updatedAt", updatedAt)
        if (mimeType != null) target.put("mimeType", mimeType)
        if (localPath != null) target.put("localPath", localPath)
        if (errorMessage != null) target.put("errorMessage", errorMessage)
    }

    private fun DownloadState.applyEphemeralOnto(target: JSONObject) {
        target.put("bytesPerSecond", bytesPerSecond)
        target.put("updatedAt", updatedAt)
    }

    private fun isTerminalPersistedStatus(status: String): Boolean =
        status == DownloadStatus.COMPLETED.name ||
            status == DownloadStatus.FAILED.name ||
            status == DownloadStatus.CANCELLED.name ||
            status == DownloadStatus.PAUSED.name

    private fun workName(url: String): String = "minnal-download-" + url.hashCode().toString()

    companion object {
        private const val TAG = "MinnalDownloadManager"
    }
}
