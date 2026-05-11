package com.browser.minnal.download.manager

import android.webkit.CookieManager
import com.browser.minnal.log.Logger
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Pure HTTP transfer for a single download. No Android lifecycle, no notifications, no DB:
 * just "given these inputs, write bytes into this file and call me back on progress".
 *
 * Higher layers (worker, manager) decide *when* to invoke this, what to do with progress, and
 * how to translate failures into UI / persistent state.
 *
 * Resume strategy:
 *  - If [stagingFile] already has bytes and the remote sent an ETag/Last-Modified before, we
 *    issue `Range: bytes=N-` together with `If-Range:` so the server either resumes (HTTP 206)
 *    or restarts cleanly (HTTP 200) when the file changed.
 *  - On `200`, we truncate the staging file and start over. On `206`, we append.
 */
@Singleton
class DownloadEngine @Inject constructor(
    private val logger: Logger
) {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // Important: do NOT install a Cache; downloads are typically large and a cache would
            // double-write every file.
            .build()
    }

    /** Result of a single transfer attempt. */
    sealed class Result {
        /** All bytes were written; staging file holds the complete content. */
        data class Success(
            val totalBytes: Long,
            val mimeType: String?,
            val eTag: String?,
            val lastModified: String?
        ) : Result()

        /** Transient failure; the worker should retry (with backoff). Staging file is preserved. */
        data class Retry(val cause: Throwable) : Result()

        /** Permanent failure; do not retry. Staging file is preserved for inspection. */
        data class Failure(val cause: Throwable) : Result()
    }

    /**
     * Synchronously runs the transfer for [url] into [stagingFile].
     *
     * Suspends so that callers (the worker) can receive `CancellationException` cleanly: the
     * underlying OkHttp call is canceled at the next [coroutineContext.ensureActive] check.
     *
     * @param onProgress invoked roughly every [PROGRESS_INTERVAL_BYTES] bytes with
     *  (bytesDownloaded, totalBytes-or-(-1)).
     */
    suspend fun download(
        url: String,
        stagingFile: File,
        userAgent: String?,
        existingETag: String?,
        existingLastModified: String?,
        onProgress: (Long, Long) -> Unit
    ): Result {
        if (url.toHttpUrlOrNull() == null) {
            return Result.Failure(IllegalArgumentException("Invalid URL: $url"))
        }

        // Respect bytes already on disk so we resume instead of re-downloading.
        val existingBytes = if (stagingFile.exists()) stagingFile.length() else 0L

        val requestBuilder = Request.Builder().url(url).get()
        if (!userAgent.isNullOrBlank()) {
            requestBuilder.header("User-Agent", userAgent)
        }
        // Forward any cookies the WebView session has for this URL so authenticated downloads
        // (e.g. paywalled mp4s) work the same way they do via android.app.DownloadManager.
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { requestBuilder.header("Cookie", it) }

        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
            // If-Range lets the server fall back to a fresh 200 response when its content has
            // changed since we last touched it; we'll detect the 200 below and truncate.
            (existingETag ?: existingLastModified)?.let { validator ->
                requestBuilder.header("If-Range", validator)
            }
        }

        val call = httpClient.newCall(requestBuilder.build())

        // Tie OkHttp cancellation to coroutine cancellation so user-cancel kills the socket.
        val job = kotlinx.coroutines.CoroutineScope(coroutineContext).coroutineContext[kotlinx.coroutines.Job]
        val cancelHandle = job?.invokeOnCompletion {
            if (!call.isCanceled()) call.cancel()
        }

        try {
            val response = try {
                call.execute()
            } catch (io: IOException) {
                logger.log(TAG, "Network error for $url", io)
                return Result.Retry(io)
            }

            response.use { resp ->
                when {
                    resp.code == 416 -> {
                        // Range Not Satisfiable usually means we already have the entire file but
                        // the bookkeeping got out of sync. Treat as success with whatever we have.
                        return Result.Success(
                            totalBytes = stagingFile.length(),
                            mimeType = resp.header("Content-Type"),
                            eTag = resp.header("ETag"),
                            lastModified = resp.header("Last-Modified")
                        )
                    }

                    !resp.isSuccessful -> {
                        return Result.Failure(
                            IOException("HTTP ${resp.code} ${resp.message} for $url")
                        )
                    }
                }

                val isPartial = resp.code == 206
                val truncate = !isPartial && existingBytes > 0L
                if (truncate) {
                    logger.log(TAG, "Server ignored Range; restarting $url from scratch")
                    stagingFile.delete()
                }

                val body = resp.body ?: return Result.Failure(IOException("Empty body for $url"))
                val contentLength = body.contentLength()
                val totalBytes = when {
                    isPartial -> {
                        // Content-Range: bytes 1234-99999/100000  → take the last group as total.
                        resp.header("Content-Range")
                            ?.substringAfterLast('/')
                            ?.toLongOrNull()
                            ?: if (contentLength >= 0) contentLength + existingBytes else -1L
                    }
                    contentLength >= 0 -> contentLength
                    else -> -1L
                }
                val mimeType = resp.header("Content-Type")?.substringBefore(';')?.trim()
                val eTag = resp.header("ETag")
                val lastModified = resp.header("Last-Modified")

                val initialBytes = if (truncate) 0L else existingBytes

                stagingFile.parentFile?.takeIf { !it.exists() }?.mkdirs()

                try {
                    body.byteStream().use { input ->
                        RandomAccessFile(stagingFile, "rw").use { raf ->
                            raf.seek(initialBytes)
                            val buffer = ByteArray(BUFFER_BYTES)
                            var written = initialBytes
                            var sinceLastEmit = 0L

                            // Initial emit so the UI shows the right starting point.
                            onProgress(written, totalBytes)

                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                raf.write(buffer, 0, read)
                                written += read
                                sinceLastEmit += read
                                if (sinceLastEmit >= PROGRESS_INTERVAL_BYTES) {
                                    onProgress(written, totalBytes)
                                    sinceLastEmit = 0L
                                }
                            }

                            // Final emit so we always report the true byte count at end.
                            onProgress(written, if (totalBytes < 0L) written else totalBytes)
                        }
                    }
                } catch (io: IOException) {
                    logger.log(TAG, "Stream error for $url at byte ${stagingFile.length()}", io)
                    return Result.Retry(io)
                }

                return Result.Success(
                    totalBytes = if (totalBytes < 0L) stagingFile.length() else totalBytes,
                    mimeType = mimeType,
                    eTag = eTag,
                    lastModified = lastModified
                )
            }
        } finally {
            cancelHandle?.dispose()
        }
    }

    companion object {
        private const val TAG = "DownloadEngine"
        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_INTERVAL_BYTES = 256 * 1024L
    }
}
