package com.browser.minnal.browser.tab.bundle

import com.browser.minnal.R
import com.browser.minnal.browser.di.DiskScheduler
import com.browser.minnal.browser.tab.BookmarkPageInitializer
import com.browser.minnal.browser.tab.DownloadPageInitializer
import com.browser.minnal.browser.tab.FreezableBundleInitializer
import com.browser.minnal.browser.tab.HistoryPageInitializer
import com.browser.minnal.browser.tab.HomePageInitializer
import com.browser.minnal.browser.tab.TabInitializer
import com.browser.minnal.browser.tab.TabModel
import com.browser.minnal.utils.FileUtils
import com.browser.minnal.utils.isBookmarkUrl
import com.browser.minnal.utils.isDownloadsUrl
import com.browser.minnal.utils.isHistoryUrl
import com.browser.minnal.utils.isSpecialUrl
import com.browser.minnal.utils.isStartPageUrl
import android.app.Application
import android.os.Bundle
import io.reactivex.rxjava3.core.Scheduler
import javax.inject.Inject

/**
 * A bundle store that serializes each tab state to disk and supports its retrieval.
 */
class DefaultBundleStore @Inject constructor(
    private val application: Application,
    private val bookmarkPageInitializer: BookmarkPageInitializer,
    private val homePageInitializer: HomePageInitializer,
    private val downloadPageInitializer: DownloadPageInitializer,
    private val historyPageInitializer: HistoryPageInitializer,
    @DiskScheduler private val diskScheduler: Scheduler
) : BundleStore {

    override fun save(tabs: List<TabModel>, selectedTabId: Int?) {
        val outState = Bundle(ClassLoader.getSystemClassLoader())
        selectedTabId?.let { outState.putInt(SELECTED_TAB_ID_KEY, it) }

        tabs.withIndex().forEach { (index, tab) ->
            if (!tab.url.isSpecialUrl()) {
                outState.putBundle(BUNDLE_KEY + index, tab.freeze())
                outState.putString(TAB_TITLE_KEY + index, tab.title)
                outState.putInt(TAB_ID_KEY + index, tab.id)
            } else {
                outState.putBundle(BUNDLE_KEY + index, Bundle().apply {
                    putString(URL_KEY, tab.url)
                })
            }
        }

        FileUtils.writeBundleToStorage(application, outState, BUNDLE_STORAGE)
            .subscribeOn(diskScheduler)
            .subscribe()
    }

    override fun retrieve(): RestoredTabBundle {
        val bundle = FileUtils.readBundleFromStorage(application, BUNDLE_STORAGE) ?: return RestoredTabBundle(
            initializers = emptyList(),
            selectedTabId = null,
        )
        val selectedTabId = bundle.getInt(SELECTED_TAB_ID_KEY, -1).takeIf { it != -1 }
        val initializers = bundle.keySet()
            .filter { it.startsWith(BUNDLE_KEY) }
            .sortedBy { it.extractNumberFromEnd().toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { bundleKey ->
                bundle.getBundle(bundleKey)?.let {
                    Triple(
                        it,
                        bundle.getString(TAB_TITLE_KEY + bundleKey.extractNumberFromEnd()),
                        bundle.getInt(TAB_ID_KEY + bundleKey.extractNumberFromEnd(), -1),
                    )
                }
            }
            .map { (tabBundle, title, id) ->
                tabBundle.getString(URL_KEY)?.let { url ->
                    when {
                        url.isBookmarkUrl() -> bookmarkPageInitializer
                        url.isDownloadsUrl() -> downloadPageInitializer
                        url.isStartPageUrl() -> homePageInitializer
                        url.isHistoryUrl() -> historyPageInitializer
                        else -> homePageInitializer
                    }
                } ?: FreezableBundleInitializer(
                    bundle = tabBundle,
                    initialTitle = title ?: application.getString(R.string.tab_frozen),
                    id = id,
                )
            }
        return RestoredTabBundle(initializers = initializers, selectedTabId = selectedTabId)
    }

    override fun deleteAll() {
        FileUtils.deleteBundleInStorage(application, BUNDLE_STORAGE)
    }

    private fun String.extractNumberFromEnd(): String {
        val underScore = lastIndexOf('_')
        return if (underScore in indices) {
            substring(underScore + 1)
        } else {
            ""
        }
    }

    companion object {
        private const val BUNDLE_KEY = "WEBVIEW_"
        private const val TAB_TITLE_KEY = "TITLE_"
        private const val TAB_ID_KEY = "ID_"
        private const val URL_KEY = "URL_KEY"
        private const val BUNDLE_STORAGE = "SAVED_TABS.parcel"
        private const val SELECTED_TAB_ID_KEY = "SELECTED_TAB_ID"
    }
}
