package com.hereliesaz.graffitixr.feature.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.hereliesaz.graffitixr.common.photo.DocumentCrop
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The exported crop: the document drawn through the crop's transform, cut to its rectangle. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class DocumentCropRenderTest {

    /** A 100 x 200 world whose artboard is all of it: red left half, blue right half. */
    private fun world(): Bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until 200) for (x in 0 until 100) setPixel(x, y, if (x < 50) Color.RED else Color.BLUE)
    }

    @Test
    fun `a crop keeps its rectangle at the document's resolution`() {
        val crop = DocumentCrop(left = 0.5f, right = 1f, top = 0f, bottom = 0.5f)
        val out = DocumentCropRender.render(world(), crop, 1000, 2000)
        assertEquals(500, out.width)
        assertEquals(1000, out.height)
        assertEquals(Color.BLUE, out.getPixel(250, 500))
    }

    @Test
    fun `a horizontal flip swaps the halves`() {
        val out = DocumentCropRender.render(world(), DocumentCrop(flipH = true), 100, 200)
        assertEquals(Color.BLUE, out.getPixel(10, 100))
        assertEquals(Color.RED, out.getPixel(90, 100))
    }

    @Test
    fun `a quarter turn of a lying crop shows the page on its side`() {
        val turned = com.hereliesaz.graffitixr.common.photo.CropGeometry.rotateQuarter(DocumentCrop(), 100, 200)
        val out = DocumentCropRender.render(world(), turned, 100, 200)
        assertEquals(200, out.width)
        assertEquals(100, out.height)
        // Clockwise: the red left half ends up on top.
        assertEquals(Color.RED, out.getPixel(100, 20))
        assertEquals(Color.BLUE, out.getPixel(100, 80))
    }
}
