package com.browser.minnal

import com.browser.minnal.browser.di.AppComponent
import com.browser.minnal.browser.di.DaggerAppComponent
import com.browser.minnal.browser.di.DatabaseScheduler
import com.browser.minnal.browser.di.injector
import com.browser.minnal.database.bookmark.BookmarkExporter
import com.browser.minnal.database.bookmark.BookmarkRepository
import com.browser.minnal.device.BuildInfo
import com.browser.minnal.device.BuildType
import com.browser.minnal.download.manager.MinnalDownloadManager
import com.browser.minnal.log.Logger
import com.browser.minnal.migration.Cleanup
import com.browser.minnal.utils.FileUtils
import com.browser.minnal.utils.LeakCanaryUtils
import android.app.Application
import android.os.Build
import android.os.StrictMode
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import com.browser.minnal.ads.AppOpenAdManager
import com.browser.minnal.ads.MobileAdsInitializer
import com.browser.minnal.firebase.PushNotificationRegistrar
import com.browser.minnal.utils.isIncognitoProcess
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import androidx.work.Configuration
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.system.exitProcess

/**
 * The browser application.
 */
class BrowserApp : Application(), Configuration.Provider {

    @Inject
    internal lateinit var leakCanaryUtils: LeakCanaryUtils

    @Inject
    internal lateinit var bookmarkModel: BookmarkRepository

    @Inject
    @DatabaseScheduler
    internal lateinit var databaseScheduler: Scheduler

    @Inject
    internal lateinit var logger: Logger

    @Inject
    internal lateinit var buildInfo: BuildInfo

    @Inject
    internal lateinit var cleanup: Cleanup

    @Inject
    internal lateinit var appOpenAdManager: AppOpenAdManager

    @Inject
    internal lateinit var minnalDownloadManager: MinnalDownloadManager

    @Inject
    internal lateinit var mobileAdsInitializer: MobileAdsInitializer

    @Inject
    internal lateinit var pushNotificationRegistrar: PushNotificationRegistrar

    lateinit var applicationComponent: AppComponent

    override val workManagerConfiguration: Configuration =
        Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.ERROR)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Must run before any code path that can create a WebView (e.g. Mobile Ads). Otherwise the
        // :incognito process shares the default WebView data dir with the main process and crashes.
        // The Ads SDK also initializes WebView internally on API 28+, so set the main-process suffix
        // before MobileAds.initialize() to avoid "already initialized" / multi-process directory bugs.
        val incognitoProcess = isIncognitoProcess()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            when {
                incognitoProcess -> {
                    File(dataDir, "app_webview_incognito").deleteRecursively()
                    WebView.setDataDirectorySuffix("incognito")
                    // AdMob renders rewarded ads through an internal WebView; allow cookies in this
                    // isolated process without affecting the main browser profile.
                    CookieManager.getInstance().setAcceptCookie(true)
                }
                else -> WebView.setDataDirectorySuffix("main")
            }
        }

        runCatching { FirebaseApp.initializeApp(this) }
            .onFailure { Log.e(TAG, "FirebaseApp.initializeApp failed", it) }

        // Crashlytics collection: firebase_crashlytics_collection_enabled in manifest (no getInstance here).

        runCatching {
            FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(true)
        }.onFailure { Log.e(TAG, "Firebase Analytics init failed", it) }

        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        val cleanupFailures = CoroutineExceptionHandler { _, throwable ->
            crashlyticsOrNull()?.recordException(throwable)
        }
        MainScope().launch(cleanupFailures) {
            cleanup.cleanup()
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            if (BuildConfig.DEBUG) {
                FileUtils.writeCrashToStorage(ex)
            }

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex)
            } else {
                exitProcess(2)
            }
        }

        RxJavaPlugins.setErrorHandler { throwable: Throwable? ->
            if (throwable == null) {
                return@setErrorHandler
            }
            if (BuildConfig.DEBUG) {
                FileUtils.writeCrashToStorage(throwable)
                throw throwable
            }
            crashlyticsOrNull()?.recordException(throwable)
        }

        applicationComponent = DaggerAppComponent.builder()
            .application(this)
            .buildInfo(createBuildInfo())
            .build()
        injector.inject(this)

        if (!incognitoProcess) {
            mobileAdsInitializer.start()
            appOpenAdManager.start()
            pushNotificationRegistrar.syncTopicSubscription()
        }
        minnalDownloadManager.resumeActiveDownloads()

        Single.fromCallable(bookmarkModel::count)
            .filter { it == 0L }
            .flatMapCompletable {
                val assetsBookmarks = BookmarkExporter.importBookmarksFromAssets(this@BrowserApp)
                bookmarkModel.addBookmarkList(assetsBookmarks)
            }
            .subscribeOn(databaseScheduler)
            .subscribe()

        if (buildInfo.buildType == BuildType.DEBUG) {
            leakCanaryUtils.setup()
        }

        if (buildInfo.buildType == BuildType.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    /**
     * Crashlytics may not register if Firebase components are stripped (R8) or misconfigured.
     */
    private fun crashlyticsOrNull(): FirebaseCrashlytics? =
        try {
            FirebaseCrashlytics.getInstance()
        } catch (_: Throwable) {
            null
        }

    /**
     * Create the [BuildType] from the [BuildConfig].
     */
    private fun createBuildInfo() = BuildInfo(
        when {
            BuildConfig.DEBUG -> BuildType.DEBUG
            else -> BuildType.RELEASE
        }
    )

    companion object {
        private const val TAG = "BrowserApp"
    }
}
