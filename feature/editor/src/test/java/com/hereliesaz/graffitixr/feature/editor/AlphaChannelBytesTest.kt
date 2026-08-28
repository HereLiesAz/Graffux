package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [alphaChannelBytes] extracts a [Bitmap]'s alpha channel into the flat R8 byte layout
 * `VulkanStampEngine.stampMaskedDabs()` expects for a tip-mask texture upload (item 15).
 *
 * Robolectric (not a plain JVM test) because reading real pixels back via [Bitmap.getPixels]
 * needs the native graphics implementation the mockable android.jar stubs out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AlphaChannelBytesTest {

    @Test
    fun `extracts alpha as unsigned bytes in row-major order`() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.argb(0, 255, 255, 255))
        bitmap.setPixel(1, 0, Color.argb(128, 0, 0, 0))
        bitmap.setPixel(0, 1, Color.argb(255, 10, 20, 30))
        bitmap.setPixel(1, 1, Color.argb(64, 0, 0, 0))

        val bytes = alphaChannelBytes(bitmap)

        assertEquals(4, bytes.size)
        assertEquals(0, bytes[0].toInt() and 0xFF)
        assertEquals(128, bytes[1].toInt() and 0xFF)
        assertEquals(255, bytes[2].toInt() and 0xFF)
        assertEquals(64, bytes[3].toInt() and 0xFF)
    }

    @Test
    fun `a fully opaque bitmap is all 255`() {
        val bitmap = RenderTestBase.filled(4, 3, Color.WHITE)

        val bytes = alphaChannelBytes(bitmap)

        assertEquals(12, bytes.size)
        assertEquals(true, bytes.all { it.toInt() and 0xFF == 255 })
    }
}
