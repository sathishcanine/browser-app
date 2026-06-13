package com.browser.minnal.download.manager

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.browser.minnal.preference.UserPreferences
import java.io.File
import java.io.FileInputStream
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
        val partFiles = parent.listFiles()
            ?.filter { it.isFile && it.name.startsWith(PART_FILE_PREFIX) }
            .orEmpty()
        if (partFiles.isNotEmpty()) {
            return partFiles.sumOf { it.length().coerceAtLeast(0L) }
        }
        return File(parent, safeFileName(fileName)).takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L
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
        val uniqueName = uniqueFileName(fileName) { candidate ->
            // Best-effort uniqueness against the same RELATIVE_PATH; we don't have a robust way
            // to query MediaStore for "does this name exist" without permission cost, so just
            // probe with a quick query.
            mediaStoreNameExists(candidate)
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, uniqueName)
            if (!mimeType.isNullOrBlank()) put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val itemUri = resolver.insert(collection, values)
            ?: error("MediaStore.insert returned null for $uniqueName")

        try {
            resolver.openOutputStream(itemUri, "w")?.use { out ->
                FileInputStream(stagingFile).use { input -> input.copyTo(out) }
            } ?: error("MediaStore.openOutputStream returned null for $itemUri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publishedValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(itemUri, publishedValues, null, null)
            }
        } catch (t: Throwable) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw t
        } finally {
            stagingFile.delete()
            stagingFile.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
        }
        return itemUri.toString()
    }

    private fun commitViaLegacyFile(stagingFile: File, fileName: String): String {
        val targetDir = userDownloadDir()
        if (!targetDir.exists()) targetDir.mkdirs()
        val target = File(targetDir, uniqueFileName(fileName) { candidate ->
            File(targetDir, candidate).exists()
        })
        val moved = stagingFile.renameTo(target)
        if (!moved) {
            stagingFile.inputStream().use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
            stagingFile.delete()
        }
        stagingFile.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
        return target.absolutePath
    }

    private fun userDownloadDir(): File {
        val configured = userPreferences.downloadDirectory.takeIf { it.isNotBlank() }
        return if (configured != null) File(configured) else
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    @Suppress("NewApi")
    private fun mediaStoreNameExists(name: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = application.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(name, "${Environment.DIRECTORY_DOWNLOADS}%")
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
    }
}
