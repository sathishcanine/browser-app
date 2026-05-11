package com.browser.minnal.download.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.browser.minnal.BrowserApp

/**
 * Routes notification action button presses (currently only "Cancel") into
 * [MinnalDownloadManager]. Registered in `AndroidManifest.xml`.
 *
 * We intentionally do NOT touch any UI state directly here; we only delegate to the manager
 * so the cancellation goes through the same code path as a programmatic cancel.
 */
class DownloadActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val app = context.applicationContext as? BrowserApp ?: return
        val manager = app.applicationComponent.minnalDownloadManager()
        when (intent.action) {
            ACTION_CANCEL -> manager.cancel(url)
            ACTION_PAUSE -> manager.pause(url)
            ACTION_RESUME -> manager.resume(url)
        }
    }

    companion object {
        const val EXTRA_URL = "minnal.extra.url"
        const val ACTION_CANCEL = "com.browser.minnal.download.CANCEL"
        const val ACTION_PAUSE = "com.browser.minnal.download.PAUSE"
        const val ACTION_RESUME = "com.browser.minnal.download.RESUME"
    }
}
