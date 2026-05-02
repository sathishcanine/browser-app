package com.browser.minnal.browser.tab

import com.browser.minnal.R
import com.browser.minnal.browser.di.Browser2Scope
import com.browser.minnal.browser.view.WebViewLongPressHandler
import com.browser.minnal.browser.view.WebViewScrollCoordinator
import com.browser.minnal.browser.view.targetUrl.LongPress
import com.browser.minnal.preference.UserPreferences
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import javax.inject.Inject

/**
 * A sort of coordinator that manages the relationship between [WebViews][WebView] and the container
 * the views are placed in.
 */
@Browser2Scope
class TabPager @Inject constructor(
    private val container: FrameLayout,
    private val webViewScrollCoordinator: WebViewScrollCoordinator,
    private val webViewLongPressHandler: WebViewLongPressHandler,
    private val userPreferences: UserPreferences,
) {

    private val webViews: MutableMap<Int, Lazy<WebView>> = mutableMapOf()
    private val swipeWrappers: MutableMap<Int, SwipeRefreshLayout> = mutableMapOf()

    var longPressListener: ((id: Int, longPress: LongPress) -> Unit)? = null

    /**
     * Select the tab with the provided [id] to be displayed by the pager.
     */
    fun selectTab(id: Int) {
        container.removeWebViews(excludeId = id)
        val webView = webViews[id]!!.value
        val displayView = wrapWebViewIfNeeded(id, webView)
        if (displayView.parent != container) {
            container.addView(
                displayView,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        webViewScrollCoordinator.configure(webView)
        webViewLongPressHandler.configure(webView, onLongClick = {
            longPressListener?.invoke(id, it)
        })
    }

    /**
     * Clear the container of the [WebView] currently shown.
     */
    fun clearTab() {
        container.removeWebViews()
    }

    /**
     * Remove pager bookkeeping for a tab that is being destroyed.
     */
    fun removeTabEntry(id: Int) {
        swipeWrappers.remove(id)?.let { srl ->
            (srl.getChildAt(0) as? WebView)?.let { wv ->
                wv.setTag(R.id.tag_pull_refresh_layout, null)
                srl.removeView(wv)
            }
            (srl.parent as? ViewGroup)?.removeView(srl)
        }
        webViews.remove(id)
    }

    /**
     * Add a [WebView] to the list of views shown by this pager.
     */
    fun addTab(id: Int, webView: Lazy<WebView>) {
        webViews[id] = webView
    }

    /**
     * Show the toolbar/search box if it is currently hidden.
     */
    fun showToolbar() {
        webViewScrollCoordinator.showToolbar()
    }

    fun isBottomTabDrawerOpen() = webViewScrollCoordinator.isBottomTabDrawerOpen()

    fun openBottomTabDrawer() {
        webViewScrollCoordinator.openBottomTabDrawer()
    }

    fun closeBottomTabDrawer() {
        webViewScrollCoordinator.closeBottomTabDrawer()
    }

    private fun wrapWebViewIfNeeded(tabId: Int, webView: WebView): ViewGroup {
        if (!userPreferences.pullToRefreshEnabled) {
            unwrapFromSwipeIfNeeded(tabId, webView)
            webView.setTag(R.id.tag_pull_refresh_layout, null)
            return webView
        }

        val swipe = swipeWrappers.getOrPut(tabId) {
            SwipeRefreshLayout(container.context).apply {
                setColorSchemeColors(ContextCompat.getColor(container.context, R.color.accent_color))
                setProgressBackgroundColorSchemeColor(
                    ContextCompat.getColor(container.context, R.color.primary_color)
                )
                setOnRefreshListener {
                    webView.reload()
                }
            }
        }

        if (webView.parent != swipe) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            swipe.removeAllViews()
            swipe.addView(
                webView,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        webView.setTag(R.id.tag_pull_refresh_layout, swipe)
        swipe.isEnabled = true
        return swipe
    }

    private fun unwrapFromSwipeIfNeeded(tabId: Int, webView: WebView) {
        swipeWrappers.remove(tabId)?.let { srl ->
            if (webView.parent == srl) {
                srl.removeView(webView)
            }
            (srl.parent as? ViewGroup)?.removeView(srl)
        }
    }

    private fun FrameLayout.removeWebViews(excludeId: Int = -1) {
        children.toList().forEach { child ->
            val tabId = when (child) {
                is WebView -> child.id
                is SwipeRefreshLayout -> (child.getChildAt(0) as? WebView)?.id ?: Int.MIN_VALUE
                else -> Int.MIN_VALUE
            }
            if (tabId != excludeId) {
                removeView(child)
            }
        }
    }
}
