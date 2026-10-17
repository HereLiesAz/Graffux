package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.LayerFrame
import com.hereliesaz.graffitixr.common.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure decisions of the zoom-crisp overlay: trigger, scale, region/size caps, invalidation. */
class HighResOverlayPlanTest {

    private val key = OverlayContentKey(1, 1, 1000, 2000, 1000, 2000, 0, 0)

    private fun layer(
        id: String = "a",
        k: OverlayContentKey = key,
        scale: Float = 1f,
        offset: Offset = Offset.Zero,
    ) = OverlayLayerInput(id, k, LayerFrame(1000, 2000), scale, offset, 0f, isActive = true)

    private fun inputs(
        zoom: Float = 2f,
        vOffset: Offset = Offset.Zero,
        layers: List<OverlayLayerInput> = listOf(layer()),
        live: String? = null,
        unsupported: Boolean = false,
    ) = OverlayInputs(zoom, vOffset, 0f, 1000, 2000, layers, live, unsupported)

    @Test
    fun triggersOnlyAboveThreshold() {
        assertFalse(HighResOverlayPlan.shouldBuild(inputs(zoom = 1f)))
        assertFalse(HighResOverlayPlan.shouldBuild(inputs(zoom = 1.5f)))
        assertTrue(HighResOverlayPlan.shouldBuild(inputs(zoom = 1.51f)))
    }

    @Test
    fun neverBuildsWhenDisabledLiveOrUnsupported() {
        assertFalse(HighResOverlayPlan.shouldBuild(inputs(), enabled = false))
        assertFalse(HighResOverlayPlan.shouldBuild(inputs(live = "a")))
        assertFalse(HighResOverlayPlan.shouldBuild(inputs(unsupported = true)))
    }

    @Test
    fun scaleFollowsMagnificationWithinLimits() {
        assertEquals(2, HighResOverlayPlan.chooseScale(1.6f))
        assertEquals(2, HighResOverlayPlan.chooseScale(2f))
        assertEquals(3, HighResOverlayPlan.chooseScale(2.2f))
        assertEquals(4, HighResOverlayPlan.chooseScale(12f))
    }

    @Test
    fun magnificationIsZoomForAScreenSizedLayer() {
        val m = HighResOverlayPlan.magnification(inputs(zoom = 3f), layer())
        assertEquals(3f, m, 1e-3f)
    }

    @Test
    fun visibleBoundsShrinkWithZoom() {
        // Zoom 2 about the top-left origin: the screen shows the top-left quarter of the layer.
        val b = HighResOverlayPlan.visibleBounds(inputs(zoom = 2f), layer())
        assertEquals(0f, b.left, 1e-2f)
        assertEquals(0f, b.top, 1e-2f)
        assertEquals(500f, b.right, 1e-2f)
        assertEquals(1000f, b.bottom, 1e-2f)
    }

    @Test
    fun regionAddsMarginAndClampsToTheBitmap() {
        val plan = HighResOverlayPlan.planRegion(
            FloatBounds(0f, 0f, 500f, 1000f), 1000, 2000, 2, 4096L * 4096L,
        )!!
        assertEquals(PixelRect(0, 0, 625, 1250), plan.region)
        assertEquals(2, plan.scale)
    }

    @Test
    fun regionOutsideTheBitmapPlansNothing() {
        assertNull(HighResOverlayPlan.planRegion(FloatBounds(-500f, -500f, -10f, -10f), 1000, 2000, 2, 1L shl 24))
    }

    @Test
    fun budgetNeverExceededAndSideCapped() {
        val total = HighResOverlayConfig.MAX_TOTAL_PIXELS
        for (budget in listOf(total, total / 2, total / 3, 1_000_000L)) {
            for (scale in 2..4) {
                val plan = HighResOverlayPlan.planRegion(
                    FloatBounds(0f, 0f, 6000f, 6000f), 6000, 6000, scale, budget,
                )!!
                val px = plan.region.width.toLong() * plan.scale * plan.region.height * plan.scale
                assertTrue("budget $budget scale $scale -> $px", px <= budget)
                assertTrue(plan.region.width * plan.scale <= HighResOverlayConfig.MAX_SIDE)
                assertTrue(plan.region.height * plan.scale <= HighResOverlayConfig.MAX_SIDE)
            }
        }
    }

