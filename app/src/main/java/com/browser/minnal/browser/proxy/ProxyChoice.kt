package com.browser.minnal.browser.proxy

import com.browser.minnal.preference.IntEnum

/**
 * HTTP proxy mode, persisted as an integer (see [com.browser.minnal.preference.UserPreferences.proxyChoice]).
 * Order matches [com.browser.minnal.R.array.proxy_choices_array].
 */
enum class ProxyChoice(override val value: Int) : IntEnum {
    NONE(0),
    ORBOT(1),
    MANUAL(2),
}
