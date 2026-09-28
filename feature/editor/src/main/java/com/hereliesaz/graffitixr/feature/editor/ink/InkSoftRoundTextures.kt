package com.hereliesaz.graffitixr.feature.editor.ink

import android.graphics.Bitmap
import androidx.ink.brush.TextureBitmapStore
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Serves the soft round's stamp textures to Ink by id — to the live InProgressStrokesView and to
 * every CanvasStrokeRenderer that commits or replays an Ink stroke, so the two always agree.
 *
 * Each texture is white with [InkSoftRound.solveStampProfile]'s radial alpha: Ink's default texture
 * blend multiplies it by the brush colour. Generated on first use and kept for the process; there are
 * at most 21 × 21 (feathering × opacity steps) and each is 128 × 128.
 */
internal object InkSoftRoundTextures : TextureBitmapStore {
    private val cache = ConcurrentHashMap<String, Bitmap>()

    override fun get(clientTextureId: String): Bitmap? = cache[clientTextureId]
        ?: InkSoftRound.parseTextureId(clientTextureId)?.let { (feathering, opacity) ->
            val bitmap = render(InkSoftRound.solveStampProfile(feathering, opacity))
            cache.putIfAbsent(clientTextureId, bitmap) ?: bitmap
        }

    private fun render(profile: FloatArray): Bitmap {
        val side = InkSoftRound.BINS * 2
        val half = side / 2f
        val pixels = IntArray(side * side)
        for (y in 0 until side) {
            for (x in 0 until side) {
                val dx = (x + PIXEL_CENTRE - half) / half
                val dy = (y + PIXEL_CENTRE - half) / half
                val a = (InkSoftRound.sample(profile, sqrt(dx * dx + dy * dy)) * MAX_BYTE).toInt().coerceIn(0, MAX_BYTE)
                pixels[y * side + x] = (a shl ALPHA_SHIFT) or WHITE_RGB
            }
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    private const val MAX_BYTE = 255
    private const val PIXEL_CENTRE = 0.5f
    private const val ALPHA_SHIFT = 24
    private const val WHITE_RGB = 0xFFFFFF
}
