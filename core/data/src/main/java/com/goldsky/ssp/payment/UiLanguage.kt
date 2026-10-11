package com.goldsky.ssp.payment

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * The terminal's interface language (Iris / Ourea), chosen in Settings and
 * applied to the whole activity: strings come from res/values-<code>, and an
 * RTL language (Arabic, Persian) mirrors the layout. Stored per terminal.
 *
 * [ALL] is what the structure is ready for (every store is in Canada: English,
 * Chinese, French, and Arabic / Persian for Middle-Eastern owners); [available]
 * is what has translations shipped -- a language only appears in Settings once
 * its values-<code>/strings.xml files exist.
 */
object UiLanguage {
    data class Lang(val code: String, val nativeName: String, val rtl: Boolean = false)

    val ALL = listOf(
        Lang("en", "English"),
        Lang("zh", "中文"),
        Lang("fr", "Français"),
        Lang("ar", "العربية", rtl = true),
        Lang("fa", "فارسی", rtl = true),
    )

    /** Languages with shipped translations (add a code here with its values-<code> files). */
    val available: List<Lang> get() = ALL.filter { it.code in SHIPPED }
    private val SHIPPED = setOf("en", "zh")

    private const val PREFS = "device_repo_prefs" // DeviceRepository's: read before it is initialised
    private const val KEY = "ui_language"

    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { c -> ALL.any { it.code == c } } ?: "en"

    fun set(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
    }

    fun isRtl(code: String) = ALL.firstOrNull { it.code == code }?.rtl == true

    /**
     * For Activity.attachBaseContext: the base context with the chosen locale
     * (and layout direction). Resources only -- deliberately NOT Locale.setDefault:
     * under Arabic or Persian, "%.2f".format() and SimpleDateFormat would switch
     * to Arabic-Indic digits, in money on receipts and in dates sent to the server.
     */
    fun wrap(base: Context): Context {
        // "-u-nu-latn": Western digits even in Arabic / Persian. Resource lookups
        // still find values-ar / values-fa, but a formatted "%d" in a string
        // stays 30 rather than ٣٠ -- next to prices that are always $1.99, mixed
        // digit systems on one card are a misreading waiting to happen at a till.
        val code = current(base)
        val locale = Locale.forLanguageTag(if (isRtl(code)) "$code-u-nu-latn" else code)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
