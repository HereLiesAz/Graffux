package com.hereliesaz.graffitixr.data.azphalt.sandbox

/**
 * Defines the capability bridge between the isolated sandbox and Graffux.
 * 
 * Each function represents an access-controlled capability. If an extension
 * lacks a capability in its manifest, the corresponding functions are not mapped
 * into the WASM environment at instantiation, causing it to fail immediately if it
 * attempts to import them (deny by default).
 */
interface AzphaltSandboxHost {
    
    // Canvas capability
    fun requestRedraw()
    fun canvasWidth(): Int
    fun canvasHeight(): Int
    fun canvasDpi(): Int
    
    // Params capability
    fun paramNumber(key: String): Double?
    fun paramBool(key: String): Boolean?
    fun paramString(key: String): String?
    
    // Color capability (RGBA 8-bit encoded as Int)
    fun colorActive(): Int
    fun colorSetActive(rgba: Int)
    
    // Assets capability
    fun assetRead(path: String): ByteArray?
    
    // Selection capability
    fun selectionSize(): Int
    fun selectionRead(): ByteArray
    
    // Layers capability
    fun layerCount(): Int

    // Bitmap capability: the pixels of the layer the extension was run on. Defaults let a host that
    // has no pixels to offer (a test double, a headless host) simply not implement them; the
    // guest then sees an empty bitmap and every write is refused.

    /** Width in pixels of the layer bitmap, or 0 when there is none. */
    fun bitmapWidth(): Int = 0

    /** Height in pixels of the layer bitmap, or 0 when there is none. */
    fun bitmapHeight(): Int = 0

    /**
     * The layer bitmap as straight RGBA, scaled down (aspect kept) so its long side is at most
     * [maxDim] when [maxDim] is positive and the bitmap is larger; at its own size when [maxDim] is
     * 0 or it already fits. Null when there is no bitmap, or it is too large to hand over at the
     * requested size. Scaling is done here, natively, because the guest runs in an interpreter.
     */
    fun bitmapRead(maxDim: Int): BitmapPixels? = null

    /**
     * Stages [pixels] as the layer's new contents. When their size differs from the layer's, the
     * host scales them (smoothly) to the layer's size. The host applies the staged result only
     * after the extension returns successfully, as one undoable step. False when there is no
     * bitmap or [pixels] is malformed.
     */
    fun bitmapWrite(pixels: BitmapPixels): Boolean = false
}

/** Bytes per pixel of [BitmapPixels]: one each for R, G, B, A. */
const val BITMAP_BYTES_PER_PIXEL = 4

/** Straight (non-premultiplied) RGBA8, `width * height * 4` bytes, row-major from the top-left. */
class BitmapPixels(val width: Int, val height: Int, val rgba: ByteArray) {
    init {
        require(width > 0 && height > 0 && rgba.size == width * height * BITMAP_BYTES_PER_PIXEL) {
            val need = width.toLong() * height * BITMAP_BYTES_PER_PIXEL
            "a ${width}x$height bitmap needs $need bytes, got ${rgba.size}"
        }
    }
}
