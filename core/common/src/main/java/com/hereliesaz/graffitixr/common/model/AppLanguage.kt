package com.hereliesaz.graffitixr.common.model

/**
 * The languages the app can be switched to.
 *
 * [code] is a **BCP-47 language tag**, because that is what it is used as:
 * `LocaleListCompat.forLanguageTags(code)` parses it, and the resulting locale is what Android
 * resolves resources against. It is NOT an Android resource qualifier — the two look alike but a
 * region is written `zh-CN` in a tag and `values-zh-rCN` on a directory. Traditional and Simplified
 * Chinese were both carrying the qualifier form, and `forLanguageTags("zh-rCN")` cannot parse `rCN`
 * as a region, so it degraded to bare `zh`, which matches neither `values-zh-rCN` nor
 * `values-zh-rHK` — picking either Chinese silently left the UI in English.
 *
 * Every entry here must have a matching `values-*` directory in `:core:design` (or be [SYSTEM] /
 * [ENGLISH], which use the default resources). An entry without one is a menu item that does
 * nothing, and a translation without an entry is work that ships and can never be selected.
 */
enum class AppLanguage(val code: String, val displayName: String) {
    SYSTEM("", "System Default"),
    ENGLISH("en", "English"),
    DUTCH("nl", "Nederlands"),
    NORWEGIAN("no", "Norsk"),
    FRENCH("fr", "Français"),
    SWEDISH("sv", "Svenska"),
    GERMAN("de", "Deutsch"),
    ITALIAN("it", "Italiano"),
    JAPANESE("ja", "日本語"),
    PORTUGUESE("pt", "Português"),
    HUNGARIAN("hu", "Magyar"),
    SPANISH("es", "Español"),
    RUSSIAN("ru", "Русский"),
    CHINESE_SIMPLIFIED("zh-CN", "简体中文"),
    CHINESE_TRADITIONAL("zh-HK", "繁體中文"),
}
