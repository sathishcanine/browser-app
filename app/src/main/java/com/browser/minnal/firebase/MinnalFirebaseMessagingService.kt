package com.browser.minnal.firebase

import com.browser.minnal.BrowserApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives FCM messages while the app is in the foreground. Background notification+data
 * payloads are shown by the system; taps are handled in [BrowserActivity] via intent extras.
 */
class MinnalFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val app = applicationContext as? BrowserApp ?: return
        app.applicationComponent.pushNotificationHelper().handleRemoteMessage(message)
    }

    override fun onNewToken(token: String) {
        val app = applicationContext as? BrowserApp ?: return
        app.applicationComponent.pushNotificationRegistrar().refreshTokenSubscription()
    }
}
