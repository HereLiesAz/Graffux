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

    fun write(pages: List<VectorPage>, out: OutputStream) = writeEach(pages, out, { it }, {})

    /**
     * Writes one page per item of [items], building each with [page] only when it is its turn and
     * calling [release] as soon as that page is finished — its drawing has been recorded into the
     * document by then — so the bitmaps behind one page (its raster fallbacks) can be freed before
     * the next page's are made, rather than every page's living until the file is written. A null
     * page is skipped (still released). [release] runs for every item whose page was built, even
     * when writing fails.
     */
    fun <T> writeEach(items: List<T>, out: OutputStream, page: (T) -> VectorPage?, release: (T) -> Unit) {
        val doc = PdfDocument()
        try {
            forEachPage(items, page, release) { p, number ->
                val info = PdfDocument.PageInfo.Builder(
                    ceil(p.width).toInt().coerceAtLeast(1), ceil(p.height).toInt().coerceAtLeast(1), number,
                ).create()
                val pdfPage = doc.startPage(info)
                draw(pdfPage.canvas, p)
                doc.finishPage(pdfPage)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    /** [writeEach]'s order: build one page, [emit] it (numbered from 1), [release] it, then the next. */
    internal fun <T> forEachPage(items: List<T>, page: (T) -> VectorPage?, release: (T) -> Unit, emit: (VectorPage, Int) -> Unit) {
        var number = 0
        for (item in items) {
            try {
                val p = page(item) ?: continue
                number += 1
                emit(p, number)
            } finally {
                release(item)
            }
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
