package com.browser.minnal

import com.browser.minnal.browser.di.AppComponent
import com.browser.minnal.browser.di.DaggerAppComponent
import com.browser.minnal.browser.di.DatabaseScheduler
import com.browser.minnal.browser.di.injector
import com.browser.minnal.database.bookmark.BookmarkExporter
import com.browser.minnal.database.bookmark.BookmarkRepository
import com.browser.minnal.device.BuildInfo
import com.browser.minnal.device.BuildType
import com.browser.minnal.log.Logger
import com.browser.minnal.migration.Cleanup
import com.browser.minnal.utils.FileUtils
import com.browser.minnal.utils.LeakCanaryUtils
import android.app.Application
import android.os.Build
import android.os.StrictMode
import android.webkit.WebView
import com.google.android.gms.ads.MobileAds
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.system.exitProcess

/**
 * The browser application.
 */
class BrowserApp : Application() {

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

    lateinit var applicationComponent: AppComponent

    override fun onCreate() {
        super.onCreate()

        // Must run before any code path that can create a WebView (e.g. Mobile Ads). Otherwise the
        // :incognito process shares the default WebView data dir with the main process and crashes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (getProcessName() == "$packageName:incognito") {
                File(dataDir, "app_webview_incognito").deleteRecursively()
                WebView.setDataDirectorySuffix("incognito")
            }
        }

        FirebaseApp.initializeApp(this)
        MobileAds.initialize(this) {}
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
        FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(true)

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

        MainScope().launch {
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
            FirebaseCrashlytics.getInstance().recordException(throwable)
        }

        applicationComponent = DaggerAppComponent.builder()
            .application(this)
            .buildInfo(createBuildInfo())
            .build()
        injector.inject(this)

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
