package com.phoneuse.i18n

import android.os.LocaleList
import java.util.Locale

/**
 * The app uses the saved app_settings language, defaulting to English.
 *
 * User-facing text is written in place as [tr]("English", "한국어"), so both languages stay next to each other
 * and plain JVM code (routines, nudges, cards) can be localized without a Context. Unit tests run in Korean
 * (`user.language=ko` in app/build.gradle.kts), so existing assertions keep their Korean text.
 */
object Lang {
    @Volatile
    private var selectedTag: String = if (isKorean(Locale.getDefault())) "ko" else "en"

    /** Compatibility for existing callers and Korean JVM fixtures. App startup calls [applyPreference]. */
    var korean: Boolean
        get() = selectedTag == "ko"
        set(value) { selectedTag = if (value) "ko" else "en" }

    val chinese: Boolean get() = selectedTag == "zh-CN"

    /** No preference means English, including on a Chinese or Korean system. */
    fun applyPreference(savedTag: String?) {
        selectedTag = if (savedTag?.substringBefore('-')?.equals("zh", ignoreCase = true) == true) "zh-CN" else "en"
    }

    /** Follows [locales]' first language; called at startup and when the configuration changes. */
    fun update(locales: LocaleList) {
        val locale = locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
        selectedTag = when (locale.language) { "ko" -> "ko"; "zh" -> "zh-CN"; else -> "en" }
    }

    fun isKorean(locale: Locale): Boolean = locale.language == "ko"

    /** The language tag the model is told to answer in when the user's own words do not settle it. */
    val tag: String get() = selectedTag
}

/** [en], or [ko] when the app runs in Korean. */
fun tr(en: String, ko: String): String = when {
    Lang.korean -> ko
    Lang.chinese -> ChineseStrings.translate(en)
    else -> en
}
