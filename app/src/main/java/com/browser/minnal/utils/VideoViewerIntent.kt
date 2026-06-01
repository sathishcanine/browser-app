package com.browser.minnal.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.browser.minnal.BuildConfig
import java.io.File

/**
 * Opens local video files, preferring Zen Video Player when installed, otherwise delegating to
 * the system default handler (same as a plain [Intent.ACTION_VIEW]).
 */
object VideoViewerIntent {

    const val ZEN_PLAYER_PACKAGE = "com.player.zen_video_player"

    fun isVideoMime(mimeType: String?): Boolean =
        mimeType?.startsWith("video/", ignoreCase = true) == true

    fun resolveMimeType(context: Context, uri: Uri, mimeType: String?): String {
        mimeType?.takeIf { it.isNotBlank() }?.let { return it }
        context.contentResolver.getType(uri)?.takeIf { it.isNotBlank() }?.let { return it }
        val extension = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotEmpty() }
            ?: return "*/*"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    }

    fun isVideo(context: Context, uri: Uri, mimeType: String?): Boolean =
        isVideoMime(resolveMimeType(context, uri, mimeType))

    /**
     * @return true if an activity was started successfully.
     */
    fun launch(context: Context, uri: Uri, mimeType: String?): Boolean {
        val resolvedMime = resolveMimeType(context, uri, mimeType)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, resolvedMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!isVideoMime(resolvedMime)) {
            return launchSystem(context, intent)
        }
        val zenIntent = Intent(intent).setPackage(ZEN_PLAYER_PACKAGE)
        if (canHandle(context, zenIntent)) {
            return runCatching {
                context.startActivity(zenIntent)
                true
            }.getOrDefault(false)
        }
        return launchSystem(context, intent)
    }

    fun buildViewIntent(context: Context, uri: Uri, mimeType: String?): Intent {
        val resolvedMime = resolveMimeType(context, uri, mimeType)
        val base = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, resolvedMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!isVideoMime(resolvedMime)) {
            return base
        }
        val zenIntent = Intent(base).setPackage(ZEN_PLAYER_PACKAGE)
        return if (canHandle(context, zenIntent)) zenIntent else base
    }

    fun fileProviderUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )

    private fun canHandle(context: Context, intent: Intent): Boolean =
        context.packageManager.resolveActivity(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        ) != null

    private fun launchSystem(context: Context, intent: Intent): Boolean {
        intent.component = null
        intent.setPackage(null)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
