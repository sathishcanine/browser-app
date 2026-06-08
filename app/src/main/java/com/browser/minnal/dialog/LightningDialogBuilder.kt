package com.browser.minnal.dialog

import com.browser.minnal.DefaultBrowserActivity
import com.browser.minnal.R
import com.browser.minnal.browser.BrowserContract
import com.browser.minnal.databinding.DialogEditBookmarkBinding
import android.app.Activity
import android.view.View
import android.widget.ArrayAdapter
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import dagger.Reusable
import javax.inject.Inject

/**
 * A builder of various dialogs.
 */
@Reusable
class LightningDialogBuilder @Inject constructor() {

    /**
     * Show the appropriated dialog for the long pressed link. It means that we try to understand
     * if the link is relative to a bookmark or is just a folder.
     *
     * @param activity used to show the dialog
     */
    fun showLongPressedDialogForBookmarkUrl(
        activity: Activity,
        onClick: (BrowserContract.BookmarkOptionEvent) -> Unit
    ) = BrowserDialog.show(
        activity, R.string.action_bookmarks,
        DialogItem(title = R.string.dialog_open_new_tab) {
            onClick(BrowserContract.BookmarkOptionEvent.NEW_TAB)
        },
        DialogItem(title = R.string.dialog_open_background_tab) {
            onClick(BrowserContract.BookmarkOptionEvent.BACKGROUND_TAB)
        },
        DialogItem(
            title = R.string.dialog_open_incognito_tab,
            isConditionMet = activity is DefaultBrowserActivity
        ) {
            onClick(BrowserContract.BookmarkOptionEvent.INCOGNITO_TAB)
        },
        DialogItem(title = R.string.action_share) {
            onClick(BrowserContract.BookmarkOptionEvent.SHARE)
        },
        DialogItem(title = R.string.dialog_copy_link) {
            onClick(BrowserContract.BookmarkOptionEvent.COPY_LINK)
        },
        DialogItem(title = R.string.dialog_remove_bookmark) {
            onClick(BrowserContract.BookmarkOptionEvent.REMOVE)
        },
        DialogItem(title = R.string.dialog_edit_bookmark) {
            onClick(BrowserContract.BookmarkOptionEvent.EDIT)
        })

    /**
     * Show the appropriated dialog for the long pressed link.
     *
     * @param activity used to show the dialog
     */
    // TODO allow individual downloads to be deleted.
    fun showLongPressedDialogForDownloadUrl(
        activity: Activity,
        onClick: (BrowserContract.DownloadOptionEvent) -> Unit
    ) = BrowserDialog.show(
        activity, R.string.action_downloads,
        DialogItem(title = R.string.dialog_delete_all_downloads) {
            onClick(BrowserContract.DownloadOptionEvent.DELETE_ALL)
        },
        DialogItem(title = R.string.dialog_delete_all_downloads) {
            onClick(BrowserContract.DownloadOptionEvent.DELETE)
        }
    )

    /**
     * Show the add bookmark dialog. Shows a dialog with the title and URL pre-populated.
     */
    fun showAddBookmarkDialog(
        activity: Activity,
        currentTitle: String,
        currentUrl: String,
        folders: List<String>,
        onSave: (title: String, url: String, folder: String) -> Unit
    ) {
        showBookmarkDialog(
            activity = activity,
            titleRes = R.string.action_add_bookmark,
            currentTitle = currentTitle,
            currentUrl = currentUrl,
            currentFolder = "",
            folders = folders,
            onSave = onSave,
        )
    }

    fun showEditBookmarkDialog(
        activity: Activity,
        currentTitle: String,
        currentUrl: String,
        currentFolder: String,
        folders: List<String>,
        onSave: (title: String, url: String, folder: String) -> Unit
    ) {
        showBookmarkDialog(
            activity = activity,
            titleRes = R.string.dialog_edit_bookmark,
            currentTitle = currentTitle,
            currentUrl = currentUrl,
            currentFolder = currentFolder,
            folders = folders,
            onSave = onSave,
        )
    }

