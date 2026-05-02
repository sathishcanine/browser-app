@file:JvmName("Injector")

package com.browser.minnal.browser.di

import com.browser.minnal.BrowserApp
import android.content.Context
import androidx.fragment.app.Fragment

/**
 * The [AppComponent] attached to the application [Context].
 */
val Context.injector: AppComponent
    get() = (applicationContext as BrowserApp).applicationComponent

/**
 * The [AppComponent] attached to the context, note that the fragment must be attached.
 */
val Fragment.injector: AppComponent
    get() = (context!!.applicationContext as BrowserApp).applicationComponent
