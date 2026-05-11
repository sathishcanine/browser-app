package com.browser.minnal.database.downloads

/**
 * Lifecycle of a download tracked by the in-built download manager.
 */
enum class DownloadStatus {
    /** Queued but the worker hasn't started yet. */
    PENDING,

    /** Worker is actively transferring bytes. */
    RUNNING,

    /** Worker was paused by the user; bytes already written are kept on disk. */
    PAUSED,

    /** Worker exited with retryable error; will resume automatically when network returns. */
    RETRYING,

    /** All bytes written and committed to the destination. */
    COMPLETED,

    /** Worker exited with a non-retryable error. See [DownloadEntry.errorMessage]. */
    FAILED,

    /** User cancelled; partial file (and DB row) was deleted by the worker. */
    CANCELLED;

    companion object {
        fun fromName(value: String?): DownloadStatus =
            value?.let { runCatching { valueOf(it) }.getOrNull() } ?: COMPLETED
    }
}
