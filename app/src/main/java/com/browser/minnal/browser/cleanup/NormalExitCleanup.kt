package com.browser.minnal.browser.cleanup

import com.browser.minnal.browser.di.DatabaseScheduler
import com.browser.minnal.database.history.HistoryDatabase
import com.browser.minnal.log.Logger
import com.browser.minnal.preference.UserPreferences
import com.browser.minnal.utils.WebUtils
import android.app.Activity
import io.reactivex.rxjava3.core.Scheduler
import javax.inject.Inject

/**
 * Exit cleanup that should run whenever the main browser process is exiting.
 */
class NormalExitCleanup @Inject constructor(
    private val userPreferences: UserPreferences,
    private val logger: Logger,
    private val historyDatabase: HistoryDatabase,
    @DatabaseScheduler private val databaseScheduler: Scheduler,
    private val activity: Activity
) : ExitCleanup {
    override fun cleanUp() {
        if (userPreferences.clearCacheExit) {
            WebUtils.clearCache(activity)
            logger.log(TAG, "Cache Cleared")
        }
        if (userPreferences.clearHistoryExitEnabled) {
            WebUtils.clearHistory(activity, historyDatabase, databaseScheduler)
            logger.log(TAG, "History Cleared")
        }
        if (userPreferences.clearCookiesExitEnabled) {
            WebUtils.clearCookies()
            logger.log(TAG, "Cookies Cleared")
        }
        if (userPreferences.clearWebStorageExitEnabled) {
            WebUtils.clearWebStorage()
            logger.log(TAG, "WebStorage Cleared")
        }
    }

    companion object {
        const val TAG = "NormalExitCleanup"
    }
}
