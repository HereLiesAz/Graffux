package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
import com.hereliesaz.graffitixr.common.azphalt.Dab
import com.hereliesaz.graffitixr.common.azphalt.LiveStrokeDabs
import com.hereliesaz.graffitixr.common.model.CatmullRom

/**
 * The dab lists a recorded stamp stroke commits and replays with, shared by the GPU commit
 * ([GpuStampCommit]) and the CPU renderer (DrawingEngine), so the two only ever differ in the
 * rasterizer. [mapped] is the stroke's path already mapped into the layer bitmap.
 */
internal object StrokeDabs {

    /**
     * Movement and held dabs exactly as the live stroke generated them ([LiveStrokeDabs]); null
     * for a command without one recorded sample per point (legacy or remote), which then takes
     * [fromPath].
     */
    @Suppress("LongParameterList")
    fun recorded(
        stroke: StrokeCommand,
        brush: AzphaltBrush,
        mapped: List<Offset>,
        brushScale: Float,
        width: Int,
        height: Int,
    ): Pair<List<Dab>, List<Dab>>? {
        if (mapped.isEmpty() || stroke.brushSamples.size != mapped.size) return null
        // Kinematics stay in the recorded screen-hand space; only x/y move into the bitmap, as live.
        val samples = stroke.brushSamples.mapIndexed { index, sample ->
            sample.copy(x = mapped[index].x, y = mapped[index].y, predicted = false)
        }
        val diameterPx = stroke.brushSize * brushScale
        val tile = tiler(stroke, width, height)
        return tile(LiveStrokeDabs.movementDabs(samples, diameterPx, brush, stroke.seed)) to
            tile(LiveStrokeDabs.heldDabs(samples, diameterPx, brush, stroke.seed))
    }

    /** The historical path-only dab list, for a command with no recorded samples. */
    @Suppress("LongParameterList")
    fun fromPath(
        stroke: StrokeCommand,
        brush: AzphaltBrush,
        mapped: List<Offset>,
        brushScale: Float,
        width: Int,
        height: Int,
    ): List<Dab> {
        val pts = ArrayList<Float>(mapped.size * 2)
        mapped.forEach { pts.add(it.x); pts.add(it.y) }
        val dabs = BrushStamps.dabs(CatmullRom.densify(pts), stroke.brushSize * brushScale, brush, stroke.seed)
        return tiler(stroke, width, height)(dabs)
    }

    private fun tiler(stroke: StrokeCommand, width: Int, height: Int): (List<Dab>) -> List<Dab> = { dabs ->
        if (stroke.wrapAroundMode) wrapTiledDabs(dabs, width, height) else dabs
    }
}
