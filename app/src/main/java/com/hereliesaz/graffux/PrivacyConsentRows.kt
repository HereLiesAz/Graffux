package com.hereliesaz.graffux

import androidx.compose.ui.res.stringResource
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
        title = stringResource(R.string.settings_analytics),
        subtitle = stringResource(R.string.settings_analytics_hint),
        checked = analytics,
        onCheckedChange = {
            analytics = it
            PrivacyConsent.setAnalyticsEnabled(context, it)
        },
    )
    HorizontalDivider()
    SwitchRow(
        title = stringResource(R.string.settings_crash_reports),
        subtitle = stringResource(R.string.settings_crash_reports_hint),
        checked = crashes,
        onCheckedChange = {
            crashes = it
            PrivacyConsent.setCrashReportsEnabled(context, it)
        },
    )
    HorizontalDivider()
}
