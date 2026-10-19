package com.hereliesaz.graffitixr.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasSizeTest {

    private val square = CanvasSize(960, 960, dpi = 96, unit = LengthUnit.INCHES)

    @Test
    fun `pixels read as inches and centimetres at the dpi`() {
        assertEquals("10", square.widthText())
        assertEquals("25.4", square.withUnit(LengthUnit.CENTIMETERS).widthText())
        assertEquals("3.2", square.withDpi(300).widthText())
    }

    @Test
    fun `dpi and unit never change the pixels`() {
        val changed = square.withDpi(600).withUnit(LengthUnit.CENTIMETERS)
        assertEquals(960, changed.widthPx)
        assertEquals(960, changed.heightPx)
    }

    @Test
    fun `width and height edits recompute the ratio`() {
        val wide = square.withWidthLength(20.0)
        assertEquals(1920, wide.widthPx)
        assertEquals("2:1", wide.ratioText())
        assertEquals("1:2", wide.withWidthLength(5.0).ratioText())
    }

    @Test
    fun `a ratio keeps the width and sets the height`() {
        val sized = CanvasSize(1920, 1920).withRatio(16.0 / 9.0)
        assertEquals(1920, sized.widthPx)
        assertEquals(1080, sized.heightPx)
        assertEquals("16:9", sized.ratioText())
    }

    @Test
    fun `sizes are clamped to what a canvas can be`() {
        assertEquals(CanvasSize.MAX_PX, square.withWidthLength(10_000.0).widthPx)
        assertEquals(CanvasSize.MIN_PX, square.withWidthLength(0.0001).widthPx)
    }

    @Test
    fun `ratios with large terms fall back to a decimal`() {
        assertEquals("1.777:1", CanvasSize.formatRatio(1777, 1000))
        assertEquals("4:3", CanvasSize.formatRatio(1024, 768))
    }

    @Test
    fun `ratio and length text parse leniently and reject nonsense`() {
        assertEquals(16.0 / 9.0, CanvasSize.parseRatio("16:9")!!, 1e-9)
        assertEquals(1.5, CanvasSize.parseRatio("3/2")!!, 1e-9)
        assertEquals(1.78, CanvasSize.parseRatio("1,78")!!, 1e-9)
        assertNull(CanvasSize.parseRatio("16:0"))
        assertNull(CanvasSize.parseRatio("abc"))
        assertNull(CanvasSize.parseRatio("-2"))
        assertEquals(8.5, CanvasSize.parseLength("8,5")!!, 1e-9)
        assertNull(CanvasSize.parseLength(""))
        assertNull(CanvasSize.parseLength("0"))
    }

    @Test
    fun `every template fits the canvas limits`() {
        DOCUMENT_CATEGORIES.flatMap { it.templates }.forEach {
            assert(it.widthPx in CanvasSize.MIN_PX..CanvasSize.MAX_PX) { it.name }
            assert(it.heightPx in CanvasSize.MIN_PX..CanvasSize.MAX_PX) { it.name }
            assert(it.dpi in DPI_OPTIONS) { it.name }
        }
    }
}
