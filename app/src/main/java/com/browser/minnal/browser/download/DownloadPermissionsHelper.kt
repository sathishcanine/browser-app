package com.browser.minnal.browser.download

import com.browser.minnal.R
import com.browser.minnal.dialog.BrowserDialog.setDialogSize
import com.browser.minnal.download.DownloadHandler
import com.browser.minnal.log.Logger
import com.browser.minnal.preference.UserPreferences
import android.Manifest
import android.app.Dialog
import android.content.DialogInterface
import android.os.Build
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.permissionx.guolindev.PermissionX
import javax.inject.Inject

/**
 * Wraps [DownloadHandler] for a better download API.
 *
 * On API <= 28 we still request the legacy storage permissions; on API 29+ they're no-ops
 * because the in-built downloader writes to MediaStore.
 */
class DownloadPermissionsHelper @Inject constructor(
    private val downloadHandler: DownloadHandler,
    private val userPreferences: UserPreferences,
    private val logger: Logger
) {

    /**
     * Download a file with the provided [url].
     */
    fun download(
        activity: FragmentActivity,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                // API 28 (Pie) and below need WRITE_EXTERNAL_STORAGE to land bytes in
                // Environment.DIRECTORY_DOWNLOADS. Newer Android writes via MediaStore.
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Without this, progress / foreground-service notifications are suppressed (API 33+).
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val onPermissionsResolved: () -> Unit = {
            val fileName = MimeTypeMap.getFileExtensionFromUrl(url)
                .takeIf(String::isNotBlank)
                ?: if (MimeTypeMap.getSingleton().hasMimeType(mimeType)) {
                    URLUtil.guessFileName(url, contentDisposition, mimeType)
                } else {
                    url
                }
            val downloadSize: String = if (contentLength > 0) {
                Formatter.formatFileSize(activity, contentLength)
            } else {
                activity.getString(R.string.unknown_size)
            }
            val dialogClickListener = DialogInterface.OnClickListener { _, which: Int ->
                when (which) {
                    DialogInterface.BUTTON_POSITIVE -> {
                        downloadHandler.onDownloadStart(
                            activity,
                            userPreferences,
                            url,
                            userAgent,
                            contentDisposition,
                            mimeType,
                            downloadSize
                        )
                    }

                    DialogInterface.BUTTON_NEGATIVE -> {
                    }
                }
            }
            val builder = AlertDialog.Builder(activity)
            val message: String = activity.getString(R.string.dialog_download, downloadSize)
            val dialog: Dialog = builder.setTitle(fileName)
                .setMessage(message)
                .setPositiveButton(
                    activity.resources.getString(R.string.action_download),
                    dialogClickListener
                )
                .setNegativeButton(
                    activity.resources.getString(R.string.action_cancel),
                    dialogClickListener
                ).show()
            setDialogSize(activity, dialog)
            logger.log(TAG, "Downloading: $fileName")
        }

        if (permissions.isEmpty()) {
            onPermissionsResolved()
        } else {
            PermissionX.init(activity)
                .permissions(permissions)
                .request { _, grantedList, _ ->
                    val legacyStorageRequired = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                    val storageOk = !legacyStorageRequired ||
                        grantedList.contains(Manifest.permission.READ_EXTERNAL_STORAGE) &&
                        grantedList.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    if (!storageOk) {
                        logger.log(TAG, "Download storage permission denied")
                        return@request
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !grantedList.contains(Manifest.permission.POST_NOTIFICATIONS)
                    ) {
                        logger.log(TAG, "POST_NOTIFICATIONS denied; download proceeds without shade UI")
                    }
                    onPermissionsResolved()
                }
        }
    }

    companion object {
        private const val TAG = "DownloadPermissionsHelper"
    }
}
