package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.Dab
import com.hereliesaz.graffitixr.common.azphalt.PaintMedium
import com.hereliesaz.graffitixr.common.azphalt.SubstrateField
import com.hereliesaz.graffitixr.common.azphalt.SubstrateProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StampBrushSubstrateRendererTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    private val brush = AzphaltBrush(name = "substrate-test", hardness = 1f)
    private val roughProfile = SubstrateProfile(heightScale = 0.8f)
    private val substrateMedium = PaintMedium(substrateResponse = 1f)

    private fun render(
        dab: Dab,
        context: SubstrateRenderContext?,
        stamp: Bitmap? = null,
    ): Bitmap {
        val bitmap = RenderTestBase.filled(40, 40, Color.TRANSPARENT)
        StampBrushRenderer.paintDabs(
            canvas = Canvas(bitmap),
            dabs = listOf(dab),
            brush = brush,
            colorArgb = Color.BLACK,
            flow = 1f,
            stamp = stamp,
            substrate = context,
        )
        return bitmap
    }

    @Test
    fun `rough tooth blocks shallow contact and accepts deep contact`() {
        val field = SubstrateField(1, 1, byteArrayOf(0xFF.toByte()))
        val context = SubstrateRenderContext(roughProfile, field, substrateMedium)

        val shallow = render(
            Dab(20f, 20f, 8f, 1f, 0f, hardness = 1f, contactDepth = 0.4f),
            context,
        )
        val deep = render(
            Dab(20f, 20f, 8f, 1f, 0f, hardness = 1f, contactDepth = 0.9f),
            context,
        )

        assertEquals(0, Color.alpha(shallow.getPixel(20, 20)))
        assertTrue(Color.alpha(deep.getPixel(20, 20)) > 240)
    }

    @Test
    fun `canvas locked tooth produces deterministic dry breakup`() {
        val field = SubstrateField(
            width = 2,
            height = 1,
            heightR8 = byteArrayOf(0x00, 0xFF.toByte()),
        )
        val context = SubstrateRenderContext(roughProfile, field, substrateMedium)
        val bitmap = render(
            Dab(20f, 20f, 10f, 1f, 0f, hardness = 1f, contactDepth = 0.4f),
            context,
        )

        assertTrue("low tooth column should receive paint", Color.alpha(bitmap.getPixel(18, 20)) > 240)
        assertEquals("high tooth column should break up", 0, Color.alpha(bitmap.getPixel(19, 20)))
        assertTrue("tile must repeat in canvas coordinates", Color.alpha(bitmap.getPixel(20, 20)) > 240)
        assertEquals(0, Color.alpha(bitmap.getPixel(21, 20)))
    }

    @Test
    fun `existing paint height fills tooth valley for later deposition`() {
        val field = SubstrateField(1, 1, byteArrayOf(0xFF.toByte()))
        val heightMap = FloatArray(40 * 40)
        heightMap[20 * 40 + 20] = 0.5f
        val context = SubstrateRenderContext(
            roughProfile,
            field,
            substrateMedium,
            paintHeight = heightMap,
        )
        val bitmap = render(
            Dab(20f, 20f, 8f, 1f, 0f, hardness = 1f, contactDepth = 0.4f),
            context,
        )

        assertTrue(Color.alpha(bitmap.getPixel(20, 20)) > 240)
        assertEquals(0, Color.alpha(bitmap.getPixel(21, 20)))
    }

    @Test
    fun `reservoir load scales visible deposition without changing color`() {
        val field = SubstrateField(1, 1, byteArrayOf(0x00))
        val context = SubstrateRenderContext(
            SubstrateProfile.SMOOTH,
            field,
            substrateMedium,
            reservoirLoad = 0.25f,
        )
        val bitmap = render(
            Dab(20f, 20f, 8f, 1f, 0f, hardness = 1f, contactDepth = 1f),
            context,
        )
        val center = bitmap.getPixel(20, 20)

        assertTrue(Color.alpha(center) in 60..68)
        assertEquals(0, Color.red(center))
        assertEquals(0, Color.green(center))
        assertEquals(0, Color.blue(center))
    }

    @Test
    fun `zero substrate response preserves legacy deposition through rough tooth`() {
        val field = SubstrateField(1, 1, byteArrayOf(0xFF.toByte()))
        val context = SubstrateRenderContext(
            roughProfile,
            field,
            PaintMedium(substrateResponse = 0f),
        )
        val bitmap = render(
            Dab(20f, 20f, 8f, 1f, 0f, hardness = 1f, contactDepth = 0f),
            context,
        )

        assertTrue(Color.alpha(bitmap.getPixel(20, 20)) > 240)
    }

    @Test
    fun `custom stamp receives the same substrate gate`() {
        val stamp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val field = SubstrateField(1, 1, byteArrayOf(0xFF.toByte()))
        val context = SubstrateRenderContext(roughProfile, field, substrateMedium)
        val bitmap = render(
            Dab(20f, 20f, 8f, 1f, 0f, hardness = 1f, contactDepth = 0.4f),
            context,
            stamp = stamp,
        )

        assertEquals(0, Color.alpha(bitmap.getPixel(20, 20)))
    }
}
