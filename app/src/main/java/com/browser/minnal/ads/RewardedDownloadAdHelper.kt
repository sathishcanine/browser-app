package com.browser.minnal.ads

import com.browser.minnal.BuildConfig
import com.browser.minnal.log.Logger
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import androidx.fragment.app.FragmentActivity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads and presents a rewarded ad before partner CDN downloads (cld./cds. hosts).
 * Falls back to a rewarded interstitial, then allows download without an ad.
 */
@Singleton
class RewardedDownloadAdHelper @Inject constructor(
    private val interstitialAdHelper: InterstitialAdHelper,
    private val logger: Logger,
) {

    private var rewardedAd: RewardedAd? = null
    private var isLoading = false
    private val pendingLoadListeners = mutableListOf<LoadListener>()

    fun preload(activity: FragmentActivity) {
        if (rewardedAd != null || isLoading) {
            return
        }
        startLoad(activity, listener = null)
    }

    /**
     * @param onLoadingChanged `true` while fetching the ad; `false` when fetch ends or ad is ready to show.
     * @param onRewarded User completed the ad; start the superfast download.
     * @param onProceedWithoutAd Ad could not be loaded or shown; download without ad.
     * @param onDismissedWithoutReward User closed the ad before earning the reward.
     */
    fun show(
        activity: FragmentActivity,
        onLoadingChanged: (Boolean) -> Unit,
        onRewarded: () -> Unit,
        onProceedWithoutAd: () -> Unit,
        onDismissedWithoutReward: () -> Unit,
    ) {
        val cached = rewardedAd
        if (cached != null) {
            rewardedAd = null
            onLoadingChanged(false)
            presentRewarded(
                activity,
                cached,
                onRewarded,
                onDismissedWithoutReward,
            ) { tryRewardedInterstitial(activity, onRewarded, onDismissedWithoutReward, onProceedWithoutAd) }
            return
        }

        onLoadingChanged(true)
        val listener = object : LoadListener {
            override fun onLoaded(ad: RewardedAd) {
                onLoadingChanged(false)
                presentRewarded(
                    activity,
                    ad,
                    onRewarded,
                    onDismissedWithoutReward,
                ) { tryRewardedInterstitial(activity, onRewarded, onDismissedWithoutReward, onProceedWithoutAd) }
            }

            override fun onFailed() {
                tryRewardedInterstitial(
                    activity,
                    onRewarded,
                    onDismissedWithoutReward,
                    onProceedWithoutAd,
                    onLoadingChanged = onLoadingChanged,
                )
            }
        }

        if (isLoading) {
            pendingLoadListeners.add(listener)
            return
        }

        startLoad(activity, listener)
    }

    private fun startLoad(activity: FragmentActivity, listener: LoadListener?) {
        if (listener != null) {
            pendingLoadListeners.add(listener)
        }
        if (isLoading) {
            return
        }
        isLoading = true
        RewardedAd.load(
            activity,
            BuildConfig.REWARDED_AD_ONE,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    isLoading = false
                    val listeners = pendingLoadListeners.toList()
                    pendingLoadListeners.clear()
                    if (listeners.isEmpty()) {
                        rewardedAd = ad
                        return
                    }
                    listeners.forEach { it.onLoaded(ad) }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    logger.log(TAG, "Rewarded download ad failed to load: ${error.message}")
                    val listeners = pendingLoadListeners.toList()
                    pendingLoadListeners.clear()
                    if (listeners.isEmpty()) {
                        return
                    }
                    listeners.forEach { it.onFailed() }
                }
            },
        )
    }

    private fun tryRewardedInterstitial(
        activity: FragmentActivity,
        onRewarded: () -> Unit,
        onDismissedWithoutReward: () -> Unit,
        onProceedWithoutAd: () -> Unit,
        onLoadingChanged: ((Boolean) -> Unit)? = null,
    ) {
        onLoadingChanged?.invoke(true)
        RewardedInterstitialAd.load(
            activity,
            BuildConfig.REWARDED_INTERSTITIAL_DOWNLOAD_AD,
            AdRequest.Builder().build(),
            object : RewardedInterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedInterstitialAd) {
                    onLoadingChanged?.invoke(false)
                    presentRewardedInterstitial(
                        activity,
                        ad,
                        onRewarded,
                        onDismissedWithoutReward,
                        onProceedWithoutAd,
                    )
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    onLoadingChanged?.invoke(false)
                    logger.log(TAG, "Rewarded interstitial download ad failed to load: ${error.message}")
                    onProceedWithoutAd()
                }
            },
        )
    }

    private fun presentRewarded(
        activity: FragmentActivity,
        ad: RewardedAd,
        onRewarded: () -> Unit,
        onDismissedWithoutReward: () -> Unit,
        onRewardedFailed: () -> Unit,
    ) {
        var rewardGranted = false
        interstitialAdHelper.beginSuppress()
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAdHelper.endSuppress()
                preload(activity)
                if (rewardGranted) {
                    onRewarded()
                } else {
                    onDismissedWithoutReward()
                }
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                interstitialAdHelper.endSuppress()
                logger.log(TAG, "Rewarded download ad failed to show: ${error.message}")
                preload(activity)
                onRewardedFailed()
            }

            override fun onAdShowedFullScreenContent() = Unit
        }
        runCatching {
            ad.show(activity) {
                rewardGranted = true
            }
        }.onFailure {
            interstitialAdHelper.endSuppress()
            logger.log(TAG, "Rewarded download ad show threw", it)
            onRewardedFailed()
        }
    }

    private fun presentRewardedInterstitial(
        activity: FragmentActivity,
        ad: RewardedInterstitialAd,
        onRewarded: () -> Unit,
        onDismissedWithoutReward: () -> Unit,
        onProceedWithoutAd: () -> Unit,
    ) {
        var rewardGranted = false
        interstitialAdHelper.beginSuppress()
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAdHelper.endSuppress()
                if (rewardGranted) {
                    onRewarded()
                } else {
                    onDismissedWithoutReward()
                }
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                interstitialAdHelper.endSuppress()
                logger.log(TAG, "Rewarded interstitial download ad failed to show: ${error.message}")
                onProceedWithoutAd()
            }

            override fun onAdShowedFullScreenContent() = Unit
        }
        runCatching {
            ad.show(activity) {
                rewardGranted = true
            }
        }.onFailure {
            interstitialAdHelper.endSuppress()
            logger.log(TAG, "Rewarded interstitial download ad show threw", it)
            onProceedWithoutAd()
        }
    }

    private interface LoadListener {
        fun onLoaded(ad: RewardedAd)
        fun onFailed()
    }

    companion object {
        private const val TAG = "RewardedDownloadAdHelper"
    }
}
