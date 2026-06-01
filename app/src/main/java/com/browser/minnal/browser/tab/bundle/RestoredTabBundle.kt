package com.browser.minnal.browser.tab.bundle

import com.browser.minnal.browser.tab.TabInitializer

/**
 * Tab state read from disk when the browser cold-starts after hibernation.
 */
data class RestoredTabBundle(
    val initializers: List<TabInitializer>,
    val selectedTabId: Int?,
)
