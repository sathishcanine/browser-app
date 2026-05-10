package com.browser.minnal.preference

import com.browser.minnal.BuildConfig
import com.browser.minnal.constant.DESKTOP_USER_AGENT
import com.browser.minnal.constant.MOBILE_USER_AGENT
import android.app.Application
import android.webkit.WebSettings

/**
 * Token appended to every outgoing User-Agent so trusted partner sites can detect
 * that the request is coming from the Minnal browser app and unlock app-only flows
 * (e.g. rewarded-ad gated downloads). Format: `MinnalBrowser/<versionName>`.
 */
const val MINNAL_UA_TOKEN_PREFIX: String = "MinnalBrowser/"
val MINNAL_UA_TOKEN: String = "$MINNAL_UA_TOKEN_PREFIX${BuildConfig.VERSION_NAME}"

/**
 * Append the Minnal browser identifier to a UA string if it isn't already present.
 */
fun String.withMinnalToken(): String =
    if (contains(MINNAL_UA_TOKEN_PREFIX)) this else "$this $MINNAL_UA_TOKEN".trim()

/**
 * Return the user agent chosen by the user or the custom user agent entered by the user.
 */
fun UserPreferences.userAgent(application: Application): String =
    when (val choice = userAgentChoice) {
        1 -> WebSettings.getDefaultUserAgent(application)
        2 -> DESKTOP_USER_AGENT
        3 -> MOBILE_USER_AGENT
        4 -> userAgentString.takeIf(String::isNotEmpty) ?: " "
        else -> throw UnsupportedOperationException("Unknown userAgentChoice: $choice")
    }.withMinnalToken()

fun UserPreferences.userAgent(defaultUserAgent: String): String =
    when (val choice = userAgentChoice) {
        1 -> defaultUserAgent
        2 -> DESKTOP_USER_AGENT
        3 -> MOBILE_USER_AGENT
        4 -> userAgentString.takeIf(String::isNotEmpty) ?: " "
        else -> throw UnsupportedOperationException("Unknown userAgentChoice: $choice")
    }.withMinnalToken()
