package org.localwake.app

import com.phoneuse.R

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

internal enum class AppLanguage(val locale: Locale, @StringRes val label: Int) {
    ENGLISH(Locale.ENGLISH, R.string.language_english),
    SIMPLIFIED_CHINESE(Locale.SIMPLIFIED_CHINESE, R.string.language_chinese);

    fun wrap(context: Context): Context {
        val override = Configuration()
        override.setLocale(locale)
        return context.createConfigurationContext(override)
    }

    fun persist(context: Context): Boolean {
        val saved = preferences(context).edit().putString(LANGUAGE_KEY, locale.toLanguageTag()).commit()
        if (saved) com.phoneuse.i18n.Lang.applyPreference(locale.toLanguageTag())
        return saved
    }

    companion object {
        const val LANGUAGE_KEY = "language"

        fun preferences(context: Context) = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        fun current(context: Context): AppLanguage {
            val saved = preferences(context).getString(LANGUAGE_KEY, null)
            return if (saved?.substringBefore('-')?.equals("zh", ignoreCase = true) == true) SIMPLIFIED_CHINESE else ENGLISH
        }
    }
}
