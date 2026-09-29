package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.Tool

/**
 * Which existing editor setter a hero slider drives. Each maps onto exactly one `EditorViewModel`
 * call the Tool Options window, the size picker or the brush HUD already makes; the carousel adds
 * no state of its own.
 */
internal enum class HeroSetter {
    /** `vm.setBrushSize` — every brush, Ink utensil and effect tool paints at this size. */
    BRUSH_SIZE,

    /** `vm.setBrushFlow` — a stamp brush's per-dab build-up. */
    BRUSH_FLOW,

    /** `vm.setBrushOpacity` — the whole-stroke ceiling an Ink utensil folds into its colour. */
    BRUSH_OPACITY,

    /** `vm.setBrushFeathering` — edge softness; brushes and the pixel effect tools both read it. */
    BRUSH_SOFTNESS,

    /** `vm.setColorSmudgeRate` — Smudge's strength. */
    SMUDGE_STRENGTH,

    /** `vm.setColorSmudgeColorRate` — Smudge's Load (how much fresh colour it carries). */
    SMUDGE_LOAD,

    /** `vm.setColorSmudgeOpacity` — Smudge's own opacity. */
    SMUDGE_OPACITY,

    /** `vm.setStabilizerLevel`. */
    STABILIZER,

    /** `vm.onSetMagicWandTolerance` — the Automatic selection shape's threshold. */
    WAND_TOLERANCE,
}

/** How a slider's value reads next to its label. */
internal enum class HeroUnit { PERCENT, PX, RAW }

/** One inline slider on the hero card. [value] is the editor's current value, read, never copied. */
internal data class HeroAdjustment(
    val id: String,
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val value: Float,
    val setter: HeroSetter,
    val unit: HeroUnit = HeroUnit.PERCENT,
)

/** The slice of editor state the hero sliders read — the same fields Tool Options shows. */
internal data class HeroAdjustmentState(
    val brushSize: Float,
    val brushFlow: Float,
    val brushOpacity: Float,
    val brushFeathering: Float,
    val smudgeRate: Float,
    val stabilizerLevel: Int,
    val magicWandTolerance: Int,
    /** Smudge's Load and opacity (`ColorSmudgeEngine.Settings.colorRate` / `.opacity`); expanded card only. */
    val smudgeColorRate: Float = 1f,
    val smudgeOpacity: Float = 1f,
)

/** Brush-size range the edge slider and the Size slider map onto — EditorReducer's clamp on SetBrushSize. */
internal const val MIN_BRUSH_SIZE = 1f
internal const val MAX_BRUSH_SIZE = 200f
private const val STABILIZER_MAX = 100f
private const val WAND_MAX = 255f

/** At most this many sliders ride on the card; the rest are behind "More". */
internal const val MAX_HERO_ADJUSTMENTS = 3

/**
 * The hero card's inline sliders for [entry], most-used first. Fixed per kind:
 *
 * - Stamp brushes (built-in, Brush Studio, installed): Size, Flow, Softness. Not opacity: the model
 *   documents `brushOpacity` as ignored by stamp brushes, which use flow instead.
 * - Ink utensils: Size, Opacity — the only two that apply to them.
 * - Smudge: Strength, Size, Softness. The other effect tools have no strength setting of their own;
 *   they paint at the brush's Size and Softness, so those are theirs.
 * - Options: the stabilizer stops get the stabilizer level, the smudge modes Smudge's strength, the
 *   Automatic selection shape its threshold; every other option, none.
 * - Installed filters, tools and LUTs: none (see [heroHasMore]).
 */
internal fun heroAdjustments(entry: CarouselEntry, s: HeroAdjustmentState): List<HeroAdjustment> =
    heroAdjustmentsUncapped(entry, s).take(MAX_HERO_ADJUSTMENTS)

