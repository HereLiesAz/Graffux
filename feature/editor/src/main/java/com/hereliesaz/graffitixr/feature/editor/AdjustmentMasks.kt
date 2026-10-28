@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * The adjustment layers' masks (the Local tab paints them): one byte per world pixel, 255 = the
 * layer's full effect. Kept here, not on the layer, because they are large; the layer's
 * `AdjustmentMask.revision` names which one it uses. Every stroke makes a new revision (the old
 * one stays for undo), so undo and redo are just the layer's revision going back and forth.
 * [REVISIONS_KEPT] are held per layer (a world-sized mask is ~2.6 MB at 1080 x 2400); an undo
 * further back than that finds no mask and the layer applies everywhere. Thread-safe.
 */
internal class AdjustmentMasks {

    private val masks = HashMap<String, LinkedHashMap<Int, ByteArray>>()
    private val generations = HashMap<String, Long>()
    private var counter = 0L

    @Synchronized
    fun get(layerId: String, revision: Int): ByteArray? = masks[layerId]?.get(revision)

    /** The newest revision held for [layerId], or 0. */
    @Synchronized
    fun latest(layerId: String): Int = masks[layerId]?.keys?.maxOrNull() ?: 0

    /** Stores [bytes] as [layerId]'s [revision] (taking ownership), dropping the oldest past the limit. */
    @Synchronized
    fun put(layerId: String, revision: Int, bytes: ByteArray) {
        val revs = masks.getOrPut(layerId) { LinkedHashMap() }
        revs[revision] = bytes
        while (revs.size > REVISIONS_KEPT) revs.remove(revs.keys.first())
        touch(layerId)
    }

    /** A change to a mask's bytes in place (a stroke in progress): what the renderer re-uploads by. */
    @Synchronized
    fun touch(layerId: String) {
        generations[layerId] = ++counter
    }

    @Synchronized
    fun generation(layerId: String): Long = generations[layerId] ?: 0L

    @Synchronized
    fun retainOnly(layerIds: Set<String>) {
        masks.keys.retainAll(layerIds)
        generations.keys.retainAll(layerIds)
    }

    companion object {
        const val REVISIONS_KEPT = 12

        /** A mask as an ALPHA_8 bitmap ([w] x [h]): what the overlay and the badge draw. */
        fun toBitmap(bytes: ByteArray, w: Int, h: Int): Bitmap =
            Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).apply { copyPixelsFromBuffer(ByteBuffer.wrap(bytes, 0, w * h)) }

        /** A mask from any bitmap's alpha, scaled to [w] x [h] (a saved mask from another screen size). */
        fun fromBitmap(bitmap: Bitmap, w: Int, h: Int): ByteArray {
            val scaled = if (bitmap.width == w && bitmap.height == h) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
            val px = IntArray(w * h)
            scaled.getPixels(px, 0, w, 0, 0, w, h)
            if (scaled !== bitmap) scaled.recycle()
            return ByteArray(w * h) { (px[it] ushr ALPHA_SHIFT).toByte() }
        }

        /** The mask as an ARGB_8888 white image with the mask as its alpha: what is saved as PNG. */
        fun toPngBitmap(bytes: ByteArray, w: Int, h: Int): Bitmap {
            val px = IntArray(w * h) { ((bytes[it].toInt() and BYTE) shl ALPHA_SHIFT) or WHITE_RGB }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }

        private const val ALPHA_SHIFT = 24
        private const val BYTE = 0xFF
        private const val WHITE_RGB = 0xFFFFFF
    }
}
