package com.browser.minnal.firebase

import com.browser.minnal.BuildConfig
import com.browser.minnal.log.Logger
import com.browser.minnal.preference.UserPreferences
import com.google.firebase.messaging.FirebaseMessaging
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subscribes opted-in users to the shared FCM topic used for update / app-promo campaigns.
 */
@Singleton
class PushNotificationRegistrar @Inject constructor(
    private val userPreferences: UserPreferences,
    private val logger: Logger,
) {

    fun syncTopicSubscription() {
        val task = if (userPreferences.promotionalPushEnabled) {
            FirebaseMessaging.getInstance().subscribeToTopic(TOPIC_ANNOUNCEMENTS)
        } else {
            FirebaseMessaging.getInstance().unsubscribeFromTopic(TOPIC_ANNOUNCEMENTS)
        }
        task.addOnCompleteListener { result ->
            if (result.isSuccessful) {
                logger.log(
                    TAG,
                    if (userPreferences.promotionalPushEnabled) {
                        "Subscribed to FCM topic $TOPIC_ANNOUNCEMENTS"
                    } else {
                        "Unsubscribed from FCM topic $TOPIC_ANNOUNCEMENTS"
                    },
                )
            } else {
                result.exception?.let { error ->
                    logger.log(TAG, "FCM topic sync failed", error)
                }
            }
        }
    }

    fun refreshTokenSubscription() {
        if (!userPreferences.promotionalPushEnabled) {
            return
        }
        syncTopicSubscription()
        if (BuildConfig.DEBUG) {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                logger.log(TAG, "FCM token (debug): $token")
            }
        }
    }

    companion object {
        private const val TAG = "PushNotificationRegistrar"
        const val TOPIC_ANNOUNCEMENTS = "minnal_announcements"
    }
}
