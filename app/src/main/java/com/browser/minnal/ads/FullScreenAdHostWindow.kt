package com.browser.minnal.ads

import android.app.Activity
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.WeakHashMap

/**
 * Resets the host activity window before AdMob full-screen formats are shown.
 *
 * Immersive browsing / [android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN] on the host can
 * cause rewarded and interstitial overlays to draw under the status or navigation bars on
 * edge-to-edge devices (Android 15+), which inflates accidental taps (high CTR) and can hide the
 * close control. We restore the previous window state when the ad closes.
 */
object FullScreenAdHostWindow {

    private data class Snapshot(
        val systemUiVisibility: Int,
        val hasFullscreenFlag: Boolean,
        val statusBarColor: Int,
        val navigationBarColor: Int,
    )

    private val snapshots = WeakHashMap<Activity, Snapshot>()

    fun prepare(activity: Activity) {
        val window = activity.window
        val decor = window.decorView
        if (!snapshots.containsKey(activity)) {
            snapshots[activity] = Snapshot(
                systemUiVisibility = decor.systemUiVisibility,
                hasFullscreenFlag = window.attributes.flags and
                    WindowManager.LayoutParams.FLAG_FULLSCREEN != 0,
                statusBarColor = window.statusBarColor,
                navigationBarColor = window.navigationBarColor,
            )
        }

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        WindowInsetsControllerCompat(window, decor).apply {
            show(WindowInsetsCompat.Type.statusBars())
            show(WindowInsetsCompat.Type.navigationBars())
        }
        ViewCompat.requestApplyInsets(decor)
    }

    fun restore(activity: Activity) {
        val snapshot = snapshots.remove(activity) ?: return
        val window = activity.window
        val decor = window.decorView

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = snapshot.statusBarColor
        window.navigationBarColor = snapshot.navigationBarColor
        if (snapshot.hasFullscreenFlag) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        @Suppress("DEPRECATION")
        decor.systemUiVisibility = snapshot.systemUiVisibility
        ViewCompat.requestApplyInsets(decor)
    }
}
