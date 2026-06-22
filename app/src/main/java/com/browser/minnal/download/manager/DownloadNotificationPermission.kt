package com.browser.minnal.download.manager

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.permissionx.guolindev.PermissionX

/**
 * Runtime notification permission (API 33+) and system notification enablement checks.
 */
object DownloadNotificationPermission {

    fun areEnabled(activity: Activity): Boolean =
        NotificationManagerCompat.from(activity).areNotificationsEnabled()

    fun hasRuntimePermission(activity: Activity): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun canShowDownloadNotifications(activity: Activity): Boolean =
        areEnabled(activity) && hasRuntimePermission(activity)

    fun requestRuntimePermissionIfNeeded(
        activity: FragmentActivity,
        onFinished: (granted: Boolean) -> Unit = {},
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onFinished(areEnabled(activity))
            return
        }
        if (hasRuntimePermission(activity)) {
            onFinished(areEnabled(activity))
            return
        }
        PermissionX.init(activity)
            .permissions(Manifest.permission.POST_NOTIFICATIONS)
            .request { _, grantedList, _ ->
                onFinished(grantedList.contains(Manifest.permission.POST_NOTIFICATIONS))
            }
    }

    fun openAppNotificationSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        }
        runCatching { activity.startActivity(intent) }
            .onFailure {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", activity.packageName, null)
                }
                runCatching { activity.startActivity(fallback) }
            }
    }
}
