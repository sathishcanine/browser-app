package com.browser.minnal.rating

import com.browser.minnal.preference.UserPreferences
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides when the Play Store rating prompt may appear on the home screen at app launch.
 *
 * First eligible on the 3rd calendar day after install. "Maybe later" snoozes until the next
 * calendar day at midnight.
 */
@Singleton
class RatingPromptHelper @Inject constructor(
    private val userPreferences: UserPreferences,
) {

    fun recordFirstLaunchIfNeeded() {
        if (userPreferences.firstLaunchEpochMs == 0L) {
            userPreferences.firstLaunchEpochMs = System.currentTimeMillis()
        }
    }

    fun shouldShowRatingPrompt(): Boolean {
        if (userPreferences.ratingPromptCompleted) {
            return false
        }
        val nowMs = System.currentTimeMillis()
        if (nowMs < userPreferences.ratingPromptSnoozedUntilMs) {
            return false
        }
        if (!hasReachedThirdUseDay(nowMs)) {
            return false
        }
        return true
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

    /** Install day = day 1; prompt may show from day 3 onward. */
    private fun hasReachedThirdUseDay(nowMs: Long): Boolean {
        val firstLaunch = userPreferences.firstLaunchEpochMs
        if (firstLaunch == 0L) {
            return false
        }
        val calendarDaysSinceInstall = calendarDaysBetween(
            startOfDayMs(firstLaunch),
            startOfDayMs(nowMs),
        )
        return calendarDaysSinceInstall >= FIRST_PROMPT_CALENDAR_DAY - 1
    }

    private fun calendarDaysBetween(startOfEarlierDayMs: Long, startOfLaterDayMs: Long): Long =
        TimeUnit.MILLISECONDS.toDays(startOfLaterDayMs - startOfEarlierDayMs)

    private fun startOfDayMs(epochMs: Long): Long =
        startOfDay(Calendar.getInstance().apply { timeInMillis = epochMs })

    private fun startOfDay(calendar: Calendar): Long {
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    companion object {
        /** First calendar day the prompt may appear (1 = install day, 3 = third day). */
        private const val FIRST_PROMPT_CALENDAR_DAY = 3
    }
}
