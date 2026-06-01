package com.browser.minnal.browser.tab

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * Tracks whether the loaded document is scrolled to the top (via JS) in addition to [scrollY].
 */
@SuppressLint("SetJavaScriptEnabled")
internal class PullRefreshWebView(context: Context) : WebView(context) {

    @Volatile
    var documentScrollAtTop: Boolean = true
        private set

    private var scrollBridgeInstalled = false

    fun isAtPageTop(): Boolean =
        scrollY <= PAGE_TOP_THRESHOLD_PX && documentScrollAtTop

    fun canPullToRefresh(): Boolean = isAtPageTop()

    fun installScrollTopBridge() {
        if (scrollBridgeInstalled) {
            evaluateJavascript(scrollTopNotifyJs, null)
            return
        }
        scrollBridgeInstalled = true
        addJavascriptInterface(ScrollTopBridge(), JS_INTERFACE_NAME)
        evaluateJavascript(scrollTopInstallJs, null)
    }

    fun resetScrollTopState() {
        documentScrollAtTop = true
        scrollBridgeInstalled = false
    }

    private inner class ScrollTopBridge {
        @JavascriptInterface
        fun onAtTopChanged(atTop: Boolean) {
            post { documentScrollAtTop = atTop }
        }
    }

    companion object {
        const val PAGE_TOP_THRESHOLD_PX = 8
        private const val JS_INTERFACE_NAME = "MinnalScroll"

        private val scrollTopInstallJs = """
            (function() {
              if (window.__minnalScrollInstalled) {
                var y = window.scrollY || document.documentElement.scrollTop || document.body.scrollTop || 0;
                $JS_INTERFACE_NAME.onAtTopChanged(y <= 2);
                return;
              }
              window.__minnalScrollInstalled = true;
              function notify() {
                var y = window.scrollY || document.documentElement.scrollTop || document.body.scrollTop || 0;
                $JS_INTERFACE_NAME.onAtTopChanged(y <= 2);
              }
              window.addEventListener('scroll', notify, { passive: true });
              notify();
            })();
        """.trimIndent()

        private val scrollTopNotifyJs = """
            (function() {
              var y = window.scrollY || document.documentElement.scrollTop || document.body.scrollTop || 0;
              $JS_INTERFACE_NAME.onAtTopChanged(y <= 2);
            })();
        """.trimIndent()
    }
}
