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
 * Loads and presents a single rewarded ad before partner CDN downloads (cld./cds. hosts).
 * On load/show failure the download proceeds without an ad.
 */
@Singleton
class RewardedDownloadAdHelper @Inject constructor(
    private val interstitialAdHelper: InterstitialAdHelper,
    private val mobileAdsInitializer: MobileAdsInitializer,
    private val logger: Logger,
) {

    private var rewardedAd: RewardedAd? = null
    private var isLoading = false
    private val pendingLoadListeners = mutableListOf<LoadListener>()

    /** Guards against a second [RewardedAd.show] in the same download gate session. */
    private var isPresenting = false

    /**
     * True while the user is in the rewarded-download gate (loading or watching an ad).
     * Used to defer auto-navigation to the downloads page until the ad flow finishes.
     */
    @Volatile
    var isDownloadRewardFlowActive: Boolean = false
        private set

    /** Warm the SDK; must use a live [FragmentActivity] (especially in :incognito). */
    fun preload(activity: FragmentActivity) {
        if (rewardedAd != null || isLoading || isPresenting || isDownloadRewardFlowActive) {
            return
        }
        startLoad(activity, listener = null)
    }

    /**
     * @param onLoadingChanged `true` while fetching the ad; `false` when the ad is on screen or the flow ends.
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
        isDownloadRewardFlowActive = true
        onLoadingChanged(true)

        val cached = rewardedAd
        rewardedAd = null
        if (cached != null) {
            presentRewarded(
                activity = activity,
                ad = cached,
                onLoadingChanged = onLoadingChanged,
                onRewarded = onRewarded,
                onDismissedWithoutReward = onDismissedWithoutReward,
                onRewardedFailed = {
                    finishFlow(onLoadingChanged)
                    onProceedWithoutAd()
                },
            )
            return
        }

        loadRewardedThenPresent(
            activity = activity,
            onLoadingChanged = onLoadingChanged,
            onRewarded = onRewarded,
            onDismissedWithoutReward = onDismissedWithoutReward,
            onFailed = {
                finishFlow(onLoadingChanged)
                onProceedWithoutAd()
            },
        )
    }

    private fun loadRewardedThenPresent(
        activity: FragmentActivity,
        onLoadingChanged: (Boolean) -> Unit,
        onRewarded: () -> Unit,
        onDismissedWithoutReward: () -> Unit,
        onFailed: () -> Unit,
    ) {
        val listener = object : LoadListener {
            override fun onLoaded(ad: RewardedAd) {
                presentRewarded(
                    activity = activity,
                    ad = ad,
                    onLoadingChanged = onLoadingChanged,
                    onRewarded = onRewarded,
                    onDismissedWithoutReward = onDismissedWithoutReward,
                    onRewardedFailed = onFailed,
                )
            }

            override fun onFailed() {
                onFailed()
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
        mobileAdsInitializer.runWhenReady {
            if (!isLoading) {
                return@runWhenReady
            }
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
                        logger.log(
                            TAG,
                            "Rewarded download ad failed to load: code=${error.code} domain=${error.domain} ${error.message}",
                        )
                        val listeners = pendingLoadListeners.toList()
                        pendingLoadListeners.clear()
                        listeners.forEach { it.onFailed() }
                    }
                },
            )
        }
    }

    private fun presentRewarded(
        activity: FragmentActivity,
        ad: RewardedAd,
        onLoadingChanged: (Boolean) -> Unit,
        onRewarded: () -> Unit,
        onDismissedWithoutReward: () -> Unit,
        onRewardedFailed: () -> Unit,
    ) {
        if (isPresenting) {
            logger.log(TAG, "Rewarded download ad skipped: already presenting")
            onRewardedFailed()
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            logger.log(TAG, "Rewarded download ad skipped: host activity not valid")
            onRewardedFailed()
            return
        }
        isPresenting = true
        var rewardGranted = false
        var contentShown = false
        interstitialAdHelper.beginSuppress()
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                isPresenting = false
                restoreHostWindow(activity)
                interstitialAdHelper.endSuppress()
                finishFlow(onLoadingChanged)
                preload(activity)
                if (rewardGranted) {
                    onRewarded()
                } else {
                    onDismissedWithoutReward()
                }
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                if (contentShown) {
                    logger.log(TAG, "Rewarded download ad ignored post-show failure: ${error.message}")
                    return
                }
                isPresenting = false
                restoreHostWindow(activity)
                interstitialAdHelper.endSuppress()
                logger.log(
                    TAG,
                    "Rewarded download ad failed to show: code=${error.code} ${error.message}",
                )
                onRewardedFailed()
            }

            override fun onAdShowedFullScreenContent() {
                contentShown = true
            }
        }
        showFullScreenAd(
            activity = activity,
            onLoadingChanged = onLoadingChanged,
            show = {
                ad.setImmersiveMode(true)
                ad.show(activity) {
                    rewardGranted = true
                }
            },
            onFailure = { error ->
                if (contentShown) {
                    logger.log(TAG, "Rewarded download ad ignored post-show throw", error)
                    return@showFullScreenAd
                }
                isPresenting = false
                restoreHostWindow(activity)
                interstitialAdHelper.endSuppress()
                logger.log(TAG, "Rewarded download ad show threw", error)
                onRewardedFailed()
            },
        )
    }

    /**
     * Dismiss the loading overlay before presenting — leaving it up blocks AdMob full-screen
     * formats (especially in the :incognito process).
     */
    private inline fun showFullScreenAd(
        activity: FragmentActivity,
        crossinline onLoadingChanged: (Boolean) -> Unit,
        crossinline show: () -> Unit,
        crossinline onFailure: (Throwable) -> Unit,
    ) {
        onLoadingChanged(false)
        mobileAdsInitializer.runWhenReadyWithWindowFocus(
            activity = activity,
            action = {
                runCatching {
                    prepareHostWindow(activity)
                    show()
                }.onFailure(onFailure)
            },
            onUnavailable = {
                onFailure(IllegalStateException("Host activity not ready for ad show"))
            },
        )
    }

    private fun finishFlow(onLoadingChanged: (Boolean) -> Unit) {
        isDownloadRewardFlowActive = false
        onLoadingChanged(false)
    }

    private fun prepareHostWindow(activity: FragmentActivity) {
        FullScreenAdHostWindow.prepare(activity)
    }

    private fun restoreHostWindow(activity: FragmentActivity) {
        FullScreenAdHostWindow.restore(activity)
    }

    private interface LoadListener {
        fun onLoaded(ad: RewardedAd)
        fun onFailed()
    }

    companion object {
        private const val TAG = "RewardedDownloadAdHelper"
    }
}
