package com.browser.minnal.browser.tab

import com.browser.minnal.BuildConfig
import com.browser.minnal.R
import com.browser.minnal.browser.di.IncognitoMode
import com.browser.minnal.constant.FILE
import com.browser.minnal.extensions.snackbar
import com.browser.minnal.log.Logger
import com.browser.minnal.preference.UserPreferences
import com.browser.minnal.utils.IntentUtils
import com.browser.minnal.utils.Utils
import com.browser.minnal.utils.isSpecialUrl
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.MailTo
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.net.URISyntaxException
import javax.inject.Inject

/**
 * Handle URLs loaded by the [WebView] and determine if they should be loaded by the browser or
 * another app.
 */
class UrlHandler @Inject constructor(
    private val activity: Activity,
    private val logger: Logger,
    private val intentUtils: IntentUtils,
    private val userPreferences: UserPreferences,
    private val popupTabGate: PopupTabGate,
    @IncognitoMode private val incognitoMode: Boolean
) {

    /**
     * Return true if the [url] should be loaded by another app or in another way, false if the
     * browser can let the [view] continue loading as it wants.
     */
    fun shouldOverrideLoading(
        view: WebView,
        url: String,
        headers: Map<String, String>,
        isForMainFrame: Boolean = true,
        hasGesture: Boolean = false,
        currentPageUrl: String? = null,
        requestNewTab: ((String) -> Unit)? = null,
    ): Boolean {
        if (incognitoMode) {
            // If we are in incognito, immediately load, we don't want the url to leave the app
            return continueLoadingUrl(view, url, headers)
        }
        if (URLUtil.isAboutUrl(url)) {
            // If this is an about page, immediately load, we don't need to leave the app
            return continueLoadingUrl(view, url, headers)
        }

        if (tryLaunchExternalScheme(view, url)) {
            return true
        }

        // Direct file-download URLs (e.g. https://.../video.mp4) must stay in the WebView so our
        // download listener fires and our in-built download manager grabs the bytes. Without
        // this guard, IntentUtils.startActivityForUrl(...) would resolve the URL through
        // Android's intent system, where apps like Chrome / Samsung Internet / video players
        // register pathPattern intent filters (host="*" + pathPattern=".*\.mp4") that count as
        // "specialized handlers" and steal the navigation, popping the system app chooser /
        // launching Chrome. Users with a real download manager (we have one) almost never want
        // this. Power users can re-enable hand-off via the same preference that controls the
        // post-download-listener hand-off.
        if (!userPreferences.preferExternalAppForDownloadableLinks && looksLikeDirectDownload(url)) {
            return continueLoadingUrl(view, url, headers)
        }

        // Keep http(s) navigations inside Minnal — do not hand off to Chrome via intents.
        if (URLUtil.isHttpUrl(url) || URLUtil.isHttpsUrl(url)) {
            if (shouldOpenInBackgroundTab(url, isForMainFrame, hasGesture, currentPageUrl)) {
                return openInBackgroundTab(view, url, headers, requestNewTab)
            }
            return continueLoadingUrl(view, url, headers)
        }

        if (url.startsWith("intent:", ignoreCase = true)) {
            return handleIntentUrl(view, url, headers, requestNewTab)
        }

        val browserDeepLink = extractHttpUrlFromBrowserDeepLink(url)
        if (browserDeepLink != null) {
            if (tryLaunchExternalScheme(view, browserDeepLink)) {
                return true
            }
            return openInBackgroundTab(view, browserDeepLink, headers, requestNewTab)
        }

        return if (isMailOrIntent(url, view) || intentUtils.startActivityForUrl(view, url)) {
            // If it was a mailto: link, or could be launched elsewhere, do that
            true
        } else {
            // If none of the special conditions was met, continue with loading the url
            continueLoadingUrl(view, url, headers)
        }
    }

    /**
     * Ad / redirect networks often use `intent://…#Intent;package=com.android.chrome;…` to force
     * Chrome. Prefer [browser_fallback_url] or an http(s) [Intent.data] in this WebView instead.
     */
    private fun handleIntentUrl(
        view: WebView,
        url: String,
        headers: Map<String, String>,
        requestNewTab: ((String) -> Unit)?,
    ): Boolean {
        val intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (e: URISyntaxException) {
            logger.log(TAG, "Bad intent URL: $url", e)
            view.stopLoading()
            return true
        }

        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null

        val inAppUrl = intent.httpUrlForInAppLoad()
            ?: extractBrowserFallbackFromIntentUri(url)
            ?: extractHttpUrlFromIntentSchemeUrl(url)
        if (inAppUrl != null) {
            if (tryLaunchExternalScheme(view, inAppUrl)) {
                return true
            }
            logger.log(TAG, "Loading intent target in background tab: $inAppUrl")
            return openInBackgroundTab(view, inAppUrl, headers, requestNewTab)
        }

        val targetPackage = intent.`package`
        if (isExternalBrowserPackage(targetPackage)) {
            logger.log(TAG, "Blocked hand-off to external browser package: $targetPackage")
            view.stopLoading()
            return true
        }

        if (targetPackage != null && targetPackage != BuildConfig.APPLICATION_ID) {
            try {
                activity.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                logger.log(TAG, "Intent target not installed: $targetPackage", e)
            }
        } else if (targetPackage == null) {
            val resolved = activity.packageManager.resolveActivity(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY,
            )
            val resolvedPackage = resolved?.activityInfo?.packageName
            if (resolved != null && !isExternalBrowserPackage(resolvedPackage)) {
                try {
                    activity.startActivity(intent)
                    return true
                } catch (e: ActivityNotFoundException) {
                    logger.log(TAG, "Resolved intent could not be started", e)
                }
            } else if (isExternalBrowserPackage(resolvedPackage)) {
                logger.log(TAG, "Blocked resolved external browser: $resolvedPackage")
            }
        }

        view.stopLoading()
        return true
    }

    /**
     * Catches `mailto:`, Play Store, and similar navigations when `window.open` loads them in a
     * new WebView without calling [shouldOverrideUrlLoading] first.
     */
    fun interceptDocumentNavigation(view: WebView, url: String): Boolean =
        tryLaunchExternalScheme(view, url)

    /**
     * Recover when the WebView tried to load `intent://` and failed with ERR_UNKNOWN_URL_SCHEME.
     */
    fun tryRecoverFromIntentSchemeError(
        view: WebView,
        url: String,
        headers: Map<String, String>,
        requestNewTab: ((String) -> Unit)?,
    ): Boolean {
        if (!url.startsWith("intent:", ignoreCase = true)) {
            return false
        }
        return handleIntentUrl(view, url, headers, requestNewTab)
    }

    /**
     * Automatic ad / popup navigations open in a new background tab so the page the user was
     * reading is not replaced.
     */
    private fun openInBackgroundTab(
        view: WebView,
        url: String,
        headers: Map<String, String>,
        requestNewTab: ((String) -> Unit)?,
    ): Boolean {
        view.stopLoading()
        if (!url.isHttpOrHttps()) {
            tryLaunchExternalScheme(view, url)
            return true
        }
        if (!popupTabGate.shouldAllowPopupTab(url)) {
            logger.log(TAG, "Suppressed extra popup tab, loading in current tab: $url")
            return continueLoadingUrl(view, url, headers)
        }
        if (requestNewTab != null) {
            popupTabGate.recordPopupTabOpened(url)
            requestNewTab(url)
            return true
        }
        logger.log(TAG, "No new-tab listener; loading in current tab: $url")
        return continueLoadingUrl(view, url, headers)
    }

    private fun shouldOpenInBackgroundTab(
        url: String,
        isForMainFrame: Boolean,
        hasGesture: Boolean,
        currentPageUrl: String?,
    ): Boolean {
        if (!userPreferences.popupsEnabled) {
            return false
        }
        if (!isForMainFrame) {
            return false
        }
        // Link taps often report hasGesture=false; rely on WebView touch tracking too.
        if (hasGesture || popupTabGate.hadRecentUserGesture()) {
            return false
        }
        if (isPlayStoreHttpUrl(url)) {
            return false
        }
        if (isLikelyAdRedirectUrl(url)) {
            return true
        }
        val currentHost = currentPageUrl?.toUri()?.host?.lowercase()
        val newHost = url.toUri().host?.lowercase()
        return currentHost != null && newHost != null && currentHost != newHost
    }

    private fun isLikelyAdRedirectUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("pyppo.com") ||
            lower.contains("doubleclick.net") ||
            lower.contains("googlesyndication.com") ||
            lower.contains("taboola.com") ||
            lower.contains("outbrain.com") ||
            lower.contains("/adclick") ||
            lower.contains("/ads?") ||
            lower.contains("click.") && lower.contains("redirect")
    }

    private fun Intent.httpUrlForInAppLoad(): String? {
        getStringExtra("browser_fallback_url")?.let { if (it.isHttpOrHttps()) return it }
        dataString?.let { if (it.isHttpOrHttps()) return it }
        data?.toString()?.let { if (it.isHttpOrHttps()) return it }
        return null
    }

    /**
     * Build https://host/path from `intent://host/path#Intent;scheme=https;…` when [Intent.parseUri]
     * does not populate [Intent.data] (common on ad redirect URLs).
     */
    private fun extractHttpUrlFromIntentSchemeUrl(url: String): String? {
        if (!url.startsWith("intent:", ignoreCase = true)) {
            return null
        }
        val scheme = INTENT_SCHEME_PARAM.find(url)?.groupValues?.get(1)?.lowercase() ?: "https"
        if (scheme != "http" && scheme != "https") {
            return null
        }
        val hostPart = url.substringAfter("intent:", "")
            .substringBefore('#')
            .trim()
        if (hostPart.isEmpty()) {
            return null
        }
        val httpUrl = when {
            hostPart.startsWith("//") -> "$scheme:$hostPart"
            else -> "$scheme://$hostPart"
        }
        return httpUrl.takeIf { it.isHttpOrHttps() }
    }

    private fun extractBrowserFallbackFromIntentUri(url: String): String? {
        val encoded = INTENT_FALLBACK_PARAM.find(url)?.groupValues?.get(1) ?: return null
        return runCatching { Uri.decode(encoded) }.getOrNull()?.takeIf { it.isHttpOrHttps() }
    }

    private fun String.isHttpOrHttps(): Boolean =
        URLUtil.isHttpUrl(this) || URLUtil.isHttpsUrl(this)

    private fun isExternalBrowserPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) {
            return false
        }
        if (packageName == BuildConfig.APPLICATION_ID) {
            return false
        }
        val lower = packageName.lowercase()
        return lower.contains("chrome") ||
            lower.contains("firefox") ||
            lower.contains("opera") ||
            lower.contains("brave") ||
            lower.contains("edge") ||
            lower.contains("samsung") ||
            lower.contains("sbrowser") ||
            lower.contains("vivaldi") ||
            lower.contains("duckduckgo") ||
            lower == "com.android.browser" ||
            (lower.contains("browser") && !lower.contains("minnal"))
    }

    /**
     * Some networks use `googlechrome://navigate?url=https%3A%2F%2F…` style deep links.
     */
    private fun extractHttpUrlFromBrowserDeepLink(url: String): String? {
        val lower = url.lowercase()
        if (!lower.startsWith("googlechrome://") &&
            !lower.startsWith("firefox://") &&
            !lower.startsWith("microsoft-edge://")
        ) {
            return null
        }
        val target = runCatching { url.toUri().getQueryParameter("url") }.getOrNull()
        return target?.takeIf { it.isHttpOrHttps() }
    }

    /**
     * Heuristic: does the URL's path end in an extension we'd consider a direct file download?
     * Conservative on purpose — we deliberately exclude images / html / css / js because the
     * WebView wants to render those inline.
     */
    private fun looksLikeDirectDownload(url: String): Boolean {
        if (!URLUtil.isNetworkUrl(url)) return false
        val path = try { Uri.parse(url).path } catch (_: Throwable) { null } ?: return false
        val lastDot = path.lastIndexOf('.')
        if (lastDot < 0 || lastDot == path.length - 1) return false
        val ext = path.substring(lastDot + 1).lowercase()
        return ext in DOWNLOAD_FILE_EXTENSIONS
    }

    private fun tryLaunchExternalScheme(view: WebView, url: String): Boolean {
        when {
            url.startsWith("mailto:", ignoreCase = true) -> return launchMailTo(url, view)
            url.startsWith("tel:", ignoreCase = true) -> {
                return startExternalActivity(view, Intent(Intent.ACTION_DIAL, url.toUri()))
            }
            url.startsWith("sms:", ignoreCase = true) -> {
                return startExternalActivity(view, Intent(Intent.ACTION_SENDTO, url.toUri()))
            }
            url.startsWith("market:", ignoreCase = true) -> {
                return startExternalActivity(view, Intent(Intent.ACTION_VIEW, url.toUri()))
            }
            isPlayStoreHttpUrl(url) -> return tryLaunchPlayStore(view, url)
            else -> return false
        }
    }

    private fun launchMailTo(url: String, view: WebView): Boolean {
        val mailTo = MailTo.parse(url)
        val i = Utils.newEmailIntent(mailTo.to, mailTo.subject, mailTo.body, mailTo.cc)
        return startExternalActivity(view, i)
    }

    private fun tryLaunchPlayStore(view: WebView, url: String): Boolean {
        val uri = url.toUri()
        val appId = uri.getQueryParameter("id")
        if (!appId.isNullOrBlank()) {
            val marketUri = Uri.parse("market://details?id=$appId")
            if (startExternalActivity(view, Intent(Intent.ACTION_VIEW, marketUri))) {
                return true
            }
        }
        val playStoreIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(PLAY_STORE_PACKAGE)
        }
        if (startExternalActivity(view, playStoreIntent)) {
            return true
        }
        return startExternalActivity(view, Intent(Intent.ACTION_VIEW, uri))
    }

    private fun startExternalActivity(view: WebView, intent: Intent): Boolean {
        return try {
            activity.startActivity(intent)
            view.stopLoading()
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun isPlayStoreHttpUrl(url: String): Boolean {
        if (!url.isHttpOrHttps()) {
            return false
        }
        val uri = url.toUri()
        val host = uri.host?.lowercase() ?: return false
        if (host != "play.google.com" && !host.endsWith(".play.google.com")) {
            return false
        }
        val path = uri.path?.lowercase().orEmpty()
        return path.contains("/store")
    }

    private fun continueLoadingUrl(
        webView: WebView,
        url: String,
        headers: Map<String, String>
    ): Boolean {
        if (!URLUtil.isNetworkUrl(url)
            && !URLUtil.isFileUrl(url)
            && !URLUtil.isAboutUrl(url)
            && !URLUtil.isDataUrl(url)
            && !URLUtil.isJavaScriptUrl(url)
        ) {
            webView.stopLoading()
            return true
        }
        return when {
            headers.isEmpty() -> false
            else -> {
                webView.loadUrl(url, headers)
                true
            }
        }
    }

    private fun isMailOrIntent(url: String, view: WebView): Boolean {
        if (url.startsWith("mailto:", ignoreCase = true)) {
            return launchMailTo(url, view).also { if (it) view.reload() }
        } else if (URLUtil.isFileUrl(url) && !url.isSpecialUrl()) {
            val file = File(url.replace(FILE, ""))

            if (file.exists()) {
                val newMimeType = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(Utils.guessFileExtension(file.toString()))

                val intent = Intent(Intent.ACTION_VIEW)
                intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                val contentUri = FileProvider.getUriForFile(
                    activity,
                    BuildConfig.APPLICATION_ID + ".fileprovider",
                    file
                )
                intent.setDataAndType(contentUri, newMimeType)

                try {
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    println("LightningWebClient: cannot open downloaded file")
                }

            } else {
                activity.snackbar(R.string.message_open_download_fail)
            }
            return true
        }
        return false
    }

    companion object {
        private const val TAG = "UrlHandler"
        private const val PLAY_STORE_PACKAGE = "com.android.vending"

        private val INTENT_SCHEME_PARAM = Regex("(?i)scheme=([^;\\s]+)")
        private val INTENT_FALLBACK_PARAM = Regex("(?i)S\\.browser_fallback_url=([^;]+);")

        /**
         * URL path extensions for which Minnal should always intercept the navigation and
         * download the bytes itself, instead of consulting Android's intent system. Kept
         * intentionally narrow: only file types that have no business being "viewed" inline
         * by another app (no images, no html, no js).
         */
        private val DOWNLOAD_FILE_EXTENSIONS: Set<String> = setOf(
            // video
            "mp4", "mkv", "mov", "avi", "webm", "flv", "m4v", "ts", "3gp", "wmv", "mpg", "mpeg",
            // audio
            "mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma",
            // documents
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "odt", "odp", "ods", "epub", "mobi",
            // archives
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz",
            // installers / disk images
            "apk", "xapk", "aab", "ipa", "exe", "msi", "dmg", "iso", "deb", "rpm",
            // misc datasets / blobs
            "csv", "json", "xml", "torrent", "bin"
        )
    }
}
