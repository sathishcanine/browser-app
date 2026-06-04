package com.browser.minnal.ads

import com.browser.minnal.BuildConfig
import com.browser.minnal.log.Logger
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import android.app.Activity
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads and shows a single cached app-open ad.
 *
 * Does not show automatically when an ad finishes loading — [AppOpenAdManager] decides when
 * a foreground transition warrants a show.
 */
@Singleton
class AppOpenAdHelper @Inject constructor(
    private val logger: Logger,
) {

    private var appOpenAd: AppOpenAd? = null
    private var loadTimeMs = 0L
    private var isLoading = false
    private var isShowing = false

    @Volatile
    private var suppressCount = 0

    private var onAdLoadedListener: (() -> Unit)? = null
    private var onAdIdleListener: (() -> Unit)? = null

    fun setOnAdLoadedListener(listener: (() -> Unit)?) {
        onAdLoadedListener = listener
    }

    /** Fired when the app-open ad is dismissed, fails to show, or fails to load. */
    fun setOnAdIdleListener(listener: (() -> Unit)?) {
        onAdIdleListener = listener
    }

    fun isLoadingAd(): Boolean = isLoading

    fun isSuppressed(): Boolean = suppressCount > 0

    fun beginSuppress() {
        suppressCount++
    }

    fun endSuppress() {
        suppressCount = (suppressCount - 1).coerceAtLeast(0)
    }

    fun isShowingAd(): Boolean = isShowing

    fun isAdReady(): Boolean = isAdAvailable()

    fun load(context: Context) {
        if (isLoading || isAdAvailable()) {
            return
        }
        if (isSuppressed()) {
            return
        }
        isLoading = true
        AppOpenAd.load(
            context,
            BuildConfig.APP_OPEN_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    isLoading = false
                    appOpenAd = ad
                    loadTimeMs = System.currentTimeMillis()
                    onAdLoadedListener?.invoke()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    logger.log(TAG, "App open ad failed to load: ${error.message}")
                    onAdIdleListener?.invoke()
                }
            },
        )
    }

    /**
     * @return true if the ad was shown.
     */
    fun showIfAvailable(activity: Activity): Boolean {
        if (isShowing || isSuppressed() || activity.isFinishing || activity.isDestroyed) {
            return false
        }
        val ad = appOpenAd
        if (ad == null || !isAdAvailable()) {
            return false
        }
        isShowing = true
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                FullScreenAdHostWindow.restore(activity)
                clearLoadedAd()
                isShowing = false
                onAdIdleListener?.invoke()
                preloadAfterShow(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                FullScreenAdHostWindow.restore(activity)
                clearLoadedAd()
                isShowing = false
                logger.log(TAG, "App open ad failed to show: ${error.message}")
                onAdIdleListener?.invoke()
                preloadAfterShow(activity)
            }

            override fun onAdShowedFullScreenContent() {
                clearLoadedAd()
            }
        }
        return runCatching {
            FullScreenAdHostWindow.prepare(activity)
            ad.setImmersiveMode(true)
            ad.show(activity)
            true
        }.getOrElse {
            FullScreenAdHostWindow.restore(activity)
            isShowing = false
            clearLoadedAd()
            logger.log(TAG, "App open ad show threw", it)
            onAdIdleListener?.invoke()
            preloadAfterShow(activity)
            false
        }
    }

    private fun preloadAfterShow(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        load(activity)
    }

    private fun isAdAvailable(): Boolean {
        if (appOpenAd == null) return false
        if (System.currentTimeMillis() - loadTimeMs >= AD_MAX_AGE_MS) {
            clearLoadedAd()
            return false
        }
        return true
    }

    private fun clearLoadedAd() {
        appOpenAd = null
        loadTimeMs = 0L
    }

    companion object {
        private const val TAG = "AppOpenAdHelper"
        private const val AD_MAX_AGE_MS = 4 * 60 * 60 * 1000L
    }
}
