package com.hereliesaz.graffitixr.feature.editor.export

import android.graphics.Color
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.feature.editor.RenderTestBase
import com.hereliesaz.graffitixr.feature.editor.exportBackgroundArgb
import com.hereliesaz.graffitixr.feature.editor.exportSavedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * The PNG export (EditorViewModel.exportImage) composites over [exportBackgroundArgb] of the canvas
 * background. Checked against the real compositor: a coloured page shows through empty pixels, a
 * transparent page leaves them transparent.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ExportBackgroundTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    private val manager = ExportManager()

    // A layer that covers only the left half, so the right half is "page".
    private fun halfLayer(): Layer {
        val bmp = RenderTestBase.filled(20, 20, Color.TRANSPARENT)
        for (x in 0 until 10) for (y in 0 until 20) bmp.setPixel(x, y, Color.RED)
        return Layer(id = "a", name = "a", bitmap = bmp)
    }

    private fun export(background: ComposeColor) = manager.compositeToDocument(
        listOf(halfLayer()), 20, 20, 20, 20,
        backgroundBitmap = null,
        backgroundColor = exportBackgroundArgb(background),
    )

    @Test
    fun `a coloured canvas background is in the exported pixels`() {
        val out = export(ComposeColor(0xFFFDF6E3))
        assertEquals(Color.RED, out.getPixel(2, 10))
        assertEquals(0xFFFDF6E3.toInt(), out.getPixel(17, 10))
    }

    @Test
    fun `a transparent canvas background exports with alpha`() {
        val out = export(ComposeColor.Transparent)
        assertEquals(Color.RED, out.getPixel(2, 10))
        assertEquals(0, Color.alpha(out.getPixel(17, 10)))
    }

    @Test
    fun `the notice says which background went with the image`() {
        assertTrue(exportSavedMessage(exportBackgroundArgb(ComposeColor.Transparent)).contains("transparent"))
        assertTrue(exportSavedMessage(exportBackgroundArgb(ComposeColor.White)).contains("canvas background"))
    }
}
