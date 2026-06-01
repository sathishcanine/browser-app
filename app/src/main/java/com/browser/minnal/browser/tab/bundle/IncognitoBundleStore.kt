package com.browser.minnal.browser.tab.bundle

import com.browser.minnal.browser.tab.TabModel
import com.browser.minnal.browser.tab.TabInitializer

/**
 * A bundle store implementation that no-ops for for incognito mode.
 */
object IncognitoBundleStore : BundleStore {
    override fun save(tabs: List<TabModel>, selectedTabId: Int?) = Unit

    override fun retrieve(): RestoredTabBundle = RestoredTabBundle(emptyList(), null)

    override fun deleteAll() = Unit
}
