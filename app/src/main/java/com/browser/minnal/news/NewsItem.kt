package com.browser.minnal.news

/**
 * A single news / discovery card.
 *
 * Kept intentionally lightweight — just what we render on the home page. Identity is the [url]
 * (also used for de-duplication when we merge feeds). [publishedAt] is wall-clock millis at the
 * publication time the feed advertised, falling back to fetch time when none is supplied.
 */
data class NewsItem(
    val title: String,
    val url: String,
    val source: NewsSource,
    val publishedAt: Long,
    val summary: String? = null,
    val imageUrl: String? = null
)
