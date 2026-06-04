package com.browser.minnal.html.download

import com.browser.minnal.migration.Cleanup
import com.browser.minnal.migration.GeneratedHtmlFiles
import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Drops the cached downloads page so upgrades pick up new JS (rating prompt bridge, completion toast).
 */
class DownloadCleanup @Inject constructor(
    private val application: Application
) : Cleanup.Action {
    override val versionCode: Int = 103

    override suspend fun execute() {
        withContext(Dispatchers.IO) {
            GeneratedHtmlFiles.deletePage(application, DownloadPageFactory.FILENAME)
        }
    }
}
