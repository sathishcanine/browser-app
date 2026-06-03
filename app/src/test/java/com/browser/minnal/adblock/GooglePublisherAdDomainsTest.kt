package com.browser.minnal.adblock

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GooglePublisherAdDomainsTest {

    @Test
    fun `exempts googlesyndication and subdomains`() {
        assertThat(GooglePublisherAdDomains.isExempt("https://pagead2.googlesyndication.com/pagead/js")).isTrue()
        assertThat(GooglePublisherAdDomains.isExempt("https://tpc.googlesyndication.com/sodar")).isTrue()
    }

    @Test
    fun `exempts doubleclick and googleadservices`() {
        assertThat(GooglePublisherAdDomains.isExempt("https://pubads.g.doubleclick.net/gampad/ads")).isTrue()
        assertThat(GooglePublisherAdDomains.isExempt("https://pagead2.googleadservices.com/pagead")).isTrue()
    }

    @Test
    fun `exempts adsense custom search and ads google`() {
        assertThat(GooglePublisherAdDomains.isExempt("https://www.adsensecustomsearchads.com/")).isTrue()
        assertThat(GooglePublisherAdDomains.isExempt("https://ads.google.com/")).isTrue()
    }

    @Test
    fun `does not exempt unrelated google hosts`() {
        assertThat(GooglePublisherAdDomains.isExempt("https://www.googletagmanager.com/gtm.js")).isFalse()
        assertThat(GooglePublisherAdDomains.isExempt("https://www.google-analytics.com/analytics.js")).isFalse()
        assertThat(GooglePublisherAdDomains.isExempt("https://www.google.com/")).isFalse()
    }

    @Test
    fun `does not exempt non-google ad networks`() {
        assertThat(GooglePublisherAdDomains.isExempt("https://ads.example.com/banner")).isFalse()
    }
}
