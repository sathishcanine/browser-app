package com.browser.minnal.download.manager

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.browser.minnal.BrowserApp
import com.browser.minnal.log.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager fallback when the process was killed mid-download. Normal enqueues run via
 * [DownloadRunner] immediately and never sit in [DownloadStatus.PENDING].
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val component get() = (applicationContext as BrowserApp).applicationComponent
    private val downloadRunner get() = component.downloadRunner()
    private val logger: Logger get() = component.logger()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(INPUT_URL)
        if (url.isNullOrBlank()) {
            logger.log(TAG, "DownloadWorker started without an INPUT_URL; refusing to run.")
            return@withContext Result.failure()
        }
        downloadRunner.runForWorker(url, runAttemptCount)
    }

    companion object {
        const val INPUT_URL = "minnal.download.input.url"
        const val WORK_TAG_PREFIX = "minnal-download:"

        private const val TAG = "DownloadWorker"

        fun workTagFor(url: String): String = WORK_TAG_PREFIX + url
    }
}
