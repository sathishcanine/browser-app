package com.browser.minnal.download.manager

import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks active transfers and drives [DownloadForegroundService] so downloads survive
 * when the user switches to another app.
 */
@Singleton
class DownloadForegroundCoordinator @Inject constructor(
    private val context: Context,
) {

    private val activeUrls = LinkedHashSet<String>()
    private val lock = Any()

    fun attach(url: String, notificationId: Int, notification: Notification) {
        synchronized(lock) {
            val wasEmpty = activeUrls.isEmpty()
            activeUrls.add(url)
            dispatch(
                action = if (wasEmpty) DownloadForegroundService.ACTION_START else DownloadForegroundService.ACTION_UPDATE,
                notificationId = notificationId,
                notification = notification,
                startForeground = wasEmpty,
            )
        }
    }

    fun update(url: String, notificationId: Int, notification: Notification) {
        synchronized(lock) {
            if (!activeUrls.contains(url)) {
                return
            }
            dispatch(
                action = DownloadForegroundService.ACTION_UPDATE,
                notificationId = notificationId,
                notification = notification,
                startForeground = false,
            )
        }
    }

    fun detach(url: String) {
        synchronized(lock) {
            activeUrls.remove(url)
            if (activeUrls.isEmpty()) {
                context.startService(
                    Intent(context, DownloadForegroundService::class.java).apply {
                        action = DownloadForegroundService.ACTION_STOP
                    },
                )
            }
        }
    }

    fun hasActiveTransfers(): Boolean = synchronized(lock) { activeUrls.isNotEmpty() }

    /** Re-attach the foreground service using the same per-URL notification (no duplicate). */
    fun rebind(url: String, notificationId: Int, notification: Notification) {
        synchronized(lock) {
            activeUrls.add(url)
            dispatch(
                action = DownloadForegroundService.ACTION_START,
                notificationId = notificationId,
                notification = notification,
                startForeground = true,
            )
        }
    }

    private fun dispatch(
        action: String,
        notificationId: Int,
        notification: Notification,
        startForeground: Boolean,
    ) {
        val intent = Intent(context, DownloadForegroundService::class.java).apply {
            this.action = action
            putExtra(DownloadForegroundService.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(DownloadForegroundService.EXTRA_NOTIFICATION, notification)
        }
        if (startForeground) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }
}
