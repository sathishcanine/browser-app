package com.browser.minnal.news

import android.app.Application
import android.net.Uri
import com.browser.minnal.database.bookmark.BookmarkRepository
import com.browser.minnal.log.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aggregates RSS feeds from [NewsSource.DEFAULTS] into a single de-duplicated, time-sorted feed
 * for the home page.
 *
 * Threading model:
 *  - Every feed fetch runs on a small dedicated thread pool (one fetch per feed in parallel).
 *  - The page calls [snapshot] from the JS bridge thread; that returns whatever's already in
 *    memory without ever blocking on the network.
 *  - The first call to [refreshAsync] kicks off a background fetch and writes the result to
 *    both the in-memory cache and disk on completion.
 *
 * Cache strategy (stale-while-revalidate):
 *  - On process start the disk cache is hydrated lazily on the first [snapshot] call so the page
 *    has *something* to render even before any network call lands.
 *  - The page polls / refreshes; when fresh data arrives the page swaps it in.
 *  - "Fresh" is decided by [FRESH_AFTER_MILLIS]; older snapshots still render but a refresh is
 *    triggered in the background.
 */
@Singleton
class NewsRepository @Inject constructor(
    private val application: Application,
    private val bookmarkRepository: BookmarkRepository,
    private val logger: Logger
) {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val executor: ExecutorService by lazy {
        // One worker per source plus headroom for the orchestrator that submits + awaits them.
        // Without this, the orchestrator's blocking `f.get()` calls hold a worker thread and we
        // end up serializing fetches behind a too-small pool.
        Executors.newFixedThreadPool(WORKER_THREADS)
    }

    private val items = ConcurrentHashMap<String, NewsItem>()

    @Volatile private var lastUpdatedMs: Long = 0L
    @Volatile private var hydrated: Boolean = false
    private val refreshing = AtomicBoolean(false)

    /**
     * Most recently resolved set of sources. Recomputed at the start of each [refreshAsync]
     * cycle so newly-added bookmarks influence the next refresh — but cached between refreshes
     * so [snapshot] doesn't pay for a SQLite hop on every poll from the page.
     */
    @Volatile
    private var resolvedSources: List<NewsSource> = NewsSource.DEFAULTS

    /**
     * Returns the cached feed (newest first). Hydrates from disk on first call so the home page
     * sees instant content even before the first network refresh completes. If the cache is
     * older than [FRESH_AFTER_MILLIS] this also fires off a background refresh.
     */
    @Synchronized
    fun snapshot(): List<NewsItem> {
        if (!hydrated) {
            hydrateFromDiskUnsafe()
            hydrated = true
        }
        if (System.currentTimeMillis() - lastUpdatedMs > FRESH_AFTER_MILLIS) {
            refreshAsync()
        }
        return sortedSnapshot()
    }

    /**
     * Schedules a background refresh of every configured feed. Coalesces concurrent calls so
     * tapping "Refresh" 5 times in a row triggers exactly one wave of fetches.
     */
    fun refreshAsync(onComplete: (() -> Unit)? = null) {
        if (!refreshing.compareAndSet(false, true)) {
            onComplete?.invoke()
            return
        }
        executor.execute {
            try {
                resolvedSources = resolveSourcesFromBookmarks()
                logger.log(TAG, "refresh: ${resolvedSources.size} sources -> " +
                    resolvedSources.joinToString { it.id })
                // Drop any cached items whose source is no longer active (e.g. user removed a
                // bookmark). Keeps the page from showing stale rows from feeds we no longer want.
                val activeIds = resolvedSources.mapTo(HashSet()) { it.id }
                items.entries.removeAll { (_, value) -> value.source.id !in activeIds }
                fetchAll()
                lastUpdatedMs = System.currentTimeMillis()
                persistToDisk()
            } catch (t: Throwable) {
                logger.log(TAG, "refreshAsync failed", t)
            } finally {
                refreshing.set(false)
                onComplete?.invoke()
            }
        }
    }

    /**
     * Read all of the user's bookmarks, map their hosts to RSS feeds, and dedupe. Resolution
     * order per bookmark:
     *
     *  1. Exact match in [NewsSource.KNOWN_FEEDS_BY_HOST] — the curated registry, where we use
     *     the publisher's own feed when one exists or a vetted Google News fallback when it
     *     doesn't.
     *  2. Synthesized Google News `site:` feed via [NewsSource.fallbackForHost] — so any
     *     bookmark contributes *something*, even if we've never seen the host before. Capped
     *     at [MAX_SYNTHESIZED_SOURCES] to bound per-refresh network load for users with
     *     hundreds of bookmarks.
     *
     * Falls back to [NewsSource.DEFAULTS] when the user has no bookmarks at all so the page
     * never goes blank on a fresh install.
     *
     * SQLite read happens on the executor thread (a worker thread), which is fine since
     * [BookmarkRepository.getAllBookmarksSorted] is synchronous-on-call via blockingGet.
     */
    private fun resolveSourcesFromBookmarks(): List<NewsSource> {
        val bookmarks = runCatching {
            bookmarkRepository.getAllBookmarksSorted().blockingGet().orEmpty()
        }.getOrElse {
            logger.log(TAG, "bookmark lookup failed", it)
            return NewsSource.DEFAULTS
        }
        if (bookmarks.isEmpty()) return NewsSource.DEFAULTS

        // LinkedHashMap keyed by source id keeps the user's bookmark order ("first bookmark
        // wins") while letting several hosts collapse onto the same source (e.g. both
        // timesofindia.indiatimes.com and indiatimes.com map to "toi").
        val resolved = LinkedHashMap<String, NewsSource>(bookmarks.size)
        var synthesized = 0
        for (entry in bookmarks) {
            val key = NewsSource.hostKey(entry.url) ?: continue
            val known = NewsSource.KNOWN_FEEDS_BY_HOST[key]
            val src = when {
                known != null -> known
                synthesized < MAX_SYNTHESIZED_SOURCES -> {
                    synthesized++
                    NewsSource.fallbackForHost(key, guessLanguageFor(key, entry.title))
                }
                else -> continue
            }
            resolved.putIfAbsent(src.id, src)
        }
        return if (resolved.isEmpty()) NewsSource.DEFAULTS else resolved.values.toList()
    }

    /**
     * Cheap heuristic for picking the Google News locale when we have to synthesize a feed for
     * an unknown bookmark. Returns Tamil when the bookmark title contains any Tamil character
     * or the host clearly belongs to a Tamil publisher (`tamil*`, `*tamil*`), English in every
     * other case. The wrong language here only changes which Google News locale's RSS we ask
     * for — articles from the actual site are still returned either way, just sorted by a
     * different default.
     */
    private fun guessLanguageFor(host: String, title: String?): NewsSource.Language {
        val tamilRange = '\u0B80'..'\u0BFF'
        if (title?.any { it in tamilRange } == true) return NewsSource.Language.TAMIL
        val lowerHost = host.lowercase()
        if (lowerHost.contains("tamil") || lowerHost.endsWith(".ta") ||
            lowerHost.endsWith(".in") && (lowerHost.contains("dina") ||
                lowerHost.contains("malar") || lowerHost.contains("hindu"))
        ) {
            return NewsSource.Language.TAMIL
        }
        return NewsSource.Language.ENGLISH
    }

    /** Wall-clock millis when the in-memory cache was last refreshed. 0 if never. */
    fun lastUpdatedAt(): Long = lastUpdatedMs

    /** True while a background refresh is in flight. */
    fun isRefreshing(): Boolean = refreshing.get()

    /** Sources currently being fetched from. Useful for diagnostics surfaced on the page. */
    fun currentSources(): List<NewsSource> = resolvedSources

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private fun fetchAll() {
        val targets = resolvedSources
        val futures = targets.map { source ->
            executor.submit { fetchOne(source) }
        }
        // Wait for all of them; one slow feed shouldn't hold us forever, hence the per-future
        // timeout. Timed-out fetches just contribute 0 items this cycle.
        for ((i, f) in futures.withIndex()) {
            runCatching { f.get(20, TimeUnit.SECONDS) }
                .onFailure { logger.log(TAG, "fetch ${targets[i].id} timed out / errored", it) }
        }
    }

    private fun fetchOne(source: NewsSource) {
        val req = Request.Builder()
            .url(source.feedUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
            .header("Accept-Language", "en-IN,en;q=0.9,ta;q=0.8")
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    logger.log(TAG, "fetch ${source.id}: HTTP ${resp.code}")
                    return
                }
                val body = resp.body ?: run {
                    logger.log(TAG, "fetch ${source.id}: empty body")
                    return
                }
                val parsed = body.byteStream().use { RssParser.parse(it, source) }
                val accepted = parsed.take(MAX_ITEMS_PER_SOURCE)
                for (item in accepted) {
                    val cleaned = item
                        .copy(title = cleanItemTitle(item.title, source))
                        .withDiscoverThumbnail()
                    items[itemKey(cleaned)] = cleaned
                }
                logger.log(TAG, "fetch ${source.id}: +${accepted.size} items")
            }
        } catch (e: IOException) {
            logger.log(TAG, "fetch ${source.id}: ${e.message}")
        } catch (t: Throwable) {
            logger.log(TAG, "fetch ${source.id}: unexpected", t)
        }
    }

    private fun itemKey(item: NewsItem): String = item.url

    /**
     * Uses only what the RSS/Atom item already exposes (normalized), then a static favicon URL
     * derived from the publisher host — no undocumented APIs and no scraping publisher pages for
     * images (conservative for Play review and third-party terms).
     */
    private fun NewsItem.withDiscoverThumbnail(): NewsItem {
        val n = RssParser.normalizeImageUrl(imageUrl)
        if (!n.isNullOrBlank()) return copy(imageUrl = n)
        return faviconFallback(this)
    }

    private fun faviconFallback(item: NewsItem): NewsItem {
        val host = NewsSource.hostKey(
            item.source.siteUrl?.takeIf { it.isNotBlank() } ?: item.url
        ) ?: return item.copy(imageUrl = null)
        return item.copy(
            imageUrl = "https://www.google.com/s2/favicons?sz=256&domain=${Uri.encode(host)}"
        )
    }

    /**
     * Strip the redundant ` - <Publisher>` (or `– <Publisher>`) suffix Google News appends to
     * every title in its `site:` search RSS. The publisher name is already shown beneath each
     * card via [NewsSource.name], so leaving it in the title duplicates information and eats
     * 15-25 characters from already long Tamil/English headlines. No-ops when the suffix is
     * absent (e.g. publishers' own feeds).
     */
    private fun cleanItemTitle(title: String, source: NewsSource): String {
        val trimmed = title.trim()
        // Match both ASCII hyphen and Unicode en-dash, with whitespace on either side.
        val candidates = listOf(" - ${source.name}", " – ${source.name}")
        for (suffix in candidates) {
            if (trimmed.endsWith(suffix, ignoreCase = true)) {
                return trimmed.dropLast(suffix.length).trim()
            }
        }
        return trimmed
    }

    private fun sortedSnapshot(): List<NewsItem> = items.values
        .sortedByDescending { it.publishedAt }
        .take(MAX_TOTAL_ITEMS)

    // -- Disk cache (single JSON file) -------------------------------------------------------

    private fun cacheFile(): File {
        val dir = File(application.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
        return File(dir, CACHE_FILE_NAME)
    }

    private fun hydrateFromDiskUnsafe() {
        val file = cacheFile()
        if (!file.exists() || file.length() == 0L) return
        runCatching {
            val raw = file.readText(Charsets.UTF_8)
            val root = JSONObject(raw)
            lastUpdatedMs = root.optLong("updatedAt", 0L)
            val arr = root.optJSONArray("items") ?: return
            // Hydrate against every source we know about, not just the currently resolved set —
            // a freshly-launched process may have cached items from a bookmark that's now gone
            // and we'd rather show them once than blank the page until the next refresh.
            val sourceById = NewsSource.KNOWN_FEEDS_BY_HOST.values
                .associateBy { source -> source.id }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sourceId = o.optString("sourceId").takeIf { it.isNotBlank() } ?: continue
                val src = sourceById[sourceId] ?: continue
                val title = o.optString("title").takeIf { it.isNotBlank() } ?: continue
                val url = o.optString("url").takeIf { it.isNotBlank() } ?: continue
                val item = NewsItem(
                    title = title,
                    url = url,
                    source = src,
                    publishedAt = o.optLong("publishedAt", System.currentTimeMillis()),
                    summary = o.optString("summary").takeIf { it.isNotBlank() },
                    imageUrl = o.optString("imageUrl").takeIf { it.isNotBlank() }
                ).withDiscoverThumbnail()
                items[itemKey(item)] = item
            }
        }.onFailure { logger.log(TAG, "hydrate failed", it) }
    }

    private fun persistToDisk() {
        val arr = JSONArray()
        for (item in sortedSnapshot()) {
            val o = JSONObject()
                .put("title", item.title)
                .put("url", item.url)
                .put("sourceId", item.source.id)
                .put("publishedAt", item.publishedAt)
            item.summary?.let { o.put("summary", it) }
            item.imageUrl?.let { o.put("imageUrl", it) }
            arr.put(o)
        }
        val root = JSONObject()
            .put("updatedAt", lastUpdatedMs)
            .put("items", arr)
        runCatching {
            cacheFile().writeText(root.toString(), Charsets.UTF_8)
        }.onFailure { logger.log(TAG, "persist failed", it) }
    }

    companion object {
        private const val TAG = "NewsRepository"
        private const val CACHE_DIR_NAME = "news"
        private const val CACHE_FILE_NAME = "feed.json"
        // Worker pool size: one thread per source we ever expect to fetch from in parallel,
        // plus headroom for the orchestrator that submits + blocks on them. Higher than this
        // would burn battery; lower than this and the orchestrator's blocking `f.get()` calls
        // serialize fetches behind a small pool.
        private const val WORKER_THREADS = 12
        private const val MAX_ITEMS_PER_SOURCE = 12
        private const val MAX_TOTAL_ITEMS = 60
        private const val FRESH_AFTER_MILLIS = 15L * 60L * 1000L // 15 minutes
        // Cap on how many unknown-host fallback feeds we'll synthesize per refresh. Each adds
        // one HTTP request to news.google.com, so we don't want a user with 200 bookmarks to
        // turn every refresh into a 200-fan-out fetch. 8 is enough to cover a "homepage plus
        // a handful of bookmarked sites" install while staying gentle on the battery.
        private const val MAX_SYNTHESIZED_SOURCES = 8
        // Many publishers (Cloudflare / Akamai-fronted in particular) return 403 to obvious
        // "bot" User-Agents. We pretend to be Chrome on Android — the only thing this changes
        // for the publisher is whether the response is delivered; we still send no cookies, no
        // identifiers, no advertising id, etc.
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36 Minnal/1.0"
    }
}
