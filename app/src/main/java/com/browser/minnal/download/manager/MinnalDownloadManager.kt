package com.browser.minnal.download.manager

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.browser.minnal.BuildConfig
import com.browser.minnal.database.downloads.DownloadEntry
import com.browser.minnal.database.downloads.DownloadStatus
import com.browser.minnal.database.downloads.DownloadsRepository
import com.browser.minnal.log.Logger
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public-facing API for enqueueing / cancelling downloads. The browser only ever talks to
 * this class; everything else (engine, worker, storage, notifications) is plumbing that this
 * coordinator wires together.
 *
 * Concurrency model: each URL is owned by exactly one work request, identified by the URL's
 * unique work name. Re-enqueueing the same URL is idempotent (`KEEP` policy).
 */
@Singleton
class MinnalDownloadManager @Inject constructor(
    private val application: Application,
    private val repository: DownloadsRepository,
    private val stateBus: DownloadStateBus,
    private val notifier: DownloadNotifier,
    private val storage: DownloadStorage,
    private val logger: Logger
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
     * Enqueue a new download. If a work request already exists for [url] (download already
     * in progress) this is a no-op. The DB row is upserted synchronously via Rx so callers
     * can immediately see it on the in-app Downloads page.
     */
    fun enqueue(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
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
        val entry = DownloadEntry(
            url = url,
            title = resolvedFileName,
            contentSize = readableSize,
            status = DownloadStatus.PENDING.name,
            mimeType = resolvedMimeType,
            userAgent = userAgent,
            cookies = null,
            totalBytes = if (contentLength > 0) contentLength else -1L,
            bytesDownloaded = 0L,
            localPath = null,
            eTag = null,
            lastModified = null,
            createdAt = now,
            updatedAt = now,
            errorMessage = null
        )

        // Surface the new entry in the bus immediately so the UI can render it before WorkManager
        // even starts the worker.
        stateBus.update(
            DownloadState(
                url = entry.url,
                title = entry.title,
                status = DownloadStatus.PENDING,
                bytesDownloaded = 0L,
                totalBytes = entry.totalBytes
            )
        )

        return repository.upsertDownload(entry).subscribe(
            {
                scheduleWork(url)
                logger.log(TAG, "Enqueued download: ${entry.title} ($url)")
            },
            { logger.log(TAG, "Failed to upsert download row for $url", it) }
        )
    }

    /**
     * Cancel a download. Removes the work request, marks the row CANCELLED, deletes any
     * partial bytes and clears the notification.
     *
     * If [also remove from the DB] is desired, callers should additionally invoke
     * [DownloadsRepository.deleteDownload] after cancel.
     */
    fun cancel(url: String) {
        WorkManager.getInstance(application).cancelUniqueWork(workName(url))
        // The worker's CancellationException handler already does the DB / staging cleanup,
        // but if the worker hasn't started yet we still need to apply terminal state.
        repository.updateStatus(
            url = url,
            status = DownloadStatus.CANCELLED,
            errorMessage = null
        ).subscribe({}, { logger.log(TAG, "cancel: status update failed", it) })
        runCatching { storage.deleteStaging(url) }
        notifier.cancel(url)
        stateBus.snapshot(url)?.let {
            stateBus.update(it.copy(status = DownloadStatus.CANCELLED))
        }
    }

    /**
     * Pause a download. Implemented as "cancel the worker and mark PAUSED". Bytes already on
     * disk are kept; calling [resume] later restarts the worker which Range-resumes from the
     * partial file via the validators stored on the row.
     */
    fun pause(url: String) {
        WorkManager.getInstance(application).cancelUniqueWork(workName(url))
        repository.updateStatus(
            url = url,
            status = DownloadStatus.PAUSED,
            errorMessage = null
        ).subscribe({}, { logger.log(TAG, "pause: status update failed", it) })
        stateBus.snapshot(url)?.let {
            stateBus.update(it.copy(status = DownloadStatus.PAUSED))
        }
    }

    /** Resume (or restart) a previously paused/failed download. */
    fun resume(url: String) {
        repository.updateStatus(
            url = url,
            status = DownloadStatus.PENDING,
            errorMessage = null
        ).subscribe(
            { scheduleWork(url) },
            { logger.log(TAG, "resume: status update failed", it) }
        )
    }

    /** Alias for [resume] surfaced as "Retry" in the UI for terminal-failed entries. */
    fun retry(url: String): Unit = resume(url)

    /**
     * Remove a download from the manager entirely.
     *
     *  - If the worker is still running, cancels it first.
     *  - Deletes any partial bytes from the staging cache.
     *  - When [alsoDeleteFile] is true and the entry was already committed, attempts to
     *    delete the published file too (works for both legacy file paths and MediaStore URIs).
     *  - Removes the row from SQLite, drops the bus snapshot and clears any leftover
     *    notification.
     */
    fun deleteEntry(url: String, alsoDeleteFile: Boolean) {
        WorkManager.getInstance(application).cancelUniqueWork(workName(url))
        runCatching { storage.deleteStaging(url) }
        if (alsoDeleteFile) {
            repository.findDownloadForUrl(url).subscribe(
                { entry -> storage.deleteCommitted(entry.localPath) },
                { logger.log(TAG, "deleteEntry: lookup failed", it) }
            )
        }
        repository.deleteDownload(url).subscribe(
            { stateBus.forget(url); notifier.cancel(url) },
            { logger.log(TAG, "deleteEntry: db delete failed", it) }
        )
    }

    /**
     * Returns true if Android successfully launched a viewer for the previously committed file.
     * The page calls this when the user taps "Open" on a completed row.
     */
    fun openCommittedFile(localPath: String?, mimeType: String?): Boolean {
        if (localPath.isNullOrBlank()) return false
        val uri: Uri = try {
            when {
                localPath.startsWith("content://") -> Uri.parse(localPath)
                localPath.startsWith("file://") -> {
                    // Legacy file:// URIs would crash on API 24+; convert via FileProvider.
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

        val resolvedMime = mimeType?.takeIf { it.isNotBlank() }
            ?: application.contentResolver.getType(uri)
            ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, resolvedMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { application.startActivity(intent); true }.getOrElse {
            logger.log(TAG, "openCommittedFile: no app could view $localPath", it)
            false
        }
    }

    private fun fileProviderUri(file: File): Uri = FileProvider.getUriForFile(
        application,
        "${BuildConfig.APPLICATION_ID}.fileprovider",
        file
    )

    /**
     * Build a JSON snapshot of every known download (DB rows + live bus state merged), suitable
     * for handing to the JS-side download manager UI. Live bus snapshots take precedence over
     * persisted rows so the page sees up-to-date progress and speed.
     */
    fun getDownloadsJson(): String {
        val persisted = repository.getAllDownloads().blockingGet().orEmpty()
        val live = stateBus.all()
        val merged = LinkedHashMap<String, JSONObject>(persisted.size + live.size)
        for (entry in persisted) {
            merged[entry.url] = entry.toJson()
        }
        // Bus values may include URLs that aren't yet persisted (PENDING just enqueued); add them.
        for ((url, state) in live) {
            val base = merged[url] ?: JSONObject().also { merged[url] = it }
            state.applyOnto(base)
        }
        val arr = JSONArray()
        for (json in merged.values) arr.put(json)
        return arr.toString()
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
        // Default ephemeral fields; the bus may overwrite them below.
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

    private fun scheduleWork(url: String) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(Data.Builder().putString(DownloadWorker.INPUT_URL, url).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(DownloadWorker.workTagFor(url))
            .build()
        WorkManager.getInstance(application)
            .enqueueUniqueWork(workName(url), ExistingWorkPolicy.KEEP, request)
    }

    private fun workName(url: String): String = "minnal-download-" + url.hashCode().toString()

    companion object {
        private const val TAG = "MinnalDownloadManager"
    }
}
