package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.PersistentWetnessField

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
    fun advanceTo(uptimeMillis: Long?) {
        val next = uptimeMillis ?: return
        val previous = lastUptimeMillis
        if (previous != null && next > previous && !field.isIdle) {
            field.advance(
                deltaSeconds = (next - previous) / 1000f,
                dryingRate = DEFAULT_DRYING_RATE,
                transportRate = DEFAULT_TRANSPORT_RATE,
            )
        }
        // A backwards uptime jump can happen across a device reboot. Treat it as a new monotonic
        // epoch instead of inventing an enormous or negative elapsed time.
        lastUptimeMillis = next
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
