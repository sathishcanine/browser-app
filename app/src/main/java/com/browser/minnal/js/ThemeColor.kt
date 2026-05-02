package com.browser.minnal.js

import com.anthonycr.mezzanine.FileStream

/**
 * Reads the theme color from the DOM.
 */
@FileStream("src/main/js/ThemeColor.js")
interface ThemeColor {

    fun provideJs(): String

}