private fun heroAdjustmentsUncapped(entry: CarouselEntry, s: HeroAdjustmentState): List<HeroAdjustment> {
    val size = HeroAdjustment(
        "size", "Size", MIN_BRUSH_SIZE..MAX_BRUSH_SIZE, s.brushSize, HeroSetter.BRUSH_SIZE, HeroUnit.PX,
    )
    val softness = HeroAdjustment("softness", "Softness", 0f..1f, s.brushFeathering, HeroSetter.BRUSH_SOFTNESS)
    val strength = HeroAdjustment("strength", "Strength", 0f..1f, s.smudgeRate, HeroSetter.SMUDGE_STRENGTH)
    return when (val action = entry.action) {
        is CarouselAction.BuiltInBrush, is CarouselAction.CustomBrush, is CarouselAction.ExtensionBrush -> listOf(
            size,
            HeroAdjustment("flow", "Flow", 0f..1f, s.brushFlow, HeroSetter.BRUSH_FLOW),
            softness,
        )
        is CarouselAction.InkUtensilPick -> listOf(
            size,
            HeroAdjustment("opacity", "Opacity", 0f..1f, s.brushOpacity, HeroSetter.BRUSH_OPACITY),
        )
        is CarouselAction.PickTool ->
            if (action.tool == Tool.SMUDGE) listOf(strength, size, softness) else listOf(size, softness)
        is CarouselAction.StabilizerLevel, is CarouselAction.Stabilizer -> listOf(
            HeroAdjustment(
                "stabilizer", "Stabilize", 0f..STABILIZER_MAX, s.stabilizerLevel.toFloat(),
                HeroSetter.STABILIZER, HeroUnit.RAW,
            ),
        )
        is CarouselAction.SmudgeMode -> listOf(strength)
        is CarouselAction.SelectShape -> if (action.shape == SelectionShape.AUTOMATIC) {
            listOf(
                HeroAdjustment(
                    "threshold", "Threshold", 0f..WAND_MAX, s.magicWandTolerance.toFloat(),
                    HeroSetter.WAND_TOLERANCE, HeroUnit.RAW,
                ),
            )
        } else {
            emptyList()
        }
        CarouselAction.OpenToolOptions,
        is CarouselAction.ExtensionContribution,
        is CarouselAction.ExtensionLut,
        -> emptyList()
    }
}

/**
 * Whether the hero card offers "More": the full Tool Options window for that item. Installed
 * filters and tools already open their own params panel when tapped, and a LUT has none, so they
 * don't; nor does the Tool Options entry itself.
 */
internal fun heroHasMore(entry: CarouselEntry): Boolean = when (entry.action) {
    CarouselAction.OpenToolOptions, is CarouselAction.ExtensionContribution, is CarouselAction.ExtensionLut -> false
    else -> true
}

private const val PERCENT = 100

/** The slider's caption: its label and value, e.g. "Flow 80%" or "Size 42 px". */
internal fun heroAdjustmentText(a: HeroAdjustment): String = when (a.unit) {
    HeroUnit.PERCENT -> "${a.label} ${(a.value * PERCENT).toInt()}%"
    HeroUnit.PX -> "${a.label} ${a.value.toInt()} px"
    HeroUnit.RAW -> "${a.label} ${a.value.toInt()}"
}

/**
 * The value a setter should receive: [raw] clamped to the slider's range, and whole-numbered for
 * the integer settings (stabilizer level, wand threshold).
 */
internal fun heroSetterValue(a: HeroAdjustment, raw: Float): Float {
    val v = raw.coerceIn(a.range)
    return if (a.unit == HeroUnit.RAW) kotlin.math.round(v) else v
}

/**
 * Every setting the expanded hero card ("More") shows for [entry]: what the Tool Options window
 * showed for that item. Brushes and effect tools add the stabilizer (when their strokes use it) to
 * their inline sliders; Smudge and its modes add Load and Smudge opacity. Settings only Tool
 * Options still carries (Smudge's dilution/pickup/radius/mixing model, the stabilizer algorithm,
 * selection feather) stay there, reached from the rail's Tool Options item or the "All options" card.
 */
internal fun heroFullAdjustments(entry: CarouselEntry, s: HeroAdjustmentState): List<HeroAdjustment> {
    val stabilizer = HeroAdjustment(
        "stabilizer", "Stabilize", 0f..STABILIZER_MAX, s.stabilizerLevel.toFloat(), HeroSetter.STABILIZER, HeroUnit.RAW,
    )
    val smudgeExtras = listOf(
        HeroAdjustment("load", "Load", 0f..1f, s.smudgeColorRate, HeroSetter.SMUDGE_LOAD),
        HeroAdjustment("smudgeOpacity", "Smudge opacity", 0f..1f, s.smudgeOpacity, HeroSetter.SMUDGE_OPACITY),
    )
    val inline = heroAdjustmentsUncapped(entry, s)
    return when (val action = entry.action) {
        is CarouselAction.BuiltInBrush, is CarouselAction.CustomBrush, is CarouselAction.ExtensionBrush,
        is CarouselAction.InkUtensilPick,
        -> inline + stabilizer
        is CarouselAction.PickTool -> {
            val withSmudge = if (action.tool == Tool.SMUDGE) inline.take(1) + smudgeExtras + inline.drop(1) else inline
            if (action.tool in STABILIZED_TOOLS) withSmudge + stabilizer else withSmudge
        }
        is CarouselAction.SmudgeMode -> inline + smudgeExtras
        else -> inline
    }
}
