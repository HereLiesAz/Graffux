package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
import com.hereliesaz.graffitixr.common.azphalt.Dab
import com.hereliesaz.graffitixr.common.azphalt.LiveStrokeDabs
import com.hereliesaz.graffitixr.common.model.CatmullRom

/**
 * The dab lists a recorded stamp stroke commits and replays with, shared by the GPU commit
 * ([GpuStampCommit]), the GPU replay session and the Impasto strokes, so they never differ in
 * which dabs they stamp. [mapped] is the stroke's path already mapped into the layer bitmap.
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
        return tile(LiveStrokeDabs.movementStroke(samples, diameterPx, brush, stroke.seed, stroke.paintPrior).dabs) to
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

    /**
     * [movement] and [held] split back into the live stroke's stamping calls: for each recorded
     * (movement, held) pair, that many movement dabs, then that many held dabs. Anything the
     * record doesn't cover (none, or a legacy command) follows as one last batch.
     */
    fun batches(movement: List<Dab>, held: List<Dab>, recorded: List<Int>?): List<Pair<List<Dab>, List<Dab>>> {
        // Grouping matters beyond the airbrush too: a masked tip without build-up combines dabs
        // within one stamping call, so replay groups them exactly as live did.
        if (recorded.isNullOrEmpty()) return listOf(movement to held)
        val out = ArrayList<Pair<List<Dab>, List<Dab>>>()
        var m = 0
        var h = 0
        for (i in 0 until recorded.size / 2) {
            val mEnd = (m + recorded[2 * i]).coerceAtMost(movement.size)
            val hEnd = (h + recorded[2 * i + 1]).coerceAtMost(held.size)
            out.add(movement.subList(m, mEnd) to held.subList(h, hEnd))
            m = mEnd
            h = hEnd
        }
        if (m < movement.size || h < held.size) {
            out.add(movement.subList(m, movement.size) to held.subList(h, held.size))
        }
        return out
    }

    private fun tiler(stroke: StrokeCommand, width: Int, height: Int): (List<Dab>) -> List<Dab> = { dabs ->
        if (stroke.wrapAroundMode) wrapTiledDabs(
            dabs, stroke.wrapFrameWidth.takeIf { it > 0 } ?: width,
            stroke.wrapFrameHeight.takeIf { it > 0 } ?: height,
        ) else dabs
    }
}
