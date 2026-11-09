package com.hereliesaz.graffitixr.common.privacy

import android.content.Context

/**
 * The one stored answer to "may Graffux file reports about this device as public GitHub issues"
 * (Settings -> Privacy -> Crash reports). It lives here, below both the app and the editor, so every
 * path that files an issue (crash upload, the saved ranking, the live ranking) reads the same
 * answer. The app's PrivacyConsent writes it; nothing else should.
 *
 * Off until the user turns it on. The file is excluded from backup and device transfer, so a restore
 * never brings a consent over to a device whose user was not asked.
 */
object ReportConsent {
    const val PREFS = "privacy_consent"
    const val KEY_CRASH = "crash_reports_enabled"
    const val KEY_CRASH_SINCE = "crash_reports_since"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun crashReportsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CRASH, false)

    /** Wall-clock time of the opt-in; reports older than this are never uploaded. */
    fun crashReportsSince(context: Context): Long = prefs(context).getLong(KEY_CRASH_SINCE, Long.MAX_VALUE)

    fun setCrashReportsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_CRASH, enabled)
            .putLong(KEY_CRASH_SINCE, if (enabled) System.currentTimeMillis() else Long.MAX_VALUE)
            .apply()
    }
}
