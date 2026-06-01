package com.browser.minnal.browser.view

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.browser.minnal.interpolator.BezierDecelerateInterpolator
import com.browser.minnal.utils.Utils
import androidx.core.view.isVisible

/**
 * Chrome-style indicator: a small tab chip flies from the page toward the tab switcher when a
 * background tab is opened.
 */
object BackgroundTabFlyInAnimation {

    private const val DURATION_MS = 380L
    private const val BOUNCE_DURATION_MS = 100L
    private const val BOUNCE_SCALE_PRIMARY = 1.16f
    private const val BOUNCE_SCALE_SECONDARY = 1.10f

    fun play(
        overlay: ViewGroup,
        contentView: View,
        tabSwitcherView: View,
        chipColor: Int,
        chipStrokeColor: Int,
    ) {
        if (!tabSwitcherView.isVisible) {
            return
        }
        overlay.post {
            if (!tabSwitcherView.isVisible) {
                return@post
            }
            val context = overlay.context
            val chipWidth = Utils.dpToPx(52f)
            val chipHeight = Utils.dpToPx(34f)
            val cornerRadius = Utils.dpToPx(6f).toFloat()
            val strokeWidth = Utils.dpToPx(1.5f)

            val chip = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(chipColor)
                    setStroke(strokeWidth, chipStrokeColor)
                    this.cornerRadius = cornerRadius
                }
                elevation = Utils.dpToPx(6f).toFloat()
                alpha = 0.95f
            }

            val layoutParams = FrameLayout.LayoutParams(chipWidth, chipHeight)
            overlay.addView(chip, layoutParams)

            val overlayLoc = IntArray(2)
            overlay.getLocationInWindow(overlayLoc)

            val contentLoc = IntArray(2)
            contentView.getLocationInWindow(contentLoc)
            val startCenterX = contentLoc[0] + contentView.width / 2f - overlayLoc[0]
            val startCenterY = contentLoc[1] + contentView.height * 0.82f - overlayLoc[1]

            val tabLoc = IntArray(2)
            tabSwitcherView.getLocationInWindow(tabLoc)
            val endCenterX = tabLoc[0] + tabSwitcherView.width / 2f - overlayLoc[0]
            val endCenterY = tabLoc[1] + tabSwitcherView.height / 2f - overlayLoc[1]

            chip.x = startCenterX - chipWidth / 2f
            chip.y = startCenterY - chipHeight / 2f
            chip.scaleX = 1f
            chip.scaleY = 1f

            val endX = endCenterX - chipWidth / 2f
            val endY = endCenterY - chipHeight / 2f
            val endScale = (tabSwitcherView.width.toFloat() / chipWidth).coerceIn(0.28f, 0.45f)

            chip.animate()
                .x(endX)
                .y(endY)
                .scaleX(endScale)
                .scaleY(endScale)
                .alpha(0.55f)
                .setDuration(DURATION_MS)
                .setInterpolator(BezierDecelerateInterpolator())
                .withEndAction {
                    overlay.removeView(chip)
                    bounceTabSwitcher(tabSwitcherView)
                }
                .start()
        }
    }

    private fun bounceTabSwitcher(tabSwitcherView: View) {
        tabSwitcherView.animate().cancel()
        tabSwitcherView.scaleX = 1f
        tabSwitcherView.scaleY = 1f
        bouncePulse(tabSwitcherView, BOUNCE_SCALE_PRIMARY) {
            bouncePulse(tabSwitcherView, BOUNCE_SCALE_SECONDARY) {
                tabSwitcherView.scaleX = 1f
                tabSwitcherView.scaleY = 1f
            }
        }
    }

    private fun bouncePulse(tabSwitcherView: View, peakScale: Float, onComplete: () -> Unit) {
        tabSwitcherView.animate()
            .scaleX(peakScale)
            .scaleY(peakScale)
            .setDuration(BOUNCE_DURATION_MS)
            .withEndAction {
                tabSwitcherView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(BOUNCE_DURATION_MS)
                    .withEndAction(onComplete)
                    .start()
            }
            .start()
    }
}
