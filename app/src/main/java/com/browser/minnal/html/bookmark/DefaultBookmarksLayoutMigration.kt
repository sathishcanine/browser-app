package com.browser.minnal.html.bookmark

import com.browser.minnal.database.bookmark.BookmarkRepository
import com.browser.minnal.migration.Cleanup
import io.reactivex.rxjava3.core.Completable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Reorders the shipped default homepage bookmarks:
 * row 2 — Behindwoods, Cricbuzz; row 3 — Tamilshow, Tamilplay.
 */
class DefaultBookmarksLayoutMigration @Inject constructor(
    private val bookmarkRepository: BookmarkRepository,
) : Cleanup.Action {

    override val versionCode: Int = 108

    override suspend fun execute() {
        withContext(Dispatchers.IO) {
            val moveCricbuzz = bookmarkRepository
                .findBookmarkForUrl(CRICBUZZ_URL)
                .flatMapCompletable { old ->
                    bookmarkRepository.editBookmark(old, old.copy(position = 3))
                }
                .onErrorComplete()

            val moveTamilshow = bookmarkRepository
                .findBookmarkForUrl(TAMILSHOWZ_URL)
                .flatMapCompletable { old ->
                    bookmarkRepository.editBookmark(
                        old,
                        old.copy(position = 4, title = TAMILSHOW_TITLE),
                    )
                }
                .onErrorComplete()

            val replaceEspn = bookmarkRepository
                .findBookmarkForUrl(ESPNCRICINFO_URL)
                .flatMapCompletable { old ->
                    bookmarkRepository.editBookmark(
                        old,
                        old.copy(
                            url = TAMILPLAY_URL,
                            title = TAMILPLAY_TITLE,
                            position = 5,
                        ),
                    )
                }
                .onErrorComplete()

            Completable.mergeArray(moveCricbuzz, moveTamilshow, replaceEspn).blockingAwait()
        }
    }

    companion object {
        private const val CRICBUZZ_URL = "https://www.cricbuzz.com/"
        private const val TAMILSHOWZ_URL = "https://tamilshowz.net/"
        private const val TAMILSHOW_TITLE = "Tamilshow"
        private const val ESPNCRICINFO_URL = "https://www.espncricinfo.com/"
        private const val TAMILPLAY_URL = "https://tamilkollymovies.xyz/"
        private const val TAMILPLAY_TITLE = "Tamilplay"
    }
}
