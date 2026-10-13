package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.LayerFrame
import com.hereliesaz.graffitixr.common.util.SafeBitmap
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor
import kotlin.math.ceil
import kotlin.math.floor

/** Pixels a layer grows by at a time, so a pan of a few pixels doesn't reallocate it. */
internal const val LAYER_GROWTH_STEP = 256

/** How many frame widths (and heights) a layer may span: centred on its frame. */
internal const val LAYER_GROWTH_MAX_FRAMES = 2

/** Room kept past the visible edge, for the dabs of a stroke that starts there. */
private const val LAYER_GROWTH_PAD = 64

/** The largest grown bitmap, in pixels (ARGB_8888: 4 bytes each). */
private const val LAYER_GROWTH_MAX_PIXELS = 24L * 1024 * 1024

/**
 * Growing a raster layer past its frame (see LayerFrame). Frame coordinates throughout: frame
 * pixel (0, 0) is the frame's corner, and a bitmap at origin (ox, oy) covers
 * `[ox, ox + width) x [oy, oy + height)`.
 */
internal object LayerGrowth {

    /**
     * The canvas area visible on screen, in [frame] pixels: the screen's corners taken back through
     * the workspace camera ([CanvasHitTest.screenToWorld]) and the layer's own transform.
     */
    @Suppress("LongParameterList")
    fun visibleFrameRect(
        frame: LayerFrame,
        canvasSize: IntSize,
        layerScale: Float,
        layerOffset: Offset,
        layerRotationZ: Float,
        viewportOffset: Offset,
        viewportZoom: Float,
        viewportRotation: Float,
    ): IntRect {
        val w = canvasSize.width.toFloat()
        val h = canvasSize.height.toFloat()
        val world = listOf(Offset(0f, 0f), Offset(w, 0f), Offset(w, h), Offset(0f, h)).map {
            CanvasHitTest.screenToWorld(it, viewportOffset, viewportZoom, viewportRotation)
        }
        val inFrame = ImageProcessor.mapScreenToBitmap(
            world, canvasSize.width, canvasSize.height, LayerFrame(frame.width, frame.height),
            layerScale, layerOffset, layerRotationZ,
        )
        return IntRect(
            floor(inFrame.minOf { it.x }).toInt(), floor(inFrame.minOf { it.y }).toInt(),
            ceil(inFrame.maxOf { it.x }).toInt(), ceil(inFrame.maxOf { it.y }).toInt(),
        )
    }

    /**
     * Where a [bitmapWidth] x [bitmapHeight] bitmap in [frame] should sit to also cover [visible]
     * (frame pixels): its current rectangle, grown on any side in [LAYER_GROWTH_STEP] steps, never
     * beyond [LAYER_GROWTH_MAX_FRAMES] frames centred on the frame. Null when it already covers what
     * it may, or the result would be too large.
     */
    fun coverage(frame: LayerFrame, bitmapWidth: Int, bitmapHeight: Int, visible: IntRect): IntRect? {
        val current = IntRect(frame.originX, frame.originY, frame.originX + bitmapWidth, frame.originY + bitmapHeight)
        // Do not allocate extra padding when the visible area already fits the bitmap.
        if (visible.left >= current.left && visible.top >= current.top &&
            visible.right <= current.right && visible.bottom <= current.bottom
        ) return null
        val spanX = frame.width * (LAYER_GROWTH_MAX_FRAMES - 1) / 2
        val spanY = frame.height * (LAYER_GROWTH_MAX_FRAMES - 1) / 2
        val cap = IntRect(-spanX, -spanY, frame.width + spanX, frame.height + spanY)
        val wanted = IntRect(
            visible.left - LAYER_GROWTH_PAD, visible.top - LAYER_GROWTH_PAD,
            visible.right + LAYER_GROWTH_PAD, visible.bottom + LAYER_GROWTH_PAD,
        )
        val clipped = wanted.intersect(cap)
        if (clipped.width <= 0 || clipped.height <= 0) return null
        val target = IntRect(
            minOf(current.left, snapDown(clipped.left).coerceAtLeast(cap.left)),
            minOf(current.top, snapDown(clipped.top).coerceAtLeast(cap.top)),
            maxOf(current.right, snapUp(clipped.right).coerceAtMost(cap.right)),
            maxOf(current.bottom, snapUp(clipped.bottom).coerceAtMost(cap.bottom)),
        )
        val pixels = target.width.toLong() * target.height
        return target.takeIf { it != current && pixels <= LAYER_GROWTH_MAX_PIXELS }
    }

    /** [bitmap] (in [frame]) copied into a transparent bitmap covering [target]; null on OOM. */
    fun grow(bitmap: Bitmap, frame: LayerFrame, target: IntRect): Bitmap? =
        SafeBitmap.create(target.width, target.height)?.also { grown ->
            Canvas(grown).drawBitmap(
                bitmap, (frame.originX - target.left).toFloat(), (frame.originY - target.top).toFloat(), null,
            )
        }

    private fun snapDown(v: Int): Int = Math.floorDiv(v, LAYER_GROWTH_STEP) * LAYER_GROWTH_STEP

    private fun snapUp(v: Int): Int = -Math.floorDiv(-v, LAYER_GROWTH_STEP) * LAYER_GROWTH_STEP
}

/**
 * The rectangle of a bitmap's non-transparent pixels: what a layer actually holds, as opposed to
 * how far its bitmap has grown. Cached per bitmap and its generation id, which changes whenever its
 * pixels do; null for a bitmap with nothing on it.
 */
internal object ContentBounds {
    private val cache = java.util.WeakHashMap<Bitmap, Pair<Int, IntRect?>>()

    @Synchronized
    fun of(bitmap: Bitmap): IntRect? {
        if (bitmap.isRecycled) return null
        val generation = bitmap.generationId
        val cached = cache[bitmap]?.takeIf { it.first == generation }
        return if (cached != null) {
            cached.second
        } else {
            scan(bitmap).also { cache[bitmap] = generation to it }
        }
    }

    private fun scan(bitmap: Bitmap): IntRect? {
        val w = bitmap.width
        val row = IntArray(w)
        var left = w
        var right = -1
        var top = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            val first = row.indexOfFirst { it ushr ALPHA_SHIFT != 0 }
            if (first < 0) continue
            val last = row.indexOfLast { it ushr ALPHA_SHIFT != 0 }
            if (top < 0) top = y
            bottom = y
            left = minOf(left, first)
            right = maxOf(right, last)
        }
        return if (top < 0) null else IntRect(left, top, right + 1, bottom + 1)
    }

    private const val ALPHA_SHIFT = 24
}
