package com.browser.minnal.html.bookmark

import com.browser.minnal.R
import com.browser.minnal.browser.di.DatabaseScheduler
import com.browser.minnal.browser.di.DiskScheduler
import com.browser.minnal.browser.theme.ThemeProvider
import com.browser.minnal.constant.FILE
import com.browser.minnal.database.Bookmark
import com.browser.minnal.database.bookmark.BookmarkRepository
import com.browser.minnal.extensions.safeUse
import com.browser.minnal.favicon.FaviconModel
import com.browser.minnal.favicon.toValidUri
import com.browser.minnal.html.HtmlPageFactory
import com.browser.minnal.html.jsoup.andBuild
import com.browser.minnal.html.jsoup.body
import com.browser.minnal.html.jsoup.clone
import com.browser.minnal.html.jsoup.findId
import com.browser.minnal.html.jsoup.id
import com.browser.minnal.html.jsoup.parse
import com.browser.minnal.html.jsoup.removeElement
import com.browser.minnal.html.jsoup.style
import com.browser.minnal.html.jsoup.tag
import com.browser.minnal.html.jsoup.title
import com.browser.minnal.search.SearchEngineProvider
import com.browser.minnal.utils.ThemeUtils
import android.app.Application
import android.graphics.Bitmap
import androidx.core.net.toUri
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import javax.inject.Inject

/**
 * Created by anthonycr on 9/23/18.
 */
