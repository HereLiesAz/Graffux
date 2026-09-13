package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrushTuftDabExpanderTest {
    private val parent = Dab(
        x = 100f,
        y = 50f,
        radius = 20f,
        alpha = 0.8f,
        angleDeg = 30f,
        tipRatio = 0.6f,
        hardness = 0.7f,
        flowMultiplier = 0.9f,
        colorMix = 0.25f,
        sourceRandom = 0.75f,
        mask = MaskDab(
            x = 100f,
            y = 50f,
            radius = 10f,
            tipRatio = 0.5f,
            alpha = 0.6f,
            angleDeg = 15f,
        ),
    )

    @Test
    fun emptyTopologyIsExactIdentity() {
        assertEquals(listOf(parent), BrushTuftDabExpander.expand(parent, 40f, emptyList()))
    }

    @Test
    fun resolvedContactsBecomeOrdinaryChildDabsInStableOrder() {
        val tufts = listOf(
            BrushTuftContact(0, -0.25f, 0f, -0.25f, 0.2f, 1f, -2f, 0.8f),
            BrushTuftContact(1, 0f, 0f, 0f, 0.2f, 0.9f, 0f, 1f),
            BrushTuftContact(2, 0.25f, 0f, 0.25f, 0.2f, 1f, 2f, 0.8f),
        )

        val children = BrushTuftDabExpander.expand(parent, 40f, tufts)

        assertEquals(3, children.size)
        assertEquals(40f, children[0].y, 1e-6f)
        assertEquals(50f, children[1].y, 1e-6f)
        assertEquals(60f, children[2].y, 1e-6f)
        assertEquals(4f, children[0].radius, 1e-6f)
        assertEquals(28f, children[0].angleDeg, 1e-6f)
        assertEquals(32f, children[2].angleDeg, 1e-6f)
        assertEquals(parent.colorMix, children[0].colorMix, 0f)
        assertEquals(parent.sourceRandom, children[2].sourceRandom, 0f)
    }

    @Test
    fun childMaskTracksBundleOffsetAndScale() {
        val tuft = BrushTuftContact(
            id = 0,
            rootLateralFraction = 0.2f,
            offsetXFraction = 0.1f,
            offsetYFraction = -0.2f,
            radiusScale = 0.25f,
            alphaScale = 0.5f,
            angleOffsetDeg = 5f,
            stiffnessScale = 1f,
        )

        val child = BrushTuftDabExpander.expand(parent, 40f, listOf(tuft)).single()
        val mask = child.mask ?: error("Expected child mask")

        assertEquals(104f, child.x, 1e-6f)
        assertEquals(42f, child.y, 1e-6f)
        assertEquals(5f, child.radius, 1e-6f)
        assertEquals(0.4f, child.alpha, 1e-6f)
        assertEquals(104f, mask.x, 1e-6f)
        assertEquals(42f, mask.y, 1e-6f)
        assertEquals(2.5f, mask.radius, 1e-6f)
        assertEquals(20f, mask.angleDeg, 1e-6f)
        assertEquals(0.3f, mask.alpha, 1e-6f)
    }

    @Test
    fun expansionIsDeterministic() {
        val topology = BrushTuftTopology.resolve(
            BrushMechanicalState(initialized = true, dragAngleDeg = 45f, bend = 0.7f),
            BrushContactState(splay = 0.5f, bend = 0.7f),
            BrushTuftConfig(enabled = true, count = 7),
        )
        val first = BrushTuftDabExpander.expand(parent, 40f, topology)
        val second = BrushTuftDabExpander.expand(parent, 40f, topology)

        assertEquals(first, second)
        assertTrue(first.size == 7)
    }
}
