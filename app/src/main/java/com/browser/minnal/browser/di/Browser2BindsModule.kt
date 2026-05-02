package com.browser.minnal.browser.di

import com.browser.minnal.browser.BrowserContract
import com.browser.minnal.browser.BrowserNavigator
import com.browser.minnal.browser.cleanup.DelegatingExitCleanup
import com.browser.minnal.browser.cleanup.ExitCleanup
import com.browser.minnal.browser.image.FaviconImageLoader
import com.browser.minnal.browser.image.ImageLoader
import com.browser.minnal.browser.tab.TabsRepository
import com.browser.minnal.browser.theme.DefaultThemeProvider
import com.browser.minnal.browser.theme.ThemeProvider
import android.app.Activity
import androidx.fragment.app.FragmentActivity
import dagger.Binds
import dagger.Module

/**
 * Binds implementations to interfaces for the browser scope.
 */
@Module
interface Browser2BindsModule {

    @Binds
    fun bindsActivity(fragmentActivity: FragmentActivity): Activity

    @Binds
    fun bindsBrowserModel(tabsRepository: TabsRepository): BrowserContract.Model

    @Binds
    fun bindsFaviconImageLoader(faviconImageLoader: FaviconImageLoader): ImageLoader

    @Binds
    fun bindsBrowserNavigator(browserNavigator: BrowserNavigator): BrowserContract.Navigator

    @Binds
    fun bindsExitCleanup(delegatingExitCleanup: DelegatingExitCleanup): ExitCleanup

    @Binds
    fun bindsThemeProvider(legacyThemeProvider: DefaultThemeProvider): ThemeProvider
}