class BookmarkPageFactory @Inject constructor(
    private val application: Application,
    private val bookmarkModel: BookmarkRepository,
    private val faviconModel: FaviconModel,
    @DatabaseScheduler private val databaseScheduler: Scheduler,
    @DiskScheduler private val diskScheduler: Scheduler,
    private val bookmarkPageReader: BookmarkPageReader,
    private val themeProvider: ThemeProvider,
    private val searchEngineProvider: SearchEngineProvider
) : HtmlPageFactory {

    private val title = application.getString(R.string.action_bookmarks)
    private val addShortcutLabel = application.getString(R.string.home_add_shortcut)
    private val folderIconFile by lazy {
        File(FaviconModel.faviconCacheFolder(application), FOLDER_ICON)
    }
    private val defaultIconFile by lazy {
        File(FaviconModel.faviconCacheFolder(application), DEFAULT_ICON)
    }
    private val tamilshowzTsTileFile by lazy {
        File(FaviconModel.faviconCacheFolder(application), TAMILSHOWZ_TS_TILE_FILE)
    }
    private val tamilplayTileFile by lazy {
        File(FaviconModel.faviconCacheFolder(application), TAMILPLAY_TILE_FILE)
    }

    private fun Int.toColor(): String {
        val string = Integer.toHexString(this)

        return string.substring(2) + string.substring(0, 2)
    }

    private val backgroundColor: String
        get() = themeProvider.color(R.attr.colorPrimary).toColor()
    private val cardColor: String
        get() = themeProvider.color(R.attr.autoCompleteBackgroundColor).toColor()
    private val textColor: String
        get() = themeProvider.color(R.attr.autoCompleteTitleColor).toColor()

    override fun buildPage(): Single<String> = bookmarkModel
        .getAllBookmarksSorted()
        .flattenAsObservable { it }
        .groupBy<Bookmark.Folder, Bookmark>(Bookmark.Entry::folder) { it }
        .flatMapSingle { bookmarksInFolder ->
            val folder = bookmarksInFolder.key
            return@flatMapSingle bookmarksInFolder
                .toList()
                .concatWith(
                    if (folder == Bookmark.Folder.Root) {
                        bookmarkModel.getFoldersSorted()
                            .map { it.filterIsInstance<Bookmark.Folder.Entry>() }
                    } else {
                        Single.just(emptyList())
                    }
                )
                .toList()
                .map { bookmarksAndFolders ->
                    Pair(folder, bookmarksAndFolders.flatten().map { it.asViewModel() })
                }
        }
        .map { (folder, viewModels) -> Pair(folder, construct(viewModels, folder == Bookmark.Folder.Root)) }
        .subscribeOn(databaseScheduler)
        .observeOn(diskScheduler)
        .doOnNext { (folder, content) ->
            FileWriter(createBookmarkPage(folder), false).use {
                it.write(content)
            }
        }
        .ignoreElements()
        .toSingle {
            cacheIcon(
                ThemeUtils.createThemedBitmap(
                    application,
                    R.drawable.ic_folder,
                    themeProvider.color(R.attr.autoCompleteTitleColor)
                ),
                folderIconFile
            )
            cacheIcon(faviconModel.createDefaultBitmapForTitle(null), defaultIconFile)

            "$FILE${createBookmarkPage(null)}"
        }

    private fun cacheIcon(icon: Bitmap, file: File) {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).safeUse {
                icon.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } catch (_: Exception) {
            // Best-effort; bookmark page can still load with a missing tile.
        } finally {
            if (!icon.isRecycled) {
                icon.recycle()
            }
        }
    }

    private fun construct(list: List<BookmarkViewModel>, showAddShortcut: Boolean): String {
        val searchEngine = searchEngineProvider.provideSearchEngine()
        val queryUrlForScript = searchEngine.queryUrl.replace("&", "\\u0026")
        return parse(bookmarkPageReader.provideHtml()) andBuild {
            title { title }
            style { content ->
                content.replace("--body-bg: {COLOR}", "--body-bg: #$backgroundColor;")
                    .replace("--box-bg: {COLOR}", "--box-bg: #$cardColor;")
                    .replace("--box-txt: {COLOR}", "--box-txt: #$textColor;")
                    .replace("--search-bar-bg: {COLOR}", "--search-bar-bg: #$cardColor;")
            }
            body {
                val repeatableElement = findId("repeated").removeElement()
                id("bookmark_search_engine_icon") { attr("src", searchEngine.iconUrl) }
                id("content") {
                    list.forEach {
                        appendChild(repeatableElement.clone {
                            tag("a") { attr("href", it.url) }
                            tag("img") { attr("src", it.iconUrl) }
                            id("title") { appendText(it.title) }
                        })
                    }
                    if (showAddShortcut) {
                        appendChild(
                            org.jsoup.nodes.Element("div").apply {
                                attr("class", "box add-shortcut")
                                attr("role", "button")
                                attr("tabindex", "0")
                                attr("onclick", "return lightningAddShortcut()")
                                appendElement("div").attr("class", "margin").appendElement("div")
                                    .attr("class", "box-content add-shortcut-inner").apply {
                                        appendElement("span")
                                            .attr("class", "add-shortcut-plus")
                                            .attr("aria-hidden", "true")
                                            .text("+")
                                        appendElement("p")
                                            .attr("class", "ellipses add-shortcut-label")
                                            .text(addShortcutLabel)
                                    }
                            }
                        )
                    }
                }
                tag("script") {
                    html(
                        html().replace("\${BASE_URL}", queryUrlForScript)
                    )
                }
            }
        }
    }

    private fun Bookmark.asViewModel(): BookmarkViewModel = when (this) {
        is Bookmark.Folder -> createViewModelForFolder(this)
        is Bookmark.Entry -> createViewModelForBookmark(this)
    }

    private fun createViewModelForFolder(folder: Bookmark.Folder): BookmarkViewModel {
        val folderPage = createBookmarkPage(folder)
        val url = "$FILE$folderPage"

        return BookmarkViewModel(
            title = folder.title,
            url = url,
            iconUrl = folderIconFile.toString()
        )
    }

    private fun createViewModelForBookmark(entry: Bookmark.Entry): BookmarkViewModel {
        val bookmarkUri = entry.url.toUri().toValidUri()

        val iconUrl = when {
            isTamilshowzHomeBookmark(entry.url) -> {
                if (!tamilshowzTsTileFile.exists()) {
                    cacheIcon(
                        faviconModel.createDefaultBitmapForLabel(TAMILSHOWZ_TILE_LABEL),
                        tamilshowzTsTileFile
                    )
                }
                tamilshowzTsTileFile
            }
            isTamilplayHomeBookmark(entry.url) -> {
                if (!tamilplayTileFile.exists()) {
                    cacheIcon(
                        faviconModel.createDefaultBitmapForLabel(TAMILPLAY_TILE_LABEL),
                        tamilplayTileFile
                    )
                }
                tamilplayTileFile
            }
            isDailyThanthiHomeBookmark(entry.url) -> DAILY_THANTHI_BOOKMARK_ICON_URL
            isDinamalarHomeBookmark(entry.url) -> DINAMALAR_BOOKMARK_ICON_URL
            isBehindwoodsHomeBookmark(entry.url) -> BEHINDWOODS_BOOKMARK_ICON_URL
            isCricbuzzHomeBookmark(entry.url) -> CRICBUZZ_BOOKMARK_ICON_URL
            isEspncricinfoHomeBookmark(entry.url) -> ESPNCRICINFO_BOOKMARK_ICON_URL
            bookmarkUri != null -> {
                val faviconFile = FaviconModel.getFaviconCacheFile(application, bookmarkUri)
                if (!faviconFile.exists()) {
                    val defaultFavicon = faviconModel.createDefaultBitmapForTitle(entry.title)
                    faviconModel.cacheFaviconForUrl(defaultFavicon, entry.url)
                        .subscribeOn(diskScheduler)
                        .subscribe()
                }

                faviconFile
            }

            else -> defaultIconFile
        }

        return BookmarkViewModel(
            title = entry.title,
            url = entry.url,
            iconUrl = iconUrl.toString()
        )
    }

    private fun isTamilshowzHomeBookmark(url: String): Boolean {
        val host = try {
            url.toUri().host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false
        return host == "www.tamilshowz.net" || host == "tamilshowz.net"
    }

    private fun isTamilplayHomeBookmark(url: String): Boolean {
        val host = try {
            url.toUri().host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false
        return host == "www.tamilkollymovies.xyz" || host == "tamilkollymovies.xyz"
    }

    private fun isDailyThanthiHomeBookmark(url: String): Boolean {
        val host = try {
            url.toUri().host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false
        return host == "www.dailythanthi.com" || host == "dailythanthi.com"
    }

    private fun isDinamalarHomeBookmark(url: String): Boolean {
        val host = try {
            url.toUri().host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false
        return host == "www.dinamalar.com" || host == "dinamalar.com"
    }

    private fun isBehindwoodsHomeBookmark(url: String): Boolean {
        val host = try {
            url.toUri().host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false
        return host == "www.behindwoods.com" || host == "behindwoods.com"
    }

    private fun isCricbuzzHomeBookmark(url: String): Boolean {
        val host = try {
            url.toUri().host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false
        return host == "www.cricbuzz.com" || host == "cricbuzz.com"
    }

    private fun isEspncricinfoHomeBookmark(url: String): Boolean {
        val host = try {
            url.toUri().host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false
        return host == "www.espncricinfo.com" || host == "espncricinfo.com"
    }

    /**
     * Create the bookmark page file.
     */
    fun createBookmarkPage(folder: Bookmark.Folder?): File {
        val prefix = if (folder?.title?.isNotBlank() == true) {
            "${folder.title}-"
        } else {
            ""
        }
        val generatedHtml = File(application.filesDir, "generated-html")
        generatedHtml.mkdirs()
        return File(generatedHtml, prefix + FILENAME)
    }

    companion object {

        const val FILENAME = "bookmarks.html"

        private const val FOLDER_ICON = "folder.png"
        private const val DEFAULT_ICON = "default.png"

        private const val TAMILSHOWZ_TS_TILE_FILE = "bookmark_tile_tamilshowz_ts.png"
        private const val TAMILSHOWZ_TILE_LABEL = "TS"
        private const val TAMILPLAY_TILE_FILE = "bookmark_tile_tamilplay_tp.png"
        private const val TAMILPLAY_TILE_LABEL = "TP"

        private const val DAILY_THANTHI_BOOKMARK_ICON_URL = "https://www.dailythanthi.com/favicon.ico"

        private const val DINAMALAR_BOOKMARK_ICON_URL =
            "https://stat.dinamalar.com/new/2018/images/dinamalar-app-icon.jpg"

        private const val BEHINDWOODS_BOOKMARK_ICON_URL =
            "https://www.behindwoods.com/images/bw-logo-org.png"

        private const val CRICBUZZ_BOOKMARK_ICON_URL =
            "https://imgcdn.latestmodapks.com/api/resize?url=https://www.latestmodapks.com/wp-content/uploads/2022/12/Cricbuzz-Logo.png&width=160"

        /** ESPN / cricinfo CDN (site HTML is often blocked to simple fetches; this asset loads reliably). */
        private const val ESPNCRICINFO_BOOKMARK_ICON_URL =
            "https://img1.hscicdn.com/image/upload/f_auto,t_ds_square_w_80/lsci/db/PICTURES/CMS/348000/348090.png"
    }
}
