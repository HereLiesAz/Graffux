package com.hereliesaz.graffitixr.feature.editor.util

import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush

/**
 * Semantic mapping from [KritaPresetParser.Preset]'s raw `param` key/value pairs onto Graffux's
 * own primitives ([ColorSmudgeEngine.Settings] / [AzphaltBrush]).
 *
 * [KritaPresetParser] only recovers the container: raw key/value strings with no engine meaning
 * attached. Krita's *storage* key for a given on-screen slider almost never matches either the
 * slider's own display label or the [KoID][https://api.kde.org] passed to a UI-only callback
 * property elsewhere in the same source file -- see "Smudge Mode"/`smudge_mode` below, which is
 * exactly that trap and is why this mapper does not use those UI-callback strings. Every key name
 * used here was read directly from a specific KDE/krita `master` source file (verbatim `curl`
 * fetches of `invent.kde.org/graphics/krita/-/raw/master/...`, not a WebFetch/LLM paraphrase of
 * it) and then cross-checked against `plugins/paintops/defaultpresets/colorsmudge.kpp` -- a real
 * preset file shipped in the same repository -- decompressed and inspected directly. Where a key
 * appears in both, that is noted; where it's confirmed by source alone (not present in that one
 * sample file, e.g. because the sample predates the key), that's noted too, and mapped anyway
 * since the source is unambiguous and current.
 *
 * Krita's `KisCurveOptionData`-backed settings (the vast majority of paintop parameters -- see
 * `docs/Krita Brush Engine Adoption.md` item 14) store each option under `"<id>" + <suffix>` keys,
 * per `KisKritaSensorPack::write()` (`plugins/paintops/libpaintop/KisKritaSensorPack.cpp`):
 * `<id>Value` (the flat/baseline scalar -- what a curve-off dab actually uses, and what a curve-on
 * dab uses as [KisCurveOption.ValueComponents.constant] on top of the curve's per-dab multiplier),
 * `<id>UseCurve`, `<id>Sensor` (the curve/sensor route as embedded XML), `<id>UseSameCurve`,
 * `<id>curveMode`, `<id>commonCurve`, and `"Pressure" + <id>` (whether the option is checked/on at
 * all). `<id>` is *not* the class name or the display label -- it's the literal string passed as
 * the `KoID` to the `KisCurveOptionData` constructor in that option's own `*OptionData.h`. This
 * mapper only ever reads `<id>Value` (the one key meaningful without decoding the curve/sensor XML
 * sub-format) -- see item 14's doc entry for why the rest stays out of scope for this pass.
 */
object KritaPresetMapper {

