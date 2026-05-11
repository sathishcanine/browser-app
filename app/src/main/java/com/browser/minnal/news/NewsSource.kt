package com.browser.minnal.news

import android.net.Uri
import java.util.Locale

/**
 * Configured source for the home-page news feed.
 *
 * Sources are intentionally an in-code curated list (no API keys, no remote config) because the
 * primary requirement is "no Play Store / legal surface area, just RSS that's already public".
 * If a source's feed is offline / changed, the parser silently skips it and the rest of the
 * feed still renders — see [NewsRepository.fetchAll].
 */
data class NewsSource(
    /** Stable id used as the persistence key in the disk cache. Never user-visible. */
    val id: String,
    /** Human-readable name shown on each card ("The Hindu", "ESPNcricinfo"). */
    val name: String,
    /** RSS 2.0 / Atom feed URL. */
    val feedUrl: String,
    /** Site's own home (used for fallbacks). Optional. */
    val siteUrl: String? = null,
    /** Two-letter ISO code; only used so the UI can group feeds by language if it wants to. */
    val language: Language = Language.ENGLISH,
) {
    enum class Language { ENGLISH, TAMIL }

    companion object {

        // ----- Registry: bookmark host -> RSS feed --------------------------------------------
        //
        // The Discover feed is driven by the user's own bookmarks: when a bookmark's host
        // appears here, we pull articles from the matching feed instead of using the curated
        // [DEFAULTS] below. This keeps the feed relevant ("show me news from the sites I cared
        // about enough to bookmark") without exposing a settings UI to wire feeds up by hand.
        //
        // Feed-URL policy:
        //  * Prefer the publisher's own RSS / Atom feed when one exists and is currently live.
        //  * For sites without a working public feed (most Indian Tamil publishers fall into
        //    this bucket — Daily Thanthi, Dinamalar, Behindwoods, Hindu Tamil), we synthesize
        //    a feed from Google News' public `site:` RSS search. This is the same query a user
        //    could type into news.google.com manually; it's just RSS-shaped JSON and contains
        //    no auth, no tracking parameters owned by us, and no API key.
        //
        // Add entries conservatively — every entry adds one HTTP request per refresh. Hosts are
        // stored stripped of any leading "www." and lowercased; see [hostKey].

        val KNOWN_FEEDS_BY_HOST: Map<String, NewsSource> = mapOf(
            // -- Tamil ---------------------------------------------------------------------
            // Daily Thanthi's own /Rss endpoint started returning 404 sometime before 2026-05;
            // fall through to Google News so the bookmark still contributes cards.
            "dailythanthi.com" to googleNewsForHost(
                id = "dailythanthi",
                name = "Daily Thanthi",
                host = "dailythanthi.com",
                language = Language.TAMIL
            ),
            // Dinamalar's rss.aspx returns 500; same workaround.
            "dinamalar.com" to googleNewsForHost(
                id = "dinamalar",
                name = "Dinamalar",
                host = "dinamalar.com",
                language = Language.TAMIL
            ),
            // Behindwoods has no public RSS in 2026 (their /rss/news.xml is 404). Tamil
            // entertainment is well covered by Google News' site search though.
            "behindwoods.com" to googleNewsForHost(
                id = "behindwoods",
                name = "Behindwoods",
                host = "behindwoods.com",
                language = Language.TAMIL
            ),
            // Hindu Tamil's /rss/headline.xml is 404; same fallback.
            "hindutamil.in" to googleNewsForHost(
                id = "hindutamil",
                name = "Hindu Tamil",
                host = "hindutamil.in",
                language = Language.TAMIL
            ),
            // Kumudam exposes a clean RSS 2.0 feed with current items + enclosure images.
            "kumudam.com" to NewsSource(
                id = "kumudam",
                name = "Kumudam",
                feedUrl = "https://kumudam.com/rss/latest-posts",
                siteUrl = "https://kumudam.com/",
                language = Language.TAMIL
            ),
            // Vikatan publishes its full feed via Feedburner (the on-site /rss URLs redirect
            // through Feedburner anyway).
            "vikatan.com" to NewsSource(
                id = "vikatan",
                name = "Vikatan",
                feedUrl = "https://feeds.feedburner.com/vikatan",
                siteUrl = "https://www.vikatan.com/",
                language = Language.TAMIL
            ),
            // TamilShowz has no RSS; route through Google News in Tamil so the bookmark
            // still contributes Tamil entertainment cards.
            "tamilshowz.net" to googleNewsForHost(
                id = "tamilshowz",
                name = "Tamilshowz",
                host = "tamilshowz.net",
                language = Language.TAMIL
            ),
            // -- English -------------------------------------------------------------------
            "espncricinfo.com" to NewsSource(
                id = "espncricinfo",
                name = "ESPNcricinfo",
                feedUrl = "https://www.espncricinfo.com/rss/content/story/feeds/0.xml",
                siteUrl = "https://www.espncricinfo.com/",
                language = Language.ENGLISH
            ),
            "thehindu.com" to NewsSource(
                id = "thehindu",
                name = "The Hindu",
                feedUrl = "https://www.thehindu.com/news/national/feeder/default.rss",
                siteUrl = "https://www.thehindu.com/",
                language = Language.ENGLISH
            ),
            "timesofindia.indiatimes.com" to NewsSource(
                id = "toi",
                name = "Times of India",
                feedUrl = "https://timesofindia.indiatimes.com/rssfeedstopstories.cms",
                siteUrl = "https://timesofindia.indiatimes.com/",
                language = Language.ENGLISH
            ),
            "indiatimes.com" to NewsSource(
                id = "toi",
                name = "Times of India",
                feedUrl = "https://timesofindia.indiatimes.com/rssfeedstopstories.cms",
                siteUrl = "https://timesofindia.indiatimes.com/",
                language = Language.ENGLISH
            ),
            "ndtv.com" to NewsSource(
                id = "ndtv",
                name = "NDTV",
                feedUrl = "https://feeds.feedburner.com/ndtvnews-top-stories",
                siteUrl = "https://www.ndtv.com/",
                language = Language.ENGLISH
            ),
            // Cricbuzz has no public RSS; pull cricket stories via Google News.
            "cricbuzz.com" to googleNewsForHost(
                id = "cricbuzz",
                name = "Cricbuzz",
                host = "cricbuzz.com",
                language = Language.ENGLISH
            )
        )

        /**
         * Fallback feed list for installs where none of the user's bookmarks map to a known
         * feed. Intentionally a Tamil + English mix so the page is never empty out of the box
         * regardless of which language the user reads in. All sources here are verified to
         * return live items as of 2026-05.
         */
        val DEFAULTS: List<NewsSource> = listOfNotNull(
            // Tamil
            KNOWN_FEEDS_BY_HOST["kumudam.com"],
            KNOWN_FEEDS_BY_HOST["vikatan.com"],
            KNOWN_FEEDS_BY_HOST["dailythanthi.com"],
            // English
            KNOWN_FEEDS_BY_HOST["thehindu.com"],
            KNOWN_FEEDS_BY_HOST["ndtv.com"],
            KNOWN_FEEDS_BY_HOST["espncricinfo.com"]
        )

        /**
         * Normalize a URL's host to the form used as the [KNOWN_FEEDS_BY_HOST] key — lowercase,
         * `www.` stripped. Returns null for malformed / non-network URLs (e.g. `chrome://`,
         * `file:///...`).
         */
        fun hostKey(url: String?): String? {
            if (url.isNullOrBlank()) return null
            val raw = runCatching { Uri.parse(url).host }.getOrNull() ?: return null
            return raw.lowercase().removePrefix("www.")
        }

        /**
         * Synthesize a [NewsSource] backed by Google News' public RSS `site:` search for the
         * given host. Used as a universal fallback for bookmarks whose publishers don't expose
         * an RSS feed of their own.
         *
         * The feed Google returns contains items from that single host only, in the requested
         * locale. Item links go through `news.google.com` and 302 to the publisher; we treat
         * them as regular URLs and let WebView follow the redirect. No API key, no signed
         * params — the URL is the same one you'd get by typing `site:host` into Google News.
         */
        fun googleNewsForHost(
            id: String,
            name: String,
            host: String,
            language: Language = Language.ENGLISH
        ): NewsSource {
            val (hl, ceid) = when (language) {
                Language.TAMIL -> "ta-IN" to "IN:ta"
                Language.ENGLISH -> "en-IN" to "IN:en"
            }
            val q = Uri.encode("site:$host")
            val feedUrl = "https://news.google.com/rss/search?q=$q&hl=$hl&gl=IN&ceid=$ceid"
            return NewsSource(
                id = id,
                name = name,
                feedUrl = feedUrl,
                siteUrl = "https://$host/",
                language = language
            )
        }

        /**
         * Build a synthesized Google-News-backed source for an unknown bookmark host. Used by
         * [NewsRepository] when the user's bookmark doesn't match any entry in
         * [KNOWN_FEEDS_BY_HOST]; ensures every bookmark contributes at least *something*.
         */
        fun fallbackForHost(host: String, language: Language = Language.ENGLISH): NewsSource {
            // Derive a friendly title from the host: "tamilshowz.net" -> "Tamilshowz".
            val display = host
                .removePrefix("www.")
                .substringBefore('.')
                .replaceFirstChar { if (it.isLetter()) it.titlecase(Locale.ROOT) else it.toString() }
            return googleNewsForHost(
                id = "gnews-$host",
                name = display,
                host = host,
                language = language
            )
        }
    }
}
