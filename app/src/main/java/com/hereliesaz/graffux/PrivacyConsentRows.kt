package com.hereliesaz.graffux

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Settings → Privacy: the analytics and crash-report opt-ins ([PrivacyConsent]). Both start off. */
@Suppress("FunctionNaming")
@Composable
fun PrivacyConsentRows() {
    val context = LocalContext.current
    var analytics by remember { mutableStateOf(PrivacyConsent.analyticsEnabled(context)) }
    var crashes by remember { mutableStateOf(PrivacyConsent.crashReportsEnabled(context)) }
    SwitchRow(
        title = "Usage analytics",
        subtitle = "Off by default. When on, Google Firebase Analytics records anonymous app usage " +
            "events (screens opened, app version, device model, country). No drawings, names or " +
            "location are included.",
        checked = analytics,
        onCheckedChange = {
            analytics = it
            PrivacyConsent.setAnalyticsEnabled(context, it)
        },
    )
    HorizontalDivider()
    SwitchRow(
        title = "Send crash reports",
        subtitle = "Off by default. When on, crashes and freezes after this point are posted as " +
            "public GitHub issues on HereLiesAz/Graffux: the error, device model, Android and app " +
            "version, and recent app log lines, with tokens, emails, file paths and coordinates " +
            "removed. Needs the reports token under Developer.",
        checked = crashes,
        onCheckedChange = {
            crashes = it
            PrivacyConsent.setCrashReportsEnabled(context, it)
        },
    )
    HorizontalDivider()
}
