package com.browser.minnal.news

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Tiny RSS 2.0 / Atom parser implemented on top of [XmlPullParser].
 *
 * Why not pull in a library:
 *  - We only need title / link / description / pubDate / image, and the variations across feeds
 *    are surprisingly mild for those tags.
 *  - The whole project deliberately avoids extra dependencies (already true for downloads).
 *  - Anything fancier (full Atom support, content:encoded namespaces) we treat as best-effort:
 *    if we can't parse a date we fall back to "now"; if we can't find an image we leave it null
 *    and the UI uses a typography-only card.
 *
 * Failure mode: errors are swallowed inside [parse]; we return whatever items were valid before
 * the error so a partially-broken feed still contributes some cards.
 */
internal object RssParser {

    fun parse(input: InputStream, source: NewsSource): List<NewsItem> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, /* encoding inferred from XML prolog */ null)
        }
        val items = mutableListOf<NewsItem>()
        var event = runCatching { parser.eventType }.getOrElse { return items }
        // Cheap RSS-vs-Atom dispatch: we look for the first <item> (RSS) or <entry> (Atom) tag.
        while (event != XmlPullParser.END_DOCUMENT) {
            try {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name?.lowercase(Locale.ROOT)) {
                        "item" -> readRssItem(parser, source)?.let(items::add)
                        "entry" -> readAtomEntry(parser, source)?.let(items::add)
                    }
                }
                event = parser.next()
            } catch (_: Throwable) {
                // Keep what we have. Some feeds emit invalid XML mid-stream (mis-encoded ampersands
                // are by far the most common offender); recover by bailing.
                break
            }
        }
        return items
    }

    private fun readRssItem(parser: XmlPullParser, source: NewsSource): NewsItem? {
        var title: String? = null
        var link: String? = null
        var description: String? = null
        var pubDateText: String? = null
        var imageUrl: String? = null
        val itemDepth = parser.depth

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.depth == itemDepth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name.lowercase(Locale.ROOT)) {
                    "title" -> title = readText(parser)?.trimToHtmlPlainText()
                    "link" -> link = readText(parser)
                    "description", "content:encoded" -> {
                        val raw = readText(parser)
                        // Salvage <img src> *before* stripping tags — otherwise we erase the only
                        // thumbnail many feeds embed inside HTML descriptions (Google News often
                        // omits images entirely, but publisher RSS commonly uses this pattern).
                        if (imageUrl == null && raw != null) imageUrl = extractFirstImageUrl(raw)
                        description = raw?.trimToHtmlPlainText()
                    }
                    "pubdate", "dc:date" -> pubDateText = readText(parser)
                    // ESPN Cricinfo (and a few others) ship a bare HTTPS hero URL here while
                    // <media:content> still points at legacy http:// on the same CDN.
                    "coverimages" -> {
                        val text = readText(parser)?.trim()
                        if (!text.isNullOrBlank() && imageUrl == null) imageUrl = text
                    }
                    "enclosure" -> {
                        // <enclosure url="..." type="image/..." />
                        val type = parser.getAttributeValue(null, "type")
                        val url = parser.getAttributeValue(null, "url")
                        if (type?.startsWith("image/", ignoreCase = true) == true &&
                            !url.isNullOrBlank()) {
                            imageUrl = url
                        }
                        skip(parser)
                    }
                    // With FEATURE_PROCESS_NAMESPACES=false the parser often reports the local
                    // name only ("content", "thumbnail") instead of "media:content".
                    "media:thumbnail", "media:content", "thumbnail" -> {
                        val url = parser.getAttributeValue(null, "url")
                        if (!url.isNullOrBlank() && imageUrl == null) imageUrl = url
                        skip(parser)
                    }
                    "content" -> {
                        val medium = parser.getAttributeValue(null, "medium")
                        val url = parser.getAttributeValue(null, "url")
                        if (medium == "image" && !url.isNullOrBlank() && imageUrl == null) {
                            imageUrl = url
                        }
                        skip(parser)
                    }
                }
            }
            try {
                parser.next()
            } catch (_: Throwable) {
                break
            }
        }
        if (title.isNullOrBlank() || link.isNullOrBlank()) return null
        return NewsItem(
            title = title,
            url = link,
            source = source,
            publishedAt = parsePubDate(pubDateText),
            summary = description?.takeIf { it.isNotBlank() },
            imageUrl = normalizeImageUrl(imageUrl)
        )
    }

    private fun readAtomEntry(parser: XmlPullParser, source: NewsSource): NewsItem? {
        var title: String? = null
        var link: String? = null
        var summary: String? = null
        var publishedText: String? = null
        var imageUrl: String? = null
        val entryDepth = parser.depth

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.depth == entryDepth)) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name.lowercase(Locale.ROOT)) {
                    "title" -> title = readText(parser)?.trimToHtmlPlainText()
                    "link" -> {
                        // Atom <link href="..." rel="alternate"/>
                        val href = parser.getAttributeValue(null, "href")
                        val rel = parser.getAttributeValue(null, "rel")
                        if (!href.isNullOrBlank() && (rel == null || rel == "alternate") &&
                            link == null) {
                            link = href
                        }
                        skip(parser)
                    }
                    "summary" -> {
                        val raw = readText(parser)
                        if (imageUrl == null && raw != null) imageUrl = extractFirstImageUrl(raw)
                        summary = raw?.trimToHtmlPlainText()
                    }
                    "content" -> {
                        val medium = parser.getAttributeValue(null, "medium")
                        val url = parser.getAttributeValue(null, "url")
                        if (medium == "image" && !url.isNullOrBlank() && imageUrl == null) {
                            imageUrl = url
                            skip(parser)
                        } else {
                            val raw = readText(parser)
                            if (imageUrl == null && raw != null) imageUrl = extractFirstImageUrl(raw)
                            summary = raw?.trimToHtmlPlainText()
                        }
                    }
                    "published", "updated" -> publishedText = readText(parser)
                    "media:thumbnail", "media:content", "thumbnail" -> {
                        val url = parser.getAttributeValue(null, "url")
                        if (!url.isNullOrBlank() && imageUrl == null) imageUrl = url
                        skip(parser)
                    }
                }
            }
            try {
                parser.next()
            } catch (_: Throwable) {
                break
            }
        }
        if (title.isNullOrBlank() || link.isNullOrBlank()) return null
        return NewsItem(
            title = title,
            url = link,
            source = source,
            publishedAt = parsePubDate(publishedText),
            summary = summary?.takeIf { it.isNotBlank() },
            imageUrl = normalizeImageUrl(imageUrl)
        )
    }

    private fun readText(parser: XmlPullParser): String? {
        var text: String? = null
        if (parser.next() == XmlPullParser.TEXT) {
            text = parser.text
            parser.next()
        }
        return text?.trim()
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
            }
        }
    }

    /** Quick-and-dirty HTML → plain-text. Only handles the cases we actually see in feeds. */
    private fun String.trimToHtmlPlainText(): String =
        replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("&#039;|&apos;"), "'")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("\\s+"), " ")
            .trim()

    private val IMG_SRC_REGEX = Regex(
        "<img[^>]*\\ssrc\\s*=\\s*(?:[\"']([^\"']+)[\"']|([^\\s>]+))",
        RegexOption.IGNORE_CASE
    )

    private fun extractFirstImageUrl(html: String): String? {
        val m = IMG_SRC_REGEX.find(html) ?: return null
        return m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
    }

    /**
     * Normalizes image URLs for WebView loads: protocol-relative → https, http → https (most
     * major CDNs—including ESPN's imgci—answer on TLS; keeping http breaks under stricter
     * WebView / mixed-content paths even when cleartext is globally allowed).
     */
    internal fun normalizeImageUrl(url: String?): String? {
        val u = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("http://", ignoreCase = true) -> "https://" + u.substring(7)
            else -> u
        }
    }

    /**
     * Tries the two date formats that 99% of feeds use (RFC-822 with named TZ, RFC-822 with
     * numeric TZ, ISO-8601). Returns "now" on failure rather than dropping the item, since the
     * UI sorts on this field.
     */
    private val DATE_FORMATS: List<SimpleDateFormat> = listOf(
        // RFC-822 (most RSS feeds)
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "dd MMM yyyy HH:mm:ss Z",
        // ISO-8601 (Atom)
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
    ).map {
        SimpleDateFormat(it, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    private fun parsePubDate(text: String?): Long {
        if (text.isNullOrBlank()) return System.currentTimeMillis()
        for (fmt in DATE_FORMATS) {
            val parsed: Date? = runCatching { fmt.parse(text) }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return System.currentTimeMillis()
    }
}
