package com.browser.minnal.settings.fragment

import com.browser.minnal.R
import com.browser.minnal.browser.di.injector
import com.browser.minnal.device.BuildInfo
import com.browser.minnal.device.BuildType
import android.os.Bundle
import androidx.preference.Preference
import javax.inject.Inject

/**
 * The root settings list.
 */
class RootSettingsFragment : AbstractSettingsFragment() {

    @Inject lateinit var buildInfo: BuildInfo

    override fun providePreferencesXmlResource(): Int = R.xml.preference_root

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        injector.inject(this)

        preferenceManager.findPreference<Preference>(DEBUG_KEY)?.isVisible =
            buildInfo.buildType != BuildType.RELEASE
    }

    companion object {
        private const val DEBUG_KEY = "DEBUG"
    }
}
