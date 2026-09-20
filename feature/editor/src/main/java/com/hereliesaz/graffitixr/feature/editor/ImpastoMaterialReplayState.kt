package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.DirtyRegion
import com.hereliesaz.graffitixr.common.azphalt.MaterialMixingModel
import com.hereliesaz.graffitixr.common.azphalt.PaintMedium
import com.hereliesaz.graffitixr.common.azphalt.PersistentWetnessField

/**
 * Canonical Phase-5 presentation/material ownership that must not be baked into lit layer pixels.
 *
 * [rawColor] is the unlit pigment raster. [tileMedia] stores an effective medium per material tile,
 * matching the Phase-4 active-tile simulation granularity rather than allocating many float images.
 * Repeated deposits blend coefficients by deterministic accumulated weight, so a later brush only
 * changes tiles it actually contacts and cannot reinterpret every previously wet tile on the layer.
 */
internal class ImpastoMaterialReplayState private constructor(
    val width: Int,
    val height: Int,
    val tileSize: Int,
    val rawColor: IntArray,
    private val tileMedia: MutableMap<Int, WeightedMedium>,
) {
    data class TileMediumSnapshot(
        val tx: Int,
        val ty: Int,
        val weight: Float,
        val medium: PaintMedium,
    )

    private data class WeightedMedium(
        val weight: Float,
        val medium: PaintMedium,
    )

    init {
        require(width > 0 && height > 0) { "Impasto material dimensions must be positive" }
        require(tileSize > 0) { "Impasto material tileSize must be positive" }
        require(rawColor.size == width * height) { "rawColor must contain exactly width*height pixels" }
    }

    private val columns: Int get() = (width + tileSize - 1) / tileSize
    private val rows: Int get() = (height + tileSize - 1) / tileSize

    fun copyForWork(): ImpastoMaterialReplayState = ImpastoMaterialReplayState(
        width = width,
        height = height,
        tileSize = tileSize,
        rawColor = rawColor.copyOf(),
        tileMedia = tileMedia.toMutableMap(),
    )

    fun mediumAt(x: Int, y: Int, fallback: PaintMedium): PaintMedium {
        if (x !in 0 until width || y !in 0 until height) return fallback.sanitized()
        return tileMedia[tileId(x / tileSize, y / tileSize)]?.medium ?: fallback.sanitized()
    }

    /**
     * Records the medium that actually contacted [region]. Existing ownership is blended instead of
     * overwritten, so mixed media remain a deterministic effective material at tile granularity.
     */
    fun recordMedium(region: DirtyRegion?, medium: PaintMedium, weight: Float = 1f) {
        val r = region?.clampTo(width, height) ?: return
        val incomingWeight = weight.coerceAtLeast(0f)
        if (r.isEmpty || incomingWeight <= 0f) return
        val sanitized = medium.sanitized()
        val tx0 = r.left / tileSize
        val ty0 = r.top / tileSize
        val tx1 = (r.right - 1) / tileSize
        val ty1 = (r.bottom - 1) / tileSize
        for (ty in ty0..ty1) {
            for (tx in tx0..tx1) {
                val id = tileId(tx, ty)
                val old = tileMedia[id]
                tileMedia[id] = if (old == null) {
                    WeightedMedium(incomingWeight.coerceAtMost(MAX_WEIGHT), sanitized)
                } else {
                    val total = (old.weight + incomingWeight).coerceAtLeast(MIN_WEIGHT)
                    val t = (incomingWeight / total).coerceIn(0f, 1f)
                    WeightedMedium(
                        weight = total.coerceAtMost(MAX_WEIGHT),
                        medium = blend(old.medium, sanitized, t),
                    )
                }
            }
        }
    }

    fun tileSnapshots(): List<TileMediumSnapshot> =
        tileMedia.entries
            .sortedBy { it.key }
            .map { (id, weighted) ->
                TileMediumSnapshot(
                    tx = id % columns,
                    ty = id / columns,
                    weight = weighted.weight,
                    medium = weighted.medium,
                )
            }

    fun replaceRawColor(pixels: IntArray) {
        require(pixels.size >= rawColor.size) { "pixels must contain width*height values" }
        pixels.copyInto(rawColor, endIndex = rawColor.size)
    }

    companion object {
        fun fromRaw(
            width: Int,
            height: Int,
            rawColor: IntArray,
            tileSize: Int = PersistentWetnessField.DEFAULT_TILE_SIZE,
            tileMedia: List<TileMediumSnapshot> = emptyList(),
        ): ImpastoMaterialReplayState {
            val columns = (width + tileSize - 1) / tileSize
            val rows = (height + tileSize - 1) / tileSize
            val map = LinkedHashMap<Int, WeightedMedium>(tileMedia.size * 2)
            for (entry in tileMedia) {
                if (entry.tx !in 0 until columns || entry.ty !in 0 until rows) continue
                val weight = entry.weight.coerceAtLeast(MIN_WEIGHT).coerceAtMost(MAX_WEIGHT)
                map[entry.ty * columns + entry.tx] = WeightedMedium(weight, entry.medium.sanitized())
            }
            return ImpastoMaterialReplayState(
                width = width,
                height = height,
                tileSize = tileSize,
                rawColor = rawColor.copyOf(width * height),
                tileMedia = map,
            )
        }

        private fun blend(a: PaintMedium, b: PaintMedium, t: Float): PaintMedium {
            val f = t.coerceIn(0f, 1f)
            fun mix(x: Float, y: Float): Float = x + (y - x) * f
            return PaintMedium(
                mixingModel = if (f < 0.5f) a.mixingModel else b.mixingModel,
                viscosity = mix(a.viscosity, b.viscosity),
                yieldLikeStrength = mix(a.yieldLikeStrength, b.yieldLikeStrength),
                dryingRate = mix(a.dryingRate, b.dryingRate),
                pickupRate = mix(a.pickupRate, b.pickupRate),
                depositionRate = mix(a.depositionRate, b.depositionRate),
                heightResponse = mix(a.heightResponse, b.heightResponse),
                substrateResponse = mix(a.substrateResponse, b.substrateResponse),
                levelingRate = mix(a.levelingRate, b.levelingRate),
                baseRoughness = mix(a.baseRoughness, b.baseRoughness),
                wetSpecularStrength = mix(a.wetSpecularStrength, b.wetSpecularStrength),
            ).sanitized()
        }

        private const val MIN_WEIGHT = 1e-4f
        private const val MAX_WEIGHT = 64f
    }

    private fun tileId(tx: Int, ty: Int): Int = ty * columns + tx
}
