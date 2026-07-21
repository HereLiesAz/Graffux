package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.VectorShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Geometry checks for [CanvasHitTest.topHit]. Canvas is 1000×1000 → centre (500, 500). Vector
 * layers are used so the local content bounds are exact (no bitmap / ContentScale.Fit needed).
 */
class CanvasHitTestTest {

    private val W = 1000f
    private val H = 1000f

    private fun vlayer(
        id: String,
        w: Float = 400f,
        h: Float = 400f,
        offset: Offset = Offset.Zero,
        scale: Float = 1f,
        rotationZ: Float = 0f,
        visible: Boolean = true,
    ) = Layer(
        id = id,
        name = id,
        shapes = listOf(VectorShape(kind = ShapeKind.RECTANGLE, width = w, height = h)),
        offset = offset,
        scale = scale,
        rotationZ = rotationZ,
        isVisible = visible,
    )

    @Test
    fun `tap at centre hits a centred shape`() {
        assertEquals("a", CanvasHitTest.topHit(listOf(vlayer("a")), Offset(500f, 500f), W, H))
    }

    @Test
    fun `tap inside the 400x400 box hits`() {
        // half-extent 200 → screen box [300, 700] on both axes
        assertEquals("a", CanvasHitTest.topHit(listOf(vlayer("a")), Offset(690f, 310f), W, H))
    }

    @Test
    fun `tap outside the box misses`() {
        // x = 750 → local 250 > 200
        assertNull(CanvasHitTest.topHit(listOf(vlayer("a")), Offset(750f, 500f), W, H))
    }

    @Test
    fun `offset shifts the hit box`() {
        val l = vlayer("a", offset = Offset(100f, 0f)) // box centre → (600, 500)
        assertEquals("a", CanvasHitTest.topHit(listOf(l), Offset(780f, 500f), W, H)) // local x 180
        assertNull(CanvasHitTest.topHit(listOf(l), Offset(350f, 500f), W, H))         // local x -250
    }

    @Test
    fun `scale grows the hit box`() {
        val l = vlayer("a", scale = 2f) // effective screen half-extent 400
        assertEquals("a", CanvasHitTest.topHit(listOf(l), Offset(880f, 500f), W, H)) // local x 190
    }

    @Test
    fun `90 degree rotation swaps the axes`() {
        // 400×200 box: halfW 200, halfH 100. Rotated 90°, screen-y maps to local-x and vice versa.
        val l = vlayer("a", w = 400f, h = 200f, rotationZ = 90f)
        assertEquals("a", CanvasHitTest.topHit(listOf(l), Offset(500f, 680f), W, H)) // dy180 → localX
        assertNull(CanvasHitTest.topHit(listOf(l), Offset(650f, 500f), W, H))         // dx150 → localY
    }

    @Test
    fun `topmost layer wins`() {
        val out = CanvasHitTest.topHit(listOf(vlayer("bottom"), vlayer("top")), Offset(500f, 500f), W, H)
        assertEquals("top", out)
    }

    @Test
    fun `invisible layers are skipped`() {
        assertEquals(
            "shown",
            CanvasHitTest.topHit(listOf(vlayer("shown"), vlayer("hidden", visible = false)), Offset(500f, 500f), W, H),
        )
        assertNull(CanvasHitTest.topHit(listOf(vlayer("hidden", visible = false)), Offset(500f, 500f), W, H))
    }

    @Test
    fun `empty layer list misses`() {
        assertNull(CanvasHitTest.topHit(emptyList(), Offset(500f, 500f), W, H))
    }

    @Test
    fun `zero-size canvas misses`() {
        assertNull(CanvasHitTest.topHit(listOf(vlayer("a")), Offset(0f, 0f), 0f, 0f))
    }

    @Test
    fun `screen corners of a centred unrotated box`() {
        val corners = CanvasHitTest.layerScreenCorners(vlayer("a"), W, H)!!
        assertEquals(4, corners.size)
        assertEquals(300f, corners[0].x, 0.01f) // TL
        assertEquals(300f, corners[0].y, 0.01f)
        assertEquals(700f, corners[2].x, 0.01f) // BR
        assertEquals(700f, corners[2].y, 0.01f)
    }

