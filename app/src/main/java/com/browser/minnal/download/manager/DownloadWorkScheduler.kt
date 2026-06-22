package com.browser.minnal.download.manager

import android.app.Application
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules [DownloadWorker] as a backup when the in-process runner is killed.
 */
@Singleton
class DownloadWorkScheduler @Inject constructor(
    private val application: Application,
) {

    fun schedule(url: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.INPUT_URL to url))
            .addTag(DownloadWorker.workTagFor(url))
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(application).enqueueUniqueWork(
            workName(url),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(url: String) {
        WorkManager.getInstance(application).cancelUniqueWork(workName(url))
    }

    private fun workName(url: String): String = "minnal-download-" + url.hashCode().toString()
}
