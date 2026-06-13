package com.browser.minnal.download.manager

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.browser.minnal.html.download.DownloadPageFactory
import com.browser.minnal.utils.isDownloadsUrl

/**
 * JavaScript bridge exposed to in-app pages as `window.MinnalDownloads`.
 *
 * Strictly URL-gated to the in-app downloads page (path ending in
 * [DownloadPageFactory.FILENAME]), so arbitrary websites can't poke at the user's downloads
 * even though one bridge instance is registered on every WebView.
 *
 * Bridge methods are invoked by WebView on a private background thread; methods that need to
 * touch Android UI (Activity start, etc.) are dispatched back to the main thread via
 * [mainHandler]. Pure data lookups run inline because they're cheap and don't touch UI.
 *
 * Public API (JavaScript):
 * ```
 * MinnalDownloads.isAvailable()                       // -> true
 * MinnalDownloads.list()                              // -> JSON array of downloads
 * MinnalDownloads.pause(url)
 * MinnalDownloads.resume(url)
 * MinnalDownloads.cancel(url)
 * MinnalDownloads.retry(url)
 * MinnalDownloads.deleteEntry(url, alsoDeleteFile)
 * MinnalDownloads.openFile(localPath, mimeType)       // -> boolean
 * MinnalDownloads.requestRatingPromptIfEligible()     // after a download completes on this page
 * ```
 */
class DownloadsBridge(
    private val webView: WebView,
    private val manager: MinnalDownloadManager,
    private val onRequestRatingPrompt: () -> Unit = {},
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastListJson: String? = null

    @JavascriptInterface
    fun isAvailable(): Boolean = checkDownloadsPage() != PageCheck.OffPage

    /** JSON-serialized [DownloadState]s + persisted DB rows merged. Empty array on misuse. */
    @JavascriptInterface
    fun list(): String = when (checkDownloadsPage()) {
        PageCheck.OffPage -> "[]"
        PageCheck.OnPage -> fetchAndCacheList()
        PageCheck.Unknown -> lastListJson ?: fetchAndCacheList()
    }

    @JavascriptInterface
    fun pause(url: String?) {
        if (checkDownloadsPage() != PageCheck.OnPage || url.isNullOrBlank()) return
        runCatching { manager.pause(url) }
    }

    @JavascriptInterface
    fun resume(url: String?) {
        if (checkDownloadsPage() != PageCheck.OnPage || url.isNullOrBlank()) return
        runCatching { manager.resume(url) }
    }

    @JavascriptInterface
    fun cancel(url: String?) {
        if (checkDownloadsPage() != PageCheck.OnPage || url.isNullOrBlank()) return
        runCatching { manager.cancel(url) }
    }

    @JavascriptInterface
    fun retry(url: String?) {
        if (checkDownloadsPage() != PageCheck.OnPage || url.isNullOrBlank()) return
        runCatching { manager.retry(url) }
    }

    @JavascriptInterface
    fun deleteEntry(url: String?, alsoDeleteFile: Boolean) {
        if (checkDownloadsPage() != PageCheck.OnPage || url.isNullOrBlank()) return
        runCatching { manager.deleteEntry(url, alsoDeleteFile) }
    }

    /**
     * Launch a viewer for an already-committed file. Returns true synchronously if Android
     * accepted the intent. Page should fall back to `MinnalDownloads.copyUrl(...)` etc.
     * when this returns false.
     */
    @JavascriptInterface
    fun openFile(localPath: String?, mimeType: String?): Boolean {
        if (checkDownloadsPage() != PageCheck.OnPage) return false
        return manager.openCommittedFile(localPath, mimeType)
    }

    /** Called from the downloads page when a row transitions to COMPLETED. */
    @JavascriptInterface
    fun requestRatingPromptIfEligible(): Boolean {
        if (checkDownloadsPage() != PageCheck.OnPage) return false
        mainHandler.post(onRequestRatingPrompt)
        return true
    }

    private fun fetchAndCacheList(): String =
        runCatching { manager.getDownloadsJson() }
            .getOrElse { lastListJson ?: "[]" }
            .also { json ->
                if (json != "[]" || lastListJson == null) {
                    lastListJson = json
                }
            }

    /**
     * URL gate: only methods initiated from our own downloads page should be honored. We
     * read the URL on the main thread to avoid the WebView's same-thread guarantees being
     * violated; bridge methods are invoked on a binder thread.
     */
    private fun checkDownloadsPage(): PageCheck {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return pageCheckForUrl(readWebViewUrl())
        }
        var url: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            url = readWebViewUrl()
            latch.countDown()
        }
        return runCatching {
            if (!latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return PageCheck.Unknown
            }
            pageCheckForUrl(url)
        }.getOrDefault(PageCheck.Unknown)
    }

    private fun readWebViewUrl(): String? = runCatching { webView.url }.getOrNull()

    private fun pageCheckForUrl(url: String?): PageCheck =
        if (url.isDownloadsUrl()) PageCheck.OnPage else PageCheck.OffPage

    private enum class PageCheck {
        OnPage,
        OffPage,
        Unknown,
    }

    companion object {
        const val NAME = "MinnalDownloads"
    }
}
