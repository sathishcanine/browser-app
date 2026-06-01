package com.browser.minnal.browser.tab

import com.browser.minnal.browser.BrowserContract
import com.browser.minnal.browser.di.DiskScheduler
import com.browser.minnal.browser.di.InitialUrl
import com.browser.minnal.browser.di.MainScheduler
import com.browser.minnal.browser.tab.bundle.BundleStore
import com.browser.minnal.preference.UserPreferences
import com.browser.minnal.utils.isFileUrl
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import javax.inject.Inject

/**
 * The repository for tabs that implements the [BrowserContract.Model] interface. Manages the state
 * of the tabs list and adding new tabs to it or removing tabs from it.
 */
class TabsRepository @Inject constructor(
    private val webViewFactory: WebViewFactory,
    private val tabPager: TabPager,
    @DiskScheduler private val diskScheduler: Scheduler,
    @MainScheduler private val mainScheduler: Scheduler,
    private val bundleStore: BundleStore,
    private val recentTabModel: RecentTabModel,
    private val tabFactory: TabFactory,
    private val userPreferences: UserPreferences,
    @InitialUrl private val initialUrl: String?,
    private val permissionInitializerFactory: PermissionInitializer.Factory
) : BrowserContract.Model {

    private var isInitialized = BehaviorSubject.createDefault(false)
    private var selectedTab: TabModel? = null
    private val tabsListObservable = PublishSubject.create<List<TabModel>>()

    private fun afterInitialization(): Single<Boolean> =
        isInitialized.filter { it }.firstOrError()

    override fun deleteTab(id: Int): Completable = Completable.fromAction {
        tabPager.removeTabEntry(id)
        if (selectedTab?.id == id) {
            tabPager.clearTab()
        }
        val tab = tabsList.forId(id)
        recentTabModel.addClosedTab(tab.freeze())
        tab.destroy()
        tabsList = tabsList - tab
    }.doOnComplete {
        tabsListObservable.onNext(tabsList)
    }.subscribeOn(mainScheduler)

    override fun deleteAllTabs(): Completable =
        afterInitialization().flatMapCompletable {
            Completable.fromAction {
                tabPager.clearTab()
                tabsList.forEach { tab ->
                    tabPager.removeTabEntry(tab.id)
                    tab.destroy()
                }
                tabsList = emptyList()
            }
        }.doOnComplete {
            tabsListObservable.onNext(tabsList)
        }.subscribeOn(mainScheduler)

    override fun createTab(
        tabInitializer: TabInitializer,
        tabType: TabModel.Type
    ): Single<TabModel> = afterInitialization()
        .flatMap { createTabUnsafe(tabInitializer, tabType) }
        .subscribeOn(mainScheduler)

    /**
     * Creates a tab without waiting for the browser to be initialized.
     */
    private fun createTabUnsafe(
        tabInitializer: TabInitializer,
        tabType: TabModel.Type
    ): Single<TabModel> =
        Single.fromCallable(webViewFactory::createWebView)
            .flatMap { webViewLazy ->
                tabFactory.constructTab(tabInitializer, webViewLazy, tabType)
                    .map { webViewLazy to it }
            }
            .doOnSuccess { (webViewLazy, tabModel) ->
                tabPager.addTab(tabModel.id, webViewLazy)
            }
            .map { (_, tabModel) -> tabModel }
            .doOnSuccess {
                tabsList = tabsList + it
                tabsListObservable.onNext(tabsList)
            }
            .subscribeOn(mainScheduler)

    override fun reopenTab(): Maybe<TabModel> = Maybe.fromCallable(recentTabModel::lastClosed)
        .flatMapSingle { createTab(BundleInitializer(it)) }
        .subscribeOn(mainScheduler)

    override fun selectTab(id: Int): TabModel {
        val selected = tabsList.forId(id)
        selectedTab = selected
        tabPager.selectTab(id)

        return selected
    }

    override var tabsList = emptyList<TabModel>()
        private set

    override fun tabsListChanges(): Observable<List<TabModel>> = tabsListObservable.hide()

    private var restoredSelectedTabId: Int? = null

    override fun initializeTabs(): Maybe<List<TabModel>> =
        Single.fromCallable(bundleStore::retrieve)
            .subscribeOn(diskScheduler)
            .observeOn(mainScheduler)
            .doOnSuccess { restoredSelectedTabId = it.selectedTabId }
            .flatMapObservable { Observable.fromIterable(it.initializers) }
            .flatMapSingle { createTabUnsafe(it, tabType = TabModel.Type.NORMAL) }
            .concatWith(Maybe.fromCallable { initialUrl }.map {
                if (it.isFileUrl()) {
                    permissionInitializerFactory.create(it)
                } else {
                    UrlInitializer(it)
                }
            }.flatMapSingle { createTabUnsafe(it, tabType = TabModel.Type.EPHEMERAL) })
            .toList()
            .filter(List<TabModel>::isNotEmpty)
            .doAfterTerminate {
                isInitialized.onNext(true)
            }

    override fun markAllNonEphemeral() {
        tabsList.forEach { tab ->
            if (tab.tabType == TabModel.Type.EPHEMERAL) {
                tab.tabType = TabModel.Type.NORMAL
            }
        }
    }

    override fun freeze() {
        if (userPreferences.restoreLostTabsEnabled) {
            val tabsToSave = tabsList.filter { it.tabType != TabModel.Type.POP_UP }
            bundleStore.save(tabsToSave, selectedTab?.id)
        }
    }

    override fun restoredSelectedTabId(): Int? = restoredSelectedTabId

    override fun clean() {
        bundleStore.deleteAll()
    }

    private fun List<TabModel>.forId(id: Int): TabModel = requireNotNull(find { it.id == id })
}
