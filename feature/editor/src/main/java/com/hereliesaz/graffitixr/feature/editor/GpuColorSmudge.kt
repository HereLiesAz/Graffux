package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.MaterialMixingModel
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import com.hereliesaz.graffitixr.nativebridge.ColorSmudgeDab
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine

/**
 * Color Smudge's resolved plans (ColorSmudgeEngine.resolvePlans) stamped on a wgpu engine's layer,
 * in order: the one path the commit, replay and live preview share. Native modes 0/1 are Smear and
 * Dulling, 2/3 the same with the RYB material mixer; reservoir load and pickup run in the same
 * pipeline.
 */
internal object GpuColorSmudge {

    /**
     * Stamps [plans] onto [engine] with [settings]. [sampleSource] (straight ARGB words,
     * [width] x [height]) is Sample Merged's composite, null for the layer itself.
     */
    @Suppress("LongParameterList")
    fun stamp(
        engine: GpuStampEngine,
        plans: List<ColorSmudgeEngine.ResolvedPlan>,
        settings: ColorSmudgeEngine.Settings,
        sampleSource: IntArray?,
        width: Int,
        height: Int,
    ): Boolean {
        val baseMode = if (settings.mode == ColorSmudgeEngine.Mode.SMEAR) 0 else 1
        val mode = baseMode + if (settings.mixingModel == MaterialMixingModel.PIGMENT_RYB) 2 else 0
        return plans.all { plan ->
            plan.dabs.size < 2 || engine.colorSmudge(
                plan.dabs.map { dab ->
                    ColorSmudgeDab(
                        dab.x, dab.y, dab.smudgeRate, dab.colorRate,
                        dab.opacity, dab.smudgeRadius,
                        dab.colorRateMultiplier, dab.distanceDeltaPx,
                    )
                },
                mode, settings.radiusPx, settings.feathering,
                settings.smearAlpha, settings.paintColor, settings.dilution,
                baseColorRate = settings.colorRate,
                chargeDecayRate = settings.chargeDecayRate,
                pickupRate = settings.pickupRate,
                sampleSource = sampleSource,
                sampleSourceWidth = width, sampleSourceHeight = height,
            )
        }
    }
}
