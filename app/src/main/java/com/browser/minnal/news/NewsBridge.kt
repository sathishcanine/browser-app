package com.browser.minnal.news

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.browser.minnal.html.bookmark.BookmarkPageFactory
import com.browser.minnal.html.homepage.HomePageFactory
import com.browser.minnal.preference.UserPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * JavaScript bridge exposed to in-app pages as `window.MinnalNews`.
 *
 * Strictly URL-gated to the home / bookmarks page (paths ending in
 * [BookmarkPageFactory.FILENAME] or [HomePageFactory.FILENAME]). One bridge instance is
 * registered on every WebView; the URL gate makes it safe for arbitrary pages to ignore.
 *
 * Bridge methods are invoked on a binder thread by WebView, so anything that touches the
 * WebView is hopped to the main thread via [mainHandler]. Pure data lookups (`list`, `state`)
 * are cheap and run inline.
 *
 * Public API (JavaScript):
 * ```
 * MinnalNews.isAvailable()                 // -> true on the home page
 * MinnalNews.list()                        // -> JSON array of items (newest first)
 * MinnalNews.state()                       // -> { updatedAt: <ms>, refreshing: <bool>, enabled: <bool> }
 * MinnalNews.refresh()                     // fire-and-forget; the page polls state() to know when done
 * ```
 */
class NewsBridge(
    private val webView: WebView,
    private val repository: NewsRepository,
    private val userPreferences: UserPreferences
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun isAvailable(): Boolean = isOnHomePage()

    @JavascriptInterface
    fun isEnabled(): Boolean = userPreferences.discoverFeedEnabled

    /** JSON-serialized snapshot of the current feed. Empty array on misuse / disabled. */
    @JavascriptInterface
    fun list(): String {
        if (!isOnHomePage() || !userPreferences.discoverFeedEnabled) return "[]"
        val arr = JSONArray()
        for (item in repository.snapshot()) {
            arr.put(
                JSONObject()
                    .put("title", item.title)
                    .put("url", item.url)
                    .put("sourceId", item.source.id)
                    .put("sourceName", item.source.name)
                    .put("language", item.source.language.name)
                    .put("publishedAt", item.publishedAt)
                    .apply {
                        item.summary?.let { put("summary", it) }
                        item.imageUrl?.let { put("imageUrl", it) }
                    }
            )
        }
        return arr.toString()
    }

    /** Compact JSON snapshot of the loader / preference state (used by the page header chip). */
    @JavascriptInterface
    fun state(): String {
        val sources = JSONArray()
        for (s in repository.currentSources()) {
            sources.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("language", s.language.name)
            )
        }
        return JSONObject()
            .put("enabled", userPreferences.discoverFeedEnabled)
            .put("refreshing", repository.isRefreshing())
            .put("updatedAt", repository.lastUpdatedAt())
            .put("sources", sources)
            .toString()
    }

    @JavascriptInterface
    fun refresh() {
        if (!isOnHomePage() || !userPreferences.discoverFeedEnabled) return
        repository.refreshAsync()
    }

    /**
     * URL gate: only methods initiated from a Minnal home page (start page or bookmark page)
     * should be honored. We read the URL on the main thread to respect WebView's same-thread
     * guarantees; bridge methods are invoked on a binder thread.
     */
    private fun isOnHomePage(): Boolean {
        var url: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            url = webView.url
            latch.countDown()
        }
        return runCatching {
            latch.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            val u = url ?: return@runCatching false
            u.startsWith("file://") &&
                (u.endsWith(BookmarkPageFactory.FILENAME) || u.endsWith(HomePageFactory.FILENAME))
        }.getOrDefault(false)
    }

    companion object {
        const val NAME = "MinnalNews"
    }
}
