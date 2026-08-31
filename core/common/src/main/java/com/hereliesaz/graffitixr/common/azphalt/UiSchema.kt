package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A control panel for one code [Contribution] (spec `docs/specs/ui-schema.md`), pointed at by that
 * contribution's own [Contribution.ui] — an in-package path (spec/extension-manifest.md's own
 * example: `"ui": "ui/panel.json"`) to a file in this exact shape. The host renders [controls] as
 * native widgets (this is a Compose render, never HTML/DOM); the extension reads the live values
 * back through the `params` capability (spec/capability-model.md) — [ParamValue] is what a host
 * hands across that boundary per control [UiControl.key].
 */
@Serializable
data class UiSchema(val controls: List<UiControl> = emptyList())

/**
 * One control (spec's Core controls table, version `0.1`): [type] picks which row — `slider`,
 * `number`, `toggle`, `select`, `color`, `text`, `button`, or `group` — and only the fields that
 * row actually uses are meaningful; the rest sit at their default. An unrecognised `type` (a
 * future schema version's) still parses — [UiSchema] tolerates it structurally — but nothing here
 * knows how to render or evaluate it, so the host's own render pass is what actually drops it,
 * the same "unknown degrades to ignored, not a parse failure" policy [AssetType]/[Capability] use.
 */
@Serializable
data class UiControl(
    val type: String,
    val key: String = "",
    val label: String = "",
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val default: JsonElement? = null,
    val options: List<UiOption> = emptyList(),
    val alpha: Boolean? = null,
    val placeholder: String? = null,
    /** `button` only: the entry-side action name this control's tap fires (spec's "apply/commit
     *  path for expensive operations"). */
    val action: String? = null,
    /** `group` only: a container's own nested controls. */
    val controls: List<UiControl> = emptyList(),
)

@Serializable
data class UiOption(val value: String, val label: String)

/** Parse a `ui/panel.json`-shaped file's contents, or throw with context — same convention as
 *  [parseManifest]. */
fun parseUiSchema(json: String): UiSchema = AzphaltJson.decodeFromString(json)

/**
 * A control's current live value, boxed by the shape [UiControl.type] actually needs (spec's
 * Value column: `slider`/`number` -> number, `toggle` -> boolean, everything else -> string,
 * `select`'s being the chosen option's `value` rather than its `label`). What
 * [com.hereliesaz.graffitixr.data.azphalt.sandbox.AzphaltSandboxHost]'s `paramNumber`/`paramBool`/
 * `paramString` resolve a control's [UiControl.key] to at sandbox-call time.
 */
sealed interface ParamValue {
    data class Num(val value: Double) : ParamValue
    data class Bool(val value: Boolean) : ParamValue
    data class Str(val value: String) : ParamValue
}

/** [UiControl.default] decoded into the [ParamValue] shape [UiControl.type] calls for — the
 *  starting point for a fresh control before the user has touched it. Null for a `group`/`button`,
 *  neither of which owns a value of its own (a group holds its children's; a button only fires
 *  [UiControl.action]). */
fun UiControl.defaultParamValue(): ParamValue? = when (type) {
    "slider", "number" -> default?.let { (it as? JsonPrimitive)?.doubleOrNull }?.let(ParamValue::Num)
    "toggle" -> default?.let { (it as? JsonPrimitive)?.booleanOrNull }?.let(ParamValue::Bool)
    "select", "color", "text" -> default?.let { (it as? JsonPrimitive)?.contentOrNull }?.let(ParamValue::Str)
    else -> null
}
