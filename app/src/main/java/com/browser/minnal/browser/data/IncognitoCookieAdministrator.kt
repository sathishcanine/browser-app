package com.browser.minnal.browser.data

import android.webkit.CookieManager
import javax.inject.Inject

/**
 * Cookie settings for incognito tabs, which run in an isolated `:incognito` process.
 *
 * Browsing data in this process never mixes with normal mode. Cookies must stay enabled so
 * AdMob's internal WebView can load rewarded ads; disabling them only breaks ads without
 * adding meaningful privacy on top of the separate process.
 */
class IncognitoCookieAdministrator @Inject constructor() : CookieAdministrator {
    override fun adjustCookieSettings() {
        CookieManager.getInstance().setAcceptCookie(true)
    }
}
