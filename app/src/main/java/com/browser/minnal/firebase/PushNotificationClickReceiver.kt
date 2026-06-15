package com.browser.minnal.firebase

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.browser.minnal.BrowserApp

/**
 * Opens the Play Store when the user taps an FCM announcement notification.
 */
class PushNotificationClickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? BrowserApp ?: return
        app.applicationComponent.pushNotificationHelper().handleNotificationOpen(intent)
    }
}
