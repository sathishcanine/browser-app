package com.browser.minnal.database.downloads

import com.browser.minnal.database.databaseDelegate
import com.browser.minnal.extensions.firstOrNullMap
import com.browser.minnal.extensions.useMap
import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentValues
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The disk backed download database. See [DownloadsRepository] for function documentation.
 *
 * Schema versions:
 *  - v1: legacy {url, title, size}.
 *  - v2: adds status / progress / mime / cookies / etag / paths so the in-built downloader can
 *        persist live state. Existing rows are migrated additively (no data loss); their `status`
 *        defaults to COMPLETED so the in-app Downloads page renders them as before.
 */
@SuppressLint("Range")
@Singleton
class DownloadsDatabase @Inject constructor(
    application: Application
) : SQLiteOpenHelper(application, DATABASE_NAME, null, DATABASE_VERSION), DownloadsRepository {

    private val database: SQLiteDatabase by databaseDelegate()

    override fun onCreate(db: SQLiteDatabase) {
        val createDownloadsTable = buildString {
            append("CREATE TABLE ")
            append(DatabaseUtils.sqlEscapeString(TABLE_DOWNLOADS))
            append('(')
            append(DatabaseUtils.sqlEscapeString(KEY_ID)).append(" INTEGER PRIMARY KEY,")
            append(DatabaseUtils.sqlEscapeString(KEY_URL)).append(" TEXT UNIQUE,")
            append(DatabaseUtils.sqlEscapeString(KEY_TITLE)).append(" TEXT,")
            append(DatabaseUtils.sqlEscapeString(KEY_SIZE)).append(" TEXT,")
            append(DatabaseUtils.sqlEscapeString(KEY_STATUS)).append(" TEXT,")
            append(DatabaseUtils.sqlEscapeString(KEY_MIME_TYPE)).append(" TEXT,")
            append(DatabaseUtils.sqlEscapeString(KEY_USER_AGENT)).append(" TEXT,")
            append(DatabaseUtils.sqlEscapeString(KEY_COOKIES)).append(" TEXT,")
            append(DatabaseUtils.sqlEscapeString(KEY_TOTAL_BYTES)).append(" INTEGER DEFAULT -1,")
            append(DatabaseUtils.sqlEscapeString(KEY_BYTES_DOWNLOADED)).append(" INTEGER DEFAULT 0,")
            append(DatabaseUtils.sqlEscapeString(KEY_LOCAL_PATH)).append(" TEXT,")
            append(DatabaseUtils.sqlEscapeString(KEY_ETAG)).append(" TEXT,")
            append(DatabaseUtils.sqlEscapeString(KEY_LAST_MODIFIED)).append(" TEXT,")
            append(DatabaseUtils.sqlEscapeString(KEY_CREATED_AT)).append(" INTEGER DEFAULT 0,")
            append(DatabaseUtils.sqlEscapeString(KEY_UPDATED_AT)).append(" INTEGER DEFAULT 0,")
            append(DatabaseUtils.sqlEscapeString(KEY_ERROR_MESSAGE)).append(" TEXT")
            append(')')
        }
        db.execSQL(createDownloadsTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2 && newVersion >= 2) {
            // Additive migration: keep existing {url, title, size} rows and add the new columns
            // with sensible defaults so previously completed downloads still render in the page.
            db.beginTransaction()
            try {
                db.execSQL("ALTER TABLE $TABLE_DOWNLOADS ADD COLUMN $KEY_STATUS TEXT")
                db.execSQL("ALTER TABLE $TABLE_DOWNLOADS ADD COLUMN $KEY_MIME_TYPE TEXT")
                db.execSQL("ALTER TABLE $TABLE_DOWNLOADS ADD COLUMN $KEY_USER_AGENT TEXT")
                db.execSQL("ALTER TABLE $TABLE_DOWNLOADS ADD COLUMN $KEY_COOKIES TEXT")
                db.execSQL("ALTER TABLE $TABLE_DOWNLOADS ADD COLUMN $KEY_TOTAL_BYTES INTEGER DEFAULT -1")
                db.execSQL("ALTER TABLE $TABLE_DOWNLOADS ADD COLUMN $KEY_BYTES_DOWNLOADED INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE_DOWNLOADS ADD COLUMN $KEY_LOCAL_PATH TEXT")
                db.execSQL("ALTER TABLE $TABLE_DOWNLOADS ADD COLUMN $KEY_ETAG TEXT")
                db.execSQL("ALTER TABLE $TABLE_DOWNLOADS ADD COLUMN $KEY_LAST_MODIFIED TEXT")
                db.execSQL("ALTER TABLE $TABLE_DOWNLOADS ADD COLUMN $KEY_CREATED_AT INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE_DOWNLOADS ADD COLUMN $KEY_UPDATED_AT INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE_DOWNLOADS ADD COLUMN $KEY_ERROR_MESSAGE TEXT")
                db.execSQL(
                    "UPDATE $TABLE_DOWNLOADS SET $KEY_STATUS = ? WHERE $KEY_STATUS IS NULL",
                    arrayOf<Any>(DownloadStatus.COMPLETED.name)
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override fun findDownloadForUrl(url: String): Maybe<DownloadEntry> = Maybe.fromCallable {
        database.query(
            TABLE_DOWNLOADS,
            null,
            "$KEY_URL=?",
            arrayOf(url),
            null,
            null,
            "1"
        ).firstOrNullMap { it.bindToDownloadItem() }
    }

    override fun isDownload(url: String): Single<Boolean> = Single.fromCallable {
        database.query(
            TABLE_DOWNLOADS,
            null,
            "$KEY_URL=?",
            arrayOf(url),
            null,
            null,
            null,
            "1"
        ).use {
            return@fromCallable it.moveToFirst()
        }
    }

    override fun addDownloadIfNotExists(entry: DownloadEntry): Single<Boolean> =
        Single.fromCallable {
            database.query(
                TABLE_DOWNLOADS,
                null,
                "$KEY_URL=?",
                arrayOf(entry.url),
                null,
                null,
                "1"
            ).use {
                if (it.moveToFirst()) {
                    return@fromCallable false
                }
            }

            val id = database.insert(TABLE_DOWNLOADS, null, entry.toContentValues())

            return@fromCallable id != -1L
        }

    override fun upsertDownload(entry: DownloadEntry): Completable = Completable.fromAction {
        val updated = database.update(
            TABLE_DOWNLOADS,
            entry.toContentValues(),
            "$KEY_URL=?",
            arrayOf(entry.url)
        )
        if (updated == 0) {
            database.insert(TABLE_DOWNLOADS, null, entry.toContentValues())
        }
    }

    override fun updateProgress(
        url: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        status: DownloadStatus
    ): Completable = Completable.fromAction {
        val values = ContentValues(4).apply {
            put(KEY_BYTES_DOWNLOADED, bytesDownloaded)
            put(KEY_TOTAL_BYTES, totalBytes)
            put(KEY_STATUS, status.name)
            put(KEY_UPDATED_AT, System.currentTimeMillis())
        }
        database.update(TABLE_DOWNLOADS, values, "$KEY_URL=?", arrayOf(url))
    }

    override fun updateStatus(
        url: String,
        status: DownloadStatus,
        localPath: String?,
        errorMessage: String?
    ): Completable = Completable.fromAction {
        val values = ContentValues(4).apply {
            put(KEY_STATUS, status.name)
            put(KEY_UPDATED_AT, System.currentTimeMillis())
            if (localPath != null) put(KEY_LOCAL_PATH, localPath)
            if (errorMessage != null) put(KEY_ERROR_MESSAGE, errorMessage)
        }
        database.update(TABLE_DOWNLOADS, values, "$KEY_URL=?", arrayOf(url))
    }

    override fun addDownloadsList(downloadEntries: List<DownloadEntry>): Completable =
        Completable.fromAction {
            database.apply {
                beginTransaction()
                try {
                    for (item in downloadEntries) {
                        addDownloadIfNotExists(item).subscribe()
                    }
                    setTransactionSuccessful()
                } finally {
                    endTransaction()
                }
            }
        }

    override fun deleteDownload(url: String): Single<Boolean> = Single.fromCallable {
        return@fromCallable database.delete(TABLE_DOWNLOADS, "$KEY_URL=?", arrayOf(url)) > 0
    }

    override fun deleteAllDownloads(): Completable = Completable.fromAction {
        database.run {
            delete(TABLE_DOWNLOADS, null, null)
            close()
        }
    }

    override fun getAllDownloads(): Single<List<DownloadEntry>> = Single.fromCallable {
        return@fromCallable database.query(
            TABLE_DOWNLOADS,
            null,
            null,
            null,
            null,
            null,
            "$KEY_ID DESC"
        ).useMap { it.bindToDownloadItem() }
    }

    override fun count(): Long = DatabaseUtils.queryNumEntries(database, TABLE_DOWNLOADS)

    /**
     * Maps the fields of [DownloadEntry] to [ContentValues].
     */
    private fun DownloadEntry.toContentValues() = ContentValues(15).apply {
        put(KEY_TITLE, title)
        put(KEY_URL, url)
        put(KEY_SIZE, contentSize)
        put(KEY_STATUS, status)
        put(KEY_MIME_TYPE, mimeType)
        put(KEY_USER_AGENT, userAgent)
        put(KEY_COOKIES, cookies)
        put(KEY_TOTAL_BYTES, totalBytes)
        put(KEY_BYTES_DOWNLOADED, bytesDownloaded)
        put(KEY_LOCAL_PATH, localPath)
        put(KEY_ETAG, eTag)
        put(KEY_LAST_MODIFIED, lastModified)
        put(KEY_CREATED_AT, createdAt)
        put(KEY_UPDATED_AT, updatedAt)
        put(KEY_ERROR_MESSAGE, errorMessage)
    }

    /**
     * Binds a [Cursor] to a single [DownloadEntry]. Tolerant of legacy rows whose extra columns
     * are NULL because they were inserted before schema v2.
     */
    private fun Cursor.bindToDownloadItem(): DownloadEntry {
        fun nullableLong(column: String, default: Long): Long {
            val idx = getColumnIndex(column)
            return if (idx < 0 || isNull(idx)) default else getLong(idx)
        }

        fun nullableString(column: String): String? {
            val idx = getColumnIndex(column)
            return if (idx < 0 || isNull(idx)) null else getString(idx)
        }

        return DownloadEntry(
            url = getString(getColumnIndex(KEY_URL)),
            title = getString(getColumnIndex(KEY_TITLE)).orEmpty(),
            contentSize = getString(getColumnIndex(KEY_SIZE)).orEmpty(),
            status = nullableString(KEY_STATUS) ?: DownloadStatus.COMPLETED.name,
            mimeType = nullableString(KEY_MIME_TYPE),
            userAgent = nullableString(KEY_USER_AGENT),
            cookies = nullableString(KEY_COOKIES),
            totalBytes = nullableLong(KEY_TOTAL_BYTES, -1L),
            bytesDownloaded = nullableLong(KEY_BYTES_DOWNLOADED, 0L),
            localPath = nullableString(KEY_LOCAL_PATH),
            eTag = nullableString(KEY_ETAG),
            lastModified = nullableString(KEY_LAST_MODIFIED),
            createdAt = nullableLong(KEY_CREATED_AT, 0L),
            updatedAt = nullableLong(KEY_UPDATED_AT, 0L),
            errorMessage = nullableString(KEY_ERROR_MESSAGE)
        )
    }

    companion object {
        private const val DATABASE_VERSION = 2
        private const val DATABASE_NAME = "downloadManager"

        private const val TABLE_DOWNLOADS = "download"

        private const val KEY_ID = "id"
        private const val KEY_URL = "url"
        private const val KEY_TITLE = "title"
        private const val KEY_SIZE = "size"
        private const val KEY_STATUS = "status"
        private const val KEY_MIME_TYPE = "mime_type"
        private const val KEY_USER_AGENT = "user_agent"
        private const val KEY_COOKIES = "cookies"
        private const val KEY_TOTAL_BYTES = "total_bytes"
        private const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        private const val KEY_LOCAL_PATH = "local_path"
        private const val KEY_ETAG = "etag"
        private const val KEY_LAST_MODIFIED = "last_modified"
        private const val KEY_CREATED_AT = "created_at"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val KEY_ERROR_MESSAGE = "error_message"
    }
}
