package com.hereliesaz.graffux

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * The quiet launch check: once per launch (not per recreation), and only an available update
 * speaks. Up to date and failures (offline, rate-limited) say nothing.
 */
@Suppress("FunctionNaming")
@Composable
fun LaunchUpdatePrompt() {
    val context = LocalContext.current
    var checked by rememberSaveable { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateStatus.Available?>(null) }
    LaunchedEffect(Unit) {
        if (checked) return@LaunchedEffect
        checked = true
        update = AppUpdateCheck.check(context.applicationContext) as? UpdateStatus.Available
    }
    update?.let { available ->
        UpdateDialog(available, onDismiss = { update = null })
    }
}

@Suppress("FunctionNaming")
@Composable
fun UpdateDialog(update: UpdateStatus.Available, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update available") },
        text = { Text("Graffux ${update.version} is on ${update.channel.label}.") },
        confirmButton = {
            TextButton(onClick = { onDismiss(); AppUpdateCheck.open(context, update) }) {
                Text("Open ${update.channel.label}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
    )
}

/** Settings row: checks on tap, reports the result, and opens the update when there is one. */
@Suppress("FunctionNaming")
@Composable
fun CheckForUpdatesRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<UpdateStatus?>(null) }
    var prompt by remember { mutableStateOf<UpdateStatus.Available?>(null) }
    prompt?.let { UpdateDialog(it, onDismiss = { prompt = null }) }

    val channel = remember { AppUpdateCheck.channel(context) }
    val subtitle = when (val s = status) {
        null -> "Installed from ${channel.label}; updates come from there."
        is UpdateStatus.UpToDate -> "Up to date on ${s.channel.label}."
        is UpdateStatus.Available -> "Graffux ${s.version} is on ${s.channel.label}. Tap to open it."
        is UpdateStatus.Failed -> "Couldn't reach ${s.channel.label}: ${s.reason}"
    }
    ActionRow(
        title = if (checking) "Checking…" else "Check for updates",
        subtitle = subtitle,
        onClick = {
            val current = status
            if (current is UpdateStatus.Available) {
                AppUpdateCheck.open(context, current)
            } else if (!checking) {
                checking = true
                scope.launch {
                    val result = AppUpdateCheck.check(context.applicationContext)
                    status = result
                    if (result is UpdateStatus.Available) prompt = result
                    checking = false
                }
            }
        },
    )
}
