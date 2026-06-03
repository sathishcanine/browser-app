package com.browser.minnal.browser

import com.browser.minnal.AppTheme
import com.browser.minnal.R
import com.browser.minnal.ThemableBrowserActivity
import com.browser.minnal.ads.ActiveTimeInterstitialController
import com.browser.minnal.ads.AppOpenAdManager
import com.browser.minnal.ads.HomeScreenNativeAdScheduler
import com.browser.minnal.ads.BookmarkNativeAdController
import com.browser.minnal.animation.AnimationUtils
import com.browser.minnal.browser.bookmark.BookmarkRecyclerViewAdapter
import com.browser.minnal.browser.color.ColorAnimator
import com.browser.minnal.browser.di.MainHandler
import com.browser.minnal.browser.di.injector
import com.browser.minnal.browser.image.ImageLoader
import com.browser.minnal.browser.keys.KeyEventAdapter
import com.browser.minnal.browser.menu.MenuItemAdapter
import com.browser.minnal.browser.search.IntentExtractor
import com.browser.minnal.browser.search.SearchListener
import com.browser.minnal.browser.search.StyleRemovingTextWatcher
import com.browser.minnal.browser.tab.DesktopTabRecyclerViewAdapter
import com.browser.minnal.browser.tab.GridTabRecyclerViewAdapter
import com.browser.minnal.browser.tab.TabPager
import com.browser.minnal.browser.tab.TabViewHolder
import com.browser.minnal.browser.tab.TabViewState
import com.browser.minnal.browser.theme.ThemeProvider
import com.browser.minnal.browser.ui.BookmarkConfiguration
import com.browser.minnal.browser.ui.TabConfiguration
import com.browser.minnal.browser.ui.UiConfiguration
import com.browser.minnal.browser.menu.MenuSelection
import com.browser.minnal.browser.view.BackgroundTabFlyInAnimation
import com.browser.minnal.rating.RatingPromptDialog
import com.browser.minnal.rating.RatingPromptHelper
import com.browser.minnal.browser.view.ViewDelegate
import com.browser.minnal.view.RadialFabMenu
import com.browser.minnal.view.RadialFabMenuItem
import com.browser.minnal.browser.view.delegates.DesktopTabViewDelegate
import com.browser.minnal.browser.view.delegates.DrawerTabViewDelegate
import com.browser.minnal.browser.view.targetUrl.LongPress
import com.browser.minnal.constant.HTTP
import com.browser.minnal.database.Bookmark
import com.browser.minnal.database.HistoryEntry
import com.browser.minnal.database.SearchSuggestion
import com.browser.minnal.database.WebPage
import com.browser.minnal.database.downloads.DownloadEntry
import com.browser.minnal.databinding.BrowserActivityDesktopBinding
import com.browser.minnal.databinding.BrowserActivityDrawerBinding
import com.browser.minnal.databinding.BrowserBottomTabsBinding
import com.browser.minnal.dialog.BrowserDialog
import com.browser.minnal.dialog.DefaultBrowserPromptDialog
import com.browser.minnal.dialog.DialogItem
import com.browser.minnal.dialog.LightningDialogBuilder
import com.browser.minnal.icon.TabCountView
import com.browser.minnal.extensions.drawable
import com.browser.minnal.extensions.resizeAndShow
import com.browser.minnal.extensions.snackbar
import com.browser.minnal.extensions.takeIfInstance
import com.browser.minnal.extensions.tint
import com.browser.minnal.search.SuggestionsAdapter
import com.browser.minnal.ssl.createSslDrawableForState
import com.browser.minnal.update.ForceUpdateRemoteConfig
import com.browser.minnal.utils.DefaultBrowserHelper
import com.browser.minnal.utils.value
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import java.lang.ref.WeakReference
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.util.TypedValue
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.addCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.MenuRes
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.drawerlayout.widget.DrawerLayout
import com.browser.minnal.extensions.color
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import javax.inject.Inject

/**
 * The base browser activity that governs the browsing experience for both default and incognito
 * browsers.
 */
abstract class BrowserActivity : ThemableBrowserActivity() {

    private lateinit var binding: ViewDelegate
    private lateinit var radialFabMenu: RadialFabMenu
    private lateinit var tabsAdapter: ListAdapter<TabViewState, TabViewHolder>
    private lateinit var bookmarksAdapter: BookmarkRecyclerViewAdapter
    private var activeRecyclerView: RecyclerView? = null

    private var menuItemShare: MenuItem? = null
    private var menuItemCopyLink: MenuItem? = null
    private var menuItemAddToHome: MenuItem? = null
    private var menuItemAddBookmark: MenuItem? = null

    private val defaultColor by lazy { color(R.color.primary_color) }
    private val backgroundDrawable by lazy { defaultColor.toDrawable() }

    private var customView: View? = null

    private var bookmarkNativeAdController: BookmarkNativeAdController? = null
    private var downloadsNativeAdController: BookmarkNativeAdController? = null

    private var interstitialAccumulatedActiveMs = 0L

    private var bottomTabsBinding: BrowserBottomTabsBinding? = null
    private var addressBarContainer: ConstraintLayout? = null
    private var tabGridTabCount: TabCountView? = null
    private var tabGridEmptyView: TextView? = null
    private var tabGridSearchView: EditText? = null
    private var pendingScroll = -1
    private var tabGridDoneButton: View? = null

    private var defaultBrowserPrompt: AlertDialog? = null

    private var forceUpdateDialog: AlertDialog? = null

    @Suppress("ConvertLambdaToReference")
    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { presenter.onFileChooserResult(it) }

