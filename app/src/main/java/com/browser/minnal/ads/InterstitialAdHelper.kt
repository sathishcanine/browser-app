package com.browser.minnal.ads

import com.browser.minnal.BuildConfig
import com.browser.minnal.log.Logger
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import androidx.fragment.app.FragmentActivity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads and shows a single cached interstitial (e.g. timed session ads).
 */
@Singleton
class InterstitialAdHelper @Inject constructor(
    private val logger: Logger,
) {

    private var interstitialAd: InterstitialAd? = null
    private var isLoading = false

    @Volatile
    private var suppressCount = 0

    fun isSuppressed(): Boolean = suppressCount > 0 || !ENABLED

    fun beginSuppress() {
        suppressCount++
    }

    fun endSuppress() {
        suppressCount = (suppressCount - 1).coerceAtLeast(0)
    }

    fun preload(activity: FragmentActivity) {
        if (!ENABLED || interstitialAd != null || isLoading || isSuppressed()) {
            return
        }
        loadAd(activity)
    }

    /**
     * @return true if the ad was shown.
     */
    fun showIfReady(
        activity: FragmentActivity,
        onDismissed: () -> Unit,
    ): Boolean {
        if (!ENABLED || isSuppressed() || activity.isFinishing || activity.isDestroyed) {
            return false
        }
        val ad = interstitialAd ?: run {
            preload(activity)
            return false
        }
        present(activity, ad, onDismissed)
        return true
    }

    private fun loadAd(activity: FragmentActivity) {
        isLoading = true
        InterstitialAd.load(
            activity,
            BuildConfig.INTERSTITIAL_AD_ONE,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    isLoading = false
                    interstitialAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    logger.log(TAG, "Interstitial failed to load: ${error.message}")
                }
            },
        )
    }

    private fun present(
        activity: FragmentActivity,
        ad: InterstitialAd,
        onDismissed: () -> Unit,
    ) {
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                preload(activity)
                onDismissed()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                interstitialAd = null
                logger.log(TAG, "Interstitial failed to show: ${error.message}")
                preload(activity)
                onDismissed()
            }

            override fun onAdShowedFullScreenContent() {
                interstitialAd = null
            }
        }
        runCatching {
            ad.show(activity)
        }.onFailure {
            interstitialAd = null
            logger.log(TAG, "Interstitial show threw", it)
            preload(activity)
        }
    }

    companion object {
        private const val TAG = "InterstitialAdHelper"

        /** Set to true to re-enable timed full-screen interstitials. */
        const val ENABLED = false
    }
}
