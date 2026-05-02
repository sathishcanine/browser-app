package com.browser.minnal.html.download

import com.browser.minnal.migration.Cleanup
import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class DownloadCleanup @Inject constructor(
    private val application: Application
) : Cleanup.Action {
    override val versionCode: Int = 101

    override suspend fun execute() {
        withContext(Dispatchers.IO) {
            application.filesDir.listFiles()
                ?.filter { it.endsWith(DownloadPageFactory.FILENAME) }
                ?.forEach(File::delete)
        }
    }

}
