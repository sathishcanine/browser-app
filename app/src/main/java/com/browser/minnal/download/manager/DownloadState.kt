package com.browser.minnal.download.manager

import com.browser.minnal.database.downloads.DownloadStatus

/**
 * In-memory snapshot of a single download. Emitted by [DownloadStateBus] to UI listeners
 * (in-app downloads page, future widgets, tests).
 *
 * Includes ephemeral fields (e.g. live transfer rate) that we deliberately don't persist
 * because they only make sense while a worker is actively running.
 */
data class DownloadState(
    val url: String,
    val title: String,
    val status: DownloadStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val errorMessage: String? = null,
    val localPath: String? = null,
    val mimeType: String? = null,
    /** Instantaneous transfer rate sampled by the worker. 0 when unknown / not running. */
    val bytesPerSecond: Long = 0L,
    /** Wall-clock millis the manager last touched this state, useful for "last updated" labels. */
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** Progress as a 0..100 integer. -1 when total size is unknown. */
    val progressPercent: Int
        get() = when {
            totalBytes <= 0 -> -1
            else -> ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
        }

    /** Estimated seconds remaining, or -1 when total size or speed is unknown. */
    val etaSeconds: Long
        get() = when {
            totalBytes <= 0 || bytesPerSecond <= 0 || bytesDownloaded >= totalBytes -> -1L
            else -> (totalBytes - bytesDownloaded) / bytesPerSecond
        }
}
