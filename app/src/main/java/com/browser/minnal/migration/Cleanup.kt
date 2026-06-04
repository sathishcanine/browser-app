package com.browser.minnal.migration

import android.app.Application
import android.content.Context
import javax.inject.Inject

/**
 * Runs one-shot cleanup / migration actions after an app upgrade (or on first install).
 *
 * Each [Action] has a monotonically increasing [Action.versionCode]; actions with a code greater
 * than the last executed code are run once and never repeated on later launches.
 */
class Cleanup @Inject constructor(
    private val application: Application,
    private val actions: List<@JvmSuppressWildcards Action>,
) {

    suspend fun cleanup() {
        val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastExecutedVersion = prefs.getInt(KEY_LAST_EXECUTED_ACTION_VERSION, 0)
        val pending = actions
            .filter { it.versionCode > lastExecutedVersion }
            .sortedBy { it.versionCode }
        if (pending.isEmpty()) {
            return
        }

        pending.forEach { action ->
            runCatching { action.execute() }
                .onFailure { throwable ->
                    // Do not block startup; a failed migration must not crash the upgrade path.
                    android.util.Log.w(TAG, "Cleanup action ${action.versionCode} failed", throwable)
                }
        }

        prefs.edit()
            .putInt(KEY_LAST_EXECUTED_ACTION_VERSION, pending.maxOf { it.versionCode })
            .apply()
    }

    interface Action {
        val versionCode: Int
        suspend fun execute()
    }

    companion object {
        private const val TAG = "Cleanup"
        private const val PREFS_NAME = "migration_cleanup"
        private const val KEY_LAST_EXECUTED_ACTION_VERSION = "lastExecutedActionVersion"
    }
}
