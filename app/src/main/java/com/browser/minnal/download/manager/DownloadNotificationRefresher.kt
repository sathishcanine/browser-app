package com.browser.minnal.download.manager

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts download notifications on a fixed main-thread cadence so progress in the shade
 * stays smooth without rebuilding heavy [android.widget.RemoteViews] on every I/O callback.
 */
@Singleton
class DownloadNotificationRefresher @Inject constructor(
    private val notifier: DownloadNotifier,
) {

    data class Snapshot(
        val url: String,
        val title: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long = 0L,
        val finalizing: Boolean = false,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val latest = ConcurrentHashMap<String, Snapshot>()
    private val ticking = AtomicBoolean(false)

    private val tickRunnable = object : Runnable {
        override fun run() {
            flushAll()
            if (latest.isEmpty()) {
                ticking.set(false)
            } else {
                mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }
    }

    /** Queue [snapshot] for the next scheduled refresh (about every [REFRESH_INTERVAL_MS]). */
    fun update(snapshot: Snapshot) {
        latest[snapshot.url] = snapshot
        ensureTicking()
    }

    /** Post immediately — use at start, finalize, and resume. */
    fun flushNow(snapshot: Snapshot) {
        latest[snapshot.url] = snapshot
        mainHandler.post { postSnapshot(snapshot) }
    }

    fun remove(url: String) {
        latest.remove(url)
    }

    private fun ensureTicking() {
        if (ticking.compareAndSet(false, true)) {
            mainHandler.post(tickRunnable)
        }
    }

    private fun flushAll() {
        for (snapshot in latest.values) {
            postSnapshot(snapshot)
        }
    }

    private fun postSnapshot(snapshot: Snapshot) {
        val notificationId = notifier.notificationIdFor(snapshot.url)
        val notification = notifier.buildOngoingNotification(
            url = snapshot.url,
            title = snapshot.title,
            bytesDownloaded = snapshot.bytesDownloaded,
            totalBytes = snapshot.totalBytes,
            bytesPerSecond = snapshot.bytesPerSecond,
            finalizing = snapshot.finalizing,
        )
        notifier.notifyNow(notificationId, notification)
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 100L
    }
}
