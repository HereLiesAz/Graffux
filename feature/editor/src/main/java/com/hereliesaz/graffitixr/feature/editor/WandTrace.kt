@file:Suppress("ComplexCondition", "MaxLineLength", "LongParameterList")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.SelectionRing
import com.hereliesaz.graffitixr.common.model.frame
import com.hereliesaz.graffitixr.common.util.ContourTrace
import com.hereliesaz.graffitixr.common.util.SelectionGeometry
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor

/**
 * A pixel [mask] of [layer]'s [bitmap], traced at [mw] by [mh] (the bitmap's own size, or a scaled copy's:
 * each axis is mapped back by its own ratio), as rings in the layer's flat (screen) space, holes marked.
 */
internal fun traceMaskRings(
    mask: BooleanArray,
    mw: Int,
    mh: Int,
    layer: Layer,
    bitmap: Bitmap,
    canvasSize: IntSize,
): List<SelectionRing> {
    val traced = ContourTrace.contours(mask, mw, mh)
    if (traced.isEmpty()) return emptyList()
    val sx = bitmap.width.toFloat() / mw
    val sy = bitmap.height.toFloat() / mh
    return traced.map { ring ->
        val inBitmap = if (sx == 1f && sy == 1f) ring.path else ring.path.map { Offset(it.x * sx, it.y * sy) }
        ring.copy(
            path = SelectionGeometry.simplify(
                ImageProcessor.mapBitmapToScreen(
                    inBitmap, canvasSize.width, canvasSize.height,
                    layer.frame(bitmap.width, bitmap.height), layer.scale, layer.offset, layer.rotationZ,
                ),
                // Gentler than a traced lasso's thinning: the vertices are already only the turns.
                minSpacing = 1.5f,
            ),
        )
    }.filter { it.isUsable }
}
