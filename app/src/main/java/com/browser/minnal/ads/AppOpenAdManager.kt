package com.browser.minnal.ads

import com.browser.minnal.browser.BrowserActivity
import com.browser.minnal.preference.UserPreferences
import com.browser.minnal.rating.RatingPromptDialog
import android.app.Activity
import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shows an app-open ad only when the user returns from the background (not on cold app open).
 *
 * Does not load or show during the install's first foreground session
 * ([UserPreferences.appOpenAdFirstSessionCompleted]), while blocking overlays are visible
 * (default-browser prompt, system default-browser picker, rating prompt), or on cold start.
 * Other full-screen UI (e.g. rating prompt) should wait until [isBlockingRatingPrompt] is false —
 * see [setOnAppOpenFlowIdleListener].
 */
@Singleton
class AppOpenAdManager @Inject constructor(
    private val application: Application,
    private val appOpenAdHelper: AppOpenAdHelper,
    private val userPreferences: UserPreferences,
    private val homeScreenNativeAdScheduler: Lazy<HomeScreenNativeAdScheduler>,
) : DefaultLifecycleObserver, Application.ActivityLifecycleCallbacks {

    private var started = false

    /** Set when the user leaves the app after the browser activity was stopped. */
    private var wasAppBackgrounded = false

    /** Set in [onActivityStopped] when the browser activity is no longer visible. */
    private var browserActivityWasStopped = false

    /** Set in [onStop] when the user leaves the app; cleared after a show attempt on resume. */
    private var pendingShowOnNextBrowserResume = false

    private var resumedBrowserActivity: BrowserActivity? = null

    /** Prevents showing again after dismiss until the next background → foreground cycle. */
    private var showedAdThisForegroundSession = false

    /** Ad was not ready on resume; retry when [AppOpenAdHelper] finishes loading. */
    private var awaitingAdForForeground = false

    /** Retry show/load after a blocking overlay is dismissed. */
    private var pendingShowAfterOverlayClear = false

    private var defaultBrowserPromptVisible = false

    private var defaultBrowserSystemFlowActive = false

    private var forceUpdateDialogVisible = false

    private var onAppOpenFlowIdleListener: (() -> Unit)? = null

    fun start() {
        if (started) {
            return
        }
        started = true
        migrateExistingInstallAppOpenSession()
        application.registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        appOpenAdHelper.setOnAdLoadedListener { onAppOpenAdLoaded() }
        appOpenAdHelper.setOnAdIdleListener { onAppOpenAdIdle() }
    }

    fun setOnAppOpenFlowIdleListener(listener: (() -> Unit)?) {
        onAppOpenFlowIdleListener = listener
    }

    fun setDefaultBrowserPromptVisible(visible: Boolean) {
        if (defaultBrowserPromptVisible == visible) {
            return
        }
        defaultBrowserPromptVisible = visible
        if (visible) {
            cancelInFlightForegroundAdRequest()
        } else {
            notifyBlockingOverlayDismissed()
            return
        }
        notifyAdEnvironmentChanged()
    }

    fun setDefaultBrowserSystemFlowActive(active: Boolean) {
        if (defaultBrowserSystemFlowActive == active) {
            return
        }
        defaultBrowserSystemFlowActive = active
        if (active) {
            cancelInFlightForegroundAdRequest()
        } else {
            notifyBlockingOverlayDismissed()
            return
        }
        notifyAdEnvironmentChanged()
    }

    /** Call when any blocking overlay (e.g. rating prompt) may have closed. */
    fun notifyBlockingOverlayDismissed() {
        notifyAdEnvironmentChanged()
        if (isBlockedByUiOverlay()) {
            return
        }
        onOverlayUnblocked()
    }

    fun setForceUpdateDialogVisible(visible: Boolean) {
        if (forceUpdateDialogVisible == visible) {
            return
        }
        forceUpdateDialogVisible = visible
        if (visible) {
            cancelInFlightForegroundAdRequest()
        } else {
            notifyBlockingOverlayDismissed()
        }
    }

    /** True while home-screen native ads must not load or show. */
    fun blocksHomeScreenNativeAd(): Boolean =
        isBlockedByUiOverlay() ||
            appOpenAdHelper.isShowingAd() ||
            (isAppOpenAdEligible() && awaitingAdForForeground)

    /**
     * True while an app-open ad is visible or we are still trying to show one after backgrounding.
     * The rating prompt should wait until this is false.
     */
    fun isBlockingRatingPrompt(): Boolean =
        if (!isAppOpenAdEligible()) {
            false
        } else {
            appOpenAdHelper.isShowingAd() || awaitingAdForForeground
        }

    override fun onStart(owner: LifecycleOwner) {
        homeScreenNativeAdScheduler.get().onAppForegrounded()
    }

    override fun onStop(owner: LifecycleOwner) {
        if (!browserActivityWasStopped) {
            return
        }
        markFirstSessionCompletedIfNeeded()
        wasAppBackgrounded = true
        pendingShowOnNextBrowserResume = true
        showedAdThisForegroundSession = false
        awaitingAdForForeground = false
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity is BrowserActivity) {
            browserActivityWasStopped = true
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is BrowserActivity) {
            resumedBrowserActivity = activity
        }
        if (activity !is BrowserActivity) {
            return
        }
        if (!wasAppBackgrounded || showedAdThisForegroundSession) {
            browserActivityWasStopped = false
            return
        }
        if (!browserActivityWasStopped && !pendingShowOnNextBrowserResume) {
            browserActivityWasStopped = false
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

    private fun tryShowOnResume(activity: BrowserActivity) {
        if (!isAppOpenAdEligible()) {
            finishAppOpenForegroundFlow()
            return
        }
        if (!wasAppBackgrounded || showedAdThisForegroundSession || appOpenAdHelper.isShowingAd()) {
            return
        }
        if (!canRequestOrShowAppOpenAd()) {
            pendingShowAfterOverlayClear = true
            finishAppOpenForegroundFlow()
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
            pendingShowAfterOverlayClear = false
            notifyAdEnvironmentChanged()
        } else if (appOpenAdHelper.isAdReady() || appOpenAdHelper.isLoadingAd()) {
            awaitingAdForForeground = true
            notifyAdEnvironmentChanged()
            if (!appOpenAdHelper.isLoadingAd() && !appOpenAdHelper.isAdReady()) {
                preload(activity)
            }
        } else {
            awaitingAdForForeground = true
            notifyAdEnvironmentChanged()
            preload(activity)
        }
    }

    private fun onAppOpenAdLoaded() {
        if (!isAppOpenAdEligible() || !awaitingAdForForeground || showedAdThisForegroundSession) {
            return
        }
        if (!canRequestOrShowAppOpenAd()) {
            awaitingAdForForeground = false
            pendingShowAfterOverlayClear = true
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
            pendingShowAfterOverlayClear = false
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
        notifyAdEnvironmentChanged()
        onAppOpenFlowIdleListener?.invoke()
    }

    private fun preload(activity: BrowserActivity) {
        if (!canRequestOrShowAppOpenAd() || activity.isFinishing || activity.isDestroyed) {
            return
        }
        appOpenAdHelper.load(activity)
    }

    private fun cancelInFlightForegroundAdRequest() {
        if (!awaitingAdForForeground) {
            return
        }
        awaitingAdForForeground = false
        notifyAdEnvironmentChanged()
    }

    private fun notifyAdEnvironmentChanged() {
        homeScreenNativeAdScheduler.get().onBlockingEnvironmentChanged()
    }

    private fun onOverlayUnblocked() {
        if (!pendingShowAfterOverlayClear || showedAdThisForegroundSession || !wasAppBackgrounded) {
            return
        }
        if (!canRequestOrShowAppOpenAd()) {
            return
        }
        pendingShowAfterOverlayClear = false
        val activity = resumedBrowserActivity ?: return
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        showedAdThisForegroundSession = false
        tryShowOnResume(activity)
    }

    private fun canRequestOrShowAppOpenAd(): Boolean =
        isAppOpenAdEligible() && !isBlockedByUiOverlay()

    private fun isBlockedByUiOverlay(): Boolean =
        defaultBrowserPromptVisible ||
            defaultBrowserSystemFlowActive ||
            forceUpdateDialogVisible ||
            RatingPromptDialog.isShowing()

    private fun isAppOpenAdEligible(): Boolean = userPreferences.appOpenAdFirstSessionCompleted

    private fun markFirstSessionCompletedIfNeeded() {
        if (!userPreferences.appOpenAdFirstSessionCompleted) {
            userPreferences.appOpenAdFirstSessionCompleted = true
        }
    }

    /** Upgrades: installs that already recorded [UserPreferences.firstLaunchEpochMs]. */
    private fun migrateExistingInstallAppOpenSession() {
        if (!userPreferences.appOpenAdFirstSessionCompleted &&
            userPreferences.firstLaunchEpochMs != 0L
        ) {
            userPreferences.appOpenAdFirstSessionCompleted = true
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (resumedBrowserActivity === activity) {
            resumedBrowserActivity = null
        }
        if (activity is BrowserActivity && !activity.isIncognito()) {
            setDefaultBrowserPromptVisible(false)
            setDefaultBrowserSystemFlowActive(false)
        }
    }
}
