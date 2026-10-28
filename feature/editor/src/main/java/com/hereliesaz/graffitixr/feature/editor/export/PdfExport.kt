@file:Suppress("MagicNumber", "CyclomaticComplexMethod", "MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor.export

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import kotlin.math.ceil

/**
 * Writes [VectorPage]s as a PDF, one page each, through Android's [PdfDocument]: its page canvas
 * records paths, fills and strokes as PDF vectors and bitmaps as embedded images. Group opacity is
 * carried down onto each paint (a page canvas has no offscreen layers), and blend modes are not
 * kept: a PDF reader draws every layer normally.
 */
object PdfExport {

    fun write(pages: List<VectorPage>, out: OutputStream) {
        val doc = PdfDocument()
        try {
            pages.forEachIndexed { index, page ->
                val info = PdfDocument.PageInfo.Builder(
                    ceil(page.width).toInt().coerceAtLeast(1), ceil(page.height).toInt().coerceAtLeast(1), index + 1,
                ).create()
                val pdfPage = doc.startPage(info)
                draw(pdfPage.canvas, page)
                doc.finishPage(pdfPage)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    /** Draws [page] onto [canvas]: also how a test renders one to a bitmap. */
    fun draw(canvas: Canvas, page: VectorPage) {
        canvas.save()
        canvas.concat(page.root.toMatrix())
        page.nodes.forEach { node(canvas, it, 1f) }
        canvas.restore()
    }

    private fun node(canvas: Canvas, n: VectorNode, parentOpacity: Float) {
        val opacity = parentOpacity * n.opacity.coerceIn(0f, 1f)
        if (opacity <= 0f) return
        canvas.save()
        if (!n.transform.isIdentity) canvas.concat(n.transform.toMatrix())
        when (n) {
            is VectorNode.Group -> {
                val box = n.box
                if (box != null) {
                    val rect = RectF(box[0], box[1], box[0] + box[2], box[1] + box[3])
                    if (n.clips) canvas.clipPath(Path().apply { addRoundRect(rect, box[4], box[4], Path.Direction.CW) })
                    n.fillArgb?.let { canvas.drawRoundRect(rect, box[4], box[4], paint(it, opacity, Paint.Style.FILL)) }
                }
                n.children.forEach { node(canvas, it, opacity) }
            }
            is VectorNode.Shape -> {
                val path = n.path.toPath()
                n.fillArgb?.let { canvas.drawPath(path, paint(it, opacity, Paint.Style.FILL)) }
                if (n.strokeArgb != null && n.strokeWidth > 0f) {
                    canvas.drawPath(path, paint(n.strokeArgb, opacity, Paint.Style.STROKE).apply { strokeWidth = n.strokeWidth })
                }
            }
            is VectorNode.Image -> n.layer.bitmap?.takeIf { !it.isRecycled }?.let { bmp ->
                val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = (opacity * 255).toInt() }
                canvas.drawBitmap(bmp, 0f, 0f, p)
            }
        }
        canvas.restore()
    }

    private fun paint(argb: Long, opacity: Float, style: Paint.Style) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = argb.toInt()
        alpha = (alpha * opacity).toInt().coerceIn(0, 255)
        this.style = style
    }

    private fun List<PathCmd>.toPath(): Path = Path().also { p ->
        forEach {
            when (it) {
                is PathCmd.Move -> p.moveTo(it.x, it.y)
                is PathCmd.Line -> p.lineTo(it.x, it.y)
                is PathCmd.Cubic -> p.cubicTo(it.x1, it.y1, it.x2, it.y2, it.x, it.y)
                PathCmd.Close -> p.close()
            }
        }
    }

    private fun Affine.toMatrix(): Matrix = Matrix().apply { setValues(floatArrayOf(a, c, e, b, d, f, 0f, 0f, 1f)) }
}
