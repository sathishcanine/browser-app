package com.browser.minnal.onboarding

import com.browser.minnal.DefaultBrowserActivity
import com.browser.minnal.R
import com.browser.minnal.browser.di.injector
import com.browser.minnal.preference.UserPreferences
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.viewpager2.widget.ViewPager2
import javax.inject.Inject

/**
 * First-run carousel that introduces Minnal's key advantages before browsing starts.
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        /**
         * True for a brand-new install that has not finished onboarding.
         *
         * [UserPreferences.firstLaunchEpochMs] is also checked so upgrades from builds before
         * onboarding skip the carousel
         * without treating them as a fresh install.
         */
        fun shouldShow(userPreferences: UserPreferences): Boolean =
            !userPreferences.onboardingCompleted && userPreferences.firstLaunchEpochMs == 0L

        /** Persists onboarding completion for installs that predated the onboarding feature. */
        fun markLegacyOnboardingCompleteIfNeeded(userPreferences: UserPreferences) {
            if (!userPreferences.onboardingCompleted && userPreferences.firstLaunchEpochMs != 0L) {
                userPreferences.onboardingCompleted = true
            }
        }
    }

    @Inject
    internal lateinit var userPreferences: UserPreferences

    private lateinit var viewPager: ViewPager2
    private lateinit var dotsContainer: LinearLayout
    private lateinit var nextButton: AppCompatButton
    private lateinit var skipButton: View

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            updateUiForPage(position)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        injector.inject(this)
        if (!shouldShow(userPreferences)) {
            markLegacyOnboardingCompleteIfNeeded(userPreferences)
            super.onCreate(savedInstanceState)
            launchBrowserAndFinish()
            return
        }
        setTheme(R.style.Theme_Onboarding)
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_onboarding)

        viewPager = findViewById(R.id.onboarding_pager)
        dotsContainer = findViewById(R.id.onboarding_dots)
        nextButton = findViewById(R.id.onboarding_next_button)
        skipButton = findViewById(R.id.onboarding_skip_button)

        val root = findViewById<View>(R.id.onboarding_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right,
                bottom = systemBars.bottom,
            )
            insets
        }

        val pages = OnboardingPages.all
        viewPager.adapter = OnboardingPagerAdapter(pages)
        buildDots(pages.size)
        updateUiForPage(0)

        viewPager.registerOnPageChangeCallback(pageChangeCallback)

        nextButton.setOnClickListener {
            val lastIndex = pages.lastIndex
            if (viewPager.currentItem < lastIndex) {
                viewPager.setCurrentItem(viewPager.currentItem + 1, true)
            } else {
                completeOnboarding()
            }
        }

        skipButton.setOnClickListener {
            completeOnboarding()
        }
    }

    override fun onDestroy() {
        if (::viewPager.isInitialized) {
            viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        }
        super.onDestroy()
    }

    private fun buildDots(count: Int) {
        dotsContainer.removeAllViews()
        val size = resources.getDimensionPixelSize(R.dimen.onboarding_dot_size)
        val margin = resources.getDimensionPixelSize(R.dimen.onboarding_dot_margin)
        repeat(count) { index ->
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = if (index == 0) 0 else margin
                    marginEnd = if (index == count - 1) 0 else margin
                }
                setBackgroundResource(R.drawable.onboarding_dot_unselected)
            }
            dotsContainer.addView(dot)
        }
    }

    private fun updateUiForPage(position: Int) {
        val pages = OnboardingPages.all
        val isLast = position == pages.lastIndex
        nextButton.text = getString(
            if (isLast) R.string.onboarding_get_started else R.string.onboarding_next,
        )
        skipButton.visibility = if (isLast) View.GONE else View.VISIBLE

        for (i in 0 until dotsContainer.childCount) {
            dotsContainer.getChildAt(i).setBackgroundResource(
                if (i == position) R.drawable.onboarding_dot_selected else R.drawable.onboarding_dot_unselected,
            )
        }
    }

    private fun completeOnboarding() {
        if (userPreferences.firstLaunchEpochMs == 0L) {
            userPreferences.firstLaunchEpochMs = System.currentTimeMillis()
        }
        userPreferences.onboardingCompleted = true
        launchBrowserAndFinish()
    }

    private fun launchBrowserAndFinish() {
        startActivity(
            Intent(this, DefaultBrowserActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
