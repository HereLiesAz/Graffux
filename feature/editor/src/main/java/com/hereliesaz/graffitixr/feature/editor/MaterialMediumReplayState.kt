package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.PaintMedium

/**
 * Spatial material-response ownership for Impasto v2.
 *
 * Index 0 means no v2 material has claimed the pixel. Positive indices address a deduplicated
 * [PaintMedium] palette (index = palette position + 1). A later brush claims only pixels where it
 * actually deposits height or vehicle, so untouched wet paint keeps the viscosity, drying,
 * leveling and optical response of the medium that created it.
 *
 * The map is allocated only for materialized layers and is copied alongside height/wetness during
 * replay. It is response metadata, not a second paint/wet-mix backend.
 */
internal class MaterialMediumReplayState private constructor(
    val width: Int,
    val height: Int,
    private val palette: MutableList<PaintMedium>,
    private val ownerIds: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "Material medium dimensions must be positive" }
        require(ownerIds.size == width * height) { "Material medium ownership size mismatch" }
    }

    fun mediumAt(x: Int, y: Int): PaintMedium? {
        if (x !in 0 until width || y !in 0 until height) return null
        val id = ownerIds[y * width + x]
        return if (id > 0 && id <= palette.size) palette[id - 1] else null
    }

    fun assign(x: Int, y: Int, incoming: PaintMedium) {
        if (x !in 0 until width || y !in 0 until height) return
        val medium = incoming.sanitized()
        var paletteIndex = palette.indexOf(medium)
        if (paletteIndex < 0) {
            palette += medium
            paletteIndex = palette.lastIndex
        }
        ownerIds[y * width + x] = paletteIndex + 1
    }

    fun clear(x: Int, y: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        ownerIds[y * width + x] = 0
    }

    fun copyForWork(): MaterialMediumReplayState =
        MaterialMediumReplayState(width, height, palette.toMutableList(), ownerIds.copyOf())

    fun paletteSnapshot(): List<PaintMedium> = palette.toList()

    fun ownerIdSnapshot(): IntArray = ownerIds.copyOf()

    val hasOwners: Boolean get() = ownerIds.any { it != 0 }

    companion object {
        fun empty(width: Int, height: Int): MaterialMediumReplayState =
            MaterialMediumReplayState(width, height, mutableListOf(), IntArray(width * height))

        fun fromSnapshot(
            width: Int,
            height: Int,
            palette: List<PaintMedium>,
            ownerIds: IntArray,
        ): MaterialMediumReplayState {
            require(ownerIds.size == width * height) { "Material medium ownership size mismatch" }
            val sanitizedPalette = palette.map { it.sanitized() }
            require(ownerIds.all { it in 0..sanitizedPalette.size }) {
                "Material medium ownership contains an invalid palette id"
            }
            return MaterialMediumReplayState(
                width = width,
                height = height,
                palette = sanitizedPalette.toMutableList(),
                ownerIds = ownerIds.copyOf(),
            )
        }
    }
}
