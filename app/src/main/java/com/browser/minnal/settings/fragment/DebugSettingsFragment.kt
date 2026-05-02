package com.browser.minnal.settings.fragment

import com.browser.minnal.R
import com.browser.minnal.browser.di.injector
import com.browser.minnal.extensions.snackbar
import com.browser.minnal.preference.DeveloperPreferenceStore
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

class DebugSettingsFragment : AbstractSettingsFragment() {

    @Inject internal lateinit var developerPreferenceStore: DeveloperPreferenceStore

    override fun providePreferencesXmlResource() = R.xml.preference_debug

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        injector.inject(this)

        runBlocking {
            togglePreference(
                preference = LEAK_CANARY,
                isChecked = developerPreferenceStore.useLeakCanary.get(),
                onCheckChange = { change ->
                    activity?.snackbar(R.string.app_restart)
                    runBlocking {
                        developerPreferenceStore.useLeakCanary.set(change)
                    }
                }
            )
        }
    }

    companion object {
        private const val LEAK_CANARY = "leak_canary_enabled"
    }
}
