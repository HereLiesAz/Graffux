package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.Dab
import com.hereliesaz.graffitixr.common.azphalt.PaintMedium
import com.hereliesaz.graffitixr.nativebridge.GpuWetSimulation

/**
 * The layer state Impasto v2's GPU simulation (GpuWetSimulation) takes, packed the way its buffers
 * hold it. Shared by the commit/replay session (GpuImpastoV2Stroke) and the live stroke.
 */
internal object WetUpload {

    /** Each [tileSize] tile's active flag (row-major, 1 = active) for a grid [cols] wide. */
    fun activeTiles(wetness: WetnessReplayState?, cols: Int, tiles: Int): IntArray = IntArray(tiles).also { out ->
        wetness?.field?.activeTileCoordinates()?.forEach { (tx, ty) -> out[ty * cols + tx] = 1 }
    }

    /** Each tile's effective medium (ImpastoMaterialReplayState.mediumAt), sanitized. */
    fun mediaTable(
        material: ImpastoMaterialReplayState,
        medium: PaintMedium,
        cols: Int,
        tiles: Int,
    ): FloatArray {
        val out = FloatArray(tiles * GpuWetSimulation.MEDIA_FLOATS)
        val tileSize = material.tileSize
        for (t in 0 until tiles) {
            val m = material.mediumAt((t % cols) * tileSize, (t / cols) * tileSize, medium).sanitized()
            val o = t * GpuWetSimulation.MEDIA_FLOATS
            out[o] = m.viscosity
            out[o + M_YIELD] = m.yieldLikeStrength
            out[o + M_LEVELING] = m.levelingRate
            out[o + M_DRYING] = m.dryingRate
            out[o + M_ROUGHNESS] = m.baseRoughness
            out[o + M_SPECULAR] = m.wetSpecularStrength
        }
        return out
    }

    /** [dabs] as the simulation's MaterialDab records (x, y, radius, alpha, flow, contact). */
    fun dabs(dabs: List<Dab>): FloatArray {
        val flat = FloatArray(dabs.size * GpuWetSimulation.DAB_FLOATS)
        dabs.forEachIndexed { i, d ->
            val o = i * GpuWetSimulation.DAB_FLOATS
            flat[o] = d.x
            flat[o + 1] = d.y
            flat[o + D_RADIUS] = d.radius
            flat[o + D_ALPHA] = d.alpha
            flat[o + D_FLOW] = d.flowMultiplier
            flat[o + D_CONTACT] = d.contactDepth
        }
        return flat
    }

    /** Words before the per-dab flags in the control buffer: two tile sets and the load slot. */
    fun flagsOffset(tiles: Int): Int = tiles * 2 + 1

    private const val M_YIELD = 1
    private const val M_LEVELING = 2
    private const val M_DRYING = 3
    private const val M_ROUGHNESS = 4
    private const val M_SPECULAR = 5
    private const val D_RADIUS = 2
    private const val D_ALPHA = 3
    private const val D_FLOW = 4
    private const val D_CONTACT = 5
}
