/*
 * Copyright 2014 A.C.R. Development
 */
package com.browser.minnal.download;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.webkit.URLUtil;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.browser.minnal.BuildConfig;
import com.browser.minnal.R;
import com.browser.minnal.DefaultBrowserActivity;
import com.browser.minnal.dialog.BrowserDialog;
import com.browser.minnal.download.manager.MinnalDownloadManager;
import com.browser.minnal.extensions.ActivityExtensions;
import com.browser.minnal.log.Logger;
import com.browser.minnal.preference.UserPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

/**
 * Entry point for download requests originating from the WebView's download listener,
 * the long-press "Save…" dialog, etc.
 *
 * The actual byte transfer is owned by {@link MinnalDownloadManager} (in-built downloader); this
 * class is responsible for:
 *   - deciding whether a non-attachment URL should be handed off to a streaming app first,
 *   - performing storage / state preconditions,
 *   - and enqueueing into the manager.
 */
@Singleton
public class DownloadHandler {

    private static final String TAG = "DownloadHandler";

    private final MinnalDownloadManager minnalDownloadManager;
    private final Logger logger;

    @Inject
    public DownloadHandler(MinnalDownloadManager minnalDownloadManager, Logger logger) {
        this.minnalDownloadManager = minnalDownloadManager;
        this.logger = logger;
    }

    /**
     * Notify the host application a download should be done, or that the data
     * should be streamed if a streaming viewer is available.
     *
     * @param context            The context in which the download was requested.
     * @param url                The full url to the content that should be downloaded
     * @param userAgent          User agent of the downloading application.
     * @param contentDisposition Content-disposition http header, if present.
     * @param mimeType           The mimeType of the content reported by the server
     * @param contentSize        The size of the content (best-effort, human readable)
     */
    public void onDownloadStart(@NonNull Activity context,
                                @NonNull UserPreferences manager,
                                @NonNull String url, String userAgent,
                                @Nullable String contentDisposition,
                                @Nullable String mimeType,
                                @NonNull String contentSize) {

        logger.log(TAG, "DOWNLOAD: Trying to download from URL: " + url);
        logger.log(TAG, "DOWNLOAD: Content disposition: " + contentDisposition);
        logger.log(TAG, "DOWNLOAD: MimeType: " + mimeType);
        logger.log(TAG, "DOWNLOAD: User agent: " + userAgent);

        // Most users want a download (we have a real in-built manager for it) and not a chooser
        // popup that surfaces every video player on the device. The "Open in external app" path
        // is opt-in via [UserPreferences.preferExternalAppForDownloadableLinks]; explicit
        // attachments always download in-app regardless.
        boolean isExplicitAttachment = contentDisposition != null
            && contentDisposition.regionMatches(true, 0, "attachment", 0, 10);
        if (!isExplicitAttachment && manager.getPreferExternalAppForDownloadableLinks()) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(url), mimeType);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setComponent(null);
            intent.setSelector(null);
            ResolveInfo info = context.getPackageManager().resolveActivity(intent,
                PackageManager.MATCH_DEFAULT_ONLY);
            if (info != null) {
                // Only hand off to an external app when the resolved handler is NOT us,
                // otherwise we'd loop forever opening our own browser with the same URL.
                if (!BuildConfig.APPLICATION_ID.equals(info.activityInfo.packageName)
                    && !DefaultBrowserActivity.class.getName().equals(info.activityInfo.name)) {
                    try {
                        context.startActivity(intent);
                        return;
                    } catch (ActivityNotFoundException ignored) {
                        // Fall through to download.
                    }
                }
            }
        }
        enqueueWithMinnalDownloader(context, url, userAgent, contentDisposition, mimeType, contentSize);
    }

    /**
     * Perform storage preconditions and hand the download off to {@link MinnalDownloadManager}.
     *
     * On API 29+ the manager writes to {@link android.provider.MediaStore} which works without
     * external storage being mounted; on older devices it writes to
     * {@link Environment#DIRECTORY_DOWNLOADS}, which requires the SD card to be mounted.
     */
    private void enqueueWithMinnalDownloader(@NonNull Activity context,
                                             @NonNull String url,
                                             @Nullable String userAgent,
                                             @Nullable String contentDisposition,
                                             @Nullable String mimeType,
                                             @NonNull String contentSize) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            String state = Environment.getExternalStorageState();
            if (!Environment.MEDIA_MOUNTED.equals(state)) {
                int title;
                String msg;
                if (Environment.MEDIA_SHARED.equals(state)) {
                    msg = context.getString(R.string.download_sdcard_busy_dlg_msg);
                    title = R.string.download_sdcard_busy_dlg_title;
                } else {
                    msg = context.getString(R.string.download_no_sdcard_dlg_msg);
                    title = R.string.download_no_sdcard_dlg_title;
                }
                Dialog dialog = new AlertDialog.Builder(context).setTitle(title)
                    .setIcon(android.R.drawable.ic_dialog_alert).setMessage(msg)
                    .setPositiveButton(R.string.action_ok, null).show();
                BrowserDialog.setDialogSize(context, dialog);
                return;
            }
        }

        final long parsedSize = parseLength(contentSize);
        try {
            minnalDownloadManager.enqueue(
                url,
                userAgent,
                contentDisposition,
                mimeType,
                parsedSize
            );
        } catch (IllegalArgumentException e) {
            logger.log(TAG, "Bad URL passed to download manager: " + url, e);
            ActivityExtensions.snackbar(context, R.string.cannot_download);
            return;
        }

        final String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
        ActivityExtensions.snackbar(
            context,
            context.getString(R.string.download_pending) + ' ' + filename
        );
    }

    /**
     * Pulls a numeric byte count out of the human-readable {@code contentSize} string the
     * permission helper hands us. Returns -1 when it can't be inferred (the engine will figure
     * out the real size from the HTTP response anyway).
     */
    private static long parseLength(@Nullable String contentSize) {
        if (contentSize == null) return -1L;
        // We get strings like "12.3 MB" or "Unknown size" from the dialog code; not worth
        // round-tripping that into bytes here. The engine will overwrite with the true value.
        return -1L;
    }
}
