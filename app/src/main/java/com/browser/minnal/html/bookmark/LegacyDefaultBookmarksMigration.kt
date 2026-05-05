package com.browser.minnal.html.bookmark

import com.browser.minnal.database.bookmark.BookmarkRepository
import com.browser.minnal.migration.Cleanup
import com.browser.minnal.preference.UserPreferences
import io.reactivex.rxjava3.core.Completable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Migrates shipped default bookmarks for existing installs (new installs use `default_bookmarks.dat`).
 */
class LegacyDefaultBookmarksMigration @Inject constructor(
    private val bookmarkRepository: BookmarkRepository,
    private val userPreferences: UserPreferences,
) : Cleanup.Action {

    override val versionCode: Int = 102

    override suspend fun execute() {
        withContext(Dispatchers.IO) {
            if (!userPreferences.legacyDefaultBookmarksMigrated) {
                val replaceChangeLog = bookmarkRepository
                    .findBookmarkForUrl(LEGACY_CHANGE_LOG_URL)
                    .flatMapCompletable { old ->
                        bookmarkRepository.editBookmark(
                            old,
                            old.copy(
                                url = DAILY_THANTHI_URL,
                                title = DAILY_THANTHI_TITLE
                            )
                        )
                    }
                    .onErrorComplete()

                val replaceContact = bookmarkRepository
                    .findBookmarkForUrl(LEGACY_CONTACT_URL)
                    .flatMapCompletable { old ->
                        bookmarkRepository.editBookmark(
                            old,
                            old.copy(
                                url = DINAMALAR_URL,
                                title = DINAMALAR_TITLE
                            )
                        )
                    }
                    .onErrorComplete()

                Completable.mergeArray(replaceChangeLog, replaceContact).blockingAwait()
                userPreferences.legacyDefaultBookmarksMigrated = true
            }

            if (!userPreferences.legacyWikipediaToBehindwoodsMigrated) {
                bookmarkRepository
                    .findBookmarkForUrl(LEGACY_WIKIPEDIA_WWW_URL)
                    .switchIfEmpty(bookmarkRepository.findBookmarkForUrl(LEGACY_WIKIPEDIA_ROOT_URL))
                    .flatMapCompletable { old ->
                        bookmarkRepository.editBookmark(
                            old,
                            old.copy(
                                url = BEHINDWOODS_URL,
                                title = BEHINDWOODS_TITLE
                            )
                        )
                    }
                    .onErrorComplete()
                    .blockingAwait()
                userPreferences.legacyWikipediaToBehindwoodsMigrated = true
            }

            if (!userPreferences.legacyGoogleDuckDuckGoToCricketSitesMigrated) {
                val replaceGoogle = bookmarkRepository
                    .findBookmarkForUrl(LEGACY_GOOGLE_WWW_URL)
                    .switchIfEmpty(bookmarkRepository.findBookmarkForUrl(LEGACY_GOOGLE_ROOT_URL))
                    .flatMapCompletable { old ->
                        bookmarkRepository.editBookmark(
                            old,
                            old.copy(
                                url = CRICBUZZ_URL,
                                title = CRICBUZZ_TITLE
                            )
                        )
                    }
                    .onErrorComplete()

                val replaceDuckDuckGo = bookmarkRepository
                    .findBookmarkForUrl(LEGACY_DUCKDUCKGO_URL)
                    .flatMapCompletable { old ->
                        bookmarkRepository.editBookmark(
                            old,
                            old.copy(
                                url = ESPNCRICINFO_URL,
                                title = ESPNCRICINFO_TITLE
                            )
                        )
                    }
                    .onErrorComplete()

                Completable.mergeArray(replaceGoogle, replaceDuckDuckGo).blockingAwait()
                userPreferences.legacyGoogleDuckDuckGoToCricketSitesMigrated = true
            }

            if (!userPreferences.legacyTwitterToTamilshowzMigrated) {
                bookmarkRepository
                    .findBookmarkForUrl(LEGACY_TWITTER_URL)
                    .switchIfEmpty(bookmarkRepository.findBookmarkForUrl(LEGACY_TWITTER_WWW_URL))
                    .flatMapCompletable { old ->
                        bookmarkRepository.editBookmark(
                            old,
                            old.copy(
                                url = TAMILSHOWZ_URL,
                                title = TAMILSHOWZ_BOOKMARK_TITLE
                            )
                        )
                    }
                    .onErrorComplete()
                    .blockingAwait()
                userPreferences.legacyTwitterToTamilshowzMigrated = true
            }

            if (!userPreferences.legacyTamilshowzNetBookmarkTitleMigrated) {
                bookmarkRepository
                    .findBookmarkForUrl(TAMILSHOWZ_URL)
                    .flatMapCompletable { old ->
                        if (old.title == LEGACY_KEEPIT_TS_BOOKMARK_TITLE) {
                            bookmarkRepository.editBookmark(
                                old,
                                old.copy(title = TAMILSHOWZ_BOOKMARK_TITLE)
                            )
                        } else {
                            Completable.complete()
                        }
                    }
                    .onErrorComplete()
                    .blockingAwait()
                userPreferences.legacyTamilshowzNetBookmarkTitleMigrated = true
            }
        }
    }

    companion object {
        private const val LEGACY_CHANGE_LOG_URL = "https://github.com/anthonycr/Lightning-Browser/releases"
        private const val LEGACY_CONTACT_URL = "https://twitter.com/RestainoAnthony"
        private const val DAILY_THANTHI_URL = "https://www.dailythanthi.com/"
        private const val DAILY_THANTHI_TITLE = "தினத்தந்தி"
        private const val DINAMALAR_URL = "https://www.dinamalar.com/home"
        private const val DINAMALAR_TITLE = "தினமலர்"

        private const val LEGACY_WIKIPEDIA_WWW_URL = "https://www.wikipedia.org/"
        private const val LEGACY_WIKIPEDIA_ROOT_URL = "https://wikipedia.org/"
        private const val BEHINDWOODS_URL = "https://www.behindwoods.com/"
        private const val BEHINDWOODS_TITLE = "Behindwoods"

        private const val LEGACY_GOOGLE_WWW_URL = "https://www.google.com/"
        private const val LEGACY_GOOGLE_ROOT_URL = "https://google.com/"
        private const val LEGACY_DUCKDUCKGO_URL = "https://duckduckgo.com/"
        private const val CRICBUZZ_URL = "https://www.cricbuzz.com/"
        private const val CRICBUZZ_TITLE = "Cricbuzz"
        private const val ESPNCRICINFO_URL = "https://www.espncricinfo.com/"
        private const val ESPNCRICINFO_TITLE = "ESPNcricinfo"

        private const val LEGACY_TWITTER_URL = "https://twitter.com/"
        private const val LEGACY_TWITTER_WWW_URL = "https://www.twitter.com/"
        private const val TAMILSHOWZ_URL = "https://tamilshowz.net/"
        private const val TAMILSHOWZ_BOOKMARK_TITLE = "Tamilshowz.net"
        private const val LEGACY_KEEPIT_TS_BOOKMARK_TITLE = "Keepit TS"
    }
}
