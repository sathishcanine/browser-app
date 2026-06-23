package com.browser.minnal.download.manager

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.browser.minnal.preference.UserPreferences
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encapsulates *where* a downloaded file ends up on disk.
 *
 * - The transfer always goes into a private temp file in `cacheDir/inbuilt-downloads/` so we
 *   can safely Range-resume across process restarts without polluting the user's gallery.
 * - When the transfer finishes successfully [commit] is called to atomically publish the
 *   bytes to a user-visible location:
 *     * API 29+ (Android 10+): inserted into [MediaStore.Downloads]; this works regardless of
 *       scoped storage and shows up in the system Files app.
 *     * API 26-28: copied into [Environment.DIRECTORY_DOWNLOADS] (or the user-configured
 *       [UserPreferences.downloadDirectory]) using the legacy WRITE_EXTERNAL_STORAGE permission.
 *
 * The committed location (an absolute file path or a `content://` URI string) is what the
 * worker writes back to [com.browser.minnal.database.downloads.DownloadEntry.localPath] so the
 * in-app Downloads page can link to it.
 */
@Singleton
class DownloadStorage @Inject constructor(
    private val application: Application,
    private val userPreferences: UserPreferences
) {

    /**
     * Returns (creating if needed) the staging file used for partial bytes during the
     * transfer. The same path is returned for the same [url], so subsequent resume attempts
     * reuse whatever bytes are already on disk.
     *
     * Two unrelated URLs can produce the same filename (e.g. multiple `video.mp4`s); the URL
     * itself is hashed into the directory name so they never collide on the staging area.
     */
    fun stagingFile(url: String, fileName: String): File {
        val parent = stagingDir(url)
        if (!parent.exists()) parent.mkdirs()
        return File(parent, safeFileName(fileName))
    }

    /** Number of `part-N` files on disk from a prior parallel transfer (0 if none). */
    fun existingParallelPartCount(url: String): Int {
        val parent = stagingDir(url)
        if (!parent.exists()) return 0
        return parent.listFiles()
            ?.count { file ->
                file.isFile && file.name.startsWith(PART_FILE_PREFIX) &&
                    file.name.length > PART_FILE_PREFIX.length
            }
            ?: 0
    }

    /**
     * Total bytes already on disk for an in-progress transfer. Parallel downloads store partial
     * data in `part-N` files; single-connection downloads write directly to [stagingFile].
     */
    fun stagedBytesDownloaded(url: String, fileName: String): Long {
        val parent = stagingDir(url)
        if (!parent.exists()) return 0L
        val staging = File(parent, safeFileName(fileName))
        val partFiles = parent.listFiles()
            ?.filter { it.isFile && it.name.startsWith(PART_FILE_PREFIX) }
            .orEmpty()
        if (partFiles.isNotEmpty()) {
            val legacy = partFiles.any { it.length() > PART_PROGRESS_BYTES }
            if (legacy) {
                return partFiles.sumOf { it.length().coerceAtLeast(0L) }
            }
            // Parallel direct-write: staging may be pre-allocated to full size; part-N files
            // hold per-range byte counters (8 bytes each).
            return partFiles.sumOf { readPartProgressForStorage(it) }
        }
        if (staging.exists() && staging.length() > 0L) {
            return staging.length().coerceAtLeast(0L)
        }
        return 0L
    }

    private fun readPartProgressForStorage(progressFile: File): Long {
        if (!progressFile.exists()) return 0L
        val length = progressFile.length()
        if (length < 8L) return 0L
        if (length > PART_PROGRESS_BYTES) return length
        return progressFile.inputStream().use { input ->
            val buffer = ByteArray(8)
            if (input.read(buffer) < 8) 0L else {
                var value = 0L
                for (i in 0 until 8) {
                    value = (value shl 8) or (buffer[i].toLong() and 0xFF)
                }
                value
            }
        }
    }

    private fun safeFileName(fileName: String): String {
        val base = File(fileName).name.trim()
        return base.takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: "download"
    }

    /** Removes any partial bytes for [url]. Called after Cancel or on permanent failure. */
    fun deleteStaging(url: String) {
        stagingDir(url).deleteRecursively()
    }

    private fun stagingDir(url: String): File =
        File(application.cacheDir, "$STAGING_DIR/${stableKey(url)}")

    /**
     * Best-effort removal of a previously committed file. Accepts either an absolute file path
     * (legacy storage) or a `content://` URI (MediaStore.Downloads). Failures are swallowed so a
     * "Delete from list" action never errors when the underlying file was already deleted by the
     * user via the system Files app.
     */
    fun deleteCommitted(localPath: String?): Boolean {
        if (localPath.isNullOrBlank()) return false
        return runCatching {
            if (localPath.startsWith("content://")) {
                val uri = Uri.parse(localPath)
                application.contentResolver.delete(uri, null, null) > 0
            } else {
                File(localPath).takeIf { it.exists() }?.delete() == true
            }
        }.getOrDefault(false)
    }

    /**
     * Move/copy the fully downloaded [stagingFile] into the user-visible Downloads area and
     * return a stringified locator (absolute path or `content://` URI) suitable for storing in
     * the SQLite row and opening later via [Uri.parse].
     */
    fun commit(stagingFile: File, fileName: String, mimeType: String?): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            commitViaMediaStore(stagingFile, fileName, mimeType)
        } else {
            commitViaLegacyFile(stagingFile, fileName)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun commitViaMediaStore(
        stagingFile: File,
        fileName: String,
        mimeType: String?
    ): String {
        val resolver = application.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = downloadsRelativePath()
        val uniqueName = uniqueFileName(mediaStoreDisplayName(fileName)) { candidate ->
            mediaStoreNameExists(candidate, relativePath)
        }
        val itemUri = insertMediaStoreItem(
            resolver = resolver,
            collection = collection,
            displayName = uniqueName,
            mimeType = mimeType,
            relativePath = relativePath,
        ) ?: insertMediaStoreItem(
            resolver = resolver,
            collection = collection,
            displayName = uniqueFileName(mediaStoreDisplayName(fileName, aggressive = true)) { candidate ->
                mediaStoreNameExists(candidate, relativePath)
            },
            mimeType = mimeType,
            relativePath = relativePath,
        )

        if (itemUri != null) {
            try {
                resolver.openOutputStream(itemUri, "w")?.use { rawOut ->
                    rawOut.buffered(JOIN_BUFFER_BYTES).use { out ->
                        FileInputStream(stagingFile).use { input -> input.copyTo(out) }
                    }
                } ?: throw IOException("MediaStore.openOutputStream returned null for $itemUri")

                val publishedValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(itemUri, publishedValues, null, null)
                return itemUri.toString()
            } catch (t: Throwable) {
                runCatching { resolver.delete(itemUri, null, null) }
                throw if (t is IOException) t else IOException("Failed to publish download via MediaStore", t)
            } finally {
                cleanupStaging(stagingFile)
            }
        }

        // Some OEM builds reject MediaStore.insert (null) even on API 29+; keep the file in an
        // app-accessible Downloads folder rather than failing the whole transfer.
        return commitViaAppExternalDownloads(stagingFile, uniqueName)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertMediaStoreItem(
        resolver: ContentResolver,
        collection: Uri,
        displayName: String,
        mimeType: String?,
        relativePath: String,
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            if (!mimeType.isNullOrBlank()) put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return resolver.insert(collection, values)
    }

    private fun commitViaAppExternalDownloads(stagingFile: File, fileName: String): String {
        val targetDir = application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(application.filesDir, "downloads")
        if (!targetDir.exists()) targetDir.mkdirs()
        val target = File(targetDir, uniqueFileName(fileName) { candidate ->
            File(targetDir, candidate).exists()
        })
        copyStagingToFile(stagingFile, target)
        cleanupStaging(stagingFile)
        return target.absolutePath
    }

    private fun commitViaLegacyFile(stagingFile: File, fileName: String): String {
        val targetDir = userDownloadDir()
        if (!targetDir.exists()) targetDir.mkdirs()
        val target = File(targetDir, uniqueFileName(fileName) { candidate ->
            File(targetDir, candidate).exists()
        })
        copyStagingToFile(stagingFile, target)
        cleanupStaging(stagingFile)
        return target.absolutePath
    }

    private fun copyStagingToFile(stagingFile: File, target: File) {
        val moved = stagingFile.renameTo(target)
        if (!moved) {
            stagingFile.inputStream().use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
            stagingFile.delete()
        }
    }

    private fun cleanupStaging(stagingFile: File) {
        stagingFile.delete()
        stagingFile.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
    }

    /** RELATIVE_PATH must end with '/' per MediaStore contract; some OEMs return null from insert without it. */
    private fun downloadsRelativePath(): String = "${Environment.DIRECTORY_DOWNLOADS}/"

    private fun mediaStoreDisplayName(fileName: String, aggressive: Boolean = false): String {
        val base = safeFileName(fileName)
        if (!aggressive) {
            return base
                .replace(INVALID_MEDIA_STORE_CHARS, "_")
                .take(MAX_DISPLAY_NAME_LENGTH)
                .ifBlank { "download" }
        }
        val dot = base.lastIndexOf('.')
        val namePart = if (dot > 0) base.substring(0, dot) else base
        val extPart = if (dot > 0) base.substring(dot) else ""
        val cleanName = namePart.replace(Regex("""[^\w.\- ]"""), "_").trim()
        val cleanExt = extPart.replace(Regex("""[^\w.]"""), "")
        return (cleanName + cleanExt)
            .take(MAX_DISPLAY_NAME_LENGTH)
            .ifBlank { "download" }
    }

    private fun userDownloadDir(): File {
        val configured = userPreferences.downloadDirectory.takeIf { it.isNotBlank() }
        return if (configured != null) File(configured) else
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    @Suppress("NewApi")
    private fun mediaStoreNameExists(name: String, relativePath: String = downloadsRelativePath()): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = application.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(name, "$relativePath%")
        return runCatching {
            resolver.query(collection, projection, selection, args, null)?.use { it.count > 0 }
        }.getOrNull() == true
    }

    private inline fun uniqueFileName(
        fileName: String,
        existsPredicate: (String) -> Boolean
    ): String {
        if (!existsPredicate(fileName)) return fileName
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var counter = 1
        while (true) {
            val candidate = "$base ($counter)$ext"
            if (!existsPredicate(candidate)) return candidate
            counter++
            if (counter > 999) return "$base (${System.currentTimeMillis()})$ext"
        }
    }

    /**
     * Stable, filesystem-safe identifier for [url] — used for the staging directory so two
     * concurrent downloads of files that happen to share a name don't clobber each other.
     */
    private fun stableKey(url: String): String {
        val hash = url.hashCode().toLong() and 0x7FFFFFFFL
        return hash.toString(16)
    }

    companion object {
        private const val STAGING_DIR = "inbuilt-downloads"
        private const val PART_FILE_PREFIX = "part-"
        private const val PART_PROGRESS_BYTES = 8L
        private const val JOIN_BUFFER_BYTES = 256 * 1024
        private const val MAX_DISPLAY_NAME_LENGTH = 255
        private val INVALID_MEDIA_STORE_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001f]""")
    }
}
