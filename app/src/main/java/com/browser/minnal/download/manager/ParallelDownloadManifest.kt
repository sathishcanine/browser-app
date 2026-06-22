package com.browser.minnal.download.manager

import java.io.File
import java.util.Properties
import kotlin.math.abs

/**
 * Persists parallel byte-range layout so resume attempts reuse the same part boundaries
 * instead of re-splitting when the server probe flickers.
 */
internal object ParallelDownloadManifest {

    private const val FILE_NAME = "parallel-manifest.properties"
    private const val KEY_TOTAL_BYTES = "totalBytes"
    private const val KEY_CONNECTIONS = "connections"

    data class SavedRange(val start: Long, val endInclusive: Long) {
        val length: Long get() = endInclusive - start + 1
    }

    data class SavedLayout(
        val totalBytes: Long,
        val connections: Int,
        val ranges: List<SavedRange>,
    )

    fun save(partDir: File, totalBytes: Long, ranges: List<SavedRange>) {
        val props = Properties()
        props.setProperty(KEY_TOTAL_BYTES, totalBytes.toString())
        props.setProperty(KEY_CONNECTIONS, ranges.size.toString())
        ranges.forEachIndexed { index, range ->
            props.setProperty("part.$index.start", range.start.toString())
            props.setProperty("part.$index.end", range.endInclusive.toString())
        }
        File(partDir, FILE_NAME).outputStream().use { props.store(it, "Minnal parallel download") }
    }

    fun load(partDir: File): SavedLayout? {
        val file = File(partDir, FILE_NAME)
        if (!file.exists()) return null
        val props = Properties()
        file.inputStream().use { props.load(it) }
        val totalBytes = props.getProperty(KEY_TOTAL_BYTES)?.toLongOrNull() ?: return null
        val connections = props.getProperty(KEY_CONNECTIONS)?.toIntOrNull() ?: return null
        if (totalBytes <= 0L || connections <= 1) return null
        val ranges = (0 until connections).mapNotNull { index ->
            val start = props.getProperty("part.$index.start")?.toLongOrNull() ?: return null
            val end = props.getProperty("part.$index.end")?.toLongOrNull() ?: return null
            SavedRange(start, end)
        }
        if (ranges.size != connections) return null
        return SavedLayout(totalBytes = totalBytes, connections = connections, ranges = ranges)
    }

    fun exists(partDir: File): Boolean = File(partDir, FILE_NAME).exists()

    fun isCompatible(saved: SavedLayout, probeTotalBytes: Long): Boolean {
        if (probeTotalBytes <= 0L) return true
        val tolerance = maxOf(4096L, saved.totalBytes / 500L)
        return abs(saved.totalBytes - probeTotalBytes) <= tolerance
    }

    fun delete(partDir: File) {
        File(partDir, FILE_NAME).delete()
    }
}