    /**
     * Maps a `paintopid="colorsmudge"` preset's params onto [ColorSmudgeEngine.Settings]. Returns
     * null for any other `paintopId` -- this function is Color Smudge-specific, not a generic
     * paintop mapper.
     *
     * Confirmed mappings, each keyed on a specific fetched source file:
     * - `mode` <- `SmudgeRateMode` (int, `0`=Smearing/`1`=Dulling). Source: `KisSmudgeLengthOptionData.h`
     *   (`plugins/paintops/colorsmudge/KisSmudgeLengthOptionData.h`) declares
     *   `KoID("SmudgeRate", i18n("Smudge Length"))`; `KisSmudgeLengthOptionMixInImpl::read()`/`write()`
     *   (`KisSmudgeLengthOptionData.cpp`) read/write `"SmudgeRateMode"` as an int, and
     *   `KisColorSmudgeOpSettings::uniformProperties()` (`kis_colorsmudgeop_settings.cpp`) confirms
     *   `Mode::SMEARING_MODE=0`/`DULLING_MODE=1` map onto [ColorSmudgeEngine.Mode]'s own declaration
     *   order (`SMEAR`, `DULLING`). Cross-checked: the real `colorsmudge.kpp` default preset
     *   (`plugins/paintops/defaultpresets/colorsmudge.kpp`) contains `<param name="SmudgeRateMode">0</param>`.
     *   NOTE: this item's prior pass recorded `smudge_mode` as the confirmed key. That was wrong --
     *   `smudge_mode` is the *UI uniform-property* `KoID` used only for the live properties-bar
     *   widget callback in `kis_colorsmudgeop_settings.cpp` (`KisComboBasedPaintOpPropertyCallback`);
     *   it is never read from or written to a `KisPropertiesConfiguration`/`.kpp` file. The on-disk
     *   key is `SmudgeRateMode`, confirmed above. This mapper deliberately does not use `smudge_mode`.
     * - `smudgeRate` <- `SmudgeRateValue` (float). Source: same `KisSmudgeLengthOptionData.h`/
     *   `KisKritaSensorPack::write()`'s `<id>Value` convention (`id` = `"SmudgeRate"`). Cross-checked:
     *   `colorsmudge.kpp` contains `<param name="SmudgeRateValue">0.5</param>`.
     * - `colorRate` <- `ColorRateValue` (float). Source: `KisColorSmudgeStandardOptionData.h`
     *   (`plugins/paintops/colorsmudge/KisColorSmudgeStandardOptionData.h`) declares
     *   `KisColorRateOptionData : KoID("ColorRate", ...)`. Cross-checked: `colorsmudge.kpp` contains
     *   `<param name="ColorRateValue">0.5</param>`.
     * - `opacity` <- `OpacityValue` (float). Source: `KisStandardOptionData.h`
     *   (`plugins/paintops/libpaintop/KisStandardOptionData.h`) declares
     *   `KisOpacityOptionData : KoID("Opacity", ...)`, `NotCheckable` (always applied, no separate
     *   on/off flag). Not colorsmudge-specific -- shared standard-option infrastructure used by
     *   every paintop that includes `KisOpacityOption`. Cross-checked: `colorsmudge.kpp` contains
     *   `<param name="OpacityValue">1</param>` alongside `<param name="PressureOpacity">true</param>`.
     * - `smearAlpha` <- `SmudgeRateSmearAlpha` (bool). Source: `KisSmudgeLengthOptionMixInImpl::read()`/
     *   `write()` (`KisSmudgeLengthOptionData.cpp`) read/write `"SmudgeRateSmearAlpha"` directly.
     *   LOWER CONFIDENCE than the others above: this key is *not* present in the real
     *   `colorsmudge.kpp` sample (it appears to predate this field -- the same file is also missing
     *   `SmudgeRatecurveMode`/`SmudgeRatecommonCurve`/`SmudgeRateUseSameCurve`, which current
     *   `KisKritaSensorPack::write()` unconditionally emits, so that file was evidently saved by an
     *   older Krita build). The source read/write pair is unambiguous and current, so it's mapped
     *   anyway, just flagged as source-only rather than source-plus-live-file confirmed.
     * - `smudgeRadius` <- `SmudgeRadiusValue` (float), with a source-confirmed unit fixup: Source:
     *   `KisSmudgeRadiusOptionData.cpp` (`plugins/paintops/colorsmudge/KisSmudgeRadiusOptionData.cpp`)
     *   declares `KoID("SmudgeRadius", ...)` and a `valueFixUpReadCallback` that divides the stored
     *   value by 100 whenever `"SmudgeRadiusVersion"` (int, default `1` when absent) is less than
     *   `2` -- i.e. older presets stored this as a 0-300 percentage, newer ones as a 0-3 ratio
     *   directly. This mapper reproduces that exact fixup rather than guessing a scale factor.
     *
     * Everything else in [ColorSmudgeEngine.Settings] is left at its default: `radiusPx` is
     * explicitly a replay-time value supplied by the caller (per its own doc comment), and
     * `chargeDecayRate`/`dilution`/`feathering`/`wrapAround`/`paintColor`/`symmetryMode`/`dynamics`/
     * `sampleMerged` either have no Krita source equivalent researched this pass (Graffux-only
     * Procreate-vocabulary extensions) or would need the curve/sensor XML sub-format decoded
     * (`dynamics`/`smudgeRadius`'s own sensor route) -- out of scope, per this item's standing rule
     * against guessing a curve reduction.
     */
    fun toColorSmudgeSettings(preset: KritaPresetParser.Preset): ColorSmudgeEngine.Settings? {
        if (preset.paintopId != "colorsmudge") return null

        fun float(key: String): Float? = preset.params[key]?.value?.toFloatOrNull()
        fun bool(key: String): Boolean? = preset.params[key]?.value?.toBooleanStrictOrNull()
        fun int(key: String): Int? = preset.params[key]?.value?.toIntOrNull()

        val defaults = ColorSmudgeEngine.Settings()

        val mode = when (int("SmudgeRateMode")) {
            1 -> ColorSmudgeEngine.Mode.DULLING
            0 -> ColorSmudgeEngine.Mode.SMEAR
            else -> defaults.mode
        }

        val smudgeRadiusVersion = int("SmudgeRadiusVersion") ?: 1
        val rawSmudgeRadius = float("SmudgeRadiusValue")
        val smudgeRadius = rawSmudgeRadius?.let {
            if (smudgeRadiusVersion < 2) it / 100f else it
        } ?: defaults.smudgeRadius

        return defaults.copy(
            mode = mode,
            smudgeRate = float("SmudgeRateValue") ?: defaults.smudgeRate,
            colorRate = float("ColorRateValue") ?: defaults.colorRate,
            opacity = float("OpacityValue") ?: defaults.opacity,
            smearAlpha = bool("SmudgeRateSmearAlpha") ?: defaults.smearAlpha,
            smudgeRadius = smudgeRadius,
        )
    }

    /**
     * Maps the subset of a Krita preset's params that are generic across paintops (not tied to any
     * one `paintopId`) onto a best-effort [AzphaltBrush]. Only [AzphaltBrush.opacity] is mapped;
     * every other field is left at [AzphaltBrush]'s own default.
     *
     * `opacity` <- `OpacityValue` (float), same `KisOpacityOptionData`/`KisStandardOptionData.h`
     * source cited on [toColorSmudgeSettings] -- this is genuinely paintop-generic shared
     * infrastructure (`KisOpacityOption` is instantiated by many paintops' settings, not just
     * Color Smudge's), and `NotCheckable` means the resolved dab opacity *is* this value directly
     * (no separate on/off gate to also account for), unlike e.g. `Size`/`Spacing`/`Rotation`'s
     * `Value` keys, which are curve-strength *multipliers* applied on top of a base value stored
     * elsewhere (the brush's own diameter, base spacing fraction, base angle) that this pass did
     * not locate a confirmed source for -- mapping those would silently conflate "how much the
     * sensor curve scales X" with "X itself", which is exactly the kind of guess this item's
     * standing rule forbids. They are deliberately left unmapped.
     */
    fun toAzphaltBrush(preset: KritaPresetParser.Preset): AzphaltBrush {
        val opacity = preset.params["OpacityValue"]?.value?.toFloatOrNull()
        val base = AzphaltBrush(name = preset.name.ifBlank { "Imported Brush" })
        return if (opacity != null) base.copy(opacity = opacity).sanitized() else base
    }
}
