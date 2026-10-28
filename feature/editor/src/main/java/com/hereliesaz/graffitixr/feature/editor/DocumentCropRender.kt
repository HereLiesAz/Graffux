@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.hereliesaz.graffitixr.common.photo.CropGeometry
import com.hereliesaz.graffitixr.common.photo.DocumentCrop
import com.hereliesaz.graffitixr.feature.editor.export.artboardRect
import kotlin.math.roundToInt

/**
 * The document crop on Android: the same [CropGeometry.matrix] for the canvas ([documentCrop])
 * and the export ([render]), so what is cropped on screen is what is exported. Everything is in
 * world pixels: the canvas the layers are drawn into, with the artboard centred in it.
 */
object DocumentCropRender {

    /** The artboard's rectangle in a [w] x [h] world. */
    fun artboard(w: Int, h: Int, docW: Int, docH: Int): RectF =
        artboardRect(w, h, docW, docH).let { RectF(it[0], it[1], it[0] + it[2], it[1] + it[3]) }

    fun matrix(crop: DocumentCrop, artboard: RectF): Matrix = Matrix().apply {
        setValues(CropGeometry.matrix(crop, artboard.centerX(), artboard.centerY(), artboard.width(), artboard.height()))
    }

    /** The crop rectangle in world pixels: the crop's fractions of the artboard. */
    fun cropRect(crop: DocumentCrop, artboard: RectF): RectF = RectF(
        artboard.left + crop.left * artboard.width(),
        artboard.top + crop.top * artboard.height(),
        artboard.left + crop.right * artboard.width(),
        artboard.top + crop.bottom * artboard.height(),
    )

    /**
     * The export: [full] (the layers composited into the whole world) drawn through [crop]'s
     * transform, cut to its rectangle, at the document's resolution — [docW] x [docH] scaled by the
     * crop's share of the artboard.
     */
    fun render(full: Bitmap, crop: DocumentCrop, docW: Int, docH: Int): Bitmap {
        val art = artboard(full.width, full.height, docW, docH)
        val rect = cropRect(crop, art)
        val outW = (docW * (crop.right - crop.left)).roundToInt().coerceAtLeast(1)
        val outH = (docH * (crop.bottom - crop.top)).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            scale(outW / rect.width(), outH / rect.height())
            translate(-rect.left, -rect.top)
            concat(matrix(crop, art))
            drawBitmap(full, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }
}

/**
 * Draws the content (the layer stack, in world pixels) through the document crop: transformed,
 * and, unless the Crop tab is open ([editing]), clipped to the crop. Identity crops cost nothing.
 */
fun Modifier.documentCrop(crop: DocumentCrop, editing: Boolean, docW: Int, docH: Int): Modifier =
    if (crop.isIdentity) {
        this
    } else {
        drawWithContent {
            val art = DocumentCropRender.artboard(size.width.toInt(), size.height.toInt(), docW, docH)
            drawIntoCanvas { c ->
                val native = c.nativeCanvas
                native.save()
                if (!editing) native.clipRect(DocumentCropRender.cropRect(crop, art))
                native.concat(DocumentCropRender.matrix(crop, art))
                drawContent()
                native.restore()
            }
        }
    }
