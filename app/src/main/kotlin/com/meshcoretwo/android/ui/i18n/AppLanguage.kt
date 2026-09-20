// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.ui.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Languages the UI is translated into. [tag] is the BCP-47 tag (`null` = follow the system) and
 * [nativeName] is the endonym shown in the picker — never translated, so a user who ended up in a
 * language they can't read can still find their own. Keep in sync with `res/xml/locales_config.xml`
 * and the `values-*` resource directories.
 */
enum class AppLanguage(val tag: String?, val nativeName: String?) {
    SYSTEM(null, null),
    ENGLISH("en", "English"),
    RUSSIAN("ru", "Русский"),
    FRENCH("fr", "Français"),
    GERMAN("de", "Deutsch"),
    CHINESE("zh-CN", "简体中文"),
    TURKISH("tr", "Türkçe"),
    FINNISH("fi", "Suomi"),
    SWEDISH("sv", "Svenska"),
}

/**
 * Per-app language selection without AppCompat. On Android 13+ the platform `LocaleManager` owns
 * the choice (and the system settings "App languages" screen stays in sync via `localeConfig`);
 * on 8.0–12 the choice lives in a private preference and is applied by wrapping the base context
 * ([wrap], called from `attachBaseContext` of the Application and the Activity) plus a recreate.
 */
object AppLanguageManager {
    private const val PREFS = "app_language"
    private const val KEY_TAG = "tag"

    /** The explicitly chosen language, or [AppLanguage.SYSTEM]. */
    fun current(context: Context): AppLanguage {
        val tag = if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) null else locales[0].toLanguageTag()
        } else {
            storedTag(context)
        }
        return fromTag(tag)
    }

    /** Applies [language]; on API < 33 the activity is recreated so the new resources load. */
    fun set(activity: Activity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (language.tag == null) remove(KEY_TAG) else putString(KEY_TAG, language.tag)
            }.apply()
            activity.recreate()
        }
    }

    /** `attachBaseContext` hook: a no-op on API 33+ where the platform applies the locale itself. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = storedTag(base)
        if (tag == null) {
            // Back to "system": the process default still holds the previously chosen language,
            // which would keep date/time/number formatting (Locale.getDefault()) in it.
            Locale.setDefault(base.resources.configuration.locales[0])
            return base
        }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    private fun storedTag(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null)

    /** Matches on the language part only, so `zh-Hans-CN` from the system still maps to CHINESE. */
    private fun fromTag(tag: String?): AppLanguage {
        if (tag == null) return AppLanguage.SYSTEM
        val language = Locale.forLanguageTag(tag).language
        return AppLanguage.entries.firstOrNull { it.tag != null && Locale.forLanguageTag(it.tag).language == language }
            ?: AppLanguage.SYSTEM
    }
}
