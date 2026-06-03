package com.browser.minnal.adblock

import androidx.core.net.toUri

/**
 * Hostnames used by Google AdSense and related publisher ad delivery. These remain loadable when
 * ad blocking is enabled; other entries in the hosts blocklist still apply.
 */
object GooglePublisherAdDomains {

    private val domainSuffixes = listOf(
        "googlesyndication.com",
        "googleadservices.com",
        "doubleclick.net",
        "googleadsserving.cn",
        "adsensecustomsearchads.com",
    )

    private val exactHosts = setOf(
        "ads.google.com",
        "pagead.l.google.com",
        "pagead-googlehosted.l.google.com",
        "displayads-formats.googleusercontent.com",
        "fundingchoicesmessages.google.com",
    )

    /**
     * True if [url] targets Google's publisher ad stack and should not be blocked.
     */
    fun isExempt(url: String): Boolean {
        val host = url.toUri().host?.lowercase() ?: return false
        if (exactHosts.contains(host)) {
            return true
        }
        if (host == "adservice.google.com" || host.startsWith("adservice.google.")) {
            return true
        }
        return domainSuffixes.any { suffix -> hostMatchesSuffix(host, suffix) }
    }

    private fun hostMatchesSuffix(host: String, suffix: String): Boolean =
        host == suffix || host.endsWith(".$suffix")
}
