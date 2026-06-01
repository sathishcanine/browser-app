package com.browser.minnal.dialog

import com.browser.minnal.R
import com.browser.minnal.extensions.resizeAndShow
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton

/**
 * Modern bottom-sheet-style card prompt to set Minnal as the default browser.
 */
object DefaultBrowserPromptDialog {

    fun show(
        activity: Activity,
        onSetAsDefault: () -> Unit,
        onNotNow: () -> Unit,
        onDismiss: () -> Unit,
    ): AlertDialog {
        val content = activity.layoutInflater.inflate(R.layout.dialog_default_browser_prompt, null)
        val setButton = content.findViewById<AppCompatButton>(R.id.default_browser_set_button)
        val notNowButton = content.findViewById<View>(R.id.default_browser_not_now_button)

        val dialog = AlertDialog.Builder(activity)
            .setView(content)
            .setCancelable(true)
            .resizeAndShow() as AlertDialog

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        setButton.setOnClickListener {
            onSetAsDefault()
            dialog.dismiss()
        }

        notNowButton.setOnClickListener {
            onNotNow()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            onDismiss()
        }

        content.findViewById<View>(R.id.default_browser_app_icon).let { icon ->
            val pulseX = ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 1.08f, 1f)
            val pulseY = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 1.08f, 1f)
            AnimatorSet().apply {
                playTogether(pulseX, pulseY)
                duration = 520
                startDelay = 150
                start()
            }
        }

        return dialog
    }
}
