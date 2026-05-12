package com.browser.minnal.settings.fragment

import com.browser.minnal.BuildConfig
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

        preferenceManager.findPreference<Preference>(APP_VERSION_FOOTER_KEY)?.apply {
            isSelectable = false
            title = getString(R.string.settings_version_title_format, BuildConfig.VERSION_NAME)
            summary = getString(R.string.settings_version_code_format, BuildConfig.VERSION_CODE)
        }
    }

    companion object {
        private const val DEBUG_KEY = "DEBUG"
        private const val APP_VERSION_FOOTER_KEY = "app_version_footer"
    }
}
