package com.browser.minnal.ads

import com.browser.minnal.BuildConfig
import com.browser.minnal.R
import com.browser.minnal.dialog.BrowserDialog
import com.browser.minnal.log.Logger
import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog

/**
 * JavaScript bridge exposed to web pages as `window.MinnalApp`. Lets a partner site
 * (currently allowlisted to [BuildConfig.MINNAL_BRIDGE_HOSTS]) request a rewarded ad
 * before unlocking app-only features such as gated downloads.
 *
 * One instance is bound per [WebView]; bridge methods are invoked by WebView on a
 * private background thread so anything that touches Android UI / Ads SDK is
 * dispatched back to the main thread via [mainHandler].
 *
 * Public API (JavaScript):
 * ```
 * MinnalApp.isMinnalBrowser()         // -> true
 * MinnalApp.getVersion()              // -> "1.0.0"
 * MinnalApp.showRewardedAd("onMinnalReward")
 *   // Calls window.onMinnalReward("REWARDED" | "DISMISSED_NO_REWARD" | "FAILED" | "BLOCKED").
 *   // If the user closes the ad without earning a reward, a native message is shown and the
 *   // current tab reloads after that dialog is dismissed.
 * ```
 */
class MinnalJsBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val rewardedAdController: RewardedAdController,
    private val logger: Logger,
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val allowedHosts: Set<String> =
        BuildConfig.MINNAL_BRIDGE_HOSTS
            .split(',')
            .mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }
            .toSet()

    @JavascriptInterface
    fun isMinnalBrowser(): Boolean = true

    @JavascriptInterface
    fun getVersion(): String = BuildConfig.VERSION_NAME

    /**
     * Show a rewarded ad and dispatch the result to `window[callbackName](status)` on
     * the page. Status is always one of REWARDED / DISMISSED_NO_REWARD / FAILED /
     * BLOCKED so the page can rely on a callback firing exactly once.
     */
    @JavascriptInterface
    fun showRewardedAd(callbackName: String?) {
        val safeCallback = sanitizeCallbackName(callbackName)
        mainHandler.post {
            if (!isCurrentHostAllowed()) {
                logger.log(TAG, "Bridge call from non-allowlisted host: ${webView.url}")
                dispatch(safeCallback, "BLOCKED")
                return@post
            }
            rewardedAdController.show { result ->
                when (result) {
                    RewardedAdController.Result.DISMISSED_NO_REWARD -> {
                        dispatch(safeCallback, result.name)
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            showRewardedDismissedThenReloadOnDialogClose()
                        }
                    }
                    else -> dispatch(safeCallback, result.name)
                }
            }
        }
    }

    private fun showRewardedDismissedThenReloadOnDialogClose() {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.rewarded_dismissed_title)
            .setMessage(R.string.rewarded_dismissed_message)
            .setPositiveButton(R.string.action_ok, null)
            .setOnDismissListener {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    webView.reload()
                }
            }
            .create()
        dialog.show()
        BrowserDialog.setDialogSize(activity, dialog)
    }

    private fun dispatch(callback: String?, status: String) {
        if (callback == null) return
        // Wrap in a typeof check so a missing callback doesn't throw in page console.
        val js = "if (typeof window.$callback === 'function') { window.$callback('$status'); }"
        webView.evaluateJavascript(js, null)
    }

    private fun isCurrentHostAllowed(): Boolean {
        val host = runCatching { Uri.parse(webView.url ?: return false).host }
            .getOrNull()
            ?.lowercase()
            ?: return false
        return allowedHosts.any { allowed -> host == allowed || host.endsWith(".$allowed") }
    }

    /**
     * Only allow bare JS identifiers as callback names (letters, digits, `_`, `$`) so a
     * page can't inject expressions through this parameter.
     */
    private fun sanitizeCallbackName(name: String?): String? {
        if (name.isNullOrEmpty()) return null
        return name.takeIf { it.matches(Regex("^[A-Za-z_\\$][A-Za-z0-9_\\$]*$")) }
    }

    companion object {
        const val NAME = "MinnalApp"
        private const val TAG = "MinnalJsBridge"
    }
}
