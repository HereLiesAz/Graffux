package com.hereliesaz.graffux

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import com.hereliesaz.graffitixr.common.privacy.ReportConsent

/**
 * The two data-sharing choices that are off until the user turns them on in Settings → Privacy.
 *
 * - Usage analytics: Firebase Analytics' automatic events. Collection is disabled in the manifest
 *   (`firebase_analytics_collection_enabled=false`), so nothing is collected before [apply] reads
 *   an opt-in.
 * - Crash reports: crash and ANR reports are filed as **public** GitHub issues on
 *   HereLiesAz/Graffux. Only deaths recorded after the moment of opt-in are sent, so turning it on
 *   never uploads older reports.
 */
object PrivacyConsent {
    private const val PREFS = ReportConsent.PREFS
    private const val KEY_ANALYTICS = "analytics_enabled"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun analyticsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ANALYTICS, false)

    fun setAnalyticsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANALYTICS, enabled).apply()
        apply(context)
    }

    fun crashReportsEnabled(context: Context): Boolean = ReportConsent.crashReportsEnabled(context)

    /** Wall-clock time of the opt-in; reports older than this are never uploaded. */
    fun crashReportsSince(context: Context): Long = ReportConsent.crashReportsSince(context)

    fun setCrashReportsEnabled(context: Context, enabled: Boolean) =
        ReportConsent.setCrashReportsEnabled(context, enabled)

    /** Pushes the analytics choice to Firebase. A build without google-services.json has no app. */
    fun apply(context: Context) {
        runCatching { FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(analyticsEnabled(context)) }
    }
}
