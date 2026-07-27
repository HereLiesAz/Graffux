package com.hereliesaz.graffux

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import kotlin.math.roundToInt
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hereliesaz.graffitixr.common.model.AppLanguage

/**
 * Graffux settings — the design-relevant preferences, shown as a full-bleed overlay over the editor.
 * Handedness controls which side the nav rail docks to; units feed the canvas rulers; language sets the
 * app locale. Also offers a tutorial reset and shows the build version. Values are read from and written
 * straight through [SettingsViewModel]; there's no local editing state to commit.
 */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    appVersion: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)

    val rightHanded by vm.isRightHanded.collectAsStateWithLifecycle()
    val imperial by vm.isImperialUnits.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    val sampleRate by vm.inputSampleRateHz.collectAsStateWithLifecycle()
    val renderScale by vm.canvasRenderScale.collectAsStateWithLifecycle()

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close settings")
                }
            }
            Spacer(Modifier.height(16.dp))

            SwitchRow(
                title = "Right-handed",
                subtitle = "Docks the tool rail on the left; turn off for a left-side rail.",
                checked = rightHanded,
                onCheckedChange = vm::setRightHanded,
            )
            HorizontalDivider()
            SwitchRow(
                title = "Imperial units",
                subtitle = "Show ruler measurements in inches rather than centimetres.",
                checked = imperial,
                onCheckedChange = vm::setImperialUnits,
            )
            HorizontalDivider()
            LanguageRow(current = language, onSelect = vm::setLanguage)
            HorizontalDivider()

            Text(
                "Performance",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            ChoiceRow(
                title = "Sample rate",
                subtitle = "How often a stroke is sampled and redrawn. Modern screens report touch " +
                    "far faster than they can display it, so a lower rate usually looks identical " +
                    "and draws much less power.",
                options = SAMPLE_RATES,
                selected = sampleRate,
                label = { hz -> if (hz <= 0) "Unlimited" else "$hz Hz" },
                onSelect = vm::setInputSampleRateHz,
            )
            HorizontalDivider()
            ChoiceRow(
                title = "Canvas resolution",
                subtitle = "Resolution new layers are created at, as a share of the screen. Each " +
                    "layer holds a full-size image, so halving this quarters the memory a layer " +
                    "costs — lower it if the app runs out of memory with several layers open. " +
                    "Existing layers keep the resolution they were made at.",
                options = RENDER_SCALES,
                selected = renderScale,
                label = { scale -> "${(scale * 100).roundToInt()}%" },
                onSelect = vm::setCanvasRenderScale,
            )
            HorizontalDivider()

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = vm::resetTutorials) {
                Text("Reset tutorials & hints")
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Graffux $appVersion",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Offered sample rates. 0 means unthrottled — every reported touch sample is drawn. */
private val SAMPLE_RATES = listOf(30, 60, 90, 120, 0)

/** Offered canvas resolutions. Memory scales with the square, so 50% is a quarter of the bytes. */
private val RENDER_SCALES = listOf(1f, 0.75f, 0.5f, 0.25f)

/** A titled row of mutually exclusive choices, rendered as a wrapped strip of chips. */
@Composable
private fun <T> ChoiceRow(
    title: String,
    subtitle: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Toggle from anywhere on the row (accessibility); the Switch just reflects state.
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun LanguageRow(current: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Language", style = MaterialTheme.typography.titleMedium)
            Text(
                "Interface language (restart to fully apply).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(current.displayName)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AppLanguage.entries.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(lang.displayName) },
                        onClick = { onSelect(lang); expanded = false },
                    )
                }
            }
        }
    }
}
