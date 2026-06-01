package com.browser.minnal.rating

import com.browser.minnal.R
import com.browser.minnal.extensions.resizeAndShow
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton

object RatingPromptDialog {

    private var showingDialog: AlertDialog? = null

    fun isShowing(): Boolean = showingDialog?.isShowing == true

    fun show(
        activity: Activity,
        onRated: () -> Unit,
        onLater: () -> Unit,
    ) {
        if (activity.isFinishing || showingDialog?.isShowing == true) {
            return
        }

        val content = activity.layoutInflater.inflate(R.layout.dialog_rating_prompt, null)
        val rateButton = content.findViewById<AppCompatButton>(R.id.rating_rate_button)
        val laterButton = content.findViewById<View>(R.id.rating_later_button)

        val dialog = AlertDialog.Builder(activity)
            .setView(content)
            .setCancelable(true)
            .resizeAndShow() as AlertDialog

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        rateButton.setOnClickListener {
            openPlayStoreListing(activity)
            onRated()
            dialog.dismiss()
        }

        laterButton.setOnClickListener {
            onLater()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            showingDialog = null
        }

        showingDialog = dialog

        content.findViewById<View>(R.id.rating_app_icon).let { icon ->
            val pulseX = ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 1.06f, 1f)
            val pulseY = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 1.06f, 1f)
            AnimatorSet().apply {
                playTogether(pulseX, pulseY)
                duration = 500
                startDelay = 120
                start()
            }
        }
    }

    fun dismissIfShowing() {
        showingDialog?.dismiss()
        showingDialog = null
    }

    private fun openPlayStoreListing(activity: Activity) {
        val packageName = activity.packageName
        val marketUri = Uri.parse("market://details?id=$packageName")
        val webUri = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, marketUri))
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }
}
