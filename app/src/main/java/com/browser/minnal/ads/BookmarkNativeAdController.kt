package com.browser.minnal.ads

import com.browser.minnal.BuildConfig
import com.browser.minnal.R
import com.browser.minnal.databinding.BookmarkNativeAdStripBinding
import com.browser.minnal.databinding.DownloadsNativeAdStripBinding
import com.browser.minnal.databinding.NativeAdBookmarksBinding
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import com.browser.minnal.rating.RatingPromptDialog
import com.browser.minnal.utils.DefaultBrowserHelper

/**
 * Native advanced ad strip (expand/collapse, optional close). Used for the home strip on
 * non-bookmark pages (once per session), and for the downloads page.
 */
class BookmarkNativeAdController private constructor(
    private val activity: FragmentActivity,
    private val stripRoot: View,
    private val expandToggle: ImageButton?,
    private val closeButton: ImageButton?,
    private val adContainer: FrameLayout,
    private val adUnitId: () -> String,
    private val reserveLayoutSpace: Boolean,
) {

    /**
     * Invoked when the user taps the strip close control (after hiding the strip).
     */
    var onUserDismissedStrip: (() -> Unit)? = null

    private var nativeAdViewBinding: NativeAdBookmarksBinding? = null
    private var loadedNativeAd: NativeAd? = null
    private var expanded = true
    private var dismissedForThisVisit = false
    private var presenterWantsVisible = false
    private var loadAttemptedForThisVisit = false
    private var downloadsBackSessionActive = false
    private var adUnitIdOverride: String? = null

    private val reservedAdContainerHeight: Int =
        if (reserveLayoutSpace) {
            activity.resources.getDimensionPixelSize(R.dimen.native_ad_container_min_height)
        } else {
            0
        }

    init {
        expandToggle?.setOnClickListener {
            expanded = !expanded
            updateExpandedUi()
        }
        if (closeButton != null) {
            closeButton.setOnClickListener {
                onUserDismissedStrip?.invoke()
                dismissedForThisVisit = true
                clearDownloadsBackMode()
                stripRoot.isVisible = false
                adContainer.isVisible = false
                destroyLoadedAd()
            }
        }
        updateExpandedUi()
    }

    /**
     * One-shot native ad after backing out of the downloads page. Uses [expandToggle] / [closeButton]
     * and bypasses the home-screen scheduler until dismissed.
     */
    fun showAfterDownloadsBackNavigation() {
        downloadsBackSessionActive = true
        adUnitIdOverride = BuildConfig.DOWNLOADS_BACK_NATIVE_AD_UNIT_ID
        dismissedForThisVisit = false
        loadAttemptedForThisVisit = false
        presenterWantsVisible = true
        expanded = true
        destroyLoadedAd()
        stripRoot.isVisible = true
        updateExpandedUi()
    }

    fun onPresenterShowBookmarkNativeAd(show: Boolean) {
        if (downloadsBackSessionActive) {
            if (dismissedForThisVisit) {
                clearDownloadsBackMode()
            } else {
                presenterWantsVisible = true
                stripRoot.isVisible = true
                updateExpandedUi()
                return
            }
        }
        presenterWantsVisible = show
        if (!show) {
            dismissedForThisVisit = false
            loadAttemptedForThisVisit = false
            stripRoot.isVisible = false
            adContainer.isVisible = false
            adContainer.alpha = 1f
            expanded = true
            updateExpandedUi()
            destroyLoadedAd()
            return
        }
        if (dismissedForThisVisit) {
            stripRoot.isVisible = false
            return
        }
        stripRoot.isVisible = true
        updateExpandedUi()
    }

    private fun updateExpandedUi() {
        if (expandToggle != null) {
            expandToggle.scaleY = if (expanded) -1f else 1f
            expandToggle.contentDescription = activity.getString(
                if (expanded) {
                    R.string.native_ad_collapse
                } else {
                    R.string.native_ad_expand
                }
            )
        } else {
            expanded = true
        }
        if (!expanded) {
            adContainer.isVisible = false
            adContainer.minimumHeight = 0
        } else {
            applyReservedAdSpace()
            if (loadedNativeAd != null) {
                adContainer.isVisible = true
                adContainer.alpha = 1f
            } else if (reserveLayoutSpace) {
                adContainer.isVisible = true
                adContainer.alpha = 0f
            } else {
                adContainer.isVisible = false
            }
        }
        if (expanded && presenterWantsVisible && !dismissedForThisVisit && !loadAttemptedForThisVisit) {
            loadNativeAd()
        }
    }

    private fun applyReservedAdSpace() {
        if (reservedAdContainerHeight > 0) {
            adContainer.minimumHeight = reservedAdContainerHeight
        }
    }

    private fun clearReservedAdSpace() {
        adContainer.minimumHeight = 0
    }

    private fun loadNativeAd() {
        if (loadAttemptedForThisVisit || !presenterWantsVisible || dismissedForThisVisit) {
            return
        }
        if (RatingPromptDialog.isShowing()) {
            return
        }
        loadAttemptedForThisVisit = true
        ensureNativeAdViewInflated()
        val adView = nativeAdViewBinding?.root as? NativeAdView ?: return

        AdLoader.Builder(activity, adUnitIdOverride ?: adUnitId())
            .forNativeAd { ad ->
                loadedNativeAd?.destroy()
                loadedNativeAd = ad
                populateNativeAd(adView, ad)
                adContainer.removeAllViews()
                adContainer.addView(adView)
                applyReservedAdSpace()
                adContainer.isVisible = expanded
                if (reserveLayoutSpace) {
                    adContainer.alpha = 0f
                    adContainer.animate()
                        .alpha(1f)
                        .setDuration(AD_FADE_IN_MS)
                        .start()
                } else {
                    adContainer.alpha = 1f
                }
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadAttemptedForThisVisit = false
                    clearReservedAdSpace()
                    adContainer.isVisible = false
                    adContainer.alpha = 1f
                    if (reserveLayoutSpace) {
                        stripRoot.isVisible = false
                    }
                }
            })
            .withNativeAdOptions(
                NativeAdOptions.Builder()
                    .setRequestMultipleImages(false)
                    .build()
            )
            .build()
            .loadAd(AdRequest.Builder().build())
    }

    private fun ensureNativeAdViewInflated() {
        if (nativeAdViewBinding != null) {
            return
        }
        nativeAdViewBinding = NativeAdBookmarksBinding.inflate(activity.layoutInflater, adContainer, false)
    }

    private fun populateNativeAd(adView: NativeAdView, ad: NativeAd) {
        val b = NativeAdBookmarksBinding.bind(adView)
        adView.mediaView = b.adMedia
        adView.headlineView = b.adHeadline
        adView.bodyView = b.adBody
        adView.callToActionView = b.adCallToAction
        adView.iconView = b.adAppIcon
        adView.advertiserView = b.adAdvertiser

        b.adHeadline.text = ad.headline

        val body = ad.body
        if (body != null) {
            b.adBody.text = body
            b.adBody.isVisible = true
        } else {
            b.adBody.isVisible = false
        }

        b.adCallToAction.text = ad.callToAction

        val advertiserText = ad.advertiser ?: ad.store
        if (!advertiserText.isNullOrBlank()) {
            b.adAdvertiser.text = advertiserText
            b.adAdvertiser.isVisible = true
        } else {
            b.adAdvertiser.isVisible = false
        }

        ad.mediaContent?.let { b.adMedia.mediaContent = it }

        ad.icon?.let { icon ->
            b.adAppIcon.setImageDrawable(icon.drawable)
            b.adAppIcon.isVisible = true
        } ?: run {
            b.adAppIcon.setImageDrawable(null)
            b.adAppIcon.isVisible = false
        }

        adView.setNativeAd(ad)
    }

    private fun clearDownloadsBackMode() {
        downloadsBackSessionActive = false
        adUnitIdOverride = null
    }

    private fun destroyLoadedAd() {
        loadedNativeAd?.destroy()
        loadedNativeAd = null
        adContainer.removeAllViews()
        nativeAdViewBinding = null
        clearReservedAdSpace()
    }

    fun destroy() {
        destroyLoadedAd()
    }

    fun isDownloadsBackSessionActive(): Boolean = downloadsBackSessionActive

    companion object {
        private const val AD_FADE_IN_MS = 200L

        fun forBookmarks(
            activity: FragmentActivity,
            stripBinding: BookmarkNativeAdStripBinding,
        ): BookmarkNativeAdController = BookmarkNativeAdController(
            activity = activity,
            stripRoot = stripBinding.root,
            expandToggle = stripBinding.nativeAdExpandToggle,
            closeButton = stripBinding.nativeAdClose,
            adContainer = stripBinding.nativeAdContainer,
            adUnitId = {
                if (DefaultBrowserHelper.isAppDefaultBrowser(activity)) {
                    BuildConfig.BOOKMARK_NATIVE_AD_DEFAULT_BROWSER_UNIT_ID
                } else {
                    BuildConfig.BOOKMARK_NATIVE_AD_UNIT_ID
                }
            },
            reserveLayoutSpace = true,
        )

        fun forDownloads(
            activity: FragmentActivity,
            stripBinding: DownloadsNativeAdStripBinding,
        ): BookmarkNativeAdController = BookmarkNativeAdController(
            activity = activity,
            stripRoot = stripBinding.root,
            expandToggle = null,
            closeButton = null,
            adContainer = stripBinding.nativeAdContainer,
            adUnitId = { BuildConfig.DOWNLOADS_NATIVE_AD_UNIT_ID },
            reserveLayoutSpace = true,
        )
    }
}
