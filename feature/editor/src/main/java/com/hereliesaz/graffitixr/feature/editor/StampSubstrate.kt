package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.SubstratePreset
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine
import com.hereliesaz.graffitixr.nativebridge.SubstrateStampParams

/**
 * A stamp stroke's canvas substrate as the GPU stamp shaders take it: the project surface the stroke
 * snapshotted ([StrokeCommand.substrateId]) and the brush's [AzphaltBrush.substrateResponse].
 *
 * The live stroke, its commit and every undo/redo replay all resolve it through [of] from the same
 * two inputs, so the three paint identically. [of] returns null whenever the substrate can't change a
 * pixel — a Smooth surface or a zero response — and null is the exact historical stamp path (no
 * substrate texture bound, every dab's response 0).
 */
internal class StampSubstrate private constructor(
    val preset: SubstratePreset,
    /** Per-dab substrate response, 0..1 (never 0: that case is null). */
    val response: Float,
) {
    /** The preset's profile in the engine's terms. */
    val params: SubstrateStampParams = preset.profile.sanitized().let {
        SubstrateStampParams(
            baseHeight = it.baseHeight,
            heightScale = it.heightScale,
            textureScale = it.textureScale,
            textureOffsetX = it.textureOffsetX,
            textureOffsetY = it.textureOffsetY,
        )
    }

    /**
     * Makes this preset's height tile [engine]'s substrate texture, uploading it only when the engine
     * doesn't already hold it (a fresh or re-initialised engine never does). False when the upload
     * fails; the stamp call would then refuse the substrate anyway.
     */
    fun prepare(engine: GpuStampEngine): Boolean {
        val size = SubstratePreset.TILE_SIZE
        return engine.ensureSubstrateHeight(preset.id, size, size) { checkNotNull(preset.heightR8()) }
    }

    companion object {
        /** The substrate [brush] paints against on the surface [substrateId]; null = none (see class doc). */
        fun of(substrateId: String?, brush: AzphaltBrush): StampSubstrate? {
            val preset = SubstratePreset.fromId(substrateId)
            val response = brush.substrateResponse.coerceIn(0f, 1f)
            return if (preset.isSmooth || response <= 0f) null else StampSubstrate(preset, response)
        }
    }
}
