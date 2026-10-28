package com.hereliesaz.graffitixr.common.photo

import kotlin.math.max
import kotlin.math.min

/**
 * A Heal or Red-eye layer's render, kept between changes so an edit only touches the spots it
 * changed. The layer is transparent except where its spots are, so nothing here ever walks the
 * whole image after the first render. Adding a spot draws that spot. Moving a source or removing
 * a spot redraws the spots' own rectangles. The mask is applied inside those rectangles too
 * (outside every spot the layer is empty, and a mask cannot change empty).
 *
 * A whole redraw happens only when the photo under the layer ([sourceKey]), the mask
 * ([maskKey]) or the mode change: the layer is cleared and every spot drawn, which costs one
 * clear plus the spots' area.
 */
class RetouchCache(val width: Int, val height: Int) {

    /** The layer's pixels: premultiplied RGBA8, `width * height * 4` bytes, the mask applied. */
    val out = ByteArray(width * height * 4)

    /** The spots before the mask: what [out] is made from, rectangle by rectangle. */
    private val raw = ByteArray(width * height * 4)

    private var sourceKey = Long.MIN_VALUE
    private var maskKey = Long.MIN_VALUE
    private var contentAware = true
    private var inverted = false
    private var heal: List<HealSpot> = emptyList()
    private var redEye: List<RedEyeSpot> = emptyList()
    private var drawnOnce = false

    /** The rectangle (l, t, r, b, inclusive) the last [render] changed; null when nothing did. */
    var dirty: IntArray? = null
        private set

    /** Brings [out] up to [heal] and [redEye] over [src], times [mask] (one byte a pixel) when given. */
    @Suppress("LongParameterList")
    fun render(
        src: ByteArray,
        sourceKey: Long,
        heal: List<HealSpot>,
        redEye: List<RedEyeSpot>,
        contentAware: Boolean,
        mask: ByteArray?,
        maskKey: Long,
        inverted: Boolean,
    ) {
        val whole = !drawnOnce || sourceKey != this.sourceKey || maskKey != this.maskKey ||
            contentAware != this.contentAware || inverted != this.inverted
        val appended = !whole && heal.size >= this.heal.size && redEye.size >= this.redEye.size &&
            heal.subList(0, this.heal.size) == this.heal && redEye.subList(0, this.redEye.size) == this.redEye
        val rects = mutableListOf<IntArray>()
        when {
            whole -> {
                raw.fill(0)
                out.fill(0)
                heal.forEach { RetouchMath.heal(src, width, height, it, contentAware, raw); rects += box(it.x, it.y, it.radius) }
                redEye.forEach { RetouchMath.redEye(src, width, height, it, raw); rects += box(it.x, it.y, it.radius) }
            }
            appended -> {
                heal.drop(this.heal.size).forEach { RetouchMath.heal(src, width, height, it, contentAware, raw); rects += box(it.x, it.y, it.radius) }
                redEye.drop(this.redEye.size).forEach { RetouchMath.redEye(src, width, height, it, raw); rects += box(it.x, it.y, it.radius) }
            }
            else -> {
                // Something moved or went: clear every spot's rectangle, old and new, and redraw
                // the spots in order (a spot only writes inside its own rectangle).
                val all = (this.heal + heal).map { box(it.x, it.y, it.radius) } + (this.redEye + redEye).map { box(it.x, it.y, it.radius) }
                all.forEach { clear(it) }
                heal.forEach { RetouchMath.heal(src, width, height, it, contentAware, raw) }
                redEye.forEach { RetouchMath.redEye(src, width, height, it, raw) }
                rects += all
            }
        }
        rects.forEach { finish(it, mask, inverted) }
        dirty = rects.reduceOrNull { a, b -> intArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3])) }
            ?.takeIf { !whole } ?: if (whole) intArrayOf(0, 0, width - 1, height - 1) else null
        this.sourceKey = sourceKey
        this.maskKey = maskKey
        this.contentAware = contentAware
        this.inverted = inverted
        this.heal = heal
        this.redEye = redEye
        drawnOnce = true
    }

    private fun box(x: Float, y: Float, r: Float): IntArray = intArrayOf(
        max(0, (x - r).toInt() - 1), max(0, (y - r).toInt() - 1),
        min(width - 1, (x + r).toInt() + 2), min(height - 1, (y + r).toInt() + 2),
    )

    private fun clear(r: IntArray) {
        for (y in r[1]..r[3]) raw.fill(0, (y * width + r[0]) * 4, (y * width + r[2] + 1) * 4)
    }

    /** [out] inside [r]: the raw spots, times the mask when there is one. */
    private fun finish(r: IntArray, mask: ByteArray?, inverted: Boolean) {
        for (y in r[1]..r[3]) {
            val from = (y * width + r[0]) * 4
            val to = (y * width + r[2] + 1) * 4
            if (mask == null) {
                raw.copyInto(out, from, from, to)
                continue
            }
            for (x in r[0]..r[2]) {
                val i = y * width + x
                val m = (mask[i].toInt() and 0xFF).let { if (inverted) 255 - it else it }
                for (k in 0..3) out[i * 4 + k] = ((raw[i * 4 + k].toInt() and 0xFF) * m / 255).toByte()
            }
        }
    }
}
