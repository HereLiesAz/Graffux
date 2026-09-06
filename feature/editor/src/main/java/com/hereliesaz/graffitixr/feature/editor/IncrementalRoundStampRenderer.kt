package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.hereliesaz.graffitixr.common.azphalt.CompositedTile

/** Applies changed max-combine tiles against the pristine pre-stroke base. */
internal object IncrementalRoundStampRenderer {
    fun repaintTilesFromBase(
        canvas: Canvas,
        baseBitmap: Bitmap,
        tiles: List<CompositedTile>,
    ) {
        if (tiles.isEmpty()) return
        val restorePaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
        for (tile in tiles) {
            if (tile.width <= 0 || tile.height <= 0) continue
            val right = tile.left + tile.width
            val bottom = tile.top + tile.height
            val rect = Rect(tile.left, tile.top, right, bottom)
            canvas.drawBitmap(baseBitmap, rect, rect, restorePaint)

            val overlay = Bitmap.createBitmap(tile.width, tile.height, Bitmap.Config.ARGB_8888)
            try {
                overlay.setPixels(tile.pixels, 0, tile.width, 0, 0, tile.width, tile.height)
                canvas.drawBitmap(overlay, tile.left.toFloat(), tile.top.toFloat(), null)
            } finally {
                overlay.recycle()
            }
        }
        restorePaint.xfermode = null
    }
}
