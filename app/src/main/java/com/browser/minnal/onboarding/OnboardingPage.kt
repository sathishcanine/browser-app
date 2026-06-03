package com.browser.minnal.onboarding

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.browser.minnal.R

internal data class OnboardingPage(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    @DrawableRes val illustrationRes: Int,
    val usePhotoStyle: Boolean = false,
)

internal object OnboardingPages {
    val all: List<OnboardingPage> = listOf(
        OnboardingPage(
            titleRes = R.string.onboarding_welcome_title,
            descriptionRes = R.string.onboarding_welcome_description,
            illustrationRes = R.drawable.onboarding_illustration_welcome,
        ),
        OnboardingPage(
            titleRes = R.string.onboarding_adblock_title,
            descriptionRes = R.string.onboarding_adblock_description,
            illustrationRes = R.drawable.onboarding_illustration_adblock,
        ),
        OnboardingPage(
            titleRes = R.string.onboarding_private_title,
            descriptionRes = R.string.onboarding_private_description,
            illustrationRes = R.drawable.onboarding_illustration_private,
        ),
        OnboardingPage(
            titleRes = R.string.onboarding_downloads_title,
            descriptionRes = R.string.onboarding_downloads_description,
            illustrationRes = R.drawable.onboarding_illustration_downloads,
        ),
        OnboardingPage(
            titleRes = R.string.onboarding_url_bar_title,
            descriptionRes = R.string.onboarding_url_bar_description,
            illustrationRes = R.drawable.onboarding_illustration_url_bar,
        ),
        OnboardingPage(
            titleRes = R.string.onboarding_fab_title,
            descriptionRes = R.string.onboarding_fab_description,
            illustrationRes = R.drawable.onboarding_fab_menu,
            usePhotoStyle = true,
        ),
    )
}
