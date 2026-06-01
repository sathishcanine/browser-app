package com.browser.minnal.browser.tab

import com.browser.minnal.R
import com.browser.minnal.adblock.AdBlocker
import com.browser.minnal.adblock.allowlist.AllowListModel
import com.browser.minnal.databinding.DialogAuthRequestBinding
import com.browser.minnal.extensions.resizeAndShow
import com.browser.minnal.js.TextReflow
import com.browser.minnal.log.Logger
import com.browser.minnal.preference.UserPreferences
import com.browser.minnal.ssl.SslState
import com.browser.minnal.ssl.SslWarningPreferences
import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.webkit.WebResourceError
import android.view.LayoutInflater
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.reactivex.rxjava3.subjects.PublishSubject
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.abs

/**
 * A [WebViewClient] that supports the tab adaptation.
 */
class TabWebViewClient @AssistedInject constructor(
    private val application: Application,
    private val adBlocker: AdBlocker,
    private val allowListModel: AllowListModel,
    private val urlHandler: UrlHandler,
    @Assisted private val headers: Map<String, String>,
    private val userPreferences: UserPreferences,
    private val sslWarningPreferences: SslWarningPreferences,
    private val textReflow: TextReflow,
    private val logger: Logger,
    @Assisted("cache") private val cacheStoragePathHandler: InternalStoragePathHandler,
    @Assisted("files") private val filesStoragePathHandler: InternalStoragePathHandler,
) : WebViewClient() {

    private val cache by lazy {
        File(application.cacheDir, "favicon-cache")
    }

    private val files by lazy {
        File(application.filesDir, "generated-html")
    }

    /**
     * Set by [TabAdapter] so ad / intent redirects can open in a background tab.
     */
    var onRequestNewTab: ((String) -> Unit)? = null

    /**
     * When true, the first non-ad navigation in this popup tab is moved to the opener tab.
     */
    var promoteNonAdNavigationToOpener: Boolean = false

    /**
     * Invoked with the URL to load on the tab that opened this popup.
     */
    var onPromoteNavigationToOpener: ((String) -> Unit)? = null

    /**
     * Emits changes to the current URL.
     */
    val urlObservable: PublishSubject<String> = PublishSubject.create()

    /**
     * Emits changes to the current SSL state.
     */
    val sslStateObservable: PublishSubject<SslState> = PublishSubject.create()

    /**
     * Emits changes to the can go back state of the browser.
     */
    val goBackObservable: PublishSubject<Boolean> = PublishSubject.create()

    /**
     * Emits changes to the can go forward state of the browser.
     */
    val goForwardObservable: PublishSubject<Boolean> = PublishSubject.create()

    /**
     * Emit when the tab has finished rendering its content.
     */
    val finishedObservable = PublishSubject.create<Unit>()

    /**
     * The current SSL state of the page.
     */
    var sslState: SslState = SslState.None
        private set

    private var currentUrl: String = ""
    private var isReflowRunning: Boolean = false
    private var zoomScale: Float = 0.0F
    private var urlWithSslError: String? = null

    private fun shouldBlockRequest(pageUrl: String, requestUrl: String) =
        !allowListModel.isUrlAllowedAds(pageUrl) &&
            adBlocker.isAd(requestUrl)

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        if (maybePromotePopupNavigation(view, url)) {
            return
        }
        if (urlHandler.interceptDocumentNavigation(view, url)) {
            return
        }
        super.onPageStarted(view, url, favicon)
        (view as? PullRefreshWebView)?.resetScrollTopState()
        currentUrl = url
        urlObservable.onNext(url)
        if (urlWithSslError != url) {
            urlWithSslError = null
            sslState = if (URLUtil.isHttpsUrl(url)) {
                SslState.Valid
            } else {
                SslState.None
            }
        }
        sslStateObservable.onNext(sslState)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        (view as? PullRefreshWebView)?.installScrollTopBridge()
        (view.getTag(R.id.tag_pull_refresh_layout) as? PullToRefreshLayout)?.setRefreshing(false)
        urlObservable.onNext(url)
        goBackObservable.onNext(view.canGoBack())
        goForwardObservable.onNext(view.canGoForward())
        view.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                finishedObservable.onNext(Unit)
            }
        })
    }


    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
        if (view.isShown && userPreferences.textReflowEnabled) {
            if (isReflowRunning)
                return
            val changeInPercent = abs(100 - 100 / zoomScale * newScale)
            if (changeInPercent > 2.5f && !isReflowRunning) {
                isReflowRunning = view.postDelayed({
                    zoomScale = newScale
                    view.evaluateJavascript(textReflow.provideJs()) { isReflowRunning = false }
                }, 100)
            }

        }
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String,
        realm: String
    ) {
        val context = view.context
        AlertDialog.Builder(context).apply {
            val dialogView = DialogAuthRequestBinding.inflate(LayoutInflater.from(context))

            val realmLabel = dialogView.authRequestRealmTextview
            val name = dialogView.authRequestUsernameEdittext
            val password = dialogView.authRequestPasswordEdittext

            realmLabel.text = context.getString(R.string.label_realm, realm)

            setView(dialogView.root)
            setTitle(R.string.title_sign_in)
            setCancelable(true)
            setPositiveButton(R.string.title_sign_in) { _, _ ->
                val user = name.text.toString()
                val pass = password.text.toString()
                handler.proceed(user.trim(), pass.trim())
                logger.log(TAG, "Attempting HTTP Authentication")
            }
            setNegativeButton(R.string.action_cancel) { _, _ ->
                handler.cancel()
            }
        }.resizeAndShow()
    }

    override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) {
        val context = view.context
        AlertDialog.Builder(context).apply {
            setTitle(context.getString(R.string.title_form_resubmission))
            setMessage(context.getString(R.string.message_form_resubmission))
            setCancelable(true)
            setPositiveButton(context.getString(R.string.action_yes)) { _, _ ->
                resend.sendToTarget()
            }
            setNegativeButton(context.getString(R.string.action_no)) { _, _ ->
                dontResend.sendToTarget()
            }
        }.resizeAndShow()
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(webView: WebView, handler: SslErrorHandler, error: SslError) {
        urlWithSslError = webView.url

        sslState = SslState.Invalid(error)
        sslStateObservable.onNext(sslState)

        when (sslWarningPreferences.recallBehaviorForDomain(webView.url)) {
            SslWarningPreferences.Behavior.PROCEED -> handler.proceed()
            SslWarningPreferences.Behavior.CANCEL -> handler.cancel()
            null -> handler.proceed()
        }
    }

    private fun maybePromotePopupNavigation(view: WebView, url: String): Boolean {
        if (!promoteNonAdNavigationToOpener) {
            return false
        }
        if (!urlHandler.shouldPromotePopupNavigationToOpener(url)) {
            return false
        }
        promoteNonAdNavigationToOpener = false
        view.stopLoading()
        onPromoteNavigationToOpener?.invoke(url)
        return true
    }

    private fun delegateUrlLoading(view: WebView, url: String, isForMainFrame: Boolean, hasGesture: Boolean): Boolean {
        if (maybePromotePopupNavigation(view, url)) {
            return true
        }
        return urlHandler.shouldOverrideLoading(
            view = view,
            url = url,
            headers = headers,
            isForMainFrame = isForMainFrame,
            hasGesture = hasGesture,
            currentPageUrl = view.url,
            requestNewTab = onRequestNewTab,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        return delegateUrlLoading(view, url, isForMainFrame = true, hasGesture = false) ||
            super.shouldOverrideUrlLoading(view, url)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return delegateUrlLoading(
            view = view,
            url = request.url.toString(),
            isForMainFrame = request.isForMainFrame,
            hasGesture = request.hasGesture(),
        ) || super.shouldOverrideUrlLoading(view, request)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            request.isForMainFrame &&
            error.errorCode == WebViewClient.ERROR_UNSUPPORTED_SCHEME
        ) {
            val url = request.url.toString()
            if (urlHandler.tryRecoverFromIntentSchemeError(view, url, headers, onRequestNewTab)) {
                return
            }
        }
        super.onReceivedError(view, request, error)
    }

    @Deprecated("Deprecated in Java")
    override fun onReceivedError(
        view: WebView,
        errorCode: Int,
        description: String,
        failingUrl: String,
    ) {
        if (errorCode == WebViewClient.ERROR_UNSUPPORTED_SCHEME &&
            urlHandler.tryRecoverFromIntentSchemeError(view, failingUrl, headers, onRequestNewTab)
        ) {
            return
        }
        super.onReceivedError(view, errorCode, description, failingUrl)
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        if (shouldBlockRequest(currentUrl, request.url.toString())) {
            val empty = ByteArrayInputStream(emptyResponseByteArray)
            return WebResourceResponse(BLOCKED_RESPONSE_MIME_TYPE, BLOCKED_RESPONSE_ENCODING, empty)
        }
        return if (request.url.path?.startsWith(files.path) == true) {
            filesStoragePathHandler.handle(request.url.path!!.substring(files.path.length))
        } else if (request.url.path?.startsWith(cache.path) == true) {
            cacheStoragePathHandler.handle(request.url.path!!.substring(cache.path.length))
        } else {
            super.shouldInterceptRequest(view, request)
        }
    }

    /**
     * The factory for constructing the client.
     */
    @AssistedFactory
    interface Factory {

        /**
         * Create the client.
         */
        fun create(
            headers: Map<String, String>,
            @Assisted("cache") cacheStoragePathHandler: InternalStoragePathHandler,
            @Assisted("files") filesStoragePathHandler: InternalStoragePathHandler,
        ): TabWebViewClient
    }

    companion object {
        private const val TAG = "TabWebViewClient"

        private val emptyResponseByteArray: ByteArray = byteArrayOf()

        private const val BLOCKED_RESPONSE_MIME_TYPE = "text/plain"
        private const val BLOCKED_RESPONSE_ENCODING = "utf-8"
    }
}
