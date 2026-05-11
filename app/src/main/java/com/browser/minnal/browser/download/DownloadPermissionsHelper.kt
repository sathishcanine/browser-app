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
        val permissions = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // API 28 (Pie) and below need WRITE_EXTERNAL_STORAGE to land bytes in
            // Environment.DIRECTORY_DOWNLOADS. Newer Android writes via MediaStore.
            listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        } else {
            emptyList()
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
                .request { allGranted, _, _ ->
                    if (allGranted) {
                        onPermissionsResolved()
                    } else {
                        logger.log(TAG, "Download permission denied")
                    }
                }
        }
    }

    companion object {
        private const val TAG = "DownloadPermissionsHelper"
    }
}
