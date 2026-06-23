package com.browser.minnal.download.manager

import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.browser.minnal.log.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks active transfers and drives [DownloadForegroundService] so downloads survive
 * when the user switches to another app.
 *
 * On Android 12+ [android.content.Context.startForegroundService] throws when the app is
 * backgrounded; we skip FGS in that case and keep the download alive via [DownloadNotifier]
 * plus the in-process [DownloadRunner].
 */
@Singleton
class DownloadForegroundCoordinator @Inject constructor(
    private val context: Context,
    private val notifier: DownloadNotifier,
    private val logger: Logger,
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
                stopForegroundService()
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

    private fun stopForegroundService() {
        if (DownloadForegroundService.stopIfRunning()) {
            return
        }
        val intent = Intent(context, DownloadForegroundService::class.java)
        runCatching { context.stopService(intent) }
    }

    private fun dispatch(
        action: String,
        notificationId: Int,
        notification: Notification,
        startForeground: Boolean,
    ) {
        if (startForeground && !canStartForegroundService()) {
            logger.log(TAG, "Skipping FGS start while app is backgrounded; using regular notification.")
            notifier.notifyNow(notificationId, notification)
            return
        }

        val intent = Intent(context, DownloadForegroundService::class.java).apply {
            this.action = action
            putExtra(DownloadForegroundService.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(DownloadForegroundService.EXTRA_NOTIFICATION, notification)
        }
        if (startForeground) {
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { t ->
                logger.log(TAG, "FGS start blocked; falling back to regular notification.", t)
                notifier.notifyNow(notificationId, notification)
            }
            return
        }
        runCatching {
            context.startService(intent)
        }.onFailure { t ->
            logger.log(TAG, "Failed to deliver FGS update intent; refreshing notification.", t)
            notifier.notifyNow(notificationId, notification)
        }
    }

    /** True when at least one activity is visible — required for background FGS starts on API 31+. */
    private fun canStartForegroundService(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    companion object {
        private const val TAG = "DownloadForegroundCoord"
    }
}
