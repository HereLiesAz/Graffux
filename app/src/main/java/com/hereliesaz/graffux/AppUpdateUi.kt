package com.hereliesaz.graffux

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.hereliesaz.graffitixr.design.components.ConfirmDialog
import kotlinx.coroutines.launch

/** Saves an available update across recreation (rotation, theme change). */
private val AvailableSaver = listSaver<UpdateStatus.Available?, String>(
    save = { it?.let { u -> listOf(u.channel.name, u.version, u.uri.toString()) }.orEmpty() },
    restore = { UpdateStatus.Available(UpdateChannel.valueOf(it[0]), it[1], Uri.parse(it[2])) },
)

/**
 * The quiet launch check: once per launch (not per recreation), and only an available update
 * speaks. Up to date and failures (offline, rate-limited) say nothing. The check and its result
 * both survive recreation, so a rotation mid-check neither loses nor repeats it.
 */
@Suppress("FunctionNaming")
@Composable
fun LaunchUpdatePrompt() {
    val context = LocalContext.current
    var done by rememberSaveable { mutableStateOf(false) }
    var update by rememberSaveable(stateSaver = AvailableSaver) { mutableStateOf<UpdateStatus.Available?>(null) }
    LaunchedEffect(Unit) {
        if (done) return@LaunchedEffect
        update = AppUpdateCheck.check(context.applicationContext) as? UpdateStatus.Available
        done = true
    }
    update?.let { available ->
        UpdateDialog(available, onDismiss = { update = null })
    }
}

@Suppress("FunctionNaming")
@Composable
fun UpdateDialog(update: UpdateStatus.Available, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ConfirmDialog(
        title = "Update available",
        message = "Graffux ${update.version} is on ${update.channel.label}.",
        confirmLabel = "Open ${update.channel.label}",
        onConfirm = { onDismiss(); AppUpdateCheck.open(context, update) },
        onDismiss = onDismiss,
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
        null -> if (channel == UpdateChannel.OTHER) {
            "Installed from another app store, which delivers its own updates."
        } else {
            "Installed from ${channel.label}; updates come from there."
        }
        is UpdateStatus.UpToDate -> if (s.channel == UpdateChannel.OTHER) {
            "Your app store handles updates for this install."
        } else {
            "Up to date on ${s.channel.label}."
        }
        is UpdateStatus.Available -> "Graffux ${s.version} is on ${s.channel.label}. Tap to open it."
        is UpdateStatus.Failed -> "Couldn't check ${s.channel.label}: ${s.reason}. Tap to try again."
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
