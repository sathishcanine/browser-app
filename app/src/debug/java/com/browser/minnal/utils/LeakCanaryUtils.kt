package com.browser.minnal.utils

import com.browser.minnal.concurrency.CoroutineDispatchers
import com.browser.minnal.preference.DeveloperPreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import leakcanary.LeakCanary
import javax.inject.Inject

/**
 * Sets up LeakCanary.
 */
class LeakCanaryUtils @Inject constructor(
    private val developerPreferenceStore: DeveloperPreferenceStore,
    private val appCoroutineScope: CoroutineScope,
    private val coroutineDispatchers: CoroutineDispatchers,
) {

    /**
     * Setup LeakCanary
     */
    fun setup() {
        appCoroutineScope.launch(coroutineDispatchers.io) {
            LeakCanary.config = LeakCanary.config.copy(
                dumpHeap = developerPreferenceStore.useLeakCanary.get()
            )
        }
    }

}
