@file:Suppress("MagicNumber")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.ShapeToolMode
import com.hereliesaz.graffitixr.common.model.isContainer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** What one plane layer holds, for the guide's emptiness rule: [UNKNOWN] is "cannot tell", never counted as empty. */
enum class LeafContent { EMPTY, CONTENT, UNKNOWN }

/** Why the guided surface flow is not offered (spec/guided-surface.md, section 2); the UI words each one. */
enum class SurfaceGuideReason { CONTENT, NO_LAYER, LOCKED, CHECKING, UNREADABLE, UNAVAILABLE }

/** The tools a guided step draws its outline with, vector tools first and the raw stroke last (section 5a). */
enum class GuideTool {
    RECTANGLE,
    ROUNDED_RECTANGLE,
    ELLIPSE,
    LINE,
    PEN,
    FREEHAND,
    ;

    /** A traced stroke (Pen or Freehand), the only tools outline assist cleans; the shape tools are exact. */
    val isStroke: Boolean get() = this == PEN || this == FREEHAND
}

/** What the guide works on: an existing group, or a layer wrapped in a new group when a surface is committed. */
sealed interface GuideTarget {
    data class Group(val id: String) : GuideTarget
    data class Wrap(val layerId: String) : GuideTarget
}

/**
 * The pure rules of the guided surface flow (spec/guided-surface.md): when it is offered, where it starts, the order of
 * the three perspectives and the mirror that stands in for a skipped third surface. No state, no Android.
 */
object SurfaceGuide {
    /**
     * The capability test for "3D is available" (there is no 3D switch to read, so this is one named predicate and a
     * future setting is one more clause): a usable canvas, the same finite-and-positive test the existing surface
     * arming uses.
     */
    fun available(canvasWidth: Float, canvasHeight: Float): Boolean =
        canvasWidth.isFinite() && canvasHeight.isFinite() && canvasWidth > 0f && canvasHeight > 0f

    /**
     * Whether the whole stack is empty: every plane layer answers [leaf]; a group or frame is content only through
     * what is under it (an empty group, a group of empty layers and a group with a surface but no art are all empty);
     * a layer with a pose but no art is empty. Any content wins over "cannot tell", which wins over empty, so an
     * unreadable bitmap is never counted as empty. An empty stack answers [LeafContent.EMPTY]; the caller reports
     * "no layer" separately.
     */
    fun projectContent(layers: List<Layer>, leaf: (Layer) -> LeafContent): LeafContent {
        var unknown = false
        for (layer in layers) {
            if (layer.isContainer) continue
            when (leaf(layer)) {
                LeafContent.CONTENT -> return LeafContent.CONTENT
                LeafContent.UNKNOWN -> unknown = true
                LeafContent.EMPTY -> Unit
            }
        }
        return if (unknown) LeafContent.UNKNOWN else LeafContent.EMPTY
    }

    /**
     * Why the guide is not offered, or null when it is, in the order the spec lists them: no usable canvas, no layer, a
     * locked target, content, a content scan that could not tell. ("Checking" is the caller's, while a scan runs.)
     */
    fun unavailable(
        layers: List<Layer>,
        canvasUsable: Boolean,
        targetLocked: Boolean,
        content: LeafContent,
    ): SurfaceGuideReason? = when {
        !canvasUsable -> SurfaceGuideReason.UNAVAILABLE
        layers.isEmpty() -> SurfaceGuideReason.NO_LAYER
        targetLocked -> SurfaceGuideReason.LOCKED
        content == LeafContent.CONTENT -> SurfaceGuideReason.CONTENT
        content == LeafContent.UNKNOWN -> SurfaceGuideReason.UNREADABLE
        else -> null
    }

    /**
     * The target the way "Draw surface" picks it: the active group, or the group the active layer is in, else the
     * active top-level layer (wrapped in a new group when a surface is committed). Null when there is none.
     */
    fun target(layers: List<Layer>, activeId: String?): GuideTarget? {
        val active = layers.firstOrNull { it.id == activeId } ?: return null
        val parent = active.parentId?.let { pid -> layers.firstOrNull { it.id == pid } }
        return when {
            active.isContainer -> GuideTarget.Group(active.id)
            parent?.isContainer == true -> GuideTarget.Group(parent.id)
            active.parentId == null -> GuideTarget.Wrap(active.id)
            else -> null
        }
    }

