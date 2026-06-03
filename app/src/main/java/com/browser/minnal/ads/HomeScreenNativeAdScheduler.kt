package com.browser.minnal.ads

import com.browser.minnal.browser.di.MainHandler
import com.browser.minnal.preference.UserPreferences
import android.os.Handler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Delays the home native ad strip (non-bookmark pages) until blocking UI (popups, app-open ad
 * flow) is gone, then waits [DELAY_AFTER_OVERLAYS_MS] before allowing a load. At most one show
 * per app process session.
 *
 * On later app opens (after the install's first session), also starts that delay when the app
 * returns to the foreground on an eligible page even if no popup dismiss event occurs.
 */
@Singleton
class HomeScreenNativeAdScheduler @Inject constructor(
    private val appOpenAdManager: AppOpenAdManager,
    private val userPreferences: UserPreferences,
    @MainHandler private val handler: Handler,
) {

    private var hostPageVisible = false
    private var eligible = false
    private var shownThisSession = false
    private var listener: ((Boolean) -> Unit)? = null
    private var delayRunnable: Runnable? = null
    private var returningUserFallbackRunnable: Runnable? = null

    fun setListener(listener: ((Boolean) -> Unit)?) {
        this.listener = listener
        listener?.invoke(eligible)
    }

    fun setHostPageVisible(visible: Boolean) {
        if (!visible) {
            if (!hostPageVisible) {
                return
            }
            hostPageVisible = false
            cancelDelay()
            cancelReturningUserFallback()
            setEligible(false)
            return
        }
        if (shownThisSession) {
            return
        }
        hostPageVisible = true
        scheduleDelayAfterOverlays()
    }

    fun onBlockingEnvironmentChanged() {
        if (!hostPageVisible || shownThisSession) {
            return
        }
        scheduleDelayAfterOverlays()
    }

    /**
     * App returned to foreground (including cold start). Ensures the home native ad delay runs
     * on repeat opens when no popups appear and [setHostPageVisible] does not fire again.
     */
    fun onAppForegrounded() {
        if (!hostPageVisible || shownThisSession) {
            return
        }
        scheduleDelayAfterOverlays()
        if (userPreferences.appOpenAdFirstSessionCompleted) {
            scheduleReturningUserNoPopupFallback()
        }
    }

    fun destroy() {
        setListener(null)
        hostPageVisible = false
        cancelDelay()
        cancelReturningUserFallback()
        setEligible(false)
    }

    private fun scheduleDelayAfterOverlays() {
        cancelDelay()
        if (!hostPageVisible || shownThisSession) {
            setEligible(false)
            return
        }
        if (appOpenAdManager.blocksHomeScreenNativeAd()) {
            setEligible(false)
            return
        }
        delayRunnable = Runnable {
            delayRunnable = null
            if (hostPageVisible && !shownThisSession && !appOpenAdManager.blocksHomeScreenNativeAd()) {
                setEligible(true)
            }
        }
        handler.postDelayed(delayRunnable!!, DELAY_AFTER_OVERLAYS_MS)
    }

    /**
     * Popups often appear shortly after resume; this re-checks once for returning users so the
     * delay still starts when nothing blocks (typical second+ app open).
     */
    private fun scheduleReturningUserNoPopupFallback() {
        cancelReturningUserFallback()
        returningUserFallbackRunnable = Runnable {
            returningUserFallbackRunnable = null
            if (!hostPageVisible || eligible || shownThisSession) {
                return@Runnable
            }
            if (!appOpenAdManager.blocksHomeScreenNativeAd()) {
                scheduleDelayAfterOverlays()
            }
        }
        handler.postDelayed(returningUserFallbackRunnable!!, RETURNING_USER_NO_POPUP_RECHECK_MS)
    }

    private fun setEligible(value: Boolean) {
        if (value) {
            if (shownThisSession) {
                return
            }
            shownThisSession = true
        }
        if (eligible == value) {
            return
        }
        eligible = value
        listener?.invoke(eligible)
    }

    private fun cancelDelay() {
        delayRunnable?.let(handler::removeCallbacks)
        delayRunnable = null
    }

    private fun cancelReturningUserFallback() {
        returningUserFallbackRunnable?.let(handler::removeCallbacks)
        returningUserFallbackRunnable = null
    }

    companion object {
        const val DELAY_AFTER_OVERLAYS_MS = 1_000L
        private const val RETURNING_USER_NO_POPUP_RECHECK_MS = 750L
    }
}