    private val defaultBrowserRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!isIncognito()) {
            appOpenAdManager.setDefaultBrowserSystemFlowActive(false)
        }
    }

    private val playImmediateUpdateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { /* Play immediate update typically restarts the process. */ }

    @Inject
    internal lateinit var imageLoader: ImageLoader

    @Inject
    internal lateinit var keyEventAdapter: KeyEventAdapter

    @Inject
    internal lateinit var menuItemAdapter: MenuItemAdapter

    @Inject
    internal lateinit var inputMethodManager: InputMethodManager

    @Inject
    internal lateinit var presenter: BrowserPresenter

    @Inject
    internal lateinit var tabPager: TabPager

    @Inject
    internal lateinit var intentExtractor: IntentExtractor

    @Inject
    internal lateinit var lightningDialogBuilder: LightningDialogBuilder

    @Inject
    internal lateinit var uiConfiguration: UiConfiguration

    @Inject
    internal lateinit var themeProvider: ThemeProvider

    @MainHandler
    @Inject
    internal lateinit var mainHandler: Handler

    @Inject
    internal lateinit var activeTimeInterstitialController: ActiveTimeInterstitialController

    @Inject
    internal lateinit var appOpenAdManager: AppOpenAdManager

    @Inject
    internal lateinit var homeScreenNativeAdScheduler: HomeScreenNativeAdScheduler

    @Inject
    internal lateinit var ratingPromptHelper: RatingPromptHelper

    private var wantsBookmarkNativeAdStrip = false

    /** First [onResume] after a fresh launch (not return from background). */
    private var ratingPromptColdStartResume = false

    /**
     * True if the activity is operating in incognito mode, false otherwise.
     */
    abstract fun isIncognito(): Boolean

    /**
     * Provide the menu used by the browser instance.
     */
    @MenuRes
    abstract fun menu(): Int

    /**
     * Provide the home icon used by the browser instance.
     */
    @DrawableRes
    abstract fun homeIcon(): Int

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ratingPromptColdStartResume = savedInstanceState == null
        if (savedInstanceState != null) {
            interstitialAccumulatedActiveMs = savedInstanceState.getLong(
                STATE_INTERSTITIAL_ACCUMULATED_MS,
                0L,
            )
        }
        binding = when (userPreferences.tabConfiguration) {
            TabConfiguration.DESKTOP -> {
                val actualBinding = BrowserActivityDesktopBinding.inflate(LayoutInflater.from(this))
                DesktopTabViewDelegate(actualBinding)
            }

            TabConfiguration.DRAWER_SIDE, TabConfiguration.DRAWER_BOTTOM -> {
                val actualBinding = BrowserActivityDrawerBinding.inflate(LayoutInflater.from(this))
                DrawerTabViewDelegate(actualBinding)
            }
        }

        bottomTabsBinding = if (userPreferences.tabConfiguration != TabConfiguration.DESKTOP) {
            BrowserBottomTabsBinding.inflate(layoutInflater)
        } else {
            null
        }

        setContentView(binding.root)
        if (userPreferences.tabConfiguration == TabConfiguration.DRAWER_BOTTOM) {
            applyBottomAddressBarLayout()
        }
        setSupportActionBar(binding.toolbar)

        bottomTabsBinding?.let { tabs ->
            val params = CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
            )
            tabs.root.translationY = resources.displayMetrics.heightPixels.toFloat()
            binding.root.addView(tabs.root, params)
            ViewCompat.setOnApplyWindowInsetsListener(tabs.root) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(0, systemBars.top, 0, systemBars.bottom)
                insets
            }
            tabs.root.post { ViewCompat.requestApplyInsets(tabs.root) }
            // Correct translationY after layout so the overlay is fully off-screen even
            // on devices where displayMetrics.heightPixels < CoordinatorLayout.height
            // (e.g. Samsung edge-to-edge with a tall navigation bar).
            tabs.root.doOnLayout { overlay ->
                if (overlay.translationY < overlay.height.toFloat()) {
                    overlay.translationY = overlay.height.toFloat()
                }
            }
        }

        injector.browser2ComponentBuilder()
            .activity(this)
            .browserFrame(binding.contentFrame)
            .bottomTabsLayout(bottomTabsBinding)
            .toolbarRoot(binding.uiLayout)
            .browserRoot(binding.browserLayoutContainer)
            .toolbar(binding.toolbarLayout)
            .initialIntent(intent.takeIf { savedInstanceState == null })
            .incognitoMode(isIncognito())
            .build()
            .inject(this)

        if (savedInstanceState != null) {
            activeTimeInterstitialController.restoreAccumulatedActiveMs(interstitialAccumulatedActiveMs)
        }

        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {

            override fun onDrawerOpened(drawerView: View) {
                if (drawerView == binding.tabDrawer) {
                    presenter.onTabDrawerMoved(isOpen = true)
                } else if (drawerView == binding.bookmarkDrawer) {
                    presenter.onBookmarkDrawerMoved(isOpen = true)
                }
            }

            override fun onDrawerClosed(drawerView: View) {
                if (drawerView == binding.tabDrawer) {
                    presenter.onTabDrawerMoved(isOpen = false)
                } else if (drawerView == binding.bookmarkDrawer) {
                    presenter.onBookmarkDrawerMoved(isOpen = false)
                }
            }
        })

        binding.bookmarkDrawer.layoutParams =
            (binding.bookmarkDrawer.layoutParams as DrawerLayout.LayoutParams).apply {
                gravity = when (uiConfiguration.bookmarkConfiguration) {
                    BookmarkConfiguration.LEFT -> Gravity.START
                    BookmarkConfiguration.RIGHT -> Gravity.END
                }
            }

        binding.tabDrawer.layoutParams =
            (binding.tabDrawer.layoutParams as DrawerLayout.LayoutParams).apply {
                gravity = when (uiConfiguration.bookmarkConfiguration) {
                    BookmarkConfiguration.LEFT -> Gravity.END
                    BookmarkConfiguration.RIGHT -> Gravity.START
                }
            }

        binding.homeImageView.isVisible =
            uiConfiguration.tabConfiguration == TabConfiguration.DESKTOP || isIncognito()
        binding.homeImageView.setImageResource(homeIcon())
        binding.toolbarHome.setImageResource(
            if (isIncognito() && uiConfiguration.tabConfiguration != TabConfiguration.DESKTOP) {
                R.drawable.ic_action_home
            } else {
                homeIcon()
            }
        )
        binding.toolbarHome.isVisible =
            uiConfiguration.tabConfiguration != TabConfiguration.DESKTOP
        binding.tabCountView.isVisible =
            uiConfiguration.tabConfiguration != TabConfiguration.DESKTOP && !isIncognito()

        // Always lock the tab side drawer — the standalone overlay is used for all configs.
        binding.drawerLayout.setDrawerLockMode(
            DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
            binding.tabDrawer
        )

        if (uiConfiguration.tabConfiguration != TabConfiguration.DESKTOP) {
            tabsAdapter = createGridTabsAdapter()
            val gridContent = bottomTabsBinding!!.tabGridContent
            setupTabGridUi(
                recyclerView = gridContent.drawerTabsList,
                spanCount = 2,
                tabCountView = gridContent.tabGridTabCount,
                emptyView = gridContent.tabGridEmpty,
                searchView = gridContent.tabGridSearch,
                newTabView = gridContent.tabGridNewTab,
                menuView = gridContent.tabGridMenu,
                doneView = gridContent.tabGridDone,
            )
            binding.drawerTabsList.isVisible = false
            binding.desktopTabsList.isVisible = false
            activeRecyclerView = gridContent.drawerTabsList
        } else {
            tabsAdapter = DesktopTabRecyclerViewAdapter(
                context = this,
                onClick = presenter::onTabClick,
                onCloseClick = presenter::onTabClose,
                onLongClick = presenter::onTabLongClick
            )
            binding.desktopTabsList.isVisible = true
            binding.desktopTabsList.adapter = tabsAdapter
            binding.desktopTabsList.layoutManager =
                LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            binding.desktopTabsList.itemAnimator?.takeIfInstance<SimpleItemAnimator>()
                ?.supportsChangeAnimations = false
            binding.drawerTabsList.isVisible = false
            activeRecyclerView = binding.desktopTabsList
        }

        bookmarksAdapter = BookmarkRecyclerViewAdapter(
            onClick = presenter::onBookmarkClick,
            onLongClick = presenter::onBookmarkLongClick,
            imageLoader = imageLoader
        )
        binding.bookmarkListView.adapter = bookmarksAdapter
        binding.bookmarkListView.layoutManager = LinearLayoutManager(this)

        if (!isIncognito()) {
            bookmarkNativeAdController = BookmarkNativeAdController.forBookmarks(
                this,
                binding.bookmarkNativeAdStrip,
            )
            downloadsNativeAdController = BookmarkNativeAdController.forDownloads(
                this,
                binding.downloadsNativeAdStrip,
            )
            homeScreenNativeAdScheduler.setListener { eligible ->
                applyBookmarkNativeAdStripVisibility(eligible)
            }
        }

        presenter.onViewAttached(BrowserStateAdapter(this))
        if (!isIncognito()) {
            setupRadialFabMenu()
        }

        val suggestionsAdapter = SuggestionsAdapter(this, isIncognito = isIncognito()).apply {
            onSuggestionInsertClick = {
                if (it is SearchSuggestion) {
                    binding.search.setText(it.title)
                    binding.search.setSelection(it.title.length)
                } else {
                    binding.search.setText(it.url)
                    binding.search.setSelection(it.url.length)
                }
            }
        }
        binding.search.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            binding.search.clearFocus()
            presenter.onSearchSuggestionClicked(suggestionsAdapter.getItem(position) as WebPage)
            inputMethodManager.hideSoftInputFromWindow(binding.root.windowToken, 0)
        }
        binding.search.setAdapter(suggestionsAdapter)
        val searchListener = SearchListener(
            onConfirm = { presenter.onSearch(binding.search.text.toString()) },
            inputMethodManager = inputMethodManager
        )
        binding.search.setOnEditorActionListener(searchListener)
        binding.search.setOnKeyListener(searchListener)
        binding.search.addTextChangedListener(StyleRemovingTextWatcher())
        binding.search.setOnFocusChangeListener { _, hasFocus ->
            presenter.onSearchFocusChanged(hasFocus)
            binding.search.selectAll()
        }

        binding.findPrevious.setOnClickListener { presenter.onFindPrevious() }
        binding.findNext.setOnClickListener { presenter.onFindNext() }
        binding.findQuit.setOnClickListener { presenter.onFindDismiss() }

        binding.homeButton.setOnClickListener { presenter.onTabCountViewClick() }
        binding.toolbarHome.setOnClickListener { presenter.onHomeClick() }
        binding.searchRefresh.setOnClickListener { presenter.onRefreshOrStopClick() }
        binding.actionAddBookmark.setOnClickListener { presenter.onStarClick() }
        binding.actionPageTools.setOnClickListener { presenter.onToolsClick() }
        binding.bookmarkBackButton.setOnClickListener { presenter.onBookmarkMenuClick() }
        binding.searchSslStatus.setOnClickListener { presenter.onSslIconClick() }

        binding.urlActionsBar.urlActionsShare.setOnClickListener {
            presenter.onMenuClick(MenuSelection.SHARE)
        }
        binding.urlActionsBar.urlActionsCopy.setOnClickListener {
            presenter.onMenuClick(MenuSelection.COPY_LINK)
        }
        binding.urlActionsBar.urlActionsEdit.setOnClickListener {
            binding.search.requestFocus()
            val length = binding.search.text?.length ?: 0
            binding.search.setSelection(length)
        }

        tabPager.longPressListener = presenter::onPageLongPress

        onBackPressedDispatcher.addCallback {
            if (::radialFabMenu.isInitialized && radialFabMenu.isMenuExpanded) {
                radialFabMenu.collapse()
            } else {
                presenter.onNavigateBack()
            }
        }

        if (savedInstanceState == null) {
            scheduleForceUpdateRemoteConfigCheck()
        }
    }

    private fun setupRadialFabMenu() {
        radialFabMenu = RadialFabMenu(this)
        val layoutParams = CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.MATCH_PARENT,
            CoordinatorLayout.LayoutParams.MATCH_PARENT,
        )
        binding.root.addView(radialFabMenu, layoutParams)
        binding.root.clipChildren = false
        binding.root.clipToPadding = false
        binding.root.post { updateRadialFabBottomInset() }
        binding.uiLayout.viewTreeObserver.addOnGlobalLayoutListener {
            if (::radialFabMenu.isInitialized) {
                updateRadialFabBottomInset()
            }
        }
        radialFabMenu.setMenuItems(buildRadialFabMenuItems())
        binding.root.post { radialFabMenu.showMainFab(animate = false) }
    }

    private fun buildRadialFabMenuItems(): List<RadialFabMenuItem> {
        return listOf(
            RadialFabMenuItem(
                iconRes = R.drawable.ic_action_home,
                label = getString(R.string.fab_menu_home),
                contentDescription = getString(R.string.fab_menu_home),
                onClick = { presenter.onHomeClick() },
            ),
            RadialFabMenuItem(
                iconRes = R.drawable.ic_action_star,
                label = getString(R.string.fab_menu_add_bookmark),
                contentDescription = getString(R.string.fab_menu_add_bookmark),
                onClick = { presenter.onStarClick() },
            ),
            RadialFabMenuItem(
                iconRes = R.drawable.ic_action_tabs,
                label = getString(R.string.fab_menu_close_all_tabs),
                contentDescription = getString(R.string.fab_menu_close_all_tabs),
                onClick = { showCloseAllTabsDialog() },
            ),
            RadialFabMenuItem(
                iconRes = R.drawable.ic_action_close,
                label = getString(R.string.fab_menu_close_other_tabs),
                contentDescription = getString(R.string.fab_menu_close_other_tabs),
                onClick = { showCloseOtherTabsDialog() },
            ),
            RadialFabMenuItem(
                iconRes = R.drawable.ic_history,
                label = getString(R.string.fab_menu_clear_history),
                contentDescription = getString(R.string.fab_menu_clear_history),
                onClick = { showClearHistoryDialog() },
            ),
            RadialFabMenuItem(
                iconRes = R.drawable.ic_action_downloads,
                label = getString(R.string.fab_menu_downloads),
                contentDescription = getString(R.string.fab_menu_downloads),
                onClick = { presenter.onMenuClick(MenuSelection.DOWNLOADS) },
            ),
            addressBarToggleMenuItem(),
            RadialFabMenuItem(
                iconRes = R.drawable.ic_action_report_issue,
                label = getString(R.string.fab_menu_report_issue),
                contentDescription = getString(R.string.fab_menu_report_issue),
                iconBackgroundColorRes = R.color.fab_report_issue_background,
                labelTextColorRes = R.color.fab_report_issue_label,
                onClick = { reportIssue() },
            ),
        )
    }

    private fun reportIssue() {
        val mailtoUri = Uri.parse(
            "mailto:${getString(R.string.report_issue_email)}" +
                "?subject=${Uri.encode(getString(R.string.report_issue_subject))}",
        )
        val intent = Intent(Intent.ACTION_SENDTO, mailtoUri)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No email client installed.
        }
    }

    private fun addressBarToggleMenuItem(): RadialFabMenuItem {
        return if (userPreferences.tabConfiguration == TabConfiguration.DRAWER_BOTTOM) {
            RadialFabMenuItem(
                iconRes = R.drawable.ic_action_toolbar_top,
                label = getString(R.string.fab_menu_move_address_bar_top),
                contentDescription = getString(R.string.fab_menu_move_address_bar_top),
                onClick = { toggleAddressBarPosition(toBottom = false) },
            )
        } else {
            RadialFabMenuItem(
                iconRes = R.drawable.ic_action_toolbar_bottom,
                label = getString(R.string.fab_menu_move_address_bar_bottom),
                contentDescription = getString(R.string.fab_menu_move_address_bar_bottom),
                onClick = { toggleAddressBarPosition(toBottom = true) },
            )
        }
    }

    private fun toggleAddressBarPosition(toBottom: Boolean) {
        userPreferences.tabConfiguration = if (toBottom) {
            TabConfiguration.DRAWER_BOTTOM
        } else {
            TabConfiguration.DRAWER_SIDE
        }
        restart()
    }

    private fun updateRadialFabBottomInset() {
        val inset = if (userPreferences.tabConfiguration == TabConfiguration.DRAWER_BOTTOM) {
            computeBottomToolbarInset()
        } else {
            resources.getDimensionPixelSize(R.dimen.radial_fab_extra_bottom_inset)
        }
        radialFabMenu.setFabBottomInset(inset)
    }

    private fun applyBottomAddressBarLayout() {
        val toolbarLayout = binding.toolbarLayout
        val toolbar = binding.toolbar
        val progressView = binding.progressView
        val urlActionsBar = binding.urlActionsBar.root
        val uiLayout = binding.uiLayout

        (toolbar.parent as? ViewGroup)?.removeView(toolbar)
        (progressView.parent as? ViewGroup)?.removeView(progressView)
        (urlActionsBar.parent as? ViewGroup)?.removeView(urlActionsBar)

        val bottomChrome = ConstraintLayout(this).apply {
            id = R.id.bottom_address_bar_container
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            background = toolbarLayout.background
            fitsSystemWindows = true
            clipChildren = false
            clipToPadding = false
        }

        val divider = View(this).apply {
            id = View.generateViewId()
            val typedValue = TypedValue()
            theme.resolveAttribute(R.attr.dividerColor, typedValue, true)
            setBackgroundColor(typedValue.data)
        }

        bottomChrome.addView(divider)
        bottomChrome.addView(urlActionsBar)
        bottomChrome.addView(toolbar)
        bottomChrome.addView(progressView)

        val progressHeight = resources.getDimensionPixelSize(R.dimen.progress_bar_height)
        val actionBarSize = TypedValue()
        theme.resolveAttribute(android.R.attr.actionBarSize, actionBarSize, true)
        val toolbarHeight = TypedValue.complexToDimensionPixelSize(
            actionBarSize.data,
            resources.displayMetrics,
        )

        ConstraintSet().apply {
            clone(bottomChrome)
            connect(divider.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            connect(divider.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(divider.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            constrainHeight(divider.id, 1)
            connect(urlActionsBar.id, ConstraintSet.TOP, divider.id, ConstraintSet.BOTTOM)
            connect(urlActionsBar.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(urlActionsBar.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            connect(toolbar.id, ConstraintSet.TOP, urlActionsBar.id, ConstraintSet.BOTTOM)
            connect(toolbar.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(toolbar.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            constrainHeight(toolbar.id, toolbarHeight)
            connect(progressView.id, ConstraintSet.TOP, toolbar.id, ConstraintSet.TOP)
            connect(progressView.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(progressView.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            constrainHeight(progressView.id, progressHeight)
            applyTo(bottomChrome)
        }

        uiLayout.addView(bottomChrome)
        addressBarContainer = bottomChrome
        binding.search.setDropDownAnchor(bottomChrome.id)

        // Toolbar moved to bottom; hide the empty shell at the top (was causing a gray gap).
        toolbarLayout.fitsSystemWindows = false
        toolbarLayout.isVisible = false
    }

    private fun computeBottomToolbarInset(): Int {
        val rootLocation = IntArray(2)
        binding.root.getLocationInWindow(rootLocation)
        val rootBottom = rootLocation[1] + binding.root.height

        val chromeViews = buildList {
            addressBarContainer?.let { add(it) } ?: add(binding.toolbar)
            if (binding.findBar.isVisible) {
                add(binding.findBar)
            }
            if (binding.bookmarkNativeAdStrip.root.isVisible) {
                add(binding.bookmarkNativeAdStrip.root)
            }
            if (binding.downloadsNativeAdStrip.root.isVisible) {
                add(binding.downloadsNativeAdStrip.root)
            }
        }

        val chromeTop = chromeViews.minOf { view ->
            val location = IntArray(2)
            view.getLocationInWindow(location)
            location[1]
        }

        return (rootBottom - chromeTop)
            .coerceAtLeast(resources.getDimensionPixelSize(R.dimen.toolbar_height_portrait)) +
            resources.getDimensionPixelSize(R.dimen.radial_fab_extra_bottom_inset)
    }

    private fun showCloseAllTabsDialog() {
        BrowserDialog.showPositiveNegativeDialog(
            activity = this,
            title = R.string.fab_menu_close_all_tabs,
            message = R.string.dialog_close_all_tabs,
            positiveButton = DialogItem(title = R.string.action_yes) {
                presenter.closeAllTabs()
            },
            negativeButton = DialogItem(title = R.string.action_no) {},
            onCancel = {},
        )
    }

    private fun showCloseOtherTabsDialog() {
        BrowserDialog.showPositiveNegativeDialog(
            activity = this,
            title = R.string.fab_menu_close_other_tabs,
            message = R.string.dialog_close_other_tabs,
            positiveButton = DialogItem(title = R.string.action_yes) {
                presenter.closeOtherTabs()
            },
            negativeButton = DialogItem(title = R.string.action_no) {},
            onCancel = {},
        )
    }

    private fun showClearHistoryDialog() {
        BrowserDialog.showPositiveNegativeDialog(
            activity = this,
            title = R.string.fab_menu_clear_history,
            message = R.string.dialog_history,
            positiveButton = DialogItem(title = R.string.action_yes) {
                presenter.clearHistory(this) {
                    snackbar(R.string.message_clear_history)
                }
            },
            negativeButton = DialogItem(title = R.string.action_no) {},
            onCancel = {},
        )
    }

    /**
     * Fetches Remote Config off the main thread. If this build is below the minimum supported
     * version, shows a blocking [AlertDialog] on the browser (home) screen.
     */
    private fun scheduleForceUpdateRemoteConfigCheck() {
        val appContext = applicationContext
        val activityRef = WeakReference(this)
        Thread(
            {
                runCatching {
                    ForceUpdateRemoteConfig.fetchAndActivateBlocking(appContext)
                }
                val needs = ForceUpdateRemoteConfig.readNeedsForceUpdate()
                runOnUiThread {
                    val activity = activityRef.get()
                    if (activity == null || activity.isFinishing) {
                        return@runOnUiThread
                    }
                    if (needs) {
                        activity.showForceUpdateRequiredDialog()
                    }
                }
            },
            "remote-config-force-update",
        ).start()
    }

    private fun showForceUpdateRequiredDialog() {
        if (forceUpdateDialog?.isShowing == true) {
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.force_update_title)
            .setMessage(R.string.force_update_message)
            .setPositiveButton(R.string.force_update_button_play, null)
            .setNeutralButton(R.string.force_update_button_store, null)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                startImmediatePlayUpdateFlow()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                openPlayStoreForForceUpdate()
            }
        }
        dialog.setOnDismissListener {
            forceUpdateDialog = null
            if (!isIncognito()) {
                appOpenAdManager.setForceUpdateDialogVisible(false)
            }
        }
        dialog.show()
        forceUpdateDialog = dialog
        if (!isIncognito()) {
            appOpenAdManager.setForceUpdateDialogVisible(true)
        }
    }

    private fun startImmediatePlayUpdateFlow() {
        val appUpdateManager = AppUpdateManagerFactory.create(this)
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                val inProgress =
                    info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                val available =
                    info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                        info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                if (inProgress || available) {
                    appUpdateManager.startUpdateFlowForResult(
                        info,
                        playImmediateUpdateLauncher,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                    )
                } else {
                    openPlayStoreForForceUpdate()
                }
            }
            .addOnFailureListener { openPlayStoreForForceUpdate() }
    }

    private fun openPlayStoreForForceUpdate() {
        val marketUri = Uri.parse("market://details?id=$packageName")
        val webUri = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, marketUri))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }


    override fun onResume() {
        super.onResume()
        val coldStartResume = ratingPromptColdStartResume
        ratingPromptColdStartResume = false
        presenter.onViewShown(isColdStartResume = coldStartResume)
        if (!isIncognito()) {
            activeTimeInterstitialController.onBrowserResumed(this) {
                customView == null && !isFinishing
            }
        }
    }


    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_INTERSTITIAL_ACCUMULATED_MS, interstitialAccumulatedActiveMs)
    }

    override fun onNewIntent(intent: Intent) {
        intentExtractor.extractUrlFromIntent(intent)?.let(presenter::onNewAction)
        super.onNewIntent(intent)
    }

    override fun onWindowVisibleToUserAfterResume() {
        super.onWindowVisibleToUserAfterResume()
        maybeShowDefaultBrowserPrompt()
    }

    override fun onDestroy() {
        if (!isIncognito()) {
            appOpenAdManager.setDefaultBrowserPromptVisible(false)
            appOpenAdManager.setDefaultBrowserSystemFlowActive(false)
            appOpenAdManager.setForceUpdateDialogVisible(false)
            homeScreenNativeAdScheduler.destroy()
        }
        defaultBrowserPrompt?.dismiss()
        defaultBrowserPrompt = null
        forceUpdateDialog?.dismiss()
        forceUpdateDialog = null
        bookmarkNativeAdController?.destroy()
        bookmarkNativeAdController = null
        downloadsNativeAdController?.destroy()
        downloadsNativeAdController = null
        presenter.onViewDetached()
        super.onDestroy()
    }

    override fun onPause() {
        if (!isIncognito()) {
            interstitialAccumulatedActiveMs = activeTimeInterstitialController.pauseAndGetAccumulatedMs()
        }
        super.onPause()
        presenter.onViewHidden()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(menu(), menu)
        menuItemShare = menu.findItem(R.id.action_share)
        menuItemCopyLink = menu.findItem(R.id.action_copy)
        menuItemAddToHome = menu.findItem(R.id.action_add_to_homescreen)
        menuItemAddBookmark = menu.findItem(R.id.action_add_bookmark)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_toggle_address_bar)?.title = getString(
            if (userPreferences.tabConfiguration == TabConfiguration.DRAWER_BOTTOM) {
                R.string.menu_move_url_bar_to_top
            } else {
                R.string.menu_move_url_bar_to_bottom
            },
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_toggle_address_bar) {
            toggleAddressBarPosition(
                toBottom = userPreferences.tabConfiguration != TabConfiguration.DRAWER_BOTTOM,
            )
            return true
        }
        return menuItemAdapter.adaptMenuItem(item)?.let(presenter::onMenuClick)?.let { true }
            ?: super.onOptionsItemSelected(item)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return keyEventAdapter.adaptKeyEvent(event)?.let(presenter::onKeyComboClick)?.let { true }
            ?: super.onKeyUp(keyCode, event)
    }

    /**
     * @see BrowserContract.View.renderState
     */
    fun renderState(viewState: PartialBrowserViewState) {
        viewState.isBackEnabled?.let { binding.actionBack.isEnabled = it }
        viewState.isForwardEnabled?.let { binding.actionForward.isEnabled = it }
        viewState.displayUrl?.let(binding.search::setText)
        viewState.sslState?.let {
            binding.searchSslStatus.setImageDrawable(createSslDrawableForState(it))
            binding.searchSslStatus.updateVisibilityForDrawable()
        }
        viewState.enableFullMenu?.let {
            menuItemShare?.isVisible = it
            menuItemCopyLink?.isVisible = it
            menuItemAddToHome?.isVisible = it
            menuItemAddBookmark?.isVisible = it
        }
        viewState.themeColor?.value()?.let(::animateColorChange)
        viewState.progress?.let {
            binding.progressView.isVisible = it != 100
            binding.progressView.progress = it
        }
        viewState.isRefresh?.let {
            binding.searchRefresh.setImageResource(
                if (it) {
                    R.drawable.ic_action_refresh
                } else {
                    R.drawable.ic_action_delete
                }
            )
        }
        viewState.bookmarks?.let(bookmarksAdapter::submitList)
        viewState.isBookmarked?.let { binding.actionAddBookmark.isSelected = it }
        viewState.isBookmarkEnabled?.let { binding.actionAddBookmark.isEnabled = it }
        viewState.isRootFolder?.let {
            binding.bookmarkBackButton.startAnimation(
                AnimationUtils.createRotationTransitionAnimation(
                    binding.bookmarkBackButton,
                    if (it) {
                        R.drawable.ic_action_star
                    } else {
                        R.drawable.ic_action_back
                    }
                )
            )
        }
        viewState.findInPage?.let {
            if (it.isEmpty()) {
                binding.findBar.isVisible = false
            } else {
                binding.findBar.isVisible = true
                binding.findQuery.text = it
            }
        }
        viewState.showBookmarkNativeAdStrip?.let { show ->
            wantsBookmarkNativeAdStrip = show
            if (isIncognito()) {
                bookmarkNativeAdController?.onPresenterShowBookmarkNativeAd(false)
            } else {
                homeScreenNativeAdScheduler.setHostPageVisible(show)
            }
        }
        viewState.showDownloadsNativeAdStrip?.let { show ->
            downloadsNativeAdController?.onPresenterShowBookmarkNativeAd(show)
        }
        viewState.showUrlActionsBar?.let { show ->
            binding.urlActionsBar.root.isVisible = show
            if (::radialFabMenu.isInitialized) {
                updateRadialFabBottomInset()
            }
        }
        viewState.urlActionsTitle?.let { binding.urlActionsBar.urlActionsTitle.text = it }
        viewState.urlActionsHost?.let { binding.urlActionsBar.urlActionsHost.text = it }
        if (viewState.showUrlActionsBar != null) {
            val favicon = viewState.urlActionsFavicon
            if (favicon != null) {
                binding.urlActionsBar.urlActionsFavicon.setImageBitmap(favicon)
            } else {
                binding.urlActionsBar.urlActionsFavicon.setImageResource(R.drawable.ic_webpage)
            }
        }
    }

    /**
     * @see BrowserContract.View.renderTabs
     */
    fun renderTabs(tabListState: List<TabViewState>) {
        binding.tabCountView.updateCount(tabListState.size)
        tabGridTabCount?.updateCount(tabListState.size)
        val gridAdapter = tabsAdapter as? GridTabRecyclerViewAdapter
        val shouldScroll = tabsAdapter.itemCount < tabListState.size
        if (gridAdapter != null) {
            gridAdapter.submitTabList(tabListState)
        } else {
            tabsAdapter.submitList(tabListState)
        }
        val nextSelected = tabListState.indexOfFirst(TabViewState::isSelected)
        if (shouldScroll && nextSelected != -1) {
            mainHandler.post {
                if (tabPager.isBottomTabDrawerOpen()) {
                    activeRecyclerView?.smoothScrollToPosition(nextSelected)
                } else {
                    pendingScroll = nextSelected
                }
            }
        }
    }

    /**
     * @see BrowserContract.View.playBackgroundTabAddedAnimation
     */
    fun playBackgroundTabAddedAnimation() {
        if (!binding.tabCountView.isVisible) {
            return
        }
        BackgroundTabFlyInAnimation.play(
            overlay = binding.root,
            contentView = binding.contentFrame,
            tabSwitcherView = binding.homeButton,
            chipColor = defaultColor,
            chipStrokeColor = themeProvider.color(R.attr.iconColor),
        )
    }

    /**
     * @see BrowserContract.View.showRatingPromptIfEligible
     */
    fun showRatingPromptIfEligible() {
        if (isFinishing || isDestroyed || isIncognito()) {
            presenter.onRatingPromptDismissed()
            return
        }
        showRatingPromptWhenReady(retryCount = 0)
    }

    private fun showRatingPromptWhenReady(retryCount: Int) {
        if (isFinishing || isDestroyed || isIncognito()) {
            presenter.onRatingPromptDismissed()
            return
        }
        if (!hasWindowFocus()) {
            if (retryCount < 10) {
                binding.root.postDelayed({ showRatingPromptWhenReady(retryCount + 1) }, 250L)
            } else {
                presenter.onRatingPromptDeferred()
            }
            return
        }
        binding.root.postDelayed({
            if (isFinishing || isDestroyed || isIncognito()) {
                presenter.onRatingPromptDismissed()
                return@postDelayed
            }
            if (!hasWindowFocus()) {
                if (retryCount < 10) {
                    showRatingPromptWhenReady(retryCount + 1)
                } else {
                    presenter.onRatingPromptDeferred()
                }
                return@postDelayed
            }
            if (!ratingPromptHelper.shouldShowRatingPrompt()) {
                presenter.onRatingPromptDismissed()
                return@postDelayed
            }
            if (RatingPromptDialog.isShowing()) {
                return@postDelayed
            }
            presenter.onRatingPromptShown()
            RatingPromptDialog.show(
                activity = this,
                onRated = {
                    ratingPromptHelper.markRated()
                    presenter.onRatingPromptDismissed()
                    appOpenAdManager.notifyBlockingOverlayDismissed()
                },
                onLater = {
                    ratingPromptHelper.snoozeUntilNextDay()
                    presenter.onRatingPromptDismissed()
                    appOpenAdManager.notifyBlockingOverlayDismissed()
                },
            )
        }, 150L)
    }

    private fun createGridTabsAdapter(): GridTabRecyclerViewAdapter =
        GridTabRecyclerViewAdapter(
            themeProvider = themeProvider,
            onClick = presenter::onTabClick,
            onLongClick = presenter::onTabLongClick,
            onCloseClick = presenter::onTabClose,
            onFilterChanged = { showEmpty ->
                tabGridEmptyView?.isVisible = showEmpty
            },
        )

    private fun setupTabGridUi(
        recyclerView: RecyclerView,
        spanCount: Int,
        tabCountView: TabCountView? = null,
        emptyView: TextView? = null,
        searchView: EditText? = null,
        newTabView: View? = null,
        menuView: View? = null,
        doneView: View? = null,
    ) {
        recyclerView.adapter = tabsAdapter
        recyclerView.layoutManager = GridLayoutManager(this, spanCount)
        recyclerView.itemAnimator?.takeIfInstance<SimpleItemAnimator>()?.supportsChangeAnimations =
            false

        tabCountView?.let { tabGridTabCount = it }
        emptyView?.let { tabGridEmptyView = it }
        searchView?.let { editText ->
            tabGridSearchView = editText
            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                    Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                    Unit

                override fun afterTextChanged(s: Editable?) {
                    (tabsAdapter as? GridTabRecyclerViewAdapter)?.filter(s?.toString().orEmpty())
                }
            })
        }
        newTabView?.setOnClickListener { presenter.onNewTabClick() }
        menuView?.setOnClickListener { presenter.onTabMenuClick() }
        doneView?.let { tabGridDoneButton = it }
        doneView?.setOnClickListener { closeTabDrawer() }
    }

    private fun clearTabGridSearch() {
        tabGridSearchView?.text = null
        tabGridEmptyView?.isVisible = false
        (tabsAdapter as? GridTabRecyclerViewAdapter)?.filter("")
    }

    /**
     * @see BrowserContract.View.showAddBookmarkDialog
     */
    fun showAddBookmarkDialog(title: String, url: String, folders: List<String>) {
        lightningDialogBuilder.showAddBookmarkDialog(
            activity = this,
            currentTitle = title,
            currentUrl = url,
            folders = folders,
            onSave = presenter::onBookmarkConfirmed
        )
    }

    /**
     * @see BrowserContract.View.showBookmarkOptionsDialog
     */
    fun showBookmarkOptionsDialog(bookmark: Bookmark.Entry) {
        lightningDialogBuilder.showLongPressedDialogForBookmarkUrl(
            activity = this,
            onClick = {
                presenter.onBookmarkOptionClick(bookmark, it)
            }
        )
    }

    /**
     * @see BrowserContract.View.showEditBookmarkDialog
     */
    fun showEditBookmarkDialog(title: String, url: String, folder: String, folders: List<String>) {
        lightningDialogBuilder.showEditBookmarkDialog(
            activity = this,
            currentTitle = title,
            currentUrl = url,
            currentFolder = folder,
            folders = folders,
            onSave = presenter::onBookmarkEditConfirmed
        )
    }

    /**
     * @see BrowserContract.View.showFolderOptionsDialog
     */
    fun showFolderOptionsDialog(folder: Bookmark.Folder) {
        lightningDialogBuilder.showBookmarkFolderLongPressedDialog(
            activity = this,
            onClick = {
                presenter.onFolderOptionClick(folder, it)
            }
        )
    }

    /**
     * @see BrowserContract.View.showEditFolderDialog
     */
    fun showEditFolderDialog(oldTitle: String) {
        lightningDialogBuilder.showRenameFolderDialog(
            activity = this,
            oldTitle = oldTitle,
            onSave = presenter::onBookmarkFolderRenameConfirmed
        )
    }

    /**
     * @see BrowserContract.View.showDownloadOptionsDialog
     */
    fun showDownloadOptionsDialog(download: DownloadEntry) {
        lightningDialogBuilder.showLongPressedDialogForDownloadUrl(
            activity = this,
            onClick = {
                presenter.onDownloadOptionClick(download, it)
            }
        )
    }

    /**
     * @see BrowserContract.View.showHistoryOptionsDialog
     */
    fun showHistoryOptionsDialog(historyEntry: HistoryEntry) {
        lightningDialogBuilder.showLongPressedHistoryLinkDialog(
            activity = this,
            onClick = {
                presenter.onHistoryOptionClick(historyEntry, it)
            }
        )
    }

    /**
     * @see BrowserContract.View.showFindInPageDialog
     */
    fun showFindInPageDialog() {
        BrowserDialog.showEditText(
            this,
            R.string.action_find,
            R.string.search_hint,
            R.string.search_hint,
            presenter::onFindInPage
        )
    }

    /**
     * @see BrowserContract.View.showLinkLongPressDialog
     */
    fun showLinkLongPressDialog(longPress: LongPress) {
        BrowserDialog.show(
            this, longPress.targetUrl?.replace(HTTP, ""),
            DialogItem(title = R.string.dialog_open_new_tab) {
                presenter.onLinkLongPressEvent(
                    longPress,
                    BrowserContract.LinkLongPressEvent.NEW_TAB
                )
            },
            DialogItem(title = R.string.dialog_open_background_tab) {
                presenter.onLinkLongPressEvent(
                    longPress,
                    BrowserContract.LinkLongPressEvent.BACKGROUND_TAB
                )
            },
            DialogItem(
                title = R.string.dialog_open_incognito_tab,
                isConditionMet = !isIncognito()
            ) {
                presenter.onLinkLongPressEvent(
                    longPress,
                    BrowserContract.LinkLongPressEvent.INCOGNITO_TAB
                )
            },
            DialogItem(title = R.string.action_share) {
                presenter.onLinkLongPressEvent(longPress, BrowserContract.LinkLongPressEvent.SHARE)
            },
            DialogItem(title = R.string.dialog_copy_link) {
                presenter.onLinkLongPressEvent(
                    longPress,
                    BrowserContract.LinkLongPressEvent.COPY_LINK
                )
            })
    }

    /**
     * @see BrowserContract.View.showImageLongPressDialog
     */
    fun showImageLongPressDialog(longPress: LongPress) {
        BrowserDialog.show(
            this, longPress.targetUrl?.replace(HTTP, ""),
            DialogItem(title = R.string.dialog_open_new_tab) {
                presenter.onImageLongPressEvent(
                    longPress,
                    BrowserContract.ImageLongPressEvent.NEW_TAB
                )
            },
            DialogItem(title = R.string.dialog_open_background_tab) {
                presenter.onImageLongPressEvent(
                    longPress,
                    BrowserContract.ImageLongPressEvent.BACKGROUND_TAB
                )
            },
            DialogItem(
                title = R.string.dialog_open_incognito_tab,
                isConditionMet = !isIncognito()
            ) {
                presenter.onImageLongPressEvent(
                    longPress,
                    BrowserContract.ImageLongPressEvent.INCOGNITO_TAB
                )
            },
            DialogItem(title = R.string.action_share) {
                presenter.onImageLongPressEvent(
                    longPress,
                    BrowserContract.ImageLongPressEvent.SHARE
                )
            },
            DialogItem(title = R.string.dialog_copy_link) {
                presenter.onImageLongPressEvent(
                    longPress,
                    BrowserContract.ImageLongPressEvent.COPY_LINK
                )
            },
            DialogItem(title = R.string.dialog_download_image) {
                presenter.onImageLongPressEvent(
                    longPress,
                    BrowserContract.ImageLongPressEvent.DOWNLOAD
                )
            })
    }

    /**
     * @see BrowserContract.View.showCloseBrowserDialog
     */
    fun showCloseBrowserDialog(id: Int) {
        BrowserDialog.show(
            this, R.string.dialog_title_close_browser,
            DialogItem(title = R.string.close_tab) {
                presenter.onCloseBrowserEvent(id, BrowserContract.CloseTabEvent.CLOSE_CURRENT)
            },
            DialogItem(title = R.string.close_other_tabs) {
                presenter.onCloseBrowserEvent(id, BrowserContract.CloseTabEvent.CLOSE_OTHERS)
            },
            DialogItem(title = R.string.close_all_tabs, onClick = {
                presenter.onCloseBrowserEvent(id, BrowserContract.CloseTabEvent.CLOSE_ALL)
            })
        )
    }

    /**
     * @see BrowserContract.View.openBookmarkDrawer
     */
    fun openBookmarkDrawer() {
        binding.drawerLayout.closeDrawer(binding.tabDrawer)
        binding.drawerLayout.openDrawer(binding.bookmarkDrawer)
    }

    /**
     * @see BrowserContract.View.closeBookmarkDrawer
     */
    fun closeBookmarkDrawer() {
        binding.drawerLayout.closeDrawer(binding.bookmarkDrawer)
    }

    /**
     * @see BrowserContract.View.openTabDrawer
     */
    fun openTabDrawer() {
        binding.drawerLayout.closeDrawer(binding.bookmarkDrawer)
        inputMethodManager.hideSoftInputFromWindow(binding.root.windowToken, 0)
        clearTabGridSearch()
        presenter.onTabDrawerMoved(isOpen = true)
        tabPager.openBottomTabDrawer()
        if (pendingScroll != -1) {
            activeRecyclerView?.scrollToPosition(pendingScroll)
            pendingScroll = -1
        }
    }

    /**
     * @see BrowserContract.View.closeTabDrawer
     */
    fun closeTabDrawer() {
        presenter.onTabDrawerMoved(isOpen = false)
        tabPager.closeBottomTabDrawer()
        clearTabGridSearch()
        inputMethodManager.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    /**
     * @see BrowserContract.View.showToolbar
     */
    fun showToolbar() {
        tabPager.showToolbar()
    }

    /**
     * @see BrowserContract.View.showToolsDialog
     */
    fun showToolsDialog(areAdsAllowed: Boolean, shouldShowAdBlockOption: Boolean) {
        val whitelistString = if (areAdsAllowed) {
            R.string.dialog_adblock_enable_for_site
        } else {
            R.string.dialog_adblock_disable_for_site
        }

        BrowserDialog.showWithIcons(
            this, getString(R.string.dialog_tools_title),
            DialogItem(
                icon = drawable(R.drawable.ic_action_desktop),
                title = R.string.dialog_toggle_desktop,
                onClick = presenter::onToggleDesktopAgent
            ),
            DialogItem(
                icon = drawable(R.drawable.ic_block),
                colorTint = color(R.color.error_red).takeIf { areAdsAllowed },
                title = whitelistString,
                isConditionMet = shouldShowAdBlockOption,
                onClick = presenter::onToggleAdBlocking
            )
        )
    }

    /**
     * @see BrowserContract.View.showLocalFileBlockedDialog
     */
    fun showLocalFileBlockedDialog() {
        AlertDialog.Builder(this)
            .setCancelable(true)
            .setTitle(R.string.title_warning)
            .setMessage(R.string.message_blocked_local)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                presenter.onConfirmOpenLocalFile(allow = false)
            }
            .setPositiveButton(R.string.action_open) { _, _ ->
                presenter.onConfirmOpenLocalFile(allow = true)
            }
            .setOnCancelListener { presenter.onConfirmOpenLocalFile(allow = false) }
            .resizeAndShow()
    }

    /**
     * @see BrowserContract.View.showFileChooser
     */
    fun showFileChooser(intent: Intent) {
        launcher.launch(intent)
    }

    /**
     * @see BrowserContract.View.showCustomView
     */
    fun showCustomView(view: View) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        binding.root.addView(view)
        customView = view
        setFullscreen(enabled = true, immersive = true)
    }

    /**
     * @see BrowserContract.View.hideCustomView
     */
    fun hideCustomView() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        customView?.let(binding.root::removeView)
        customView = null
        setFullscreen(enabled = false, immersive = false)
    }

    /**
     * @see BrowserContract.View.clearSearchFocus
     */
    fun clearSearchFocus() {
        binding.search.clearFocus()
    }

    // TODO: update to use non deprecated flags
    private fun setFullscreen(enabled: Boolean, immersive: Boolean) {
        val window = window
        val decor = window.decorView
        if (enabled) {
            if (immersive) {
                decor.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            } else {
                decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun animateColorChange(color: Int) {
        if (!userPreferences.colorModeEnabled || userPreferences.useTheme != AppTheme.LIGHT || isIncognito()) {
            return
        }
        val adapter = tabsAdapter as? DesktopTabRecyclerViewAdapter
        val colorAnimator = ColorAnimator(defaultColor)
        binding.toolbar.startAnimation(
            colorAnimator.animateTo(
                color
            ) { mainColor, secondaryColor ->
                if (userPreferences.tabConfiguration != TabConfiguration.DESKTOP) {
                    backgroundDrawable.color = mainColor
                    window.setBackgroundDrawable(backgroundDrawable)
                } else {
                    adapter?.updateForegroundTabColor(mainColor)
                }
                binding.toolbar.setBackgroundColor(mainColor)
                binding.searchContainer.background?.tint(secondaryColor)
            })
    }

    private fun ImageView.updateVisibilityForDrawable() {
        visibility = if (drawable == null) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun applyBookmarkNativeAdStripVisibility(schedulerEligible: Boolean) {
        bookmarkNativeAdController?.onPresenterShowBookmarkNativeAd(
            wantsBookmarkNativeAdStrip && schedulerEligible,
        )
    }

    private fun maybeShowDefaultBrowserPrompt() {
        if (isIncognito()) return
        if (userPreferences.suppressDefaultBrowserPrompt) return
        if (DefaultBrowserHelper.isAppDefaultBrowser(this)) return

        val now = System.currentTimeMillis()
        if (now < userPreferences.defaultBrowserPromptSnoozedUntilMs) return
        val last = userPreferences.lastDefaultBrowserPromptEpochMs
        if (last != 0L && now - last < DEFAULT_BROWSER_PROMPT_MIN_INTERVAL_MS) return
        if (defaultBrowserPrompt?.isShowing == true) return

        val snoozeMs = DEFAULT_BROWSER_SNOOZE_MS
        appOpenAdManager.setDefaultBrowserPromptVisible(true)
        defaultBrowserPrompt = DefaultBrowserPromptDialog.show(
            activity = this,
            onSetAsDefault = {
                val intent = DefaultBrowserHelper.createDefaultBrowserSettingsIntent(this)
                appOpenAdManager.setDefaultBrowserSystemFlowActive(true)
                appOpenAdManager.setDefaultBrowserPromptVisible(false)
                try {
                    defaultBrowserRoleLauncher.launch(intent)
                } catch (_: Exception) {
                    DefaultBrowserHelper.launchDefaultBrowserFlow(this)
                    appOpenAdManager.setDefaultBrowserSystemFlowActive(false)
                }
            },
            onNotNow = {
                userPreferences.defaultBrowserPromptSnoozedUntilMs = now + snoozeMs
            },
            onDismiss = {
                defaultBrowserPrompt = null
                appOpenAdManager.setDefaultBrowserPromptVisible(false)
                if (!DefaultBrowserHelper.isAppDefaultBrowser(this)) {
                    userPreferences.lastDefaultBrowserPromptEpochMs = System.currentTimeMillis()
                }
            },
        )
    }

    private companion object {
        private const val DEFAULT_BROWSER_PROMPT_MIN_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val DEFAULT_BROWSER_SNOOZE_MS = 24L * 60 * 60 * 1000
        private const val STATE_INTERSTITIAL_ACCUMULATED_MS = "interstitial_accumulated_active_ms"
    }
}
