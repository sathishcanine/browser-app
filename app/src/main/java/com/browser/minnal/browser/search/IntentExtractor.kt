package com.browser.minnal.browser.search

import com.browser.minnal.browser.BrowserContract
import com.browser.minnal.search.SearchEngineProvider
import com.browser.minnal.utils.QUERY_PLACE_HOLDER
import com.browser.minnal.utils.smartUrlFilter
import android.app.SearchManager
import android.content.Intent
import javax.inject.Inject

/**
 * Extracts data from an [Intent] and into a [BrowserContract.Action].
 */
class IntentExtractor @Inject constructor(private val searchEngineProvider: SearchEngineProvider) {

    /**
     * Extract the action from the [intent] or return null if no data was extracted.
     */
    fun extractUrlFromIntent(intent: Intent?): BrowserContract.Action? {
        return when (intent?.action) {
            INTENT_PANIC_TRIGGER -> BrowserContract.Action.Panic
            Intent.ACTION_WEB_SEARCH ->
                extractSearchFromIntent(intent)?.let(BrowserContract.Action::LoadUrl)

            Intent.ACTION_VIEW -> intent.dataString?.let(BrowserContract.Action::LoadUrl)
            else -> null
        }
    }

    private fun extractSearchFromIntent(intent: Intent): String? {
        val query = intent.getStringExtra(SearchManager.QUERY)
        val searchUrl = "${searchEngineProvider.provideSearchEngine().queryUrl}$QUERY_PLACE_HOLDER"

        return if (query?.isNotBlank() == true) {
            smartUrlFilter(query, true, searchUrl)
        } else {
            null
        }
    }

    companion object {
        private const val INTENT_PANIC_TRIGGER = "info.guardianproject.panic.action.TRIGGER"
    }
}
