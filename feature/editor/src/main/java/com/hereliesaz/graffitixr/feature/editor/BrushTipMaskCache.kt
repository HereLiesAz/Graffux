package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
import com.hereliesaz.graffitixr.common.azphalt.GrainBlendMode
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Android-side equivalent of Krita's dab/mask cache boundary.
 *
 * Sensor/path code resolves *what* a dab should look like. This cache owns the expensive raster form
 * of reusable tip masks and processed grain tiles. The cache key contains only values that can alter
 * those pixels; position, colour, opacity and stroke seed stay out because they are applied later.
 *
 * Evicted bitmaps are deliberately not recycled: live preview and history replay may run on different
 * workers and can still hold a returned mask after it leaves the LRU. Normal Bitmap/GC ownership is
 * safer than creating a recycled-bitmap race in the painting hot path.
 */
internal object BrushTipMaskCache {
    private const val MAX_CACHE_BYTES = 16 * 1024 * 1024

    private data class TipKey(
        val sourceIdentity: Int,
        val width: Int,
        val height: Int,
        val hardness1000: Int,
    )

    private data class GrainKey(
        val sourceIdentity: Int,
        val mode: GrainBlendMode,
        val strength1000: Int,
    )

    private val tips = object : LruCache<TipKey, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: TipKey, value: Bitmap): Int = value.allocationByteCount
    }

    private val grains = object : LruCache<GrainKey, Bitmap>(MAX_CACHE_BYTES / 2) {
        override fun sizeOf(key: GrainKey, value: Bitmap): Int = value.allocationByteCount
    }

    @Synchronized
    fun tipMask(source: Bitmap?, widthPx: Int, heightPx: Int, hardness: Float): Bitmap {
        val width = widthPx.coerceAtLeast(1)
        val height = heightPx.coerceAtLeast(1)
        val key = TipKey(
            sourceIdentity = source?.let(System::identityHashCode) ?: 0,
            width = width,
            height = height,
            hardness1000 = (hardness.coerceIn(0f, 1f) * 1000f).roundToInt(),
        )
        tips.get(key)?.takeIf { !it.isRecycled }?.let { return it }
        val created = if (source == null) generatedTip(width, height, hardness) else scaledSource(source, width, height, hardness)
        tips.put(key, created)
        return created
    }

    /**
     * Converts a source texture into an alpha-only tiling factor with strength already folded in.
     * White means "leave the tip alone" and black means "remove paint". Different texture modes use
     * deliberately different transfer curves while sharing the same downstream mask operation.
     */
    @Synchronized
    fun grainMask(source: Bitmap, mode: GrainBlendMode, strength: Float): Bitmap {
        val s = strength.coerceIn(0f, 1f)
        val key = GrainKey(System.identityHashCode(source), mode, (s * 1000f).roundToInt())
        grains.get(key)?.takeIf { !it.isRecycled }?.let { return it }

        val width = source.width.coerceAtLeast(1)
        val height = source.height.coerceAtLeast(1)
        val src = IntArray(width * height)
        source.getPixels(src, 0, width, 0, 0, width, height)
        val out = IntArray(src.size)
        for (i in src.indices) {
            val p = src[i]
            val alpha = Color.alpha(p) / 255f
            val luminance = (
                Color.red(p) * 0.2126f + Color.green(p) * 0.7152f + Color.blue(p) * 0.0722f
                ) / 255f
            val g = luminance * alpha
            val modeFactor = when (mode) {
                GrainBlendMode.MULTIPLY -> g
                GrainBlendMode.SUBTRACT -> (2f * g - 1f).coerceIn(0f, 1f)
                // Quadratic rather than MULTIPLY's linear pass-through: suppresses the texture's
                // lighter (higher-g) areas more aggressively while still mapping 0->0 and 1->1, so
                // Darken reads as "more of the grain gets removed" relative to Multiply instead of
                // being pixel-identical to it.
                GrainBlendMode.DARKEN -> (g * g).coerceIn(0f, 1f)
                GrainBlendMode.OVERLAY -> (0.5f + 0.5f * g).coerceIn(0f, 1f)
            }
            val pass = (1f - s) + s * modeFactor
            val a = (pass * 255f).roundToInt().coerceIn(0, 255)
            out[i] = (a shl 24) or 0x00FFFFFF
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(out, 0, width, 0, 0, width, height)
        grains.put(key, bitmap)
        return bitmap
    }

    @Synchronized
    fun clear() {
        tips.evictAll()
        grains.evictAll()
    }

    private fun generatedTip(width: Int, height: Int, hardness: Float): Bitmap {
        val pixels = IntArray(width * height)
        val cx = width / 2f
        val cy = height / 2f
        val rx = (width / 2f).coerceAtLeast(0.5f)
        val ry = (height / 2f).coerceAtLeast(0.5f)
        var i = 0
        for (y in 0 until height) {
            val ny = (y + 0.5f - cy) / ry
            for (x in 0 until width) {
                val nx = (x + 0.5f - cx) / rx
                val coverage = BrushStamps.stampCoverage(hypot(nx, ny), hardness)
                val a = (coverage * 255f).roundToInt().coerceIn(0, 255)
                pixels[i++] = (a shl 24) or 0x00FFFFFF
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun scaledSource(source: Bitmap, width: Int, height: Int, hardness: Float): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(
            source,
            null,
            Rect(0, 0, width, height),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        // Was scaling the source and stopping there -- hardness never touched a textured tip's
        // pixels, only the shapeless generated tip's (see generatedTip below), so the Hardness
        // slider had no effect on any brush with a real tip image at all. Same radial coverage
        // function as generatedTip and the GPU shader (BrushStamps.stampCoverage), applied here as
        // an alpha multiplier on top of whatever the source's own alpha shape already is.
        if (hardness >= 1f) return out
        val cx = width / 2f
        val cy = height / 2f
        val rx = (width / 2f).coerceAtLeast(0.5f)
        val ry = (height / 2f).coerceAtLeast(0.5f)
        val pixels = IntArray(width * height)
        out.getPixels(pixels, 0, width, 0, 0, width, height)
        var i = 0
        for (y in 0 until height) {
            val ny = (y + 0.5f - cy) / ry
            for (x in 0 until width) {
                val nx = (x + 0.5f - cx) / rx
                val coverage = BrushStamps.stampCoverage(hypot(nx, ny), hardness)
                val p = pixels[i]
                val a = (Color.alpha(p) * coverage).roundToInt().coerceIn(0, 255)
                pixels[i] = (a shl 24) or (p and 0x00FFFFFF)
                i++
            }
        }
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }
}
