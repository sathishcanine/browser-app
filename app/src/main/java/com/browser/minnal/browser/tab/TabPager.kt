package com.browser.minnal.browser.tab

import com.browser.minnal.R
import com.browser.minnal.browser.di.Browser2Scope
import com.browser.minnal.browser.view.WebViewLongPressHandler
import com.browser.minnal.browser.view.WebViewScrollCoordinator
import com.browser.minnal.browser.view.targetUrl.LongPress
import com.browser.minnal.preference.UserPreferences
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.children
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
    private val pullRefreshWrappers: MutableMap<Int, PullToRefreshLayout> = mutableMapOf()

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
        pullRefreshWrappers.remove(id)?.let { wrapper ->
            (wrapper.getChildAt(0) as? WebView)?.let { wv ->
                wv.setTag(R.id.tag_pull_refresh_layout, null)
                wrapper.removeView(wv)
            }
            (wrapper.parent as? ViewGroup)?.removeView(wrapper)
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
            unwrapFromPullRefreshIfNeeded(tabId, webView)
            webView.setTag(R.id.tag_pull_refresh_layout, null)
            return webView
        }

        val wrapper = pullRefreshWrappers.getOrPut(tabId) {
            PullToRefreshLayout(container.context).apply {
                targetWebView = webView
                onRefreshListener = {
                    val wv = targetWebView
                    setRefreshing(true)
                    wv?.stopLoading()
                    wv?.reload()
                }
            }
        }

        wrapper.targetWebView = webView

        if (webView.parent != wrapper) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            wrapper.addView(
                webView,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            wrapper.bringProgressToFront()
        }

        webView.isNestedScrollingEnabled = true
        webView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        webView.setTag(R.id.tag_pull_refresh_layout, wrapper)
        return wrapper
    }

    private fun unwrapFromPullRefreshIfNeeded(tabId: Int, webView: WebView) {
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        pullRefreshWrappers.remove(tabId)?.let { wrapper ->
            if (webView.parent == wrapper) {
                wrapper.removeView(webView)
            }
            (wrapper.parent as? ViewGroup)?.removeView(wrapper)
        }
    }

    private fun FrameLayout.removeWebViews(excludeId: Int = -1) {
        children.toList().forEach { child ->
            val tabId = when (child) {
                is WebView -> child.id
                is PullToRefreshLayout -> child.targetWebView?.id ?: Int.MIN_VALUE
                else -> Int.MIN_VALUE
            }
            if (tabId != excludeId) {
                removeView(child)
            }
        }
    }
}
