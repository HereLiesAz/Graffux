package com.hereliesaz.graffux.desktop

import com.hereliesaz.graffitixr.common.azphalt.ArgbColor
import com.hereliesaz.graffitixr.common.azphalt.CompositedTile

/** Straight-alpha SRC_OVER of a [CompositedTile] onto a full canvas-sized ARGB buffer — the desktop
 *  analogue of `android.graphics.Canvas.drawBitmap(tile, x, y, null)`'s default blend mode, since
 *  desktop has no `android.graphics.Canvas` to do this for us. */
fun blitSrcOver(dst: IntArray, dstWidth: Int, dstHeight: Int, tile: CompositedTile) {
    for (ty in 0 until tile.height) {
        val dy = tile.top + ty
        if (dy < 0 || dy >= dstHeight) continue
        val rowBase = ty * tile.width
        val dstRowBase = dy * dstWidth
        for (tx in 0 until tile.width) {
            val dx = tile.left + tx
            if (dx < 0 || dx >= dstWidth) continue
            val src = tile.pixels[rowBase + tx]
            val srcA = ArgbColor.alpha(src)
            if (srcA == 0) continue
            val dstIdx = dstRowBase + dx
            if (srcA == 255) {
                dst[dstIdx] = src
                continue
            }
            val dstPixel = dst[dstIdx]
            val dstA = ArgbColor.alpha(dstPixel)
            val srcAf = srcA / 255f
            val dstAf = dstA / 255f
            val outAf = srcAf + dstAf * (1f - srcAf)
            if (outAf <= 0f) {
                dst[dstIdx] = 0
                continue
            }
            fun blend(s: Int, d: Int): Int =
                (((s * srcAf) + (d * dstAf * (1f - srcAf))) / outAf).let {
                    it.toInt().coerceIn(0, 255)
                }
            val outR = blend(ArgbColor.red(src), ArgbColor.red(dstPixel))
            val outG = blend(ArgbColor.green(src), ArgbColor.green(dstPixel))
            val outB = blend(ArgbColor.blue(src), ArgbColor.blue(dstPixel))
            dst[dstIdx] = ArgbColor.argb((outAf * 255f).toInt().coerceIn(0, 255), outR, outG, outB)
        }
    }
}
