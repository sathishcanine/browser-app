package com.browser.minnal.browser.tab.bundle

import com.browser.minnal.browser.tab.TabModel
import com.browser.minnal.browser.tab.TabInitializer

/**
 * Used to save tab data for future restoration when the browser goes into hibernation.
 */
interface BundleStore {

    /**
     * Save the tab data for the list of [tabs] and the tab the user had in the foreground.
     */
    fun save(tabs: List<TabModel>, selectedTabId: Int?)

    /**
     * Synchronously read previously stored tab data.
     */
    fun retrieve(): RestoredTabBundle

    /**
     * Synchronously delete all stored tabs.
     */
    fun deleteAll()
}
