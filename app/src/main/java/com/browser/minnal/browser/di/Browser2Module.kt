package com.browser.minnal.browser.di

import com.browser.minnal.R
import com.browser.minnal.adblock.AdBlocker
import com.browser.minnal.adblock.UserPreferenceAdBlocker
import com.browser.minnal.browser.BrowserContract
import com.browser.minnal.browser.data.CookieAdministrator
import com.browser.minnal.browser.data.DefaultCookieAdministrator
import com.browser.minnal.browser.data.IncognitoCookieAdministrator
import com.browser.minnal.browser.history.DefaultHistoryRecord
import com.browser.minnal.browser.history.HistoryRecord
import com.browser.minnal.browser.history.NoOpHistoryRecord
import com.browser.minnal.browser.image.IconFreeze
import com.browser.minnal.browser.notification.DefaultTabCountNotifier
import com.browser.minnal.browser.notification.IncognitoTabCountNotifier
import com.browser.minnal.browser.notification.TabCountNotifier
import com.browser.minnal.browser.search.IntentExtractor
import com.browser.minnal.browser.tab.DefaultUserAgent
import com.browser.minnal.browser.tab.bundle.BundleStore
import com.browser.minnal.browser.tab.bundle.DefaultBundleStore
import com.browser.minnal.browser.tab.bundle.IncognitoBundleStore
import com.browser.minnal.browser.ui.BookmarkConfiguration
import com.browser.minnal.browser.ui.UiConfiguration
import com.browser.minnal.extensions.drawable
import com.browser.minnal.preference.UserPreferences
import com.browser.minnal.utils.IntentUtils
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.WebSettings
import androidx.core.graphics.drawable.toBitmap
import dagger.Module
import dagger.Provides
import javax.inject.Provider

/**
 * Constructs dependencies for the browser scope.
 */
@Module
class Browser2Module {

    @Provides
    fun providesAdBlocker(userPreferenceAdBlocker: UserPreferenceAdBlocker): AdBlocker =
        userPreferenceAdBlocker

    // TODO: dont force cast
    @Provides
    @InitialUrl
    fun providesInitialUrl(
        @InitialIntent initialIntent: Intent?,
        intentExtractor: IntentExtractor
    ): String? =
        (intentExtractor.extractUrlFromIntent(initialIntent) as? BrowserContract.Action.LoadUrl)?.url

    // TODO: auto inject intent utils
    @Provides
    fun providesIntentUtils(activity: Activity): IntentUtils = IntentUtils(activity)

    @Provides
    fun providesUiConfiguration(
        userPreferences: UserPreferences
    ): UiConfiguration = UiConfiguration(
        tabConfiguration = userPreferences.tabConfiguration,
        bookmarkConfiguration = if (userPreferences.bookmarksAndTabsSwapped) {
            BookmarkConfiguration.LEFT
        } else {
            BookmarkConfiguration.RIGHT
        }
    )

    @DefaultUserAgent
    @Provides
    fun providesDefaultUserAgent(application: Application): String =
        WebSettings.getDefaultUserAgent(application)


    @Provides
    fun providesHistoryRecord(
        @IncognitoMode incognitoMode: Boolean,
        defaultHistoryRecord: DefaultHistoryRecord
    ): HistoryRecord = if (incognitoMode) {
        NoOpHistoryRecord
    } else {
        defaultHistoryRecord
    }

    @Provides
    fun providesCookieAdministrator(
        @IncognitoMode incognitoMode: Boolean,
        defaultCookieAdministrator: DefaultCookieAdministrator,
        incognitoCookieAdministrator: IncognitoCookieAdministrator,
    ): CookieAdministrator = if (incognitoMode) {
        incognitoCookieAdministrator
    } else {
        defaultCookieAdministrator
    }

    @Provides
    fun providesTabCountNotifier(
        @IncognitoMode incognitoMode: Boolean,
        incognitoTabCountNotifier: IncognitoTabCountNotifier
    ): TabCountNotifier = if (incognitoMode) {
        incognitoTabCountNotifier
    } else {
        DefaultTabCountNotifier
    }

    @Provides
    fun providesBundleStore(
        @IncognitoMode incognitoMode: Boolean,
        defaultBundleStore: DefaultBundleStore
    ): BundleStore = if (incognitoMode) {
        IncognitoBundleStore
    } else {
        defaultBundleStore
    }

    @IconFreeze
    @Provides
    fun providesFrozenIcon(activity: Activity): Bitmap =
        activity.drawable(R.drawable.ic_frozen).toBitmap()

}
