package com.browser.minnal.adblock.util.`object`

import com.browser.minnal.adblock.util.hash.HashingAlgorithm
import android.app.Application
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/**
 * An [ObjectStore] that serializes objects using the [ObjectInputStream].
 *
 * @param application Application used to construct files.
 * @param hashingAlgorithm The hashing algorithm used to construct cache file names.
 */
class JvmObjectStore<T>(
    private val application: Application,
    private val hashingAlgorithm: HashingAlgorithm<String>,
    private val key: String,
    private val objectStoreDispatcher: CoroutineDispatcher,
) : ObjectStore<T> where T : Any, T : Serializable {

    /**
     * Create the file in which to store the object, using the cache directory.
     */
    private fun createStorageFile() = File(
        application.cacheDir,
        "object-store-${hashingAlgorithm.hash(key)}"
    )

    @Suppress("UNCHECKED_CAST")
    override suspend fun retrieve(): T? = withContext(objectStoreDispatcher) {
        val storageFile = createStorageFile()
        if (!storageFile.exists()) {
            return@withContext null
        }
        if (storageFile.length() < MIN_SERIALIZED_BYTES) {
            storageFile.delete()
            return@withContext null
        }
        return@withContext try {
            FileInputStream(storageFile).use { fileInputStream ->
                ObjectInputStream(fileInputStream).use { objectInputStream ->
                    @Suppress("UNCHECKED_CAST")
                    objectInputStream.readObject() as T
                }
            }
        } catch (_: Throwable) {
            storageFile.delete()
            null
        }
    }

    override suspend fun store(value: T): Unit = withContext(objectStoreDispatcher) {
        val storageFile = createStorageFile()
        val tempFile = File(storageFile.parentFile, "${storageFile.name}.tmp")
        try {
            FileOutputStream(tempFile, false).use { fileOutputStream ->
                ObjectOutputStream(fileOutputStream).use { objectOutputStream ->
                    objectOutputStream.writeObject(value)
                    objectOutputStream.flush()
                }
            }
            if (!tempFile.renameTo(storageFile)) {
                tempFile.copyTo(storageFile, overwrite = true)
                tempFile.delete()
            }
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        }
    }

    override suspend fun clear(): Unit = withContext(objectStoreDispatcher) {
        val storageFile = createStorageFile()
        storageFile.delete()
        File(storageFile.parentFile, "${storageFile.name}.tmp").delete()
    }

    companion object {
        /** Java serialization stream header is at least 4 bytes (magic + version). */
        private const val MIN_SERIALIZED_BYTES = 4L
    }
}
