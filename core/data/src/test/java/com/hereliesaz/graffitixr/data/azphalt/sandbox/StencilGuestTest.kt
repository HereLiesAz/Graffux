package com.hereliesaz.graffitixr.data.azphalt.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the real stencil package's WASM (`extensions/stencil`, the copy in test resources) through
 * [WasmSandbox], with a fake host standing in for the editor. It is the check that the `bitmap`
 * capability's wire format, the `params` strings, and the guest all agree.
 */
class StencilGuestTest {

    private class BitmapHost(
        private val w: Int,
        private val h: Int,
        private val pixels: ByteArray?,
        private val params: Map<String, String> = emptyMap(),
    ) : AzphaltSandboxHost {
        var written: BitmapPixels? = null
        override fun requestRedraw() = Unit
        override fun canvasWidth() = w
        override fun canvasHeight() = h
        override fun canvasDpi() = 160
        override fun paramNumber(key: String): Double? = null
        override fun paramBool(key: String): Boolean? = null
        override fun paramString(key: String): String? = params[key]
        override fun colorActive() = 0
        override fun colorSetActive(rgba: Int) = Unit
        override fun assetRead(path: String): ByteArray? = null
        override fun selectionSize() = 0
        override fun selectionRead() = ByteArray(0)
        override fun layerCount() = 1
        override fun bitmapWidth() = if (pixels == null) 0 else w
        override fun bitmapHeight() = if (pixels == null) 0 else h
        override fun bitmapRead(maxDim: Int): BitmapPixels? {
            val px = pixels ?: return null
            val long = maxOf(w, h)
            val scale = if (maxDim in 1 until long) maxDim.toDouble() / long else 1.0
            // Nearest-neighbour is enough for a fake; the real host scales smoothly.
            val tw = maxOf(1, (w * scale).toInt())
            val th = maxOf(1, (h * scale).toInt())
            val out = ByteArray(tw * th * 4)
            for (y in 0 until th) for (x in 0 until tw) {
                val src = ((y * h / th) * w + (x * w / tw)) * 4
                System.arraycopy(px, src, out, (y * tw + x) * 4, 4)
            }
            return BitmapPixels(tw, th, out)
        }

        override fun bitmapWrite(pixels: BitmapPixels): Boolean {
            if (this.pixels == null) return false
            written = pixels
            return true
        }
    }

    private fun assertFails(block: () -> Unit) {
        val failed = runCatching(block).isFailure
        assertTrue("expected the call to fail", failed)
    }

    private fun wasm() = javaClass.getResourceAsStream("/azphalt/stencil/stencil.wasm")!!

    private fun run(host: BitmapHost, caps: Set<String> = setOf("bitmap", "params")) {
        wasm().use { WasmSandbox(it, host, caps).run() }
    }

    /** A dark field with a bright square in the middle, fully opaque. */
    private fun darkWithPatch(w: Int, h: Int): ByteArray {
        val px = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val bright = x in (w / 3)..(2 * w / 3) && y in (h / 3)..(2 * h / 3)
            val v = if (bright) 240 else 20
            val i = (y * w + x) * 4
            px[i] = v.toByte(); px[i + 1] = v.toByte(); px[i + 2] = v.toByte(); px[i + 3] = 0xFF.toByte()
        }
        return px
    }

    private fun rgbaAt(bytes: ByteArray, w: Int, x: Int, y: Int): List<Int> =
        (0 until 4).map { bytes[(y * w + x) * 4 + it].toInt() and 0xFF }

    @Test
    fun `the stencil guest stacks black and white over a dark image`() {
        val (w, h) = 64 to 64
        val host = BitmapHost(w, h, darkWithPatch(w, h), mapOf("view" to "all", "base" to "auto"))
        run(host)
        assertNotNull(host.written)
        val out = host.written!!.rgba
        assertEquals(w * h * 4, out.size)
        assertEquals(listOf(0, 0, 0, 255), rgbaAt(out, w, 2, 2))
        assertEquals(listOf(255, 255, 255, 255), rgbaAt(out, w, w / 2, h / 2))
    }

    @Test
    fun `the view and base parameters reach the guest`() {
        val (w, h) = 48 to 48
        val img = darkWithPatch(w, h)
        val base = BitmapHost(w, h, img, mapOf("view" to "base", "base" to "light"))
        run(base)
        assertEquals(listOf(255, 255, 255, 255), rgbaAt(base.written!!.rgba, w, 2, 2))
        val secondary = BitmapHost(w, h, img, mapOf("view" to "secondary", "base" to "dark"))
        run(secondary)
        val sheet = secondary.written!!.rgba
        assertEquals("the base sheet is hidden in the secondary view", listOf(0, 0, 0, 0), rgbaAt(sheet, w, 2, 2))
        assertEquals(listOf(255, 255, 255, 255), rgbaAt(sheet, w, w / 2, h / 2))
    }

    @Test
    fun `without params the guest uses its defaults`() {
        val (w, h) = 48 to 48
        val host = BitmapHost(w, h, darkWithPatch(w, h))
        run(host)
        assertNotNull(host.written)
    }

    @Test
    fun `a layer with no bitmap traps and writes nothing`() {
        val host = BitmapHost(10, 10, null)
        // The guest panicked, which a WASM host sees as a trap.
        assertFails { run(host) }
        assertNull(host.written)
    }

    @Test
    fun `a guest not granted the bitmap capability cannot even link`() {
        val host = BitmapHost(8, 8, darkWithPatch(8, 8))
        // Unlinkable: the bitmap imports are never bound without the capability.
        assertFails { run(host, caps = setOf("params")) }
        assertNull(host.written)
    }

    @Test
    fun `a 1024 square runs well inside the sandbox time limit`() {
        val (w, h) = 1024 to 1024
        val host = BitmapHost(w, h, darkWithPatch(w, h), mapOf("view" to "all"))
        val started = System.nanoTime()
        run(host)
        val ms = (System.nanoTime() - started) / 1_000_000
        println("stencil 1024x1024 took $ms ms in the interpreter")
        // Headroom for a slower phone and a loaded build machine.
        val budget = SANDBOX_EXECUTION_TIMEOUT_MS / 2
        assertTrue("took $ms ms against a $SANDBOX_EXECUTION_TIMEOUT_MS ms limit", ms < budget)
        val out = host.written!!
        assertEquals("the guest works at its reduced size", 256, out.width)
        assertEquals(256, out.height)
    }
}
