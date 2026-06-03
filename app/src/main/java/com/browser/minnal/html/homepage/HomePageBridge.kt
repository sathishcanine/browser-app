package com.browser.minnal.html.homepage

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.browser.minnal.html.bookmark.BookmarkPageFactory

/**
 * JavaScript bridge exposed to in-app pages as `window.MinnalHome`.
 *
 * URL-gated to the bookmarks home page (path ending in [BookmarkPageFactory.FILENAME]).
 */
class HomePageBridge(
    private val webView: WebView,
    private val onAddShortcut: () -> Unit,
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun isAvailable(): Boolean = isOnBookmarkHomePage()

    @JavascriptInterface
    fun addShortcut() {
        if (!isOnBookmarkHomePage()) return
        mainHandler.post(onAddShortcut)
    }

    private fun isOnBookmarkHomePage(): Boolean {
        var url: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            url = webView.url
            latch.countDown()
        }
        return runCatching {
            latch.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            val u = url ?: return@runCatching false
            u.startsWith("file://") && u.endsWith(BookmarkPageFactory.FILENAME)
        }.getOrDefault(false)
    }

    companion object {
        const val NAME = "MinnalHome"
    }
}
