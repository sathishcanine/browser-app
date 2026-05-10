package com.browser.minnal.ads

import com.browser.minnal.BuildConfig
import com.browser.minnal.browser.di.Browser2Scope
import com.browser.minnal.log.Logger
import android.app.Activity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import javax.inject.Inject

/**
 * Loads and shows a single AdMob [RewardedAd] for the current activity. The controller
 * eagerly pre-loads the next ad after each completed show so subsequent requests are
 * instant.
 *
 * The result of a `show` is reported through [Result] regardless of whether the user
 * earned a reward, the ad failed to show, or there was no ad inventory available, so
 * callers (most importantly the JS bridge) can always send a deterministic callback to
 * the page.
 */
@Browser2Scope
class RewardedAdController @Inject constructor(
    private val activity: Activity,
    private val logger: Logger,
) {

    enum class Result { REWARDED, DISMISSED_NO_REWARD, FAILED }

    private var rewardedAd: RewardedAd? = null
    private var loading: Boolean = false

    init {
        // Warm up the cache so the first show feels instant.
        preload()
    }

    /**
     * Pre-load the next rewarded ad if one isn't already cached or loading.
     */
    fun preload() {
        if (rewardedAd != null || loading) return
        loading = true
        RewardedAd.load(
            activity,
            BuildConfig.REWARDED_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    loading = false
                    rewardedAd = ad
                    logger.log(TAG, "Rewarded ad loaded.")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading = false
                    rewardedAd = null
                    logger.log(TAG, "Rewarded ad failed to load: ${error.message}")
                }
            }
        )
    }

    /**
     * Show the rewarded ad. Must be called from the main thread. If no ad is cached the
     * controller will attempt one synchronous load, then immediately show.
     */
    fun show(onResult: (Result) -> Unit) {
        val cached = rewardedAd
        if (cached != null) {
            present(cached, onResult)
            return
        }
        // Nothing cached: try a one-shot load on demand.
        loading = true
        RewardedAd.load(
            activity,
            BuildConfig.REWARDED_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    loading = false
                    rewardedAd = ad
                    present(ad, onResult)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading = false
                    rewardedAd = null
                    logger.log(TAG, "Rewarded ad failed to load on demand: ${error.message}")
                    onResult(Result.FAILED)
                }
            }
        )
    }

    private fun present(ad: RewardedAd, onResult: (Result) -> Unit) {
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                preload()
                onResult(if (earned) Result.REWARDED else Result.DISMISSED_NO_REWARD)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                rewardedAd = null
                preload()
                logger.log(TAG, "Rewarded ad failed to show: ${error.message}")
                onResult(Result.FAILED)
            }
        }
        ad.show(activity, OnUserEarnedRewardListener { earned = true })
    }

    companion object {
        private const val TAG = "RewardedAdController"
    }
}
