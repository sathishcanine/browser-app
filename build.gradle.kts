buildscript {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }

    extra.apply {
        set("minSdkVersion", 21)
        set("targetSdkVersion", 30)
        set("buildToolsVersion", 30)
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("com.github.ben-manes.versions") version "0.54.0" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.google.firebase.crashlytics") version "3.0.3" apply false
}