    private fun showBookmarkDialog(
        activity: Activity,
        @StringRes titleRes: Int,
        currentTitle: String,
        currentUrl: String,
        currentFolder: String,
        folders: List<String>,
        onSave: (title: String, url: String, folder: String) -> Unit,
    ) {
        val dialogLayout = View.inflate(activity, R.layout.dialog_edit_bookmark, null)
        val binding = DialogEditBookmarkBinding.bind(dialogLayout)
        binding.bookmarkTitle.setText(currentTitle)
        binding.bookmarkUrl.setText(currentUrl)
        binding.bookmarkFolder.setText(currentFolder)

        val suggestionsAdapter = ArrayAdapter(
            activity,
            android.R.layout.simple_dropdown_item_1line,
            folders,
        )
        binding.bookmarkFolder.setAdapter(suggestionsAdapter)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setView(dialogLayout)
            .setPositiveButton(R.string.action_ok, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            BrowserDialog.setDialogSize(activity, dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                binding.bookmarkTitle.error = null
                binding.bookmarkUrl.error = null

                val title = binding.bookmarkTitle.text.toString().trim()
                val url = binding.bookmarkUrl.text.toString().trim()
                val folder = binding.bookmarkFolder.text.toString()

                when {
                    title.isBlank() -> {
                        binding.bookmarkTitle.error =
                            activity.getString(R.string.error_bookmark_title_required)
                    }
                    url.isBlank() -> {
                        binding.bookmarkUrl.error =
                            activity.getString(R.string.error_bookmark_url_required)
                    }
                    !isHttpOrHttpsUrl(url) -> {
                        binding.bookmarkUrl.error =
                            activity.getString(R.string.error_bookmark_url_scheme)
                    }
                    else -> {
                        onSave(title, url, folder)
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    fun showBookmarkFolderLongPressedDialog(
        activity: Activity,
        onClick: (BrowserContract.FolderOptionEvent) -> Unit
    ) = BrowserDialog.show(
        activity, R.string.action_folder,
        DialogItem(title = R.string.dialog_rename_folder) {
            onClick(BrowserContract.FolderOptionEvent.RENAME)
        },
        DialogItem(title = R.string.dialog_remove_folder) {
            onClick(BrowserContract.FolderOptionEvent.REMOVE)
        })

    fun showRenameFolderDialog(
        activity: Activity,
        oldTitle: String,
        onSave: (oldTitle: String, newTitle: String) -> Unit
    ) = BrowserDialog.showEditText(
        activity,
        R.string.title_rename_folder,
        R.string.hint_title,
        oldTitle,
        R.string.action_ok
    ) { text ->
        if (text.isNotBlank()) {
            onSave(oldTitle, text)
        }
    }

    fun showLongPressedHistoryLinkDialog(
        activity: Activity,
        onClick: (BrowserContract.HistoryOptionEvent) -> Unit
    ) = BrowserDialog.show(
        activity, R.string.action_history,
        DialogItem(title = R.string.dialog_open_new_tab) {
            onClick(BrowserContract.HistoryOptionEvent.NEW_TAB)
        },
        DialogItem(title = R.string.dialog_open_background_tab) {
            onClick(BrowserContract.HistoryOptionEvent.BACKGROUND_TAB)
        },
        DialogItem(
            title = R.string.dialog_open_incognito_tab,
            isConditionMet = activity is DefaultBrowserActivity
        ) {
            onClick(BrowserContract.HistoryOptionEvent.INCOGNITO_TAB)
        },
        DialogItem(title = R.string.action_share) {
            onClick(BrowserContract.HistoryOptionEvent.SHARE)
        },
        DialogItem(title = R.string.dialog_copy_link) {
            onClick(BrowserContract.HistoryOptionEvent.COPY_LINK)
        },
        DialogItem(title = R.string.dialog_remove_from_history) {
            onClick(BrowserContract.HistoryOptionEvent.REMOVE)
        })

    private fun isHttpOrHttpsUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
}
