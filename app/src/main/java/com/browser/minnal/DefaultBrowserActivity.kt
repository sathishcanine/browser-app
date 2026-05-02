package com.browser.minnal

import com.browser.minnal.browser.BrowserActivity

/**
 * The default browsing experience.
 */
class DefaultBrowserActivity : BrowserActivity() {
    override fun isIncognito(): Boolean = false

    override fun menu(): Int = R.menu.main

    override fun homeIcon(): Int = R.drawable.ic_action_home
}
