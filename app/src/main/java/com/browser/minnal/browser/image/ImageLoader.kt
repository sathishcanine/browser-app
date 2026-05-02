package com.browser.minnal.browser.image

import com.browser.minnal.database.Bookmark
import android.widget.ImageView

/**
 * Loads images for bookmark entries.
 */
interface ImageLoader {

    /**
     * Load a the favicon into the [imageView] for the provided [bookmark].
     */
    fun loadImage(imageView: ImageView, bookmark: Bookmark)

}
