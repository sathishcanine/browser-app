package com.browser.minnal.download.manager

import android.content.Context
import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists in-flight download URLs so [DownloadForegroundService] and WorkManager can
 * resume after the user closes or swipes away the browser.
 */
@Singleton
class ActiveDownloadRegistry @Inject constructor(
    context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markActive(url: String) {
        prefs.edit().putString(storageKey(url), url).apply()
    }

    fun markInactive(url: String) {
        prefs.edit().remove(storageKey(url)).apply()
    }

    fun hasActive(): Boolean = prefs.all.isNotEmpty()

    fun activeUrls(): Set<String> =
        prefs.all.values.mapNotNull { value ->
            (value as? String)?.takeIf { it.isNotBlank() }
        }.toSet()

    private fun storageKey(url: String): String = "url_${url.hashCode()}"

    companion object {
        private const val PREFS_NAME = "active_downloads"
    }
}
