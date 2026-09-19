package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.PersistentWetnessField
import com.hereliesaz.graffitixr.common.azphalt.WetMaterialTransport

/**
 * Mutable per-layer Phase-4 material state used by commit and deterministic replay.
 *
 * Time comes only from recorded brush samples. No wall clock is consulted, so undo/redo and a
 * fresh replay advance wetness by the same deltas. The field itself only ticks active tiles.
 */
internal class WetnessReplayState private constructor(
    val field: PersistentWetnessField,
    var lastUptimeMillis: Long? = null,
) {
    /**
     * Advances pigment mobility + wetness to an explicit recorded input time.
     *
     * Pigment transport runs before wetness diffusion/drying so the mobility for this interval is
     * the material state that existed during the interval. Both solvers touch active tiles only.
     */
    fun advanceMaterialTo(
        pixels: IntArray,
        uptimeMillis: Long?,
        beforeWetnessAdvance: ((deltaSeconds: Float) -> Unit)? = null,
    ) {
        val next = uptimeMillis ?: return
        require(pixels.size >= field.width * field.height) {
            "WetnessReplayState pixels must contain width*height entries"
        }
        val previous = lastUptimeMillis
        if (previous != null && next > previous && !field.isIdle) {
            val deltaSeconds = (next - previous) / 1000f
            beforeWetnessAdvance?.invoke(deltaSeconds)
            advanceMaterialBy(pixels, deltaSeconds)
        }
        // A backwards uptime jump can happen across a device reboot. Treat it as a new monotonic
        // epoch instead of inventing an enormous or negative elapsed time.
        lastUptimeMillis = next
    }

    /**
     * One fixed deterministic post-contact tick. This gives freshly wet paint a visible bounded
     * local settle immediately after the stroke without introducing a wall-clock render loop.
     */
    fun settleMaterial(
        pixels: IntArray,
        deltaSeconds: Float = DEFAULT_SETTLE_SECONDS,
        beforeWetnessAdvance: ((deltaSeconds: Float) -> Unit)? = null,
    ) {
        if (field.isIdle || deltaSeconds <= 0f) return
        require(pixels.size >= field.width * field.height) {
            "WetnessReplayState pixels must contain width*height entries"
        }
        // Contact relaxation is a deterministic solver quantum, not elapsed wall/material time.
        // Phase-5 height uses the same quantum before wetness transport, so both channels see the
        // same mobility state and replay ordering.
        beforeWetnessAdvance?.invoke(deltaSeconds)
        transportMaterial(pixels, deltaSeconds)
        field.advance(
            deltaSeconds = deltaSeconds,
            dryingRate = 0f,
            transportRate = DEFAULT_TRANSPORT_RATE,
        )
    }

    private fun advanceMaterialBy(pixels: IntArray, deltaSeconds: Float) {
        transportMaterial(pixels, deltaSeconds)
        field.advance(
            deltaSeconds = deltaSeconds,
            dryingRate = DEFAULT_DRYING_RATE,
            transportRate = DEFAULT_TRANSPORT_RATE,
        )
    }

    private fun transportMaterial(pixels: IntArray, deltaSeconds: Float) {
        WetMaterialTransport.advanceArgb(
            pixels = pixels,
            width = field.width,
            height = field.height,
            wetness = field,
            deltaSeconds = deltaSeconds,
            transportRate = DEFAULT_PIGMENT_TRANSPORT_RATE,
            iterations = WetMaterialTransport.DEFAULT_ITERATIONS,
        )
    }

    fun markThrough(uptimeMillis: Long?) {
        if (uptimeMillis != null) lastUptimeMillis = uptimeMillis
    }

    fun copyForWork(): WetnessReplayState = fromSnapshot(
        width = field.width,
        height = field.height,
        wetness = field.snapshot(),
        lastUptimeMillis = lastUptimeMillis,
        tileSize = field.tileSize,
    )

    fun snapshot(): FloatArray = field.snapshot()

    companion object {
        // First-order Phase-4 defaults. Semantic media profiles can replace these with profile
        // values without changing replay architecture.
        const val DEFAULT_DRYING_RATE = 0.08f
        const val DEFAULT_TRANSPORT_RATE = 0.18f
        const val DEFAULT_PIGMENT_TRANSPORT_RATE = 0.16f
        const val DEFAULT_SETTLE_SECONDS = 0.125f

        fun empty(width: Int, height: Int, tileSize: Int = PersistentWetnessField.DEFAULT_TILE_SIZE) =
            WetnessReplayState(PersistentWetnessField(width, height, tileSize))

        fun fromSnapshot(
            width: Int,
            height: Int,
            wetness: FloatArray,
            lastUptimeMillis: Long?,
            tileSize: Int = PersistentWetnessField.DEFAULT_TILE_SIZE,
        ): WetnessReplayState {
            require(wetness.size == width * height) {
                "Wetness snapshot must contain exactly width*height values"
            }
            val field = PersistentWetnessField(width, height, tileSize)
            // Restoration/rebuild is intentionally allowed to scan the saved channel once. The live
            // simulation hot path remains active-tile-only after this state has been reconstructed.
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    val value = wetness[row + x].coerceIn(0f, 1f)
                    if (value > 0f) field.addWetness(x, y, value)
                }
            }
            return WetnessReplayState(field, lastUptimeMillis)
        }
    }
}
