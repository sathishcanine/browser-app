package com.browser.minnal.utils

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Detects whether this app is the default browser and opens system UI to change it.
 */
object DefaultBrowserHelper {

    fun isAppDefaultBrowser(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java) ?: return legacyIsDefaultBrowser(context)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                return roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)
            }
        }
        return legacyIsDefaultBrowser(context)
    }

    @Suppress("DEPRECATION")
    private fun legacyIsDefaultBrowser(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com/"))
        val resolved = context.packageManager.resolveActivity(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        val pkg = resolved?.activityInfo?.packageName ?: return false
        return pkg == context.packageName
    }

    /**
     * Intent for the system flow to set the default browser (role request on Q+, else default-apps settings).
     */
    fun createDefaultBrowserSettingsIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                return roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
            }
        }
        return Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    }

    /**
     * Starts the best-available system screen so the user can set this app as the default browser.
     *
     * @return true if an activity was started, false otherwise.
     */
    fun launchDefaultBrowserFlow(activity: Activity): Boolean {
        val intent = createDefaultBrowserSettingsIntent(activity)
        return try {
            activity.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", activity.packageName, null)
                    }
                )
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
    }
}
