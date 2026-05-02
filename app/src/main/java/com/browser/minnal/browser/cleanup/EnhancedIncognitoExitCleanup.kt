package com.browser.minnal.browser.cleanup

import com.browser.minnal.log.Logger
import com.browser.minnal.utils.WebUtils
import android.app.Activity
import javax.inject.Inject

/**
 * Exit cleanup that should be run when the incognito process is exited on API >= 28. This cleanup
 * clears cookies and all web data, which can be done without affecting
 */
class EnhancedIncognitoExitCleanup @Inject constructor(
    private val logger: Logger,
    private val activity: Activity
) : ExitCleanup {
    override fun cleanUp() {
        WebUtils.clearCache(activity)
        logger.log(TAG, "Cache Cleared")
        WebUtils.clearCookies()
        logger.log(TAG, "Cookies Cleared")
        WebUtils.clearWebStorage()
        logger.log(TAG, "WebStorage Cleared")
    }

    companion object {
        private const val TAG = "EnhancedIncognitoExitCleanup"
    }
}
