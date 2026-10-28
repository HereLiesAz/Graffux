package com.hereliesaz.graffitixr.feature.editor.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Writes a [PosterPlan] of [art] (the cropped document composite) as a multi-page PDF, one tile
 * per page at real size. Each page also gets, in its margins, crop marks at the tile's corners,
 * registration marks at the middle of each edge, and its row and column.
 */
object PosterPdf {

    private const val MARK_GAP = 3f
    private const val MARK_LENGTH = 18f
    private const val MARK_STROKE = 0.5f
    private const val REG_RADIUS = 6f
    private const val LABEL_SIZE = 7f
    private const val MIN_LABEL_MARGIN = 9f
    /** The cross arms reach this far past the registration circle's radius. */
    private const val CROSS_REACH = 1.5f
    /** Baseline drop that centres the label's x-height on the margin's midline. */
    private const val LABEL_BASELINE = 1f / 3f

    /** Where pages go: a [PdfDocument] in the app, a recorder in tests (no native PDF there). */
    interface PageSink {
        /** A canvas in points for page [number] (1-based) of [width]×[height] points. */
        fun begin(width: Int, height: Int, number: Int): Canvas
        fun end()
    }

    fun write(art: Bitmap, plan: PosterPlan, spec: PosterSpec, out: OutputStream) {
        val doc = PdfDocument()
        try {
            var page: PdfDocument.Page? = null
            render(art, plan, spec, object : PageSink {
                override fun begin(width: Int, height: Int, number: Int): Canvas {
                    val p = doc.startPage(PdfDocument.PageInfo.Builder(width, height, number).create())
                    page = p
                    return p.canvas
                }
                override fun end() {
                    page?.let(doc::finishPage)
                    page = null
                }
            })
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    /** Every tile of [plan], one page each, in order, into [sink]. */
    fun render(art: Bitmap, plan: PosterPlan, spec: PosterSpec, sink: PageSink) {
        plan.tiles.forEachIndexed { i, tile ->
            val canvas = sink.begin(plan.pageWidthPt.roundToInt(), plan.pageHeightPt.roundToInt(), i + 1)
            drawPage(canvas, art, plan, spec, tile)
            sink.end()
        }
    }

    /** One page onto [canvas], whose units are points from the paper's top-left. */
    fun drawPage(canvas: Canvas, art: Bitmap, plan: PosterPlan, spec: PosterSpec, tile: PosterTile) {
        val k = art.width / plan.posterWidthPt
        val src = Rect(
            floor(tile.source.x * k).toInt().coerceIn(0, art.width - 1),
            floor(tile.source.y * k).toInt().coerceIn(0, art.height - 1),
            (tile.source.right * k).roundToInt().coerceIn(1, art.width),
            (tile.source.bottom * k).roundToInt().coerceIn(1, art.height),
        )
        val t = tile.target
        val dst = RectF(t.x, t.y, t.right, t.bottom)
        if (src.width() > 0 && src.height() > 0) {
            canvas.drawBitmap(art, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = MARK_STROKE
            style = Paint.Style.STROKE
        }
        val margin = t.x
        if (spec.cropMarks) drawCropMarks(canvas, t, margin, ink)
        if (spec.registrationMarks) drawRegistration(canvas, t, margin, ink)
        if (spec.pageLabels && margin >= MIN_LABEL_MARGIN) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = LABEL_SIZE
                textAlign = Paint.Align.LEFT
            }
            // Left, under the art's corner: the middle of the edge belongs to the registration mark.
            val label = "${tile.label} of ${plan.rows} × ${plan.columns}"
            canvas.drawText(label, t.x + MARK_GAP * 2f, t.bottom + margin / 2f + LABEL_SIZE * LABEL_BASELINE, text)
        }
    }

    /** Two short lines off each corner of [t], in the margin, a little clear of the art. */
    private fun drawCropMarks(canvas: Canvas, t: PtRect, margin: Float, ink: Paint) {
        val len = min(MARK_LENGTH, margin - MARK_GAP)
        if (len <= 0f) return
        for (x in floatArrayOf(t.x, t.right)) for (y in floatArrayOf(t.y, t.bottom)) {
            val dx = if (x == t.x) -1f else 1f
            val dy = if (y == t.y) -1f else 1f
            canvas.drawLine(x + dx * MARK_GAP, y, x + dx * (MARK_GAP + len), y, ink)
            canvas.drawLine(x, y + dy * MARK_GAP, x, y + dy * (MARK_GAP + len), ink)
        }
    }

    /** A circle and cross centred in the margin at the middle of each edge of [t]. */
    private fun drawRegistration(canvas: Canvas, t: PtRect, margin: Float, ink: Paint) {
        val r = min(REG_RADIUS, margin / 2f - 1f)
        if (r <= 1f) return
        val cx = (t.x + t.right) / 2f
        val cy = (t.y + t.bottom) / 2f
        val half = margin / 2f
        val centres = listOf(cx to t.y - half, cx to t.bottom + half, t.x - half to cy, t.right + half to cy)
        for ((x, y) in centres) {
            canvas.drawCircle(x, y, r, ink)
            canvas.drawLine(x - r * CROSS_REACH, y, x + r * CROSS_REACH, y, ink)
            canvas.drawLine(x, y - r * CROSS_REACH, x, y + r * CROSS_REACH, ink)
        }
    }
}
