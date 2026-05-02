package com.browser.minnal.browser.di

import com.browser.minnal.BrowserApp
import com.browser.minnal.ThemableBrowserActivity
import com.browser.minnal.adblock.BloomFilterAdBlocker
import com.browser.minnal.adblock.NoOpAdBlocker
import com.browser.minnal.browser.search.SearchBoxModel
import com.browser.minnal.device.BuildInfo
import com.browser.minnal.dialog.LightningDialogBuilder
import com.browser.minnal.search.SuggestionsAdapter
import com.browser.minnal.settings.activity.ThemableSettingsActivity
import com.browser.minnal.settings.fragment.AdBlockSettingsFragment
import com.browser.minnal.settings.fragment.AdvancedSettingsFragment
import com.browser.minnal.settings.fragment.BookmarkSettingsFragment
import com.browser.minnal.settings.fragment.DebugSettingsFragment
import com.browser.minnal.settings.fragment.DisplaySettingsFragment
import com.browser.minnal.settings.fragment.GeneralSettingsFragment
import com.browser.minnal.settings.fragment.PrivacySettingsFragment
import com.browser.minnal.settings.fragment.RootSettingsFragment
import android.app.Application
import dagger.BindsInstance
import dagger.Component
import dagger.Module
import javax.inject.Singleton

@Singleton
@Component(modules = [AppModule::class, AppBindsModule::class, Submodules::class])
interface AppComponent {

    @Component.Builder
    interface Builder {

        @BindsInstance
        fun application(application: Application): Builder

        @BindsInstance
        fun buildInfo(buildInfo: BuildInfo): Builder

        fun build(): AppComponent
    }

    fun inject(fragment: BookmarkSettingsFragment)

    fun inject(builder: LightningDialogBuilder)

    fun inject(activity: ThemableBrowserActivity)

    fun inject(advancedSettingsFragment: AdvancedSettingsFragment)

    fun inject(app: BrowserApp)

    fun inject(activity: ThemableSettingsActivity)

    fun inject(fragment: PrivacySettingsFragment)

    fun inject(fragment: DebugSettingsFragment)

    fun inject(suggestionsAdapter: SuggestionsAdapter)

    fun inject(searchBoxModel: SearchBoxModel)

    fun inject(activity: RootSettingsFragment)

    fun inject(generalSettingsFragment: GeneralSettingsFragment)

    fun inject(displaySettingsFragment: DisplaySettingsFragment)

    fun inject(adBlockSettingsFragment: AdBlockSettingsFragment)

    fun provideBloomFilterAdBlocker(): BloomFilterAdBlocker

    fun provideNoOpAdBlocker(): NoOpAdBlocker

    fun browser2ComponentBuilder(): Browser2Component.Builder

}

@Module(subcomponents = [Browser2Component::class])
internal class Submodules
