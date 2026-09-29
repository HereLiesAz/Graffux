package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.azphalt.MaterialMixingModel
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine

/**
 * The settings only the old Tool Options window showed, read for the expanded hero card. All are
 * global editor settings (Smudge's engine settings, the stabilizer algorithm, the selection's
 * feather), not per-item ones.
 */
internal data class HeroMoreState(
    val smudgeMode: ColorSmudgeEngine.Mode = ColorSmudgeEngine.Mode.DULLING,
    val smudgeChargeDecay: Float = 0f,
    val smudgeDilution: Float = 0f,
    val smudgePickup: Float = 0f,
    val smudgeRadius: Float = 1f,
    val smudgePigmentMixing: Boolean = false,
    val smudgeCarryAlpha: Boolean = false,
    val smudgeSampleMerged: Boolean = false,
    val stabilizerAlgorithm: StabilizerAlgorithm = StabilizerAlgorithm.STREAMLINE,
    /** The current selection's feather, or null with no selection (then there is nothing to feather). */
    val selectionFeatherPx: Float? = null,
)

/** Which global enum setting a segmented choice drives. */
internal enum class HeroChoiceSetter {
    /** `vm.setColorSmudgeMode`, by [ColorSmudgeEngine.Mode] ordinal. */
    SMUDGE_MODE,

    /** `vm.setStabilizerAlgorithm`, by [StabilizerAlgorithm] ordinal. */
    STABILIZER_ALGORITHM,
}

/** Which global on/off setting a switch drives. */
internal enum class HeroToggleSetter {
    /** `vm.setColorSmudgeMixingModel`: PIGMENT_RYB when on, LEGACY_RGB when off. */
    SMUDGE_PIGMENT_MIXING,

    /** `vm.setColorSmudgeAlphaCarry`. */
    SMUDGE_CARRY_ALPHA,

    /** `vm.setColorSmudgeSampleMerged`. */
    SMUDGE_SAMPLE_MERGED,
}

/** A segmented choice: [options] labels, [selected] index. */
internal data class HeroChoice(
    val id: String,
    val label: String,
    val options: List<String>,
    val selected: Int,
    val setter: HeroChoiceSetter,
)

internal data class HeroToggle(val id: String, val label: String, val checked: Boolean, val setter: HeroToggleSetter)

/** One titled group on the expanded card: choices first, then sliders, then switches. */
internal data class HeroSection(
    val title: String,
    val sliders: List<HeroAdjustment> = emptyList(),
    val choices: List<HeroChoice> = emptyList(),
    val toggles: List<HeroToggle> = emptyList(),
) {
    val isEmpty: Boolean get() = sliders.isEmpty() && choices.isEmpty() && toggles.isEmpty()
}

private const val CHARGE_DECAY_MAX = 0.2f
private const val RADIUS_MIN = 0.25f
private const val RADIUS_MAX = 3f
private const val FEATHER_MAX = 64f

private fun label(name: String) = name.lowercase().replaceFirstChar { it.uppercase() }

/**
 * Every setting of [entry], grouped, for its expanded ("More") card: what the Tool Options window
 * used to show for that item, which the card replaces. Per-item settings (Size, Flow, Opacity,
 * Softness, Strength) edit the item's own; the rest are global and edit the one global value.
 */
internal fun heroSections(entry: CarouselEntry, s: HeroAdjustmentState): List<HeroSection> {
    val m = s.more
    val size = HeroAdjustment(
        "size", "Size", MIN_BRUSH_SIZE..MAX_BRUSH_SIZE, s.brushSize, HeroSetter.BRUSH_SIZE, HeroUnit.PX,
    )
    val softness = HeroAdjustment("softness", "Softness", 0f..1f, s.brushFeathering, HeroSetter.BRUSH_SOFTNESS)
    val stabilizer = HeroSection(
        "Stabilizer",
        sliders = listOf(
            HeroAdjustment(
                "stabilizer", "Stabilize", 0f..STABILIZER_MAX, s.stabilizerLevel.toFloat(),
                HeroSetter.STABILIZER, HeroUnit.RAW,
            ),
        ),
        // Only meaningful while the stabilizer is on; with it off every algorithm is a no-op.
        choices = if (s.stabilizerLevel > 0) {
            listOf(
                HeroChoice(
                    "stabilizerAlgorithm", "Algorithm", StabilizerAlgorithm.entries.map { it.label },
                    m.stabilizerAlgorithm.ordinal, HeroChoiceSetter.STABILIZER_ALGORITHM,
                ),
            )
        } else {
            emptyList()
        },
    )
    val smudge = smudgeSections(s)
    val sections = when (val action = entry.action) {
        is CarouselAction.BuiltInBrush, is CarouselAction.CustomBrush, is CarouselAction.ExtensionBrush -> listOf(
            HeroSection(
                "Brush",
                sliders = listOf(
                    size, HeroAdjustment("flow", "Flow", 0f..1f, s.brushFlow, HeroSetter.BRUSH_FLOW), softness,
                ),
            ),
            stabilizer,
        )
        is CarouselAction.InkUtensilPick -> listOf(
            HeroSection(
                "Ink",
                sliders = listOf(
                    size, HeroAdjustment("opacity", "Opacity", 0f..1f, s.brushOpacity, HeroSetter.BRUSH_OPACITY),
                ),
            ),
            stabilizer,
        )
        is CarouselAction.PickTool -> buildList {
            if (action.tool == Tool.SMUDGE) addAll(smudge)
            add(HeroSection("Brush", sliders = listOf(size, softness)))
            if (action.tool in STABILIZED_TOOLS) add(stabilizer)
        }
        is CarouselAction.SmudgeMode -> smudge
        is CarouselAction.StabilizerLevel, is CarouselAction.Stabilizer -> listOf(stabilizer)
        is CarouselAction.SelectShape -> listOf(selectionSection(action.shape, s))
        is CarouselAction.ExtensionContribution, is CarouselAction.ExtensionLut -> emptyList()
    }
    return sections.filterNot { it.isEmpty }
}

