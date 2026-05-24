package com.browser.minnal.browser.tab

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Limits how many popup / background ad tabs one user tap can spawn. Movie and ad sites
 * often fire several `window.open()` or redirect chains per click.
 */
@Singleton
class PopupTabGate @Inject constructor() {

    private var openedSinceGesture = 0
    private val recentUrls = LinkedHashSet<String>()

    @Synchronized
    fun onUserGesture() {
        openedSinceGesture = 0
        recentUrls.clear()
    }

    @Synchronized
    fun shouldAllowPopupTab(url: String): Boolean {
        if (openedSinceGesture >= MAX_POPUP_TABS_PER_GESTURE) {
            return false
        }
        val key = normalizeUrlKey(url)
        if (key in recentUrls) {
            return false
        }
        return true
    }

    @Synchronized
    fun shouldAllowPopupWindow(): Boolean = openedSinceGesture < MAX_POPUP_TABS_PER_GESTURE

    @Synchronized
    fun recordPopupTabOpened(url: String) {
        openedSinceGesture++
        recentUrls.add(normalizeUrlKey(url))
        while (recentUrls.size > MAX_TRACKED_URLS) {
            val eldest = recentUrls.first()
            recentUrls.remove(eldest)
        }
    }

    @Synchronized
    fun recordPopupWindowOpened() {
        openedSinceGesture++
    }

    private fun normalizeUrlKey(url: String): String =
        url.lowercase().substringBefore('#').trimEnd('/')

    companion object {
        private const val MAX_POPUP_TABS_PER_GESTURE = 1
        private const val MAX_TRACKED_URLS = 8
    }
}
