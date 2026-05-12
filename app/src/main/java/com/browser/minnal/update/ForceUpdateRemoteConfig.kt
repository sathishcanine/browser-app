package com.browser.minnal.update

import android.content.Context
import com.browser.minnal.BuildConfig
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import java.util.concurrent.TimeUnit

/**
 * Remote Config keys (create matching parameters in the Firebase console):
 * - [KEY_FORCE_UPDATE_ENABLED]: when true, outdated clients must update before browsing.
 * - [KEY_MIN_SUPPORTED_VERSION_CODE]: minimum required [BuildConfig.VERSION_CODE]. Use 0 when
 *   forcing is off; when forcing is on, set this to the lowest version code still allowed.
 */
object ForceUpdateRemoteConfig {

    const val KEY_FORCE_UPDATE_ENABLED = "force_update_enabled"
    const val KEY_MIN_SUPPORTED_VERSION_CODE = "min_supported_version_code"

    /**
     * Fetches and activates Remote Config. Call from a background thread only
     * ([Tasks.await] may perform network I/O).
     */
    fun fetchAndActivateBlocking(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) return
        val rc = FirebaseRemoteConfig.getInstance()
        val settings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = if (BuildConfig.DEBUG) 0 else 3600
        }
        runCatching {
            Tasks.await(rc.setConfigSettingsAsync(settings), 3L, TimeUnit.SECONDS)
        }
        runCatching {
            Tasks.await(
                rc.setDefaultsAsync(
                    mapOf(
                        KEY_FORCE_UPDATE_ENABLED to false,
                        KEY_MIN_SUPPORTED_VERSION_CODE to 0L,
                    ),
                ),
                3L,
                TimeUnit.SECONDS,
            )
        }
        runCatching {
            Tasks.await(rc.fetchAndActivate(), 8L, TimeUnit.SECONDS)
        }
    }

    fun readNeedsForceUpdate(): Boolean =
        runCatching {
            val rc = FirebaseRemoteConfig.getInstance()
            val enabled = rc.getBoolean(KEY_FORCE_UPDATE_ENABLED)
            val minCode = rc.getLong(KEY_MIN_SUPPORTED_VERSION_CODE)
            enabled && minCode > 0L && BuildConfig.VERSION_CODE < minCode
        }.getOrDefault(false)
}
