package com.browser.minnal.firebase

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.browser.minnal.R
import com.browser.minnal.log.Logger
import com.browser.minnal.preference.UserPreferences
import com.google.firebase.messaging.RemoteMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shows FCM announcements (app updates, app promos) and routes taps to the Play Store.
 */
@Singleton
class PushNotificationHelper @Inject constructor(
    private val application: Application,
    private val notificationManager: NotificationManager,
    private val userPreferences: UserPreferences,
    private val logger: Logger,
) {

    init {
        createChannel()
    }

    fun handleRemoteMessage(message: RemoteMessage) {
        if (!userPreferences.promotionalPushEnabled) {
            return
        }
        val data = message.data
        val type = data[DATA_TYPE] ?: return
        if (type != TYPE_APP_UPDATE && type != TYPE_DIFFERENT) {
            return
        }
        val title = message.notification?.title
            ?: data[DATA_TITLE]
            ?: application.getString(R.string.push_notification_default_title)
        val body = message.notification?.body
            ?: data[DATA_BODY]
            ?: return
        val target = data[DATA_TARGET]?.takeIf { it.isNotBlank() }
            ?: application.packageName
        show(type = type, title = title, body = body, target = target)
    }

    fun handleNotificationOpen(intent: Intent?): Boolean {
        val type = intent?.getStringExtra(DATA_TYPE) ?: return false
        if (type != TYPE_APP_UPDATE && type != TYPE_DIFFERENT) {
            return false
        }
        val target = intent.getStringExtra(DATA_TARGET)?.takeIf { it.isNotBlank() }
            ?: application.packageName
        PlayStoreIntents.open(application, target)
        intent.removeExtra(DATA_TYPE)
        intent.removeExtra(DATA_TARGET)
        return true
    }

    private fun show(type: String, title: String, body: String, target: String) {
        val contentIntent = PendingIntent.getBroadcast(
            application,
            type.hashCode(),
            Intent(application, PushNotificationClickReceiver::class.java).apply {
                putExtra(DATA_TYPE, type)
                putExtra(DATA_TARGET, target)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(application, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_action_home)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
        logger.log(TAG, "Push shown type=$type target=$target")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            application.getString(R.string.push_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = application.getString(R.string.push_notification_channel_description)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "PushNotificationHelper"
        const val CHANNEL_ID = "minnal_announcements"
        private const val NOTIFICATION_ID = 7401

        const val TYPE_APP_UPDATE = "app_update"
        const val TYPE_DIFFERENT = "different"

        const val DATA_TYPE = "type"
        const val DATA_TARGET = "target"
        const val DATA_TITLE = "title"
        const val DATA_BODY = "body"
    }
}
