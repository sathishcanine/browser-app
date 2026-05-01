package acr.browser.lightning.browser.proxy

import acr.browser.lightning.preference.IntEnum

/**
 * HTTP proxy mode, persisted as an integer (see [acr.browser.lightning.preference.UserPreferences.proxyChoice]).
 * Order matches [acr.browser.lightning.R.array.proxy_choices_array].
 */
enum class ProxyChoice(override val value: Int) : IntEnum {
    NONE(0),
    ORBOT(1),
    MANUAL(2),
}
