package com.browser.minnal.rating

import com.browser.minnal.preference.UserPreferences
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides when the Play Store rating prompt may appear after a download completes on the
 * in-app downloads page. "Maybe later" snoozes until the next calendar day at midnight.
 */
@Singleton
class RatingPromptHelper @Inject constructor(
    private val userPreferences: UserPreferences,
) {

    fun shouldShowRatingPrompt(): Boolean {
        return runCatching {
            if (userPreferences.ratingPromptCompleted) {
                return false
            }
            System.currentTimeMillis() >= userPreferences.ratingPromptSnoozedUntilMs.coerceAtLeast(0L)
        }.getOrDefault(false)
    }

    fun markRated() {
        userPreferences.ratingPromptCompleted = true
    }

    fun snoozeUntilNextDay() {
        val nextDayStart = startOfDay(Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
        })
        userPreferences.ratingPromptSnoozedUntilMs = nextDayStart
    }

    private fun startOfDay(calendar: Calendar): Long {
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}