private fun smudgeSections(s: HeroAdjustmentState): List<HeroSection> {
    val m = s.more
    return listOf(
        HeroSection(
            "Smudge",
            choices = listOf(
                HeroChoice(
                    "smudgeMode", "Mode", ColorSmudgeEngine.Mode.entries.map { label(it.name) },
                    m.smudgeMode.ordinal, HeroChoiceSetter.SMUDGE_MODE,
                ),
            ),
            sliders = listOfNotNull(
                HeroAdjustment("strength", "Strength", 0f..1f, s.smudgeRate, HeroSetter.SMUDGE_STRENGTH),
                HeroAdjustment("load", "Load", 0f..1f, s.smudgeColorRate, HeroSetter.SMUDGE_LOAD),
                HeroAdjustment("smudgeOpacity", "Smudge opacity", 0f..1f, s.smudgeOpacity, HeroSetter.SMUDGE_OPACITY),
                // The sample radius only means something to Dulling, which samples around the dab.
                HeroAdjustment(
                    "radius", "Sample radius", RADIUS_MIN..RADIUS_MAX, m.smudgeRadius, HeroSetter.SMUDGE_RADIUS,
                    HeroUnit.MULTIPLIER,
                ).takeIf { m.smudgeMode == ColorSmudgeEngine.Mode.DULLING },
            ),
        ),
        HeroSection(
            "Wet mix",
            sliders = listOf(
                HeroAdjustment(
                    "chargeDecay", "Charge decay", 0f..CHARGE_DECAY_MAX, m.smudgeChargeDecay,
                    HeroSetter.SMUDGE_CHARGE_DECAY, HeroUnit.DECIMAL,
                ),
                HeroAdjustment("dilution", "Dilution", 0f..1f, m.smudgeDilution, HeroSetter.SMUDGE_DILUTION),
                HeroAdjustment("pickup", "Pickup", 0f..1f, m.smudgePickup, HeroSetter.SMUDGE_PICKUP),
            ),
            toggles = listOf(
                HeroToggle("pigment", "Pigment mixing", m.smudgePigmentMixing, HeroToggleSetter.SMUDGE_PIGMENT_MIXING),
            ),
        ),
        HeroSection(
            "Sampling",
            toggles = listOf(
                HeroToggle("carryAlpha", "Carry alpha", m.smudgeCarryAlpha, HeroToggleSetter.SMUDGE_CARRY_ALPHA),
                HeroToggle(
                    "sampleMerged", "Sample merged", m.smudgeSampleMerged, HeroToggleSetter.SMUDGE_SAMPLE_MERGED,
                ),
            ),
        ),
    )
}

private fun selectionSection(shape: SelectionShape, s: HeroAdjustmentState): HeroSection = HeroSection(
    "Selection",
    sliders = listOfNotNull(
        HeroAdjustment(
            "threshold", "Threshold", 0f..WAND_MAX, s.magicWandTolerance.toFloat(),
            HeroSetter.WAND_TOLERANCE, HeroUnit.RAW,
        ).takeIf { shape == SelectionShape.AUTOMATIC },
        s.more.selectionFeatherPx?.let {
            HeroAdjustment("feather", "Feather", 0f..FEATHER_MAX, it, HeroSetter.SELECTION_FEATHER, HeroUnit.PX)
        },
    ),
)

/** The [MaterialMixingModel] a pigment-mixing switch position stands for. */
internal fun heroMixingModel(pigment: Boolean): MaterialMixingModel =
    if (pigment) MaterialMixingModel.PIGMENT_RYB else MaterialMixingModel.LEGACY_RGB
