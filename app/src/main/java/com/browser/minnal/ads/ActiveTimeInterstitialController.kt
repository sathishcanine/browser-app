package com.browser.minnal.ads

import com.browser.minnal.log.Logger
import android.os.Handler
import android.os.SystemClock
import androidx.fragment.app.FragmentActivity
import com.browser.minnal.browser.di.MainHandler
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shows an interstitial after [ACTIVE_INTERVAL_MS] of foreground browser time.
 *
 * Uses elapsed realtime and a single [Handler] callback — no background thread and no polling.
 * Time pauses when the activity is not in the resumed state.
 */
@Singleton
class ActiveTimeInterstitialController @Inject constructor(
    @MainHandler private val handler: Handler,
    private val interstitialAdHelper: InterstitialAdHelper,
    private val logger: Logger,
) {

    private var accumulatedActiveMs = 0L
    private var resumedAtElapsedMs = 0L
    private var tickRunnable: Runnable? = null
    private var activityRef: WeakReference<FragmentActivity>? = null
    private var canShowCheck: (() -> Boolean)? = null

    fun restoreAccumulatedActiveMs(ms: Long) {
        accumulatedActiveMs = ms.coerceIn(0L, ACTIVE_INTERVAL_MS)
    }

    fun onBrowserResumed(activity: FragmentActivity, canShow: () -> Boolean) {
        activityRef = WeakReference(activity)
        canShowCheck = canShow
        if (resumedAtElapsedMs != 0L) {
            return
        }
        resumedAtElapsedMs = SystemClock.elapsedRealtime()
        interstitialAdHelper.preload(activity)
        scheduleNextTick()
    }

    /**
     * Pauses the active-time clock and returns accumulated ms for [onSaveInstanceState].
     */
    fun pauseAndGetAccumulatedMs(): Long {
        flushResumedSegment()
        cancelTick()
        activityRef = null
        canShowCheck = null
        return accumulatedActiveMs
    }

    private fun flushResumedSegment() {
        if (resumedAtElapsedMs == 0L) {
            return
        }
        val segment = SystemClock.elapsedRealtime() - resumedAtElapsedMs
        accumulatedActiveMs = (accumulatedActiveMs + segment).coerceAtMost(ACTIVE_INTERVAL_MS)
        resumedAtElapsedMs = 0L
    }

    private fun totalActiveMs(): Long {
        val live = if (resumedAtElapsedMs != 0L) {
            SystemClock.elapsedRealtime() - resumedAtElapsedMs
        } else {
            0L
        }
        return accumulatedActiveMs + live
    }

    private fun scheduleNextTick() {
        cancelTick()
        val activity = activityRef?.get() ?: return
        val remaining = ACTIVE_INTERVAL_MS - totalActiveMs()
        if (remaining <= 0L) {
            handler.post { onIntervalElapsed(activity) }
            return
        }
        tickRunnable = Runnable {
            val act = activityRef?.get() ?: return@Runnable
            if (resumedAtElapsedMs == 0L) {
                return@Runnable
            }
            onIntervalElapsed(act)
        }
        handler.postDelayed(tickRunnable!!, remaining)
    }

    private fun onIntervalElapsed(activity: FragmentActivity) {
        accumulatedActiveMs = ACTIVE_INTERVAL_MS
        attemptShow(activity)
    }

    private fun attemptShow(activity: FragmentActivity) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        if (canShowCheck?.invoke() != true || interstitialAdHelper.isSuppressed()) {
            scheduleRetry(RETRY_WHEN_BLOCKED_MS)
            return
        }
        val shown = interstitialAdHelper.showIfReady(activity) {
            onInterstitialFinished(activity)
        }
        if (!shown) {
            interstitialAdHelper.preload(activity)
            scheduleRetry(RETRY_WHEN_NOT_READY_MS)
        }
    }

    private fun onInterstitialFinished(activity: FragmentActivity) {
        accumulatedActiveMs = 0L
        if (resumedAtElapsedMs != 0L) {
            resumedAtElapsedMs = SystemClock.elapsedRealtime()
            scheduleNextTick()
        }
        logger.log(TAG, "Interstitial cycle complete; next in ${ACTIVE_INTERVAL_MS / 60_000} min active use")
    }

    private fun scheduleRetry(delayMs: Long) {
        cancelTick()
        tickRunnable = Runnable {
            activityRef?.get()?.let(::attemptShow)
        }
        handler.postDelayed(tickRunnable!!, delayMs)
    }

    private fun cancelTick() {
        tickRunnable?.let(handler::removeCallbacks)
        tickRunnable = null
    }

    companion object {
        private const val TAG = "ActiveTimeInterstitial"

        private const val ACTIVE_INTERVAL_MS = 6 * 60 * 1000L
        private const val RETRY_WHEN_BLOCKED_MS = 30_000L
        private const val RETRY_WHEN_NOT_READY_MS = 60_000L
    }
}
