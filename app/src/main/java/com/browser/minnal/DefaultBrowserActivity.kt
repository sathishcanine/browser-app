package com.browser.minnal

import com.browser.minnal.browser.BrowserActivity
import com.browser.minnal.browser.di.injector
import com.browser.minnal.onboarding.OnboardingActivity
import android.content.Intent
import android.os.Bundle

/**
 * The default browsing experience.
 */
class DefaultBrowserActivity : BrowserActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState == null && shouldShowOnboarding()) {
            pendingOnboardingRedirect = true
        }
        super.onCreate(savedInstanceState)
    }

    override fun isIncognito(): Boolean = false

    override fun menu(): Int = R.menu.main

    override fun homeIcon(): Int = R.drawable.ic_action_home

    private fun shouldShowOnboarding(): Boolean {
        injector.inject(this)
        return OnboardingActivity.shouldShow(userPreferences)
    }

    companion object {
        @Volatile
        private var pendingOnboardingRedirect = false

        internal fun takePendingOnboardingRedirect(): Boolean {
            if (!pendingOnboardingRedirect) {
                return false
            }
            pendingOnboardingRedirect = false
            return true
        }
    }
}
