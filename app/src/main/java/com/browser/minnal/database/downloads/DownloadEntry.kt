package com.browser.minnal.database.downloads

/**
 * An entry in the downloads database.
 *
 * Older rows (created before the in-built downloader) only populate [url], [title] and
 * [contentSize]; the remaining fields default to a "completed, system-managed" download
 * so the in-app Downloads page still renders them correctly.
 *
 * @param url The URL of the original download.
 * @param title The file name displayed to the user.
 * @param contentSize The user-readable content size at the time the download was queued.
 * @param status One of [DownloadStatus] values.
 * @param mimeType MIME type reported by the server (or guessed from the URL).
 * @param userAgent User agent that was used to issue the download request.
 * @param cookies Cookie header forwarded from the WebView session, if any.
 * @param totalBytes Total bytes for the download, or -1 when unknown.
 * @param bytesDownloaded Bytes already written for this download.
 * @param localPath For in-progress downloads: absolute path to the partial file.
 *                  For completed downloads: absolute path or a `content://` MediaStore URI.
 * @param eTag HTTP `ETag` header for resume validation.
 * @param lastModified HTTP `Last-Modified` header for resume validation.
 * @param createdAt Wall-clock millis when the download was first queued.
 * @param updatedAt Wall-clock millis of the most recent state change.
 * @param errorMessage Last error message, when [status] == [DownloadStatus.FAILED].
 */
data class DownloadEntry(
    val url: String,
    val title: String,
    val contentSize: String,
    val status: String = DownloadStatus.COMPLETED.name,
    val mimeType: String? = null,
    val userAgent: String? = null,
    val cookies: String? = null,
    val totalBytes: Long = -1L,
    val bytesDownloaded: Long = 0L,
    val localPath: String? = null,
    val eTag: String? = null,
    val lastModified: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val errorMessage: String? = null
)
