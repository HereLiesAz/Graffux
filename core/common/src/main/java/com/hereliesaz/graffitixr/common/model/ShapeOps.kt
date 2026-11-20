package com.hereliesaz.graffitixr.common.model

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * The shapes the Shape tool draws, as pure geometry. A shape is an ordinary vector [Layer]
 * ([LayerType.VECTOR] holding one [VectorShape]), the model the Vector group already creates, so it
 * needs no new layer type, enum value or format field (`spec/shape-tool.md`, section 5).
 *
 * Points are in the editor's world space (the canvas centre is the origin), the space
 * [Layer.offset] lives in.
 */
object ShapeOps {

    /** A drag whose box is smaller than this on either side (a line: shorter than this) adds nothing. */
    const val MIN_SIZE = 4f

    /**
     * The height a line's [VectorShape] carries. The renderers and exporters ignore it (a line is a run of
     * its width), but the hit test boxes a vector by width x height, so it is the stroke width: a thin
     * strip along the line, not the 400 default.
     */
    const val LINE_BOX_HEIGHT = 6f

    /** A new line's stroke width, the Vector group's line default. */
    const val LINE_STROKE_WIDTH = 6f

    /** A rounded rectangle starts with a radius of this fraction of its shorter side. */
    const val ROUNDED_RADIUS_FRACTION = 0.2f

    /** The side of a shape added by the Add button rather than drawn. */
    const val ADD_SIZE = 200f

    /** The kind [mode] stores, or null for a mode that draws no vector shape (the frame). */
    fun kindOf(mode: ShapeToolMode): ShapeKind? = when (mode) {
        ShapeToolMode.RECTANGLE, ShapeToolMode.ROUNDED_RECTANGLE -> ShapeKind.RECTANGLE
        ShapeToolMode.ELLIPSE -> ShapeKind.ELLIPSE
        ShapeToolMode.LINE -> ShapeKind.LINE
        else -> null
    }

    /** The box with opposite corners [a] and [b], whichever way the drag went. */
    fun boxFromDrag(a: Offset, b: Offset): Rect =
        Rect(min(a.x, b.x), min(a.y, b.y), abs(b.x - a.x), abs(b.y - a.y))

    /** What a new shape layer is called and coloured: its [id], [name] and [colorArgb] (the colour in hand). */
    data class Spec(val id: String, val name: String, val colorArgb: Long)

    /**
     * The shape [mode] draws for a drag from [a] to [b]: a closed shape is filled with [Spec.colorArgb], a
     * line stroked with it. Null when the mode draws no shape or the drag is too small to be one.
     */
    fun create(mode: ShapeToolMode, spec: Spec, a: Offset, b: Offset): Layer? {
        val kind = kindOf(mode) ?: return null
        return if (kind == ShapeKind.LINE) {
            line(spec, a, b)
        } else {
            boxFromDrag(a, b)
                .takeIf { it.width >= MIN_SIZE && it.height >= MIN_SIZE }
                ?.let { closed(mode, kind, spec, it) }
        }
    }

    /** The shape [mode] draws at the default size, centred on [centre] (the Add button); null for the frame. */
    fun createCentred(mode: ShapeToolMode, spec: Spec, centre: Offset): Layer? {
        val kind = kindOf(mode) ?: return null
        val half = ADD_SIZE / 2f
        return if (kind == ShapeKind.LINE) {
            line(spec, Offset(centre.x - half, centre.y), Offset(centre.x + half, centre.y))
        } else {
            closed(mode, kind, spec, Rect(centre.x - half, centre.y - half, ADD_SIZE, ADD_SIZE))
        }
    }

    private fun closed(mode: ShapeToolMode, kind: ShapeKind, spec: Spec, box: Rect): Layer {
        val shorter = min(box.width, box.height)
        val radius = if (mode == ShapeToolMode.ROUNDED_RECTANGLE) shorter * ROUNDED_RADIUS_FRACTION else 0f
        return Layer(
            id = spec.id,
            name = spec.name,
            type = LayerType.VECTOR,
            offset = Offset(box.centerX, box.centerY),
            shapes = listOf(
                VectorShape(
                    kind = kind,
                    width = box.width,
                    height = box.height,
                    cornerRadius = radius,
                    fillArgb = spec.colorArgb,
                    strokeWidth = 0f,
                ),
            ),
        )
    }

    /**
     * A line is a horizontal run of the segment's length centred on the layer origin, so the layer's
     * offset is the segment's midpoint and its Z rotation is the segment's direction.
     */
    private fun line(spec: Spec, a: Offset, b: Offset): Layer? {
        val length = hypot(b.x - a.x, b.y - a.y)
        val direction = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
        return if (length < MIN_SIZE) {
            null
        } else {
            Layer(
                id = spec.id,
                name = spec.name,
                type = LayerType.VECTOR,
                offset = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f),
                rotationZ = direction,
                shapes = listOf(
                    VectorShape(
                        kind = ShapeKind.LINE,
                        width = length,
                        height = LINE_BOX_HEIGHT,
                        strokeArgb = spec.colorArgb,
                        strokeWidth = LINE_STROKE_WIDTH,
                    ),
                ),
            )
        }
    }

    /**
     * The number a new shape's name carries: one past the highest number already used after [base] (for
     * example "Rectangle 3" among [layers] makes 4 for base "Rectangle"). A deleted layer's number is not
     * reused until it is the highest.
     */
    fun nextNumber(layers: List<Layer>, base: String): Int {
        val used = layers.mapNotNull { l ->
            if (l.name.startsWith("$base ")) l.name.removePrefix("$base ").toIntOrNull() else null
        }
        return (used.maxOrNull() ?: 0) + 1
    }
}