    @Test
    fun scaleDropsBeforeTheRegionShrinks() {
        // 1000x1000 fits at scale 2 (4M) but not at 4 (16M) in a 4M budget: scale falls to 2.
        val plan = HighResOverlayPlan.planRegion(FloatBounds(0f, 0f, 800f, 800f), 1000, 1000, 4, 4_200_000L)!!
        assertEquals(2, plan.scale)
        assertTrue(plan.region.width >= 1000 || plan.region.width >= 800)
    }

    @Test
    fun shrunkRegionStaysAroundTheVisibleCentre() {
        val plan = HighResOverlayPlan.planRegion(
            FloatBounds(2900f, 2900f, 3100f, 3100f), 6000, 6000, 2, 1_000_000L,
        )!!
        assertTrue(plan.region.left <= 3000 && plan.region.right >= 3000)
        assertTrue(plan.region.top <= 3000 && plan.region.bottom >= 3000)
    }

    @Test
    fun budgetSplitsAcrossLayers() {
        assertEquals(HighResOverlayConfig.MAX_TOTAL_PIXELS / 2, HighResOverlayPlan.budgetPerLayer(2))
        assertEquals(0L, HighResOverlayPlan.budgetPerLayer(0))
    }

    @Test
    fun anyContentChangeMakesAnOverlayStale() {
        assertFalse(HighResOverlayPlan.isStale(key, key))
        assertTrue(HighResOverlayPlan.isStale(key, key.copy(generation = 2)))
        assertTrue(HighResOverlayPlan.isStale(key, key.copy(bitmapId = 9)))
        assertTrue(HighResOverlayPlan.isStale(key, key.copy(originX = 5)))
        assertTrue(HighResOverlayPlan.isStale(key, null))
    }

    @Test
    fun refreshWhenMissingCoarseOrUncoveredOnly() {
        val visible = FloatBounds(0f, 0f, 500f, 500f)
        val plan = RegionPlan(PixelRect(0, 0, 625, 625), 2)
        assertTrue(HighResOverlayPlan.needsRefresh(null, plan, visible))
        assertTrue(HighResOverlayPlan.needsRefresh(RegionPlan(plan.region, 2), plan.copy(scale = 3), visible))
        assertFalse(HighResOverlayPlan.needsRefresh(plan, plan, visible))
        // Panned off the old region: a different plan is wanted.
        val panned = FloatBounds(900f, 900f, 1400f, 1400f)
        assertTrue(HighResOverlayPlan.needsRefresh(plan, RegionPlan(PixelRect(800, 800, 1500, 1500), 2), panned))
        // Same (capped) region that can never cover it: not re-rendered forever.
        assertFalse(HighResOverlayPlan.needsRefresh(plan, plan, panned))
    }

    private fun stroke(tool: Tool = Tool.BRUSH, wrap: Boolean = false, fill: Boolean = false, substrate: String? = null) =
        StrokeCommand(
            path = listOf(Offset(1f, 1f)), canvasSize = IntSize(10, 10), tool = tool, brushSize = 10f,
            brushColor = 0, intensity = 1f, wrapAroundMode = wrap, fillSelection = fill, substrateId = substrate,
        )

    @Test
    fun onlyPlainStampAndEraserStrokesReplay() {
        assertTrue(HighResOverlayPlan.strokeReplayable(stroke()))
        assertTrue(HighResOverlayPlan.strokeReplayable(stroke(Tool.ERASER)))
        assertFalse(HighResOverlayPlan.strokeReplayable(stroke(Tool.SMUDGE)))
        assertFalse(HighResOverlayPlan.strokeReplayable(stroke(Tool.LIQUIFY)))
        assertFalse(HighResOverlayPlan.strokeReplayable(stroke(Tool.FILL)))
        assertFalse(HighResOverlayPlan.strokeReplayable(stroke(wrap = true)))
        assertFalse(HighResOverlayPlan.strokeReplayable(stroke(fill = true)))
        assertFalse(HighResOverlayPlan.strokeReplayable(stroke(substrate = "canvas")))
    }

    @Test
    fun visibleBoundsAccountForLayerScale() {
        val b = HighResOverlayPlan.visibleBounds(inputs(zoom = 2f, layers = listOf(layer(scale = 2f))), layer(scale = 2f))
        assertNotNull(b)
        // Layer drawn twice as large: the screen shows a quarter of the visible width again.
        assertEquals(250f, b.width, 1f)
    }
}
