package com.browser.minnal.browser.tab

import com.browser.minnal.R
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.webkit.WebView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.isVisible
import kotlin.math.min

/**
 * Pull-to-refresh container that intercepts downward drags when the page is at the top.
 */
internal class PullToRefreshLayout(context: Context) : FrameLayout(context) {

    var targetWebView: WebView? = null
    var onRefreshListener: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val triggerDistancePx = (96 * resources.displayMetrics.density).toInt()
    private val maxDragPx = (160 * resources.displayMetrics.density)
    private val refreshIconSizePx = (48 * resources.displayMetrics.density).toInt()

    private var initialDownY = 0f
    private var dragging = false
    private var isRefreshing = false

    private val refreshActiveAnimation: Animation =
        AnimationUtils.loadAnimation(context, R.anim.pull_refresh_active)

    private val refreshIcon = ImageView(context).apply {
        isClickable = false
        isFocusable = false
        visibility = GONE
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(R.drawable.pull_refresh_icon)
        contentDescription = context.getString(R.string.pull_to_refresh)
    }

    init {
        clipToPadding = false
        clipChildren = false
        addView(
            refreshIcon,
            LayoutParams(refreshIconSizePx, refreshIconSizePx).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (12 * resources.displayMetrics.density).toInt()
            },
        )
    }

    fun bringProgressToFront() {
        bringChildToFront(refreshIcon)
    }

    override fun addView(child: android.view.View?, index: Int, params: ViewGroup.LayoutParams?) {
        super.addView(child, index, params)
        if (child != null && child !== refreshIcon) {
            bringChildToFront(refreshIcon)
        }
    }

    fun setRefreshing(refreshing: Boolean) {
        isRefreshing = refreshing
        if (refreshing) {
            refreshIcon.isVisible = true
            refreshIcon.alpha = 1f
            refreshIcon.translationY = 0f
            refreshIcon.scaleX = 1f
            refreshIcon.scaleY = 1f
            startRefreshAnimations()
        } else {
            stopRefreshAnimations()
            if (!dragging) {
                resetDragVisuals()
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val webView = targetWebView ?: return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialDownY = ev.y
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!canPull(webView)) {
                    return false
                }
                val dy = ev.y - initialDownY
                if (!dragging && dy > touchSlop) {
                    dragging = true
                    cancelChildTouch(webView)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                dragging = false
            }
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) {
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dy = (event.y - initialDownY).coerceAtLeast(0f)
                updateDragVisuals(dy)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dy = event.y - initialDownY
                if (dy >= triggerDistancePx) {
                    triggerRefresh()
                } else {
                    resetDragVisuals()
                }
                dragging = false
                return true
            }
        }
        return true
    }

    private fun canPull(webView: WebView): Boolean =
        when (webView) {
            is PullRefreshWebView -> webView.canPullToRefresh()
            else -> webView.scrollY <= PullRefreshWebView.PAGE_TOP_THRESHOLD_PX
        }

    private fun cancelChildTouch(webView: WebView) {
        val now = System.currentTimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        webView.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun updateDragVisuals(dy: Float) {
        if (isRefreshing) {
            return
        }
        stopRefreshAnimations()
        val clamped = min(dy, maxDragPx)
        val progress = (clamped / triggerDistancePx).coerceIn(0f, 1f)
        refreshIcon.isVisible = true
        refreshIcon.alpha = 0.35f + 0.65f * progress
        refreshIcon.translationY = clamped * 0.45f
        val scale = 0.55f + 0.45f * progress
        refreshIcon.scaleX = scale
        refreshIcon.scaleY = scale
        refreshIcon.rotation = 180f * progress
    }

    private fun resetDragVisuals() {
        if (isRefreshing) {
            return
        }
        stopRefreshAnimations()
        refreshIcon.isVisible = false
        refreshIcon.translationY = 0f
        refreshIcon.alpha = 0f
        refreshIcon.rotation = 0f
        refreshIcon.scaleX = 1f
        refreshIcon.scaleY = 1f
    }

    private fun triggerRefresh() {
        refreshIcon.isVisible = true
        refreshIcon.alpha = 1f
        refreshIcon.translationY = 0f
        refreshIcon.scaleX = 1f
        refreshIcon.scaleY = 1f
        onRefreshListener?.invoke()
    }

    private fun startRefreshAnimations() {
        if (refreshIcon.animation === refreshActiveAnimation) {
            return
        }
        refreshIcon.clearAnimation()
        refreshIcon.startAnimation(refreshActiveAnimation)
    }

    private fun stopRefreshAnimations() {
        refreshIcon.clearAnimation()
        if (!isRefreshing) {
            refreshIcon.rotation = 0f
            refreshIcon.scaleX = 1f
            refreshIcon.scaleY = 1f
        }
    }
}
