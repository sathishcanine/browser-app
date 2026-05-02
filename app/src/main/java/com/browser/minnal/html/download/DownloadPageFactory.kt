package com.browser.minnal.html.download

import com.browser.minnal.R
import com.browser.minnal.browser.theme.ThemeProvider
import com.browser.minnal.constant.FILE
import com.browser.minnal.database.downloads.DownloadEntry
import com.browser.minnal.database.downloads.DownloadsRepository
import com.browser.minnal.html.HtmlPageFactory
import com.browser.minnal.html.ListPageReader
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
import com.browser.minnal.preference.UserPreferences
import android.app.Application
import io.reactivex.rxjava3.core.Single
import java.io.File
import java.io.FileWriter
import javax.inject.Inject

/**
 * The factory for the downloads page.
 */
class DownloadPageFactory @Inject constructor(
    private val application: Application,
    private val userPreferences: UserPreferences,
    private val manager: DownloadsRepository,
    private val listPageReader: ListPageReader,
    private val themeProvider: ThemeProvider
) : HtmlPageFactory {

    private fun Int.toColor(): String {
        val string = Integer.toHexString(this)

        return string.substring(2) + string.substring(0, 2)
    }

    private val backgroundColor: String
        get() = themeProvider.color(R.attr.colorPrimary).toColor()
    private val dividerColor: String
        get() = themeProvider.color(R.attr.autoCompleteBackgroundColor).toColor()
    private val textColor: String
        get() = themeProvider.color(R.attr.autoCompleteTitleColor).toColor()
    private val subtitleColor: String
        get() = themeProvider.color(R.attr.autoCompleteUrlColor).toColor()

    override fun buildPage(): Single<String> = manager
        .getAllDownloads()
        .map { list ->
            parse(listPageReader.provideHtml()) andBuild {
                title { application.getString(R.string.action_downloads) }
                style { content ->
                    content.replace("--body-bg: {COLOR}", "--body-bg: #$backgroundColor;")
                        .replace("--divider-color: {COLOR}", "--divider-color: #$dividerColor;")
                        .replace("--title-color: {COLOR}", "--title-color: #$textColor;")
                        .replace("--subtitle-color: {COLOR}", "--subtitle-color: #$subtitleColor;")
                }
                body {
                    val repeatableElement = findId("repeated").removeElement()
                    id("content") {
                        list.forEach {
                            appendChild(repeatableElement.clone {
                                tag("a") { attr("href", createFileUrl(it.title)) }
                                id("title") { text(createFileTitle(it)) }
                                id("url") { text(it.url) }
                            })
                        }
                    }
                }
            }
        }
        .map { content -> Pair(createDownloadsPageFile(), content) }
        .doOnSuccess { (page, content) ->
            FileWriter(page, false).use { it.write(content) }
        }
        .map { (page, _) -> "$FILE$page" }


    private fun createDownloadsPageFile(): File {
        val generatedHtml = File(application.filesDir, "generated-html")
        generatedHtml.mkdirs()
        return File(generatedHtml, FILENAME)
    }

    private fun createFileUrl(fileName: String): String =
        "$FILE${userPreferences.downloadDirectory}/$fileName"

    private fun createFileTitle(downloadItem: DownloadEntry): String {
        val contentSize = if (downloadItem.contentSize.isNotBlank()) {
            "[${downloadItem.contentSize}]"
        } else {
            ""
        }

        return "${downloadItem.title} $contentSize"
    }

    companion object {

        const val FILENAME = "downloads.html"

    }

}
