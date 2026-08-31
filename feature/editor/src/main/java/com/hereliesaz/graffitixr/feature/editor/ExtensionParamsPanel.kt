package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.common.azphalt.ParamValue
import com.hereliesaz.graffitixr.common.azphalt.UiControl
import com.hereliesaz.graffitixr.common.azphalt.UiSchema

/**
 * Renders one code contribution's own control panel (spec `docs/specs/ui-schema.md`) natively —
 * this is the host's whole job under that spec: no HTML/DOM, no extension-supplied layout, just
 * these eight sanctioned control types laid out as ordinary Compose. [params] holds each control's
 * CURRENT value by [UiControl.key] (seeded from its own `default` before the user touches
 * anything); [onParamChanged] is called on every drag/toggle/selection, purely to update that local
 * state — nothing re-runs the contribution's sandboxed code until [onRun] fires, which is either
 * the schema's own declared `button` control (spec: "the apply/commit path for expensive
 * operations") or, when a schema declares none, the synthesized one this panel adds at the bottom.
 * A slider/toggle/etc. re-running a real WASM/JS execution on every drag frame — with no
 * incremental preview path in this app's sandbox model — is exactly the kind of continuous-vs-
 * committed distinction the Transform tool's own Apply step exists for; this is the same call.
 */
@Composable
fun ExtensionParamsPanel(
    contributionName: String,
    schema: UiSchema,
    params: Map<String, ParamValue>,
    onParamChanged: (key: String, value: ParamValue) -> Unit,
    onRun: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.clickable { onBack() }.padding(end = 8.dp),
                )
                Text(contributionName, style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
            Text(
                "Close",
                color = Color.Gray,
                modifier = Modifier.clickable { onClose() }.padding(8.dp),
            )
        }

        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ControlList(schema.controls, params, onParamChanged, onRun)
        }

        // A schema with no button control of its own still needs a way to actually invoke the
        // contribution — this is that fallback, not a duplicate of one already declared.
        if (schema.controls.none { it.hasButton() }) {
            Spacer(Modifier.height(8.dp))
            AzButton(
                text = "Run",
                onClick = onRun,
                shape = AzButtonShape.RECTANGLE,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun UiControl.hasButton(): Boolean = type == "button" || controls.any { it.hasButton() }

@Composable
private fun ControlList(
    controls: List<UiControl>,
    params: Map<String, ParamValue>,
    onParamChanged: (String, ParamValue) -> Unit,
    onRun: () -> Unit,
) {
    controls.forEach { control ->
        ControlRow(control, params, onParamChanged, onRun)
    }
}

@Composable
private fun ControlRow(
    control: UiControl,
    params: Map<String, ParamValue>,
    onParamChanged: (String, ParamValue) -> Unit,
    onRun: () -> Unit,
) {
    when (control.type) {
        "slider" -> {
            val current = (params[control.key] as? ParamValue.Num)?.value
                ?: control.default?.let { 0.0 } ?: 0.0
            val min = (control.min ?: 0.0).toFloat()
            val max = (control.max ?: 1.0).toFloat()
            val step = control.step?.toFloat()?.takeIf { it > 0f }
            val steps = step?.let { ((max - min) / it).toInt() - 1 }?.coerceAtLeast(0) ?: 0
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(
                    "${control.label}  ${formatParam(current)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                Slider(
                    value = current.toFloat().coerceIn(min, max),
                    onValueChange = { onParamChanged(control.key, ParamValue.Num(snapTo(it, min, step).toDouble())) },
                    valueRange = min..max,
                    steps = steps,
                )
            }
        }

        "number" -> {
            val current = (params[control.key] as? ParamValue.Num)?.value ?: 0.0
            var text = formatParam(current)
            OutlinedTextField(
                value = text,
                onValueChange = { new ->
                    text = new
                    new.toDoubleOrNull()?.let { onParamChanged(control.key, ParamValue.Num(it)) }
                },
                label = { Text(control.label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }

        "toggle" -> {
            val current = (params[control.key] as? ParamValue.Bool)?.value ?: false
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onParamChanged(control.key, ParamValue.Bool(!current)) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(control.label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (current) "On" else "Off",
                    color = if (current) MaterialTheme.colorScheme.primary else Color.Gray,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        "select" -> {
            val current = (params[control.key] as? ParamValue.Str)?.value
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(control.label, style = MaterialTheme.typography.labelMedium, color = Color.White)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    control.options.forEach { option ->
                        val selected = option.value == current
                        Box(
                            Modifier
                                .background(
                                    if (selected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable { onParamChanged(control.key, ParamValue.Str(option.value)) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                option.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) Color.White else Color.Gray,
                            )
                        }
                    }
                }
            }
        }

        "color" -> {
            // A full colour-wheel picker is real scope beyond this MVP pass (Graffux already has
            // one, SketchToolsDialog's ColorPickerDialog, but wiring a second host into this panel
            // is its own change) — a hex field, matching the schema's own RGBA/hex-string value
            // shape, is enough for a control that reports a string either way.
            val current = (params[control.key] as? ParamValue.Str)?.value ?: "#000000"
            OutlinedTextField(
                value = current,
                onValueChange = { onParamChanged(control.key, ParamValue.Str(it)) },
                label = { Text(control.label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }

        "text" -> {
            val current = (params[control.key] as? ParamValue.Str)?.value ?: ""
            OutlinedTextField(
                value = current,
                onValueChange = { onParamChanged(control.key, ParamValue.Str(it)) },
                label = { Text(control.label) },
                placeholder = control.placeholder?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }

        "button" -> {
            AzButton(
                text = control.label.ifBlank { "Run" },
                onClick = onRun,
                shape = AzButtonShape.RECTANGLE,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }

        "group" -> {
            Column(Modifier.padding(top = 8.dp)) {
                if (control.label.isNotBlank()) {
                    Text(
                        control.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                ControlList(control.controls, params, onParamChanged, onRun)
            }
        }

        // An unrecognised control type (a later schema version's) — see UiSchema's own doc
        // comment for why this parses at all rather than failing the whole panel. Nothing to
        // render for it; the rest of the panel still works.
        else -> {}
    }
}

private fun snapTo(value: Float, min: Float, step: Float?): Float {
    if (step == null || step <= 0f) return value
    val steps = ((value - min) / step).let { Math.round(it) }
    return min + steps * step
}

private fun formatParam(value: Double): String =
    if (value == Math.floor(value) && !value.isInfinite()) value.toInt().toString()
    else "%.2f".format(value)
