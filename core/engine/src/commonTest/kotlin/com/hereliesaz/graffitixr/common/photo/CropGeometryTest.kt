package com.hereliesaz.graffitixr.common.photo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CropGeometryTest {

    private fun near(e: Float, a: Float, tol: Float = 1e-3f) = assertTrue(abs(e - a) <= tol, "expected $e, was $a")

    private val cx = 50f
    private val cy = 100f

    @Test
    fun `the neutral crop draws the document as it is`() {
        val m = CropGeometry.matrix(DocumentCrop(), cx, cy, 100f, 200f)
        val (x, y) = CropGeometry.apply(m, 10f, 30f)
        near(10f, x); near(30f, y)
    }

    @Test
    fun `a quarter turn, a flip and straighten turn about the centre`() {
        // A point 10 right of centre goes 10 below it on a clockwise quarter turn (y down).
        val q = CropGeometry.matrix(DocumentCrop(quarterTurns = 1), cx, cy, 100f, 200f)
        val (qx, qy) = CropGeometry.apply(q, cx + 10f, cy)
        near(cx, qx); near(cy + 10f, qy)
        val f = CropGeometry.matrix(DocumentCrop(flipH = true), cx, cy, 100f, 200f)
        near(cx - 10f, CropGeometry.apply(f, cx + 10f, cy).first)
        val s = CropGeometry.matrix(DocumentCrop(straighten = -3f), cx, cy, 100f, 200f)
        val (sx, sy) = CropGeometry.apply(s, cx + 10f, cy)
        val a = -3.0 * PI / 180.0
        near(cx + 10f * cos(a).toFloat(), sx); near(cy + 10f * sin(a).toFloat(), sy)
    }

    @Test
    fun `vertical perspective widens the top and keeps the centre`() {
        val m = CropGeometry.matrix(DocumentCrop(vertical = 100f), cx, cy, 100f, 200f)
        val (ccx, ccy) = CropGeometry.apply(m, cx, cy)
        near(cx, ccx); near(cy, ccy)
        // Top edge: w = 1 + 0.4/100 * (-100) = 0.6, so x offsets grow by 1/0.6.
        val (tx, _) = CropGeometry.apply(m, cx + 10f, 0f)
        near(cx + 10f / 0.6f, tx)
        val (bx, _) = CropGeometry.apply(m, cx + 10f, 200f)
        near(cx + 10f / 1.4f, bx)
    }

    @Test
    fun `aspect presets reshape the crop about its centre`() {
        val sq = CropGeometry.withAspect(DocumentCrop(), "1:1", 1000, 2000)
        // 1000 x 2000: the largest square is 1000 wide, half the height, centred.
        near(0f, sq.left); near(1f, sq.right); near(0.25f, sq.top); near(0.75f, sq.bottom)
        assertEquals("1:1", sq.aspect)
        val fourFive = CropGeometry.withAspect(DocumentCrop(), "4:5", 1000, 1000)
        near(0.1f, fourFive.left); near(0.9f, fourFive.right); near(0f, fourFive.top)
        assertEquals(DocumentCrop(aspect = "free"), CropGeometry.withAspect(DocumentCrop(), "free", 10, 10))
    }

    @Test
    fun `a quarter turn turns the crop with the content`() {
        val turned = CropGeometry.rotateQuarter(DocumentCrop(), 1000, 2000)
        assertEquals(1, turned.quarterTurns)
        // The 1000 x 2000 page lying down is 2000 x 1000 about the same centre.
        near(-0.5f, turned.left); near(1.5f, turned.right); near(0.25f, turned.top); near(0.75f, turned.bottom)
        assertEquals(0, (1..4).fold(DocumentCrop()) { c, _ -> CropGeometry.rotateQuarter(c, 1000, 2000) }.quarterTurns)
    }

    @Test
    fun `auto level finds a tilted horizon and undoes it`() {
        for (tilt in listOf(-6f, 4f)) {
            val w = 120
            val h = 90
            val r = tilt * PI.toFloat() / 180f
            // Soft bands along lines at `tilt` degrees (hard steps would alias into 0 and 90).
            val luma = FloatArray(w * h) { i ->
                val x = (i % w) - w / 2f
                val y = (i / w) - h / 2f
                val d = -x * sin(r) + y * cos(r)
                0.5f + 0.4f * sin(d / 4f)
            }
            near(-tilt, CropGeometry.autoLevel(luma, w, h), 0.6f)
        }
        // A flat grey image has nothing to level by.
        assertEquals(0f, CropGeometry.autoLevel(FloatArray(40 * 40) { 0.5f }, 40, 40))
    }
}
