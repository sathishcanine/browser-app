package com.browser.minnal.migration

import android.app.Application
import java.io.File

/** Helpers for cached in-app HTML under `filesDir/generated-html/`. */
object GeneratedHtmlFiles {

    fun deletePage(application: Application, filename: String) {
        val file = File(File(application.filesDir, GENERATED_HTML_DIR), filename)
        if (file.exists()) {
            file.delete()
        }
    }

    private const val GENERATED_HTML_DIR = "generated-html"
}
