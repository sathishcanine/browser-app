package com.browser.minnal.download.manager

import android.webkit.CookieManager
import com.browser.minnal.log.Logger
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive

/**
 * HTTP transfer for downloads. Supports single-connection downloads with Range resume, and
 * multi-part parallel downloads (IDM-style) when the server advertises `Accept-Ranges: bytes`.
 *
 * Resume strategy (single connection):
 *  - If [stagingFile] already has bytes and the remote sent an ETag/Last-Modified before, we
 *    issue `Range: bytes=N-` together with `If-Range:` so the server either resumes (HTTP 206)
 *    or restarts cleanly (HTTP 200) when the file changed.
 *
 * Resume strategy (parallel):
 *  - Each part is stored in a separate `part-N` file beside [stagingFile]. Incomplete parts
 *    resume with a ranged request from their current byte offset. When all parts finish, they
 *    are concatenated into [stagingFile].
 */
@Singleton
class DownloadEngine @Inject constructor(
    private val logger: Logger
) {

    private val httpClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequestsPerHost = MAX_CONNECTIONS
            maxRequests = MAX_CONNECTIONS * 2
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Result of a single transfer attempt. */
    sealed class Result {
        data class Success(
            val totalBytes: Long,
            val mimeType: String?,
            val eTag: String?,
            val lastModified: String?
        ) : Result()

        data class Retry(val cause: Throwable) : Result()
        data class Failure(val cause: Throwable) : Result()
    }

    private data class ProbeResult(
        val totalBytes: Long,
        val acceptsRanges: Boolean,
        val mimeType: String?,
        val eTag: String?,
        val lastModified: String?
    )

    private data class ByteRange(val start: Long, val endInclusive: Long) {
        val length: Long get() = endInclusive - start + 1
    }

    /**
     * Downloads [url] into [stagingFile].
     *
     * When [parallelConnections] is [PARALLEL_AUTO] or greater than 1 and the server supports byte
     * ranges, the file is split into dynamically chosen parts (4 up to 400 MB, 8 up to 2 GB,
     * 12 above) downloaded
     * concurrently and joined on completion. [SINGLE_CONNECTION] disables parallel mode. Otherwise
     * falls back to a single-connection transfer.
     */
    suspend fun download(
        url: String,
        stagingFile: File,
        userAgent: String?,
        existingETag: String?,
        existingLastModified: String?,
        parallelConnections: Int = 1,
        onProgress: (Long, Long) -> Unit
    ): Result {
        if (url.toHttpUrlOrNull() == null) {
            return Result.Failure(IllegalArgumentException("Invalid URL: $url"))
        }

        if (parallelConnections != SINGLE_CONNECTION) {
            when (val parallel = downloadParallel(
                url = url,
                stagingFile = stagingFile,
                userAgent = userAgent,
                connections = parallelConnections,
                existingETag = existingETag,
                existingLastModified = existingLastModified,
                onProgress = onProgress,
            )) {
                is ParallelOutcome.Completed -> return parallel.result
                ParallelOutcome.FallbackToSingle -> Unit
            }
        }

        clearPartFiles(stagingFile)
        return downloadSingle(
            url = url,
            stagingFile = stagingFile,
            userAgent = userAgent,
            existingETag = existingETag,
            existingLastModified = existingLastModified,
            onProgress = onProgress,
        )
    }

    private sealed class ParallelOutcome {
        data class Completed(val result: Result) : ParallelOutcome()
        data object FallbackToSingle : ParallelOutcome()
    }

    private suspend fun downloadParallel(
        url: String,
        stagingFile: File,
        userAgent: String?,
        connections: Int,
        existingETag: String?,
        existingLastModified: String?,
        onProgress: (Long, Long) -> Unit,
    ): ParallelOutcome {
        val probe = probeResource(url, userAgent) ?: return ParallelOutcome.FallbackToSingle
        if (!probe.acceptsRanges || probe.totalBytes < MIN_PARALLEL_BYTES) {
            logger.log(TAG, "Parallel download unavailable for $url (ranges=${probe.acceptsRanges}, size=${probe.totalBytes})")
            return ParallelOutcome.FallbackToSingle
        }

        val partDir = stagingFile.parentFile ?: return ParallelOutcome.FallbackToSingle
        partDir.mkdirs()

        val existingPartCount = partDir.listFiles()
            ?.count { it.isFile && it.name.startsWith("part-") }
            ?: 0

        val resolvedConnections = when {
            connections <= PARALLEL_AUTO -> dynamicParallelConnections(probe.totalBytes)
            else -> connections
        }
        // Reuse the same part layout when resuming so byte ranges stay aligned with part files.
        val effectiveConnections = when {
            existingPartCount > 1 -> existingPartCount.coerceIn(2, MAX_CONNECTIONS)
            else -> effectiveConnections(resolvedConnections, probe.totalBytes)
        }
        if (effectiveConnections <= 1) {
            return ParallelOutcome.FallbackToSingle
        }

        val ranges = splitRanges(probe.totalBytes, effectiveConnections)
        val partFiles = ranges.mapIndexed { index, _ -> File(partDir, "part-$index") }

        val hasOversizedParts = ranges.indices.any { index ->
            val part = partFiles[index]
            part.exists() && part.length() > ranges[index].length
        }
        if (hasOversizedParts) {
            logger.log(TAG, "Incompatible parallel parts for $url; falling back to single connection")
            clearPartFiles(stagingFile)
            return ParallelOutcome.FallbackToSingle
        }

        val initialBytes = ranges.indices.sumOf { index ->
            partFiles[index].takeIf { it.exists() }?.length()?.coerceAtMost(ranges[index].length) ?: 0L
        }
        safeProgress(onProgress, initialBytes, probe.totalBytes)

        val downloaded = AtomicLong(initialBytes)
        val lastProgressEmit = AtomicLong(0L)
        logger.log(
            TAG,
            "Parallel download: $effectiveConnections connections for $url " +
                "(${probe.totalBytes} bytes, requested=${if (connections <= PARALLEL_AUTO) "auto" else connections})",
        )

        val partResults = try {
            coroutineScope {
                ranges.mapIndexed { index, range ->
                    async {
                        downloadPart(
                            url = url,
                            range = range,
                            partFile = partFiles[index],
                            userAgent = userAgent,
                            eTag = existingETag ?: probe.eTag,
                            lastModified = existingLastModified ?: probe.lastModified,
                            onBytesWritten = { delta ->
                                val now = downloaded.addAndGet(delta)
                                val lastEmit = lastProgressEmit.get()
                                val time = System.currentTimeMillis()
                                if (time - lastEmit >= PROGRESS_EMIT_INTERVAL_MS &&
                                    lastProgressEmit.compareAndSet(lastEmit, time)
                                ) {
                                    safeProgress(onProgress, now, probe.totalBytes)
                                }
                            },
                        )
                    }
                }.awaitAll()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (io: IOException) {
            logger.log(TAG, "Parallel download interrupted for $url", io)
            return ParallelOutcome.Completed(Result.Retry(io))
        }

        val firstFailure = partResults.firstOrNull { it is Result.Failure } as? Result.Failure
        if (firstFailure != null) {
            return ParallelOutcome.Completed(firstFailure)
        }
        val firstRetry = partResults.firstOrNull { it is Result.Retry } as? Result.Retry
        if (firstRetry != null) {
            return ParallelOutcome.Completed(firstRetry)
        }

        for ((index, range) in ranges.withIndex()) {
            val part = partFiles[index]
            if (!part.exists() || part.length() != range.length) {
                return ParallelOutcome.Completed(
                    Result.Failure(IOException("Part $index incomplete after parallel transfer"))
                )
            }
        }

        return try {
            joinParts(stagingFile, partFiles)
            safeProgress(onProgress, probe.totalBytes, probe.totalBytes)
            ParallelOutcome.Completed(
                Result.Success(
                    totalBytes = probe.totalBytes,
                    mimeType = probe.mimeType,
                    eTag = probe.eTag,
                    lastModified = probe.lastModified,
                )
            )
        } catch (io: IOException) {
            logger.log(TAG, "Failed to join parallel parts for $url", io)
            ParallelOutcome.Completed(Result.Retry(io))
        }
    }

    private suspend fun downloadPart(
        url: String,
        range: ByteRange,
        partFile: File,
        userAgent: String?,
        eTag: String?,
        lastModified: String?,
        onBytesWritten: (Long) -> Unit,
    ): Result {
        val existingBytes = if (partFile.exists()) partFile.length().coerceAtMost(range.length) else 0L
        if (existingBytes >= range.length) {
            return Result.Success(
                totalBytes = range.length,
                mimeType = null,
                eTag = eTag,
                lastModified = lastModified,
            )
        }

        val requestStart = range.start + existingBytes
        val requestBuilder = buildRequest(url, userAgent)
            .header("Range", "bytes=$requestStart-${range.endInclusive}")
        if (existingBytes > 0L) {
            (eTag ?: lastModified)?.let { requestBuilder.header("If-Range", it) }
        }

        val call = httpClient.newCall(requestBuilder.build())
        val cancelHandle = attachCancellation(call, coroutineContext[Job])

        try {
            val response = try {
                call.execute()
            } catch (io: IOException) {
                return Result.Retry(io)
            }

            return response.use { resp ->
                when {
                    resp.code == 416 -> Result.Success(range.length, null, eTag, lastModified)
                    resp.code != 206 -> Result.Failure(
                        IOException(
                            "HTTP ${resp.code} for parallel part bytes=$requestStart-${range.endInclusive} " +
                                "(range response required)",
                        ),
                    )
                    else -> {
                        val body = resp.body ?: return Result.Failure(IOException("Empty body for part"))
                        partFile.parentFile?.takeIf { !it.exists() }?.mkdirs()
                        try {
                            body.byteStream().use { input ->
                                RandomAccessFile(partFile, "rw").use { raf ->
                                    raf.seek(existingBytes)
                                    val buffer = ByteArray(BUFFER_BYTES)
                                    while (true) {
                                        coroutineContext.ensureActive()
                                        val read = input.read(buffer)
                                        if (read == -1) break
                                        raf.write(buffer, 0, read)
                                        onBytesWritten(read.toLong())
                                    }
                                }
                            }
                        } catch (io: IOException) {
                            return Result.Retry(io)
                        }

                        val finalLength = partFile.length()
                        if (finalLength != range.length) {
                            Result.Failure(
                                IOException("Part size mismatch: expected ${range.length}, got $finalLength")
                            )
                        } else {
                            Result.Success(
                                range.length,
                                resp.header("Content-Type")?.substringBefore(';')?.trim(),
                                eTag,
                                lastModified,
                            )
                        }
                    }
                }
            }
        } finally {
            cancelHandle?.dispose()
        }
    }

    private suspend fun downloadSingle(
        url: String,
        stagingFile: File,
        userAgent: String?,
        existingETag: String?,
        existingLastModified: String?,
        onProgress: (Long, Long) -> Unit
    ): Result {
        val existingBytes = if (stagingFile.exists()) stagingFile.length() else 0L

        val requestBuilder = buildRequest(url, userAgent)
        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
            (existingETag ?: existingLastModified)?.let { requestBuilder.header("If-Range", it) }
        }

        val call = httpClient.newCall(requestBuilder.build())
        val cancelHandle = attachCancellation(call, coroutineContext[Job])

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

                            safeProgress(onProgress, written, totalBytes)

                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                raf.write(buffer, 0, read)
                                written += read
                                sinceLastEmit += read
                                if (sinceLastEmit >= PROGRESS_INTERVAL_BYTES) {
                                    safeProgress(onProgress, written, totalBytes)
                                    sinceLastEmit = 0L
                                }
                            }

                            safeProgress(onProgress, written, if (totalBytes < 0L) written else totalBytes)
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

    private fun probeResource(url: String, userAgent: String?): ProbeResult? {
        val headRequest = buildRequest(url, userAgent).head().build()
        probeFromResponse(executeProbe(headRequest))?.let { return it }

        val rangeProbe = buildRequest(url, userAgent)
            .header("Range", "bytes=0-0")
            .get()
            .build()
        return probeFromResponse(executeProbe(rangeProbe), rangeProbe = true)
    }

    private fun executeProbe(request: Request): okhttp3.Response? =
        try {
            httpClient.newCall(request).execute()
        } catch (io: IOException) {
            logger.log(TAG, "Probe failed for ${request.url}", io)
            null
        }

    private fun probeFromResponse(response: okhttp3.Response?, rangeProbe: Boolean = false): ProbeResult? {
        if (response == null) return null
        return response.use { resp ->
            if (!resp.isSuccessful && resp.code != 206) return null

            val acceptsRanges = resp.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true ||
                resp.code == 206

            val totalBytes = when {
                rangeProbe -> parseTotalFromContentRange(resp.header("Content-Range")) ?: -1L
                else -> resp.header("Content-Length")?.toLongOrNull() ?: -1L
            }

            if (totalBytes <= 0L) return null

            ProbeResult(
                totalBytes = totalBytes,
                acceptsRanges = acceptsRanges,
                mimeType = resp.header("Content-Type")?.substringBefore(';')?.trim(),
                eTag = resp.header("ETag"),
                lastModified = resp.header("Last-Modified"),
            )
        }
    }

    private fun parseTotalFromContentRange(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        val total = contentRange.substringAfterLast('/')
        if (total == "*" || total.isBlank()) return null
        return total.toLongOrNull()?.takeIf { it > 0L }
    }

    private fun splitRanges(totalBytes: Long, connections: Int): List<ByteRange> {
        val chunkSize = totalBytes / connections
        return (0 until connections).map { index ->
            val start = index * chunkSize
            val end = if (index == connections - 1) totalBytes - 1 else (index + 1) * chunkSize - 1
            ByteRange(start, end)
        }
    }

    private fun dynamicParallelConnections(totalBytes: Long): Int = when {
        totalBytes >= VERY_LARGE_FILE_THRESHOLD_BYTES -> CONNECTIONS_VERY_LARGE_FILE
        totalBytes >= LARGE_FILE_THRESHOLD_BYTES -> CONNECTIONS_LARGE_FILE
        else -> CONNECTIONS_SMALL_FILE
    }

    private fun effectiveConnections(requested: Int, totalBytes: Long): Int {
        val maxBySize = (totalBytes / MIN_PART_BYTES).toInt().coerceAtLeast(1)
        return requested.coerceIn(1, MAX_CONNECTIONS).coerceAtMost(maxBySize)
    }

    private fun clearPartFiles(stagingFile: File) {
        stagingFile.parentFile
            ?.listFiles()
            ?.filter { it.name.startsWith("part-") }
            ?.forEach { it.delete() }
    }

    private fun joinParts(destination: File, parts: List<File>) {
        destination.parentFile?.takeIf { !it.exists() }?.mkdirs()
        if (destination.exists() && !destination.delete()) {
            throw IOException("Could not replace staging file ${destination.absolutePath}")
        }
        try {
            destination.outputStream().use { out ->
                for (part in parts) {
                    if (!part.exists()) {
                        throw IOException("Missing part file ${part.name} during join")
                    }
                    part.inputStream().use { input -> input.copyTo(out) }
                }
            }
        } catch (t: Throwable) {
            runCatching { destination.delete() }
            throw t
        }
        parts.forEach { runCatching { it.delete() } }
    }

    private fun safeProgress(onProgress: (Long, Long) -> Unit, written: Long, total: Long) {
        runCatching { onProgress(written, total) }.onFailure {
            logger.log(TAG, "Progress callback failed at byte $written", it)
        }
    }

    private fun buildRequest(url: String, userAgent: String?): Request.Builder {
        val builder = Request.Builder().url(url)
        if (!userAgent.isNullOrBlank()) {
            builder.header("User-Agent", userAgent)
        }
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.header("Cookie", it) }
        return builder
    }

    private fun attachCancellation(call: okhttp3.Call, job: Job?): DisposableHandle? {
        if (job == null) return null
        return job.invokeOnCompletion {
            if (!call.isCanceled()) call.cancel()
        }
    }

    companion object {
        private const val TAG = "DownloadEngine"
        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_INTERVAL_BYTES = 256 * 1024L
        private const val MIN_PARALLEL_BYTES = 2L * 1024 * 1024
        private const val MIN_PART_BYTES = 512L * 1024
        private const val MAX_CONNECTIONS = 12
        private const val PROGRESS_EMIT_INTERVAL_MS = 250L
        private const val LARGE_FILE_THRESHOLD_BYTES = 400L * 1024 * 1024
        private const val VERY_LARGE_FILE_THRESHOLD_BYTES = 2L * 1024 * 1024 * 1024
        private const val CONNECTIONS_SMALL_FILE = 4
        private const val CONNECTIONS_LARGE_FILE = 8
        private const val CONNECTIONS_VERY_LARGE_FILE = 12

        /** Pick 4, 8, or 12 connections automatically based on probed file size. */
        const val PARALLEL_AUTO = 0

        /** Disable multi-part parallel downloading. */
        const val SINGLE_CONNECTION = 1
    }
}
