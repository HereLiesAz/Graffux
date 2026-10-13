package com.hereliesaz.graffitixr.common.model

/**
 * Where a raster layer's pixels sit. The canvas fits the frame ([width] x [height]) to the screen,
 * the layer's own transform moves that, and the bitmap is drawn inside it with its pixel (0, 0) at
 * frame pixel ([originX], [originY]). A bitmap that has grown past the frame has a negative origin
 * and/or a size beyond the frame's. Frame pixel `p` is bitmap pixel `p - origin`.
 */
data class LayerFrame(
    val width: Int,
    val height: Int,
    val originX: Int = 0,
    val originY: Int = 0,
) {
    companion object {
        /** The frame of a bitmap that has never grown: the bitmap itself. */
        fun of(bitmapWidth: Int, bitmapHeight: Int) = LayerFrame(bitmapWidth, bitmapHeight)
    }
}

/** [layer]'s frame for a [bitmapWidth] x [bitmapHeight] bitmap ([Layer.frameWidth] 0 = that size). */
fun Layer.frame(bitmapWidth: Int, bitmapHeight: Int): LayerFrame = LayerFrame(
    if (frameWidth > 0) frameWidth else bitmapWidth,
    if (frameHeight > 0) frameHeight else bitmapHeight,
    originX,
    originY,
)

/** [Layer.frame] for the layer's own bitmap; null without one. */
val Layer.frame: LayerFrame? get() = bitmap?.let { frame(it.width, it.height) }
