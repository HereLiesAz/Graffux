package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.util.SafeBitmap
import com.hereliesaz.graffitixr.nativebridge.GpuSelection
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine

/**
 * A live stroke's selection, confined on the GPU at commit (GpuSelection). At the stroke's start
 * the selection's per-pixel coverage goes to the stroke's engine and the engine keeps the layer as
 * it is ([prepare]); the commit blends the paint back through the coverage ([confine]) -- the same
 * composite the CPU commit made with GpuStampCommit.keepInside (hard clip) and SelectionMask.feather.
 *
 * The coverage is selection state, not stroke work: rasterized once per selection, size and layer
 * transform, and cached.
 */
internal object GpuStrokeSelection {

    /** What a stroke started under: whether its selection is feathered. */
    data class Prepared(val feathered: Boolean)

    private data class Key(
        val selection: Selection,
        val width: Int,
        val height: Int,
        val frame: com.hereliesaz.graffitixr.common.model.LayerFrame,
        val scale: Float,
        val offset: Offset,
        val rotationZ: Float,
    )

    private var cachedKey: Key? = null
    private var cached: Pair<ByteArray, Boolean>? = null

    /**
     * Uploads [selection]'s coverage for a [width]x[height] layer bitmap sitting in [frame], with the
     * given transform, and keeps
     * the layer as it is now. Null when there is no selection or the engine can't (the commit then
     * paints the stroke again through the replay path, which masks it).
     */
    @Suppress("LongParameterList")
    fun prepare(
        engine: GpuStampEngine,
        selection: Selection?,
        width: Int,
        height: Int,
        frame: com.hereliesaz.graffitixr.common.model.LayerFrame,
        scale: Float,
        offset: Offset,
        rotationZ: Float,
    ): Prepared? {
        val built = selection?.takeIf { it.isUsable }
            ?.let { coverage(Key(it, width, height, frame, scale, offset, rotationZ)) }
        val gpu = GpuSelection(engine)
        return built?.let { (coverage, feathered) ->
            Prepared(feathered).takeIf { gpu.set(coverage) && gpu.takeShadow() }
        }
    }

    /** Confines the stroke on [engine] to the selection it [prepared] under. */
    fun confine(engine: GpuStampEngine, prepared: Prepared): Boolean = GpuSelection(engine).confine(prepared.feathered)

    @Synchronized
    private fun coverage(key: Key): Pair<ByteArray, Boolean>? {
        if (key == cachedKey) return cached
        val path = SelectionMask.bitmapPath(key.selection, key.frame, key.scale, key.offset, key.rotationZ)
        val radius = SelectionMask.featherRadius(key.selection, key.frame, key.scale)
        val bytes = if (radius > 0f) {
            SelectionMask.featherWeights(path, key.width, key.height, radius)
        } else {
            path?.let { clipCoverage(it, key.width, key.height) }
        }
        val result = bytes?.let { it to (radius > 0f) }
        cachedKey = key
        cached = result
        return result
    }

    /** The antialiased coverage a Canvas clip to [path] gives, one byte per pixel. */
    private fun clipCoverage(path: android.graphics.Path, width: Int, height: Int): ByteArray? {
        val mask = SafeBitmap.create(width, height) ?: return null
        return try {
            Canvas(mask).drawPath(path, Paint().apply { isAntiAlias = true; color = Color.WHITE })
            alphaBytes(mask)
        } finally {
            mask.recycle()
        }
    }

    private fun alphaBytes(mask: Bitmap): ByteArray {
        val width = mask.width
        val out = ByteArray(width * mask.height)
        val row = IntArray(width)
        for (y in 0 until mask.height) {
            mask.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) out[y * width + x] = (row[x] ushr ALPHA_SHIFT and BYTE_MASK).toByte()
        }
        return out
    }

    private const val ALPHA_SHIFT = 24
    private const val BYTE_MASK = 0xFF
}
