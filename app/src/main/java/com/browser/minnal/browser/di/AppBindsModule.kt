package com.browser.minnal.browser.di

import com.browser.minnal.adblock.allowlist.AllowListModel
import com.browser.minnal.adblock.allowlist.SessionAllowListModel
import com.browser.minnal.adblock.source.AssetsHostsDataSource
import com.browser.minnal.adblock.source.HostsDataSource
import com.browser.minnal.adblock.source.HostsDataSourceProvider
import com.browser.minnal.adblock.source.PreferencesHostsDataSourceProvider
import com.browser.minnal.database.adblock.HostsDatabase
import com.browser.minnal.database.adblock.HostsRepository
import com.browser.minnal.database.allowlist.AdBlockAllowListDatabase
import com.browser.minnal.database.allowlist.AdBlockAllowListRepository
import com.browser.minnal.database.bookmark.BookmarkDatabase
import com.browser.minnal.database.bookmark.BookmarkRepository
import com.browser.minnal.database.downloads.DownloadsDatabase
import com.browser.minnal.database.downloads.DownloadsRepository
import com.browser.minnal.database.history.HistoryDatabase
import com.browser.minnal.database.history.HistoryRepository
import com.browser.minnal.ssl.SessionSslWarningPreferences
import com.browser.minnal.ssl.SslWarningPreferences
import dagger.Binds
import dagger.Module

/**
 * Dependency injection module used to bind implementations to interfaces.
 */
@Module
interface AppBindsModule {

    @Binds
    fun bindsBookmarkModel(bookmarkDatabase: BookmarkDatabase): BookmarkRepository

    @Binds
    fun bindsDownloadsModel(downloadsDatabase: DownloadsDatabase): DownloadsRepository

    @Binds
    fun bindsHistoryModel(historyDatabase: HistoryDatabase): HistoryRepository

    @Binds
    fun bindsAdBlockAllowListModel(adBlockAllowListDatabase: AdBlockAllowListDatabase): AdBlockAllowListRepository

    @Binds
    fun bindsAllowListModel(sessionAllowListModel: SessionAllowListModel): AllowListModel

    @Binds
    fun bindsSslWarningPreferences(sessionSslWarningPreferences: SessionSslWarningPreferences): SslWarningPreferences

    @Binds
    fun bindsHostsDataSource(assetsHostsDataSource: AssetsHostsDataSource): HostsDataSource

    @Binds
    fun bindsHostsRepository(hostsDatabase: HostsDatabase): HostsRepository

    @Binds
    fun bindsHostsDataSourceProvider(preferencesHostsDataSourceProvider: PreferencesHostsDataSourceProvider): HostsDataSourceProvider
}
