package com.browser.minnal.firebase

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

object PlayStoreIntents {

    fun open(context: Context, target: String) {
        val packageName = resolvePackageName(target)
        val marketUri = Uri.parse("market://details?id=$packageName")
        val webUri = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, marketUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, webUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * Accepts a package name or a Play Store / market URL (including when a full URL was
     * mistakenly passed as the [Uri.getQueryParameter] `id` value).
     */
    internal fun resolvePackageName(target: String): String {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) {
            return trimmed
        }
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
        val id = uri?.getQueryParameter("id")?.trim()?.takeIf { it.isNotEmpty() }
        if (id != null) {
            return resolvePackageName(id)
        }
        return trimmed
    }
}
