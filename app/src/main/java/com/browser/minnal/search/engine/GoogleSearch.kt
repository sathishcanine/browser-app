package com.browser.minnal.search.engine

import com.browser.minnal.R

/**
 * The Google search engine.
 *
 * See https://www.google.com/images/srpr/logo11w.png for the icon.
 */
class GoogleSearch : BaseSearchEngine(
    "file:///android_asset/google.png",
    "https://www.google.com/search?client=lightning&ie=UTF-8&oe=UTF-8&q=",
    R.string.search_engine_google
)