    @Test
    fun `screen corners rotate about the centre`() {
        // TL local (-200,-200) maps to (700, 300) at +90°.
        val corners = CanvasHitTest.layerScreenCorners(vlayer("a", rotationZ = 90f), W, H)!!
        assertEquals(700f, corners[0].x, 0.01f)
        assertEquals(300f, corners[0].y, 0.01f)
    }

    @Test
    fun `nearest corner within radius picks the closest handle`() {
        // corners: TL(300,300) TR(700,300) BR(700,700) BL(300,700)
        val corners = CanvasHitTest.layerScreenCorners(vlayer("a"), W, H)!!
        assertEquals(1, CanvasHitTest.nearestCornerIndex(Offset(705f, 305f), corners, 30f)) // TR
        assertEquals(3, CanvasHitTest.nearestCornerIndex(Offset(295f, 690f), corners, 30f)) // BL
    }

    @Test
    fun `nearest corner returns null when nothing is within radius`() {
        val corners = CanvasHitTest.layerScreenCorners(vlayer("a"), W, H)!!
        assertNull(CanvasHitTest.nearestCornerIndex(Offset(500f, 500f), corners, 30f)) // centre, far
    }

    @Test
    fun `box centre is the average of the corners`() {
        val bc = CanvasHitTest.boxCenter(CanvasHitTest.layerScreenCorners(vlayer("a"), W, H)!!)
        assertEquals(500f, bc.x, 0.01f)
        assertEquals(500f, bc.y, 0.01f)
    }

    @Test
    fun `rotation handle sits out beyond the top edge`() {
        val rot = CanvasHitTest.rotationHandlePos(CanvasHitTest.layerScreenCorners(vlayer("a"), W, H)!!, 50f)!!
        assertEquals(500f, rot.x, 0.01f) // above the top-edge midpoint (500, 300)
        assertEquals(250f, rot.y, 0.01f) // 50 px further up
    }

    @Test
    fun `angle delta measures signed rotation about the centre`() {
        val c = Offset(500f, 500f)
        assertEquals(-90f, CanvasHitTest.angleDeltaDegrees(c, Offset(600f, 500f), Offset(500f, 400f)), 0.01f)
        assertEquals(90f, CanvasHitTest.angleDeltaDegrees(c, Offset(600f, 500f), Offset(500f, 600f)), 0.01f)
    }

    // ── Infinite-canvas camera composition ──────────────────────────────────────

    @Test
    fun `viewport zoom scales screen corners about the top-left`() {
        // zoom 2, no pan: world corner (300,300) → screen (600,600); (700,700) → (1400,1400)
        val corners = CanvasHitTest.layerScreenCorners(vlayer("a"), W, H, Offset.Zero, 2f)!!
        assertEquals(600f, corners[0].x, 0.01f)
        assertEquals(600f, corners[0].y, 0.01f)
        assertEquals(1400f, corners[2].x, 0.01f)
        assertEquals(1400f, corners[2].y, 0.01f)
    }

    @Test
    fun `viewport offset translates screen corners`() {
        // pan (100,50), zoom 1: world corner (300,300) → screen (400,350)
        val corners = CanvasHitTest.layerScreenCorners(vlayer("a"), W, H, Offset(100f, 50f), 1f)!!
        assertEquals(400f, corners[0].x, 0.01f)
        assertEquals(350f, corners[0].y, 0.01f)
    }

    @Test
    fun `topHit undoes the camera before testing`() {
        val l = listOf(vlayer("a"))
        // zoom 2: the layer's world centre (500,500) sits at screen (1000,1000).
        assertEquals("a", CanvasHitTest.topHit(l, Offset(1000f, 1000f), W, H, Offset.Zero, 2f))
        // screen (600,600) → world (300,300) = TL corner → hit
        assertEquals("a", CanvasHitTest.topHit(l, Offset(600f, 600f), W, H, Offset.Zero, 2f))
        // screen (500,500) → world (250,250) → local x -250 outside ±200 → miss
        assertNull(CanvasHitTest.topHit(l, Offset(500f, 500f), W, H, Offset.Zero, 2f))
        // pan (200,200), zoom 1: world centre (500,500) → screen (700,700)
        assertEquals("a", CanvasHitTest.topHit(l, Offset(700f, 700f), W, H, Offset(200f, 200f), 1f))
    }
}
