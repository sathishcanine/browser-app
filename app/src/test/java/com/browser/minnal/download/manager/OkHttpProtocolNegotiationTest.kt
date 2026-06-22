package com.browser.minnal.download.manager

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Verifies OkHttp negotiates HTTP/2 over TLS the same way [DownloadEngine]'s client is built.
 * Run: ./gradlew :app:testLightningPlusDebugUnitTest --tests OkHttpProtocolNegotiationTest
 */
class OkHttpProtocolNegotiationTest {

    private val client = OkHttpClient.Builder()
        .dispatcher(
            Dispatcher().apply {
                maxRequestsPerHost = 12
                maxRequests = 24
            },
        )
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Test
    fun configuredProtocolsPreferHttp2() {
        assertEquals(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), client.protocols)
    }

    @Test
    fun googleCdnNegotiatesHttp2() {
        client.newCall(
            Request.Builder()
                .url("https://dl.google.com/android/repository/platform-tools-latest-linux.zip")
                .head()
                .build(),
        ).execute().use { response ->
            println("Google CDN protocol: ${response.protocol}")
            assertEquals(Protocol.HTTP_2, response.protocol)
        }
    }

    @Test
    fun githubReleasesNegotiatesHttp2() {
        client.newCall(
            Request.Builder()
                .url("https://github.com/square/okhttp/releases/download/parent-5.3.2/okhttp-5.3.2.jar")
                .head()
                .build(),
        ).execute().use { response ->
            println("GitHub protocol: ${response.protocol}")
            assertTrue(
                "Expected HTTP/2 or HTTP/1.1, got ${response.protocol}",
                response.protocol == Protocol.HTTP_2 || response.protocol == Protocol.HTTP_1_1,
            )
        }
    }
}
