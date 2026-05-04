package com.browser.minnal.ads

import com.browser.minnal.BuildConfig
import com.browser.minnal.R
import com.browser.minnal.databinding.BookmarkNativeAdStripBinding
import com.browser.minnal.databinding.NativeAdBookmarksBinding
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity

/**
 * Native advanced ad on the bookmarks (start) page: header with expand/collapse and close.
 * Starts expanded; close hides the strip until the user leaves the bookmark page and returns.
 */
class BookmarkNativeAdController(
    private val activity: FragmentActivity,
    stripBinding: BookmarkNativeAdStripBinding,
) {

    private val stripRoot = stripBinding.root
    private val expandToggle = stripBinding.nativeAdExpandToggle
    private val closeButton = stripBinding.nativeAdClose
    private val adContainer = stripBinding.nativeAdContainer

    private var nativeAdViewBinding: NativeAdBookmarksBinding? = null
    private var loadedNativeAd: NativeAd? = null
    private var expanded = true
    private var dismissedForThisBookmarkVisit = false
    private var presenterWantsVisible = false
    private var loadAttemptedForThisVisit = false

    init {
        expandToggle.setOnClickListener {
            expanded = !expanded
            updateExpandedUi()
        }
        closeButton.setOnClickListener {
            dismissedForThisBookmarkVisit = true
            stripRoot.isVisible = false
            adContainer.isVisible = false
            destroyLoadedAd()
        }
        updateExpandedUi()
    }

    fun onPresenterShowBookmarkNativeAd(show: Boolean) {
        presenterWantsVisible = show
        if (!show) {
            dismissedForThisBookmarkVisit = false
            loadAttemptedForThisVisit = false
            stripRoot.isVisible = false
            adContainer.isVisible = false
            expanded = true
            updateExpandedUi()
            destroyLoadedAd()
            return
        }
        if (dismissedForThisBookmarkVisit) {
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
        if (expanded && presenterWantsVisible && !dismissedForThisBookmarkVisit && !loadAttemptedForThisVisit) {
            loadNativeAd()
        }
    }

    private fun loadNativeAd() {
        if (loadAttemptedForThisVisit || !presenterWantsVisible || dismissedForThisBookmarkVisit) {
            return
        }
        loadAttemptedForThisVisit = true
        ensureNativeAdViewInflated()
        val adView = nativeAdViewBinding?.root as? NativeAdView ?: return

        AdLoader.Builder(activity, BuildConfig.BOOKMARK_NATIVE_AD_UNIT_ID)
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
}
