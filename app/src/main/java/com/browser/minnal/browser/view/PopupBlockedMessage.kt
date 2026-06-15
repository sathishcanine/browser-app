package com.browser.minnal.browser.view

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.browser.minnal.R

/**
 * Compact, short-lived banner when a popup ad is intercepted. Shown near 75% screen height,
 * not full width.
 */
object PopupBlockedMessage {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeBanner: View? = null
    private var dismissRunnable: Runnable? = null

    fun show(activity: Activity, anchor: ViewGroup, @StringRes messageRes: Int) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        dismissRunnable?.let(mainHandler::removeCallbacks)
        activeBanner?.let { banner ->
            (banner.parent as? ViewGroup)?.removeView(banner)
        }
        activeBanner = null

        val banner = activity.layoutInflater.inflate(R.layout.popup_blocked_banner, anchor, false)
        banner.findViewById<TextView>(R.id.message).setText(messageRes)
        banner.alpha = 0f

        val params = topCenterLayoutParams(anchor)
        anchor.addView(banner, params)
        activeBanner = banner

        anchor.post {
            if (banner.parent == null) {
                return@post
            }
            val verticalAnchor = anchor.height * VERTICAL_POSITION_FRACTION
            params.topMargin = (verticalAnchor - banner.height / 2f)
                .toInt()
                .coerceIn(0, (anchor.height - banner.height).coerceAtLeast(0))
            banner.layoutParams = params
            banner.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        }

        val dismiss = Runnable {
            if (banner.parent == null) {
                return@Runnable
            }
            banner.animate()
                .alpha(0f)
                .setDuration(FADE_OUT_MS)
                .withEndAction {
                    (banner.parent as? ViewGroup)?.removeView(banner)
                    if (activeBanner === banner) {
                        activeBanner = null
                    }
                }
                .start()
        }
        dismissRunnable = dismiss
        mainHandler.postDelayed(dismiss, VISIBLE_MS)
    }

    private fun topCenterLayoutParams(anchor: ViewGroup): ViewGroup.MarginLayoutParams {
        val width = ViewGroup.LayoutParams.WRAP_CONTENT
        val height = ViewGroup.LayoutParams.WRAP_CONTENT
        return when (anchor) {
            is CoordinatorLayout -> CoordinatorLayout.LayoutParams(width, height).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            is FrameLayout -> FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            else -> ViewGroup.MarginLayoutParams(width, height)
        }
    }

    private const val VERTICAL_POSITION_FRACTION = 0.75f
    private const val VISIBLE_MS = 900L
    private const val FADE_IN_MS = 120L
    private const val FADE_OUT_MS = 120L
}
