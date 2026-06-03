package com.browser.minnal.adblock

import com.browser.minnal.preference.UserPreferences
import javax.inject.Inject

/**
 * Gates [BloomFilterAdBlocker] on [UserPreferences.adBlockEnabled] so the setting applies
 * immediately without recreating the browser activity.
 */
class UserPreferenceAdBlocker @Inject constructor(
    private val userPreferences: UserPreferences,
    private val bloomFilterAdBlocker: BloomFilterAdBlocker,
) : AdBlocker {

    override fun isAd(url: String): Boolean {
        if (GooglePublisherAdDomains.isExempt(url)) {
            return false
        }
        return userPreferences.adBlockEnabled && bloomFilterAdBlocker.isAd(url)
    }
}
