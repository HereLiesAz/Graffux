@file:Suppress("MagicNumber", "LongParameterList", "ComplexCondition", "MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.frame
import com.hereliesaz.graffitixr.common.scene.SurfaceMath
import com.hereliesaz.graffitixr.common.scene.SurfaceOwner
import com.hereliesaz.graffitixr.common.scene.SurfaceViewMove
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor

/**
 * One view image's pixels against the surface's paint (unrolled) space: both maps are affine (the layer's
 * 2D transform, then the unroll), so they are found once from three points and are plain arithmetic after.
 * [lifted] holds the image's pixels cut to the selection ([SelectionMask.lift]) when it is a move's source.
 */
internal class ViewImageSpace(
    layer: Layer,
    bitmap: Bitmap,
    owner: SurfaceOwner,
    canvasW: Int,
    canvasH: Int,
    private val lifted: IntArray? = null,
) : SurfaceViewMove.ViewPixels {
    override val width: Int = bitmap.width
    override val height: Int = bitmap.height

    private val ox: Float
    private val oy: Float
    private val ax: Float
    private val ay: Float
    private val bx: Float
    private val by: Float
    private val det: Float

    init {
        val flat = ImageProcessor.mapBitmapToScreen(
            listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(0f, 1f)), canvasW, canvasH,
            layer.frame(bitmap.width, bitmap.height), layer.scale, layer.offset, layer.rotationZ,
        )
        val paint = flat.map { SurfaceMath.flatToUnrolled(it.x, it.y, owner.innerChain, canvasW.toFloat(), canvasH.toFloat()) }
        ox = paint[0].first; oy = paint[0].second
        ax = paint[1].first - ox; ay = paint[1].second - oy
        bx = paint[2].first - ox; by = paint[2].second - oy
        det = ax * by - ay * bx
    }

    override fun paintOf(px: Float, py: Float): Pair<Float, Float> = (ox + px * ax + py * bx) to (oy + px * ay + py * by)

    override fun pixelOf(x: Float, y: Float): Pair<Float, Float>? {
        if (kotlin.math.abs(det) < 1e-12f) return null
        val dx = x - ox
        val dy = y - oy
        return (dx * by - dy * bx) / det to (ax * dy - ay * dx) / det
    }

    override fun argb(px: Int, py: Int): Int = lifted?.get(py * width + px) ?: 0
}
