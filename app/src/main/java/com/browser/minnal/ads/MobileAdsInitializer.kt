package com.browser.minnal.ads

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.ViewTreeObserver
import androidx.fragment.app.FragmentActivity
import com.browser.minnal.log.Logger
import com.google.android.gms.ads.MobileAds
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ensures [MobileAds.initialize] has finished in this process before any ad format loads.
 *
 * Incognito runs in `:incognito`, a separate process from normal browsing. Its SDK bootstrap is
 * deferred until [startForIncognitoSession] so WebView suffixing and the host activity are ready.
 */
@Singleton
class MobileAdsInitializer @Inject constructor(
    private val application: Application,
    private val logger: Logger,
) {

    @Volatile
    private var initialized = false

    private var started = false
    private var incognitoSession = false
    private val pendingActions = mutableListOf<() -> Unit>()
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        synchronized(lock) {
            if (started) {
                return
            }
            started = true
        }
        beginInitialize()
    }

    /**
     * Bootstrap Mobile Ads after [IncognitoBrowserActivity] is up (post–WebView suffix, with a host window).
     */
    fun startForIncognitoSession(activity: Activity) {
        synchronized(lock) {
            if (started) {
                return
            }
            incognitoSession = true
            started = true
        }
        val bootstrap = Runnable { beginInitialize() }
        activity.window?.decorView?.post(bootstrap) ?: mainHandler.post(bootstrap)
    }

    fun runWhenReady(action: () -> Unit) {
        synchronized(lock) {
            if (initialized) {
                mainHandler.post(action)
            } else {
                pendingActions.add(action)
                if (!started) {
                    logger.log(TAG, "MobileAds runWhenReady queued before start()")
                }
            }
        }
    }

    /**
     * Runs [action] on the main thread once the SDK is ready and [activity] has window focus.
     */
    fun runWhenReadyWithWindowFocus(
        activity: FragmentActivity,
        action: () -> Unit,
        onUnavailable: () -> Unit = {},
    ) {
        runWhenReady {
            runOnUiThreadWithWindowFocus(activity, action, onUnavailable)
        }
    }

    private fun beginInitialize() {
        val timeoutMs = if (incognitoSession) {
            INCOGNITO_INIT_TIMEOUT_MS
        } else {
            INIT_TIMEOUT_MS
        }
        // Some devices never invoke the init callback in a secondary process; unblock loads anyway.
        mainHandler.postDelayed({
            synchronized(lock) {
                if (!initialized) {
                    logger.log(TAG, "MobileAds init callback timed out; unblocking ad loads")
                    dispatchReady()
                }
            }
        }, timeoutMs)
        runCatching {
            MobileAds.initialize(application) { status ->
                logger.log(TAG, "MobileAds initialized (${status.adapterStatusMap.size} adapters)")
                dispatchReady()
            }
        }.onFailure {
            logger.log(TAG, "MobileAds.initialize failed", it)
            dispatchReady()
        }
    }

    private fun dispatchReady() {
        val actions: List<() -> Unit>
        synchronized(lock) {
            if (initialized) {
                return
            }
            initialized = true
            actions = pendingActions.toList()
            pendingActions.clear()
        }
        actions.forEach { action ->
            mainHandler.post {
                runCatching { action() }.onFailure {
                    logger.log(TAG, "MobileAds ready callback failed", it)
                }
            }
        }
    }

    private fun runOnUiThreadWithWindowFocus(
        activity: FragmentActivity,
        action: () -> Unit,
        onUnavailable: () -> Unit,
    ) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) {
                onUnavailable()
                return@runOnUiThread
            }
            val decor = activity.window?.decorView
            if (decor == null) {
                onUnavailable()
                return@runOnUiThread
            }
            val dispatched = AtomicBoolean(false)
            val runAction = Runnable {
                if (!dispatched.compareAndSet(false, true)) {
                    return@Runnable
                }
                if (activity.isFinishing || activity.isDestroyed) {
                    onUnavailable()
                } else {
                    action()
                }
            }
            if (activity.hasWindowFocus()) {
                decor.post(runAction)
                return@runOnUiThread
            }
            val observer = decor.viewTreeObserver
            val listener = object : ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    if (!hasFocus) {
                        return
                    }
                    observer.removeOnWindowFocusChangeListener(this)
                    decor.post(runAction)
                }
            }
            observer.addOnWindowFocusChangeListener(listener)
            mainHandler.postDelayed({
                observer.removeOnWindowFocusChangeListener(listener)
                decor.post(runAction)
            }, WINDOW_FOCUS_WAIT_MS)
        }
    }

    companion object {
        private const val TAG = "MobileAdsInitializer"
        private const val INIT_TIMEOUT_MS = 4_000L
        private const val INCOGNITO_INIT_TIMEOUT_MS = 10_000L
        private const val WINDOW_FOCUS_WAIT_MS = 2_000L
    }
}