    /**
     * The perspective the camera looks most nearly along: the axis of the largest absolute component of its view
     * direction; ties go to the front (z), then to the side (x).
     */
    fun firstPerspective(camera: SceneCamera): SurfaceView {
        val dx = abs(cos(camera.pitch) * sin(camera.yaw))
        val dy = abs(sin(camera.pitch))
        val dz = abs(cos(camera.pitch) * cos(camera.yaw))
        return when {
            dz + TIE >= dx && dz + TIE >= dy -> SurfaceView.FRONT
            dx + TIE >= dy -> SurfaceView.SIDE
            else -> SurfaceView.TOP
        }
    }

    /** The first, second and third perspective for a first perspective [first]: the cyclic order front, side, top. */
    fun order(first: SurfaceView): List<SurfaceView> = SurfaceView.cyclicFrom(first)

    /**
     * The tool a guide opens with: the Shape tool's mode when it is a rectangle, rounded rectangle, ellipse or
     * line, else the ellipse.
     */
    fun defaultTool(shapeMode: ShapeToolMode): GuideTool = when (shapeMode) {
        ShapeToolMode.RECTANGLE -> GuideTool.RECTANGLE
        ShapeToolMode.ROUNDED_RECTANGLE -> GuideTool.ROUNDED_RECTANGLE
        ShapeToolMode.LINE -> GuideTool.LINE
        else -> GuideTool.ELLIPSE
    }

    /**
     * Whether [profile], on its own, is a closed outline: it encloses area by the hull's own rule ([SurfaceHull.of])
     * AND ends on its start (within [CLOSE_GAP] of its length). A rectangle, rounded rectangle, ellipse, or a stroke
     * that comes back to where it began is closed, and a guided first surface that is closed becomes a solid; a line,
     * an arc or a path that does not return is open, even though joining its ends would enclose area.
     */
    fun isClosed(profile: SurfaceProfile): Boolean {
        val own = profile.view(profile.baseView)
        if (own == null || own.count < 3 || own.length <= 0f) return false
        val gap = FloatArray(3) { own.points[it] - own.points[(own.count - 1) * 3 + it] }
        val ends = kotlin.math.sqrt(gap[0] * gap[0] + gap[1] * gap[1] + gap[2] * gap[2])
        return ends <= CLOSE_GAP * own.length && SurfaceHull.of(own) != null
    }

    /**
     * The surface a skipped third step stands in with (section 4): [second], drawn in the perspective [secondView],
     * reflected through the plane that contains the first perspective's axis, passes through the centre of [first]'s
     * bounding box, and swaps the second's and third's look-along axes; the result is a profile in the third
     * perspective, with the sweep carried across. Open or closed alike. An involution: mirroring the result with the
     * second and third perspectives exchanged gives [second] back.
     */
    fun mirrored(
        second: SurfaceProfile,
        first: SurfaceProfile,
        firstView: SurfaceView,
        secondView: SurfaceView,
    ): SurfaceProfile {
        val a2 = secondView.axis
        val a3 = SurfaceView.entries.sumOf { it.axis } - firstView.axis - a2
        val centre = FloatArray(3) { k ->
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in 0 until first.count) {
                val v = first.points[i * 3 + k]
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            if (first.count == 0) 0f else (lo + hi) / 2f
        }
        val out = FloatArray(second.points.size)
        for (i in 0 until second.count) {
            for (k in 0..2) out[i * 3 + k] = second.points[i * 3 + k]
            out[i * 3 + a2] = centre[a2] + (second.points[i * 3 + a3] - centre[a3])
            out[i * 3 + a3] = centre[a3] + (second.points[i * 3 + a2] - centre[a2])
        }
        val sweep = second.sweep.copyOf().also { it[a2] = second.sweep[a3]; it[a3] = second.sweep[a2] }
        return second.copy(
            points = out.toList(), sweepX = sweep[0], sweepY = sweep[1], sweepZ = sweep[2],
            side = null, top = null, front = null, base = null, solid = false,
        )
    }

    private const val TIE = 1e-6f

    /** How far from its start a stroke may end, as a fraction of its length, and still be closed. */
    private const val CLOSE_GAP = 0.05f
}
