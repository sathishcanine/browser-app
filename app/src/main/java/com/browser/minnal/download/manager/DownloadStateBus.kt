package com.browser.minnal.download.manager

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-singleton in-memory cache + Rx stream of live [DownloadState]s.
 *
 * The downloader writes to this from background threads (worker / receiver); UI components
 * (Downloads page, browser presenter) consume the [Observable] on the main thread to render
 * progress without polling SQLite on every byte.
 *
 * The DB is still the source of truth for restarts / process death — this bus is a pure
 * convenience cache and is wiped when the process is killed.
 */
@Singleton
class DownloadStateBus @Inject constructor() {

    private val states = ConcurrentHashMap<String, DownloadState>()
    private val subject: PublishSubject<DownloadState> = PublishSubject.create()

    /** Latest known state for [url], or null if the manager has never tracked it this run. */
    fun snapshot(url: String): DownloadState? = states[url]

    /** All currently-tracked states. */
    fun all(): Map<String, DownloadState> = states.toMap()

    /** Push an updated state for [DownloadState.url]. Replaces any prior cached value. */
    fun update(state: DownloadState) {
        states[state.url] = state
        subject.onNext(state)
    }

    /** Drop the cached entry for a URL (e.g. user deleted it). */
    fun forget(url: String) {
        states.remove(url)
    }

    /** Stream of every state change. Subscribe on the main scheduler in UI. */
    fun changes(): Observable<DownloadState> = subject.hide()
}
