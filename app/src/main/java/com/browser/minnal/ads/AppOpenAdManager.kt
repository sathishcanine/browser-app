package com.browser.minnal.ads

import com.browser.minnal.DefaultBrowserActivity
import android.app.Activity
import android.app.Application
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shows an app-open ad when the user returns to the browser from the background.
 *
 * Does not show on cold start. Other full-screen UI (e.g. rating prompt) should wait until
 * [isBlockingRatingPrompt] is false — see [setOnAppOpenFlowIdleListener].
 */
@Singleton
class AppOpenAdManager @Inject constructor(
    private val application: Application,
    private val appOpenAdHelper: AppOpenAdHelper,
) : DefaultLifecycleObserver, Application.ActivityLifecycleCallbacks {

    private var started = false

    /** Set in [onStop] when the user leaves the app (home / switch app). */
    private var wasAppBackgrounded = false

    /** Set in [onActivityStopped] when the browser activity is no longer visible. */
    private var browserActivityWasStopped = false

    /** Set in [onStop] when the user leaves the app; cleared after a show attempt on resume. */
    private var pendingShowOnNextBrowserResume = false

    private var resumedBrowserActivity: DefaultBrowserActivity? = null

    /** Prevents showing again after dismiss until the next background → foreground cycle. */
    private var showedAdThisForegroundSession = false

    /** Ad was not ready on resume; retry when [AppOpenAdHelper] finishes loading. */
    private var awaitingAdForForeground = false

    private var onAppOpenFlowIdleListener: (() -> Unit)? = null

    fun start() {
        if (started || isIncognitoProcess()) {
            return
        }
        started = true
        application.registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        appOpenAdHelper.setOnAdLoadedListener { onAppOpenAdLoaded() }
        appOpenAdHelper.setOnAdIdleListener { onAppOpenAdIdle() }
    }

    fun setOnAppOpenFlowIdleListener(listener: (() -> Unit)?) {
        onAppOpenFlowIdleListener = listener
    }

    /**
     * True while an app-open ad is visible or we are still trying to show one after backgrounding.
     * The rating prompt should wait until this is false.
     */
    fun isBlockingRatingPrompt(): Boolean =
        appOpenAdHelper.isShowingAd() || awaitingAdForForeground

    override fun onStart(owner: LifecycleOwner) {
        if (!wasAppBackgrounded) {
            resumedBrowserActivity?.let { preload(it) }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        wasAppBackgrounded = true
        pendingShowOnNextBrowserResume = true
        showedAdThisForegroundSession = false
        awaitingAdForForeground = false
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity is DefaultBrowserActivity) {
            browserActivityWasStopped = true
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is DefaultBrowserActivity) {
            resumedBrowserActivity = activity
        }
        if (activity !is DefaultBrowserActivity) {
            return
        }
        if (!wasAppBackgrounded || showedAdThisForegroundSession) {
            return
        }
        if (!browserActivityWasStopped && !pendingShowOnNextBrowserResume) {
            return
        }
        browserActivityWasStopped = false
        pendingShowOnNextBrowserResume = false
        tryShowOnResume(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedBrowserActivity === activity && !appOpenAdHelper.isShowingAd()) {
            resumedBrowserActivity = null
        }
    }

    private fun tryShowOnResume(activity: DefaultBrowserActivity) {
        if (!wasAppBackgrounded || showedAdThisForegroundSession || appOpenAdHelper.isShowingAd()) {
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            finishAppOpenForegroundFlow()
            return
        }
        if (appOpenAdHelper.showIfAvailable(activity)) {
            showedAdThisForegroundSession = true
            pendingShowOnNextBrowserResume = false
            awaitingAdForForeground = false
        } else if (appOpenAdHelper.isAdReady() || appOpenAdHelper.isLoadingAd()) {
            awaitingAdForForeground = true
            if (!appOpenAdHelper.isLoadingAd() && !appOpenAdHelper.isAdReady()) {
                preload(activity)
            }
        } else {
            awaitingAdForForeground = true
            preload(activity)
        }
    }

    private fun onAppOpenAdLoaded() {
        if (!awaitingAdForForeground || showedAdThisForegroundSession) {
            return
        }
        val activity = resumedBrowserActivity ?: run {
            finishAppOpenForegroundFlow()
            return
        }
        if (!wasAppBackgrounded) {
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            finishAppOpenForegroundFlow()
            return
        }
        if (appOpenAdHelper.showIfAvailable(activity)) {
            showedAdThisForegroundSession = true
            awaitingAdForForeground = false
        } else {
            finishAppOpenForegroundFlow()
        }
    }

    private fun onAppOpenAdIdle() {
        if (appOpenAdHelper.isShowingAd()) {
            return
        }
        if (awaitingAdForForeground) {
            finishAppOpenForegroundFlow()
        }
    }

    private fun finishAppOpenForegroundFlow() {
        awaitingAdForForeground = false
        if (!showedAdThisForegroundSession) {
            showedAdThisForegroundSession = true
        }
        onAppOpenFlowIdleListener?.invoke()
    }

    private fun preload(activity: DefaultBrowserActivity) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        appOpenAdHelper.load(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (resumedBrowserActivity === activity) {
            resumedBrowserActivity = null
        }
    }

    private fun isIncognitoProcess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName().endsWith(":incognito")
        }
        return false
    }
}
