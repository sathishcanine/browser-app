package com.browser.minnal.html.history

import com.browser.minnal.migration.Cleanup
import com.browser.minnal.migration.GeneratedHtmlFiles
import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class HistoryCleanup @Inject constructor(
    private val application: Application
) : Cleanup.Action {
    override val versionCode: Int = 101

    override suspend fun execute() {
        withContext(Dispatchers.IO) {
            GeneratedHtmlFiles.deletePage(application, HistoryPageFactory.FILENAME)
        }
    }
}
