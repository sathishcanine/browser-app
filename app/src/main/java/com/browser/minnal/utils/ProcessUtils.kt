package com.browser.minnal.utils

import android.app.Application
import android.content.Context
import android.os.Build

/** True when running in the isolated incognito browser process (`:incognito`). */
fun Context.isIncognitoProcess(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        Application.getProcessName() == "${applicationContext.packageName}:incognito"
