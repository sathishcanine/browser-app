package com.browser.minnal.browser.download

import com.browser.minnal.R
import com.browser.minnal.ads.RewardedDownloadAdHelper
import com.browser.minnal.dialog.BrowserDialog.setDialogSize
import com.browser.minnal.download.DownloadHandler
import com.browser.minnal.extensions.snackbar
import com.browser.minnal.log.Logger
import com.browser.minnal.preference.UserPreferences
import com.browser.minnal.utils.requiresRewardedAdGateForDownload
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
    private val rewardedDownloadAdHelper: RewardedDownloadAdHelper,
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
            if (url.requiresRewardedAdGateForDownload()) {
                rewardedDownloadAdHelper.preload(activity)
                showRewardedDownloadGate(
                    activity = activity,
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    contentLength = contentLength,
                )
            } else {
                showStandardDownloadConfirmation(
                    activity = activity,
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    contentLength = contentLength,
                )
            }
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

    private fun showRewardedDownloadGate(
        activity: FragmentActivity,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        val fileName = resolveDisplayFileName(url, contentDisposition, mimeType)
        val downloadParams = DownloadParams(
            url = url,
            userAgent = userAgent,
            contentDisposition = contentDisposition,
            mimeType = mimeType,
            contentLength = contentLength,
        )
        val dialogClickListener = DialogInterface.OnClickListener { dialog, which: Int ->
            if (which != DialogInterface.BUTTON_POSITIVE) {
                return@OnClickListener
            }
            dialog.dismiss()
            // Wait for the gate dialog to finish dismissing before showing the loading overlay.
            activity.window?.decorView?.post {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    showRewardedAdThenDownload(activity, fileName, downloadParams)
                }
            }
        }
        val dialog: Dialog = AlertDialog.Builder(activity)
            .setTitle(fileName)
            .setMessage(R.string.dialog_rewarded_download_message)
            .setPositiveButton(R.string.action_continue_for_downloading, dialogClickListener)
            .setCancelable(true)
            .show()
        setDialogSize(activity, dialog)
        logger.log(TAG, "Rewarded download gate: $fileName")
    }

    private fun showRewardedAdThenDownload(
        activity: FragmentActivity,
        fileName: String,
        params: DownloadParams,
    ) {
        var loadingDialog: Dialog? = null
        rewardedDownloadAdHelper.show(
            activity = activity,
            onLoadingChanged = { loading ->
                if (loading) {
                    if (loadingDialog?.isShowing != true) {
                        loadingDialog = AlertDialog.Builder(activity)
                            .setView(R.layout.dialog_loading_ad)
                            .setCancelable(false)
                            .show()
                        setDialogSize(activity, loadingDialog!!)
                    }
                } else {
                    loadingDialog?.dismiss()
                    loadingDialog = null
                }
            },
            onRewarded = {
                startDownload(activity, params)
            },
            onProceedWithoutAd = {
                logger.log(TAG, "Rewarded download ad unavailable; proceeding without ad: $fileName")
                activity.snackbar(R.string.message_rewarded_download_proceeding_without_ad)
                startDownload(activity, params)
            },
            onDismissedWithoutReward = {
                logger.log(TAG, "Rewarded download ad dismissed without reward: $fileName")
            },
        )
    }

    private data class DownloadParams(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?,
        val contentLength: Long,
    )

    private fun startDownload(
        activity: FragmentActivity,
        params: DownloadParams,
    ) {
        startDownload(
            activity = activity,
            url = params.url,
            userAgent = params.userAgent,
            contentDisposition = params.contentDisposition,
            mimeType = params.mimeType,
            contentLength = params.contentLength,
        )
    }

    private fun showStandardDownloadConfirmation(
        activity: FragmentActivity,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        val fileName = resolveDisplayFileName(url, contentDisposition, mimeType)
        val downloadSize: String = if (contentLength > 0) {
            Formatter.formatFileSize(activity, contentLength)
        } else {
            activity.getString(R.string.unknown_size)
        }
        val dialogClickListener = DialogInterface.OnClickListener { _, which: Int ->
            when (which) {
                DialogInterface.BUTTON_POSITIVE -> {
                    startDownload(
                        activity = activity,
                        url = url,
                        userAgent = userAgent,
                        contentDisposition = contentDisposition,
                        mimeType = mimeType,
                        contentLength = contentLength,
                    )
                }

                DialogInterface.BUTTON_NEGATIVE -> {
                }
            }
        }
        val dialog: Dialog = AlertDialog.Builder(activity)
            .setTitle(fileName)
            .setMessage(activity.getString(R.string.dialog_download, downloadSize))
            .setPositiveButton(R.string.action_download, dialogClickListener)
            .setNegativeButton(R.string.action_cancel, dialogClickListener)
            .show()
        setDialogSize(activity, dialog)
        logger.log(TAG, "Downloading: $fileName")
    }

    private fun startDownload(
        activity: FragmentActivity,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        val downloadSize: String = if (contentLength > 0) {
            Formatter.formatFileSize(activity, contentLength)
        } else {
            activity.getString(R.string.unknown_size)
        }
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

    private fun resolveDisplayFileName(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
    ): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(url)
        return extension.takeIf(String::isNotBlank)
            ?: if (MimeTypeMap.getSingleton().hasMimeType(mimeType)) {
                URLUtil.guessFileName(url, contentDisposition, mimeType)
            } else {
                url
            }
    }

    companion object {
        private const val TAG = "DownloadPermissionsHelper"
    }
}
