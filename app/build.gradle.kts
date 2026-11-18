
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
}

android {
    namespace = "com.phoneuse"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.localwake.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 12
        versionName = "0.5.0"
        // AGP replaces the manifest runner with this value. Select the guarded physical-device
        // fixture explicitly when building its separate test APK; the shipped app has no runner.
        testInstrumentationRunner = providers.gradleProperty("unlocktime.testRunner")
            .orElse("org.localwake.app.EmulatorCompatibilityInstrumentation").get()
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // User-facing text follows the device language (i18n/Lang); tests pin it to Korean.
        unitTests.all {
            it.systemProperty("user.language", "ko")
            it.systemProperty("user.country", "KR")
        }
    }
    // Compressed dex keeps the download small enough to attach to an email (about 75MB uncompressed).
    packaging {
        dex { useLegacyPackaging = true }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    bundle { language { enableSplit = false } }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.text.google.fonts)
    implementation(libs.activity.compose)
    implementation(libs.fragment) // A library brings 1.1.0, too old for registerForActivityResult (release lint).
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.datastore.preferences)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.service)
    implementation(libs.savedstate)
    implementation(libs.exifinterface)
    implementation(libs.osmdroid)
    implementation(libs.window)
    // Place routines: geofences (Galaxy phones have Play services; without them place routines are off).
    implementation(libs.play.services.location)
    // Home-screen widgets (widget/): Glance, and WorkManager for the Today brief's background refresh.
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.work.runtime)
    // Claude (Anthropic API key). Its Jackson Kotlin module needs kotlin-reflect at the compiler's version.
    implementation(libs.anthropic.java)
    implementation(libs.kotlin.reflect)

    testImplementation("org.json:json:20250517")
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Runs the chat.db schema and its FTS queries on real SQLite in JVM tests.
    testImplementation(libs.sqlite.jdbc)
}
