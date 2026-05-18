import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.github.ben-manes.versions")
    id("com.google.devtools.ksp") version "2.3.7"
    id("com.anthonycr.plugins.mezzanine") version "2.3.0"
    id("com.autonomousapps.dependency-analysis") version "3.10.0"
    id("com.squareup.sort-dependencies") version "0.17.1"
    // Apply last so merged Firebase JSON / Crashlytics mapping run after other codegen.
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        versionName = "1.0.0"
        vectorDrawables.useSupportLibrary = true
        // AdMob app id (tilde form). Override in `release` with the id from AdMob → Apps → App settings.
        manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
    }

    val isCi = System.getenv("CI") == "true"

    sourceSets {
        create("lightningPlus").apply {
            setRoot("src/LightningPlus")
        }
        if (!isCi) {
            create("lightningLite").apply {
                setRoot("src/LightningLite")
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        named("debug") {
            multiDexEnabled = true
            isMinifyEnabled = false
            isShrinkResources = false
            setProguardFiles(listOf("proguard-project.txt"))
            enableUnitTestCoverage = false
            enableAndroidTestCoverage = false
            buildConfigField(
                "String",
                "BOOKMARK_NATIVE_AD_UNIT_ID",
                "\"ca-app-pub-3940256099942544/2247696110\""
            )
        }

        named("release") {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            multiDexEnabled = false
            isMinifyEnabled = !isCi
            isShrinkResources = !isCi
            setProguardFiles(listOf("proguard-project.txt"))
            enableUnitTestCoverage = false
            enableAndroidTestCoverage = false
            // Replace with your real AdMob application id (AdMob → Apps → App settings), tilde form.
            manifestPlaceholders["admobAppId"] = "ca-app-pub-4789468551786381~4851690628"
            buildConfigField(
                "String",
                "BOOKMARK_NATIVE_AD_UNIT_ID",
                "\"ca-app-pub-4789468551786381/3791531224\""
            )

            ndk {
                abiFilters.add("arm64-v8a")
                abiFilters.add("armeabi-v7a")
                abiFilters.add("armeabi")
                abiFilters.add("mips")
            }
        }
    }

    flavorDimensions.add("capabilities")

    productFlavors {
        create("lightningPlus") {
            dimension = "capabilities"
            buildConfigField("boolean", "FULL_VERSION", "Boolean.parseBoolean(\"true\")")
            applicationId = "com.browser.minnal"
            versionCode = 6
        }

        if (!isCi) {
            create("lightningLite") {
                dimension = "capabilities"
                buildConfigField("boolean", "FULL_VERSION", "Boolean.parseBoolean(\"true\")")
                applicationId = "com.browser.minnal"
                versionCode = 6
            }
        }
    }
    packaging {
        resources {
            excludes += listOf(".readme")
        }
    }
    lint {
        abortOnError = true
    }
    namespace = "com.browser.minnal"
}

dependencies {
    val robolectric = "4.16.1"
    val mezzanineVersion = "2.3.0"
    val daggerVersion = "2.59.2"
    val kotlin = "2.3.21"
    val datastore = "1.2.1"
    val coil = "3.4.0"

    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.annotation:annotation:1.10.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.3.0")
    implementation("androidx.core:core:1.18.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.datastore:datastore:$datastore")
    implementation("androidx.datastore:datastore-core:$datastore")
    implementation("androidx.datastore:datastore-preferences:$datastore")
    implementation("androidx.datastore:datastore-preferences-core:$datastore")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.lifecycle:lifecycle-common:2.10.0")
    implementation("androidx.palette:palette:1.0.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.15.0")
    // Pin WorkManager; transitive copy from Firebase/Ads can init at app startup. We disable
    // WorkManagerInitializer in the manifest and use on-demand init via Configuration.Provider.
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.anthonycr.mezzanine:core:$mezzanineVersion")
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.google.dagger:dagger:$daggerVersion")
    implementation("com.guolindev.permissionx:permissionx:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.okio:okio:3.17.0")
    implementation("io.coil-kt.coil3:coil:$coil")
    implementation("io.coil-kt.coil3:coil-core:$coil")
    implementation("io.coil-kt.coil3:coil-network-okhttp:$coil")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.2")
    implementation("io.reactivex.rxjava3:rxjava:3.1.12")
    implementation("io.reactivex.rxjava3:rxkotlin:3.0.1")
    implementation("javax.inject:javax.inject:1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlin")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("org.jspecify:jspecify:1.0.0")
    implementation("org.reactivestreams:reactive-streams:1.0.4")

    implementation(platform("com.google.firebase:firebase-bom:33.9.0"))
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-config-ktx")
    implementation("com.google.firebase:firebase-crashlytics-ktx")
    implementation("com.google.android.play:app-update:2.1.0")

    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")

    compileOnly("javax.annotation:jsr250-api:1.0")

    testImplementation("com.nhaarman:mockito-kotlin:1.6.0") {
        exclude(group = "org.jetbrains.kotlin")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.robolectric:annotations:$robolectric")
    testImplementation("org.robolectric:robolectric:$robolectric")
    testImplementation("org.robolectric:shadows-framework:$robolectric")

    ksp("com.anthonycr.mezzanine:processor:$mezzanineVersion")
    ksp("com.google.dagger:dagger-compiler:$daggerVersion")
}

mezzanine {
    files = files(
        "src/main/html/list.html",
        "src/main/html/bookmarks.html",
        "src/main/html/homepage.html",
        "src/main/js/InvertPage.js",
        "src/main/js/TextReflow.js",
        "src/main/js/ThemeColor.js"
    )
}

kotlin {
    jvmToolchain(17)
}
