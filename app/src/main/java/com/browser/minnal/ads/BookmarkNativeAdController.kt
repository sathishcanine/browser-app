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
 * Native advanced ad strip (expand/collapse, optional close). Used for the bookmarks start page,
 * downloads page, and an extra strip when the user returns to the app on a non-bookmark tab.
 */
class BookmarkNativeAdController private constructor(
    private val activity: FragmentActivity,
    private val stripRoot: View,
    private val expandToggle: ImageButton,
    private val closeButton: ImageButton?,
    private val adContainer: FrameLayout,
    private val adUnitId: () -> String,
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

    init {
        expandToggle.setOnClickListener {
            expanded = !expanded
            updateExpandedUi()
        }
        if (closeButton != null) {
            closeButton.setOnClickListener {
                onUserDismissedStrip?.invoke()
                dismissedForThisVisit = true
                stripRoot.isVisible = false
                adContainer.isVisible = false
                destroyLoadedAd()
            }
        }
        updateExpandedUi()
    }

    fun onPresenterShowBookmarkNativeAd(show: Boolean) {
        presenterWantsVisible = show
        if (!show) {
            dismissedForThisVisit = false
            loadAttemptedForThisVisit = false
            stripRoot.isVisible = false
            adContainer.isVisible = false
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
        expandToggle.scaleY = if (expanded) -1f else 1f
        expandToggle.contentDescription = activity.getString(
            if (expanded) {
                R.string.native_ad_collapse
            } else {
                R.string.native_ad_expand
            }
        )
        if (!expanded) {
            adContainer.isVisible = false
        } else if (loadedNativeAd != null) {
            adContainer.isVisible = true
        }
        if (expanded && presenterWantsVisible && !dismissedForThisVisit && !loadAttemptedForThisVisit) {
            loadNativeAd()
        }
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

        AdLoader.Builder(activity, adUnitId())
            .forNativeAd { ad ->
                loadedNativeAd?.destroy()
                loadedNativeAd = ad
                populateNativeAd(adView, ad)
                adContainer.removeAllViews()
                adContainer.addView(adView)
                adContainer.isVisible = expanded
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadAttemptedForThisVisit = false
                    adContainer.isVisible = false
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

    private fun destroyLoadedAd() {
        loadedNativeAd?.destroy()
        loadedNativeAd = null
        adContainer.removeAllViews()
        nativeAdViewBinding = null
    }

    fun destroy() {
        destroyLoadedAd()
    }

    companion object {
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
        )

        fun forDownloads(
            activity: FragmentActivity,
            stripBinding: DownloadsNativeAdStripBinding,
        ): BookmarkNativeAdController = BookmarkNativeAdController(
            activity = activity,
            stripRoot = stripBinding.root,
            expandToggle = stripBinding.nativeAdExpandToggle,
            closeButton = null,
            adContainer = stripBinding.nativeAdContainer,
            adUnitId = { BuildConfig.DOWNLOADS_NATIVE_AD_UNIT_ID },
        )
    }
}
