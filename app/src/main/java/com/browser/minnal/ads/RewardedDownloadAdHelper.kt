package com.browser.minnal.ads

import com.browser.minnal.BuildConfig
import com.browser.minnal.log.Logger
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import androidx.fragment.app.FragmentActivity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads and presents a rewarded ad before partner CDN downloads (cld./cds. hosts).
 */
@Singleton
class RewardedDownloadAdHelper @Inject constructor(
    private val interstitialAdHelper: InterstitialAdHelper,
    private val logger: Logger,
) {

    private var rewardedAd: RewardedAd? = null
    private var isLoading = false

    fun preload(activity: FragmentActivity) {
        if (rewardedAd != null || isLoading) {
            return
        }
        loadAd(activity)
    }

    /**
     * @param onRewarded User completed the ad and should receive the superfast download.
     * @param onDismissedWithoutReward User closed the ad before earning the reward.
     * @param onUnavailable Ad could not be loaded or shown.
     */
    fun show(
        activity: FragmentActivity,
        onRewarded: () -> Unit,
        onDismissedWithoutReward: () -> Unit,
        onUnavailable: () -> Unit,
    ) {
        val cached = rewardedAd
        if (cached != null) {
            present(activity, cached, onRewarded, onDismissedWithoutReward, onUnavailable)
            return
        }
        if (isLoading) {
            onUnavailable()
            return
        }
        loadAd(
            activity,
            onLoaded = { ad ->
                present(activity, ad, onRewarded, onDismissedWithoutReward, onUnavailable)
            },
            onFailed = onUnavailable,
        )
    }

    private fun loadAd(
        activity: FragmentActivity,
        onLoaded: ((RewardedAd) -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
    ) {
        isLoading = true
        RewardedAd.load(
            activity,
            BuildConfig.REWARDED_AD_ONE,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    isLoading = false
                    rewardedAd = ad
                    onLoaded?.invoke(ad)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    logger.log(TAG, "Rewarded download ad failed to load: ${error.message}")
                    onFailed?.invoke()
                }
            },
        )
    }

    private fun present(
        activity: FragmentActivity,
        ad: RewardedAd,
        onRewarded: () -> Unit,
        onDismissedWithoutReward: () -> Unit,
        onUnavailable: () -> Unit,
    ) {
        var rewardGranted = false
        interstitialAdHelper.beginSuppress()
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAdHelper.endSuppress()
                rewardedAd = null
                preload(activity)
                if (rewardGranted) {
                    onRewarded()
                } else {
                    onDismissedWithoutReward()
                }
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                interstitialAdHelper.endSuppress()
                rewardedAd = null
                logger.log(TAG, "Rewarded download ad failed to show: ${error.message}")
                onUnavailable()
            }

            override fun onAdShowedFullScreenContent() {
                rewardedAd = null
            }
        }
        runCatching {
            ad.show(activity) {
                rewardGranted = true
            }
        }.onFailure {
            interstitialAdHelper.endSuppress()
            logger.log(TAG, "Rewarded download ad show threw", it)
            onUnavailable()
        }
    }

    companion object {
        private const val TAG = "RewardedDownloadAdHelper"
    }
}
