package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubstrateDepositionModelTest {
    private val color = MaterialColor(0.8f, 0.2f, 0.1f, 1f)
    private val fullReservoir = BrushReservoirState(load = 1f, wetness = 0f, carriedColor = color)
    private val substrateMedium = PaintMedium(depositionRate = 1f, substrateResponse = 1f)

    private fun assertNear(expected: Float, actual: Float, epsilon: Float = 0.00001f) {
        assertTrue(abs(expected - actual) <= epsilon, "expected $expected, got $actual")
    }

    @Test
    fun `smooth substrate preserves full deposition`() {
        val result = SubstrateDepositionModel.resolve(
            contactDepth = 0f,
            localPaintHeightContribution = 0f,
            reservoir = fullReservoir,
            medium = substrateMedium,
            substrate = SubstrateProfile.SMOOTH.sampleWithoutTexture(),
        )

        assertTrue(result.penetrates)
        assertNear(1f, result.coverage)
        assertNear(1f, result.deposition)
    }

    @Test
    fun `same contact produces crest-only breakup on a rough tiled substrate`() {
        val field = SubstrateField(
            width = 2,
            height = 1,
            heightR8 = byteArrayOf(0, 255.toByte()),
        )
        val profile = SubstrateProfile(heightScale = 1f)
        val dabOnLowTooth = Dab(x = 0f, y = 0f, radius = 4f, alpha = 1f, contactDepth = 0.25f)
        val dabOnHighTooth = dabOnLowTooth.copy(x = 1f)

        val low = SubstrateDepositionModel.resolveDab(
            dab = dabOnLowTooth,
            localPaintHeightContribution = 0f,
            reservoir = fullReservoir,
            medium = substrateMedium,
            profile = profile,
            field = field,
        )
        val high = SubstrateDepositionModel.resolveDab(
            dab = dabOnHighTooth,
            localPaintHeightContribution = 0f,
            reservoir = fullReservoir,
            medium = substrateMedium,
            profile = profile,
            field = field,
        )

        assertTrue(low.penetrates)
        assertNear(1f, low.deposition)
        assertFalse(high.penetrates)
        assertNear(0f, high.deposition)
    }

    @Test
    fun `resolved contact depth changes substrate penetration instead of only opacity or size`() {
        val substrate = SubstrateSample(height = 0.6f)
        val light = SubstrateDepositionModel.resolve(
            contactDepth = 0.2f,
            localPaintHeightContribution = 0f,
            reservoir = fullReservoir,
            medium = substrateMedium,
            substrate = substrate,
        )
        val heavy = SubstrateDepositionModel.resolve(
            contactDepth = 0.8f,
            localPaintHeightContribution = 0f,
            reservoir = fullReservoir,
            medium = substrateMedium,
            substrate = substrate,
        )

        assertFalse(light.penetrates)
        assertNear(0f, light.deposition)
        assertTrue(heavy.penetrates)
        assertNear(1f, heavy.deposition)
    }

    @Test
    fun `existing paint height fills valleys and lowers the penetration barrier`() {
        val substrate = SubstrateSample(height = 0.7f)
        val bare = SubstrateDepositionModel.resolve(
            contactDepth = 0.3f,
            localPaintHeightContribution = 0f,
            reservoir = fullReservoir,
            medium = substrateMedium,
            substrate = substrate,
        )
        val filled = SubstrateDepositionModel.resolve(
            contactDepth = 0.3f,
            localPaintHeightContribution = 0.5f,
            reservoir = fullReservoir,
            medium = substrateMedium,
            substrate = substrate,
        )

        assertFalse(bare.penetrates)
        assertNear(0.7f, bare.penetrationBarrier)
        assertTrue(filled.penetrates)
        assertNear(0.2f, filled.penetrationBarrier)
        assertNear(1f, filled.deposition)
    }

    @Test
    fun `substrate response zero is the exact legacy deposition gate`() {
        val medium = PaintMedium(
            depositionRate = 0.8f,
            substrateResponse = 0f,
        )
        val reservoir = fullReservoir.copy(load = 0.5f)
        val result = SubstrateDepositionModel.resolve(
            contactDepth = 0f,
            localPaintHeightContribution = 0f,
            reservoir = reservoir,
            medium = medium,
            substrate = SubstrateSample(height = 1f),
        )

        assertFalse(result.penetrates)
        assertNear(1f, result.coverage)
        assertNear(0.4f, result.deposition)
    }

    @Test
    fun `partial substrate response blends compatibility and tooth gating`() {
        val result = SubstrateDepositionModel.resolve(
            contactDepth = 0.1f,
            localPaintHeightContribution = 0f,
            reservoir = fullReservoir.copy(load = 0.5f),
            medium = PaintMedium(depositionRate = 0.8f, substrateResponse = 0.4f),
            substrate = SubstrateSample(height = 0.9f),
        )

        assertFalse(result.penetrates)
        assertNear(0.6f, result.coverage)
        assertNear(0.24f, result.deposition)
    }

    @Test
    fun `canvas locked tile coordinates mirror grain scale offset and wrapping semantics`() {
        val heights = byteArrayOf(0, 64, 128.toByte(), 255.toByte())
        val absorbency = byteArrayOf(255.toByte(), 128.toByte(), 64, 0)
        val field = SubstrateField(2, 2, heights, absorbency)
        val profile = SubstrateProfile(
            baseHeight = 0.1f,
            heightScale = 0.5f,
            absorbency = 0.25f,
            textureScale = 2f,
            textureOffsetX = 1f,
            textureOffsetY = 0f,
        )

        val origin = field.sample(0f, 0f, profile)
        assertNear(0.1f + (64f / 255f) * 0.5f, origin.height)
        assertNear(128f / 255f, origin.absorbency)

        val wrappedNegative = field.sample(-2f, 0f, profile)
        assertNear(0.1f, wrappedNegative.height)
        assertNear(1f, wrappedNegative.absorbency)

        heights[0] = 255.toByte()
        absorbency[0] = 0
        val afterCallerMutation = field.sample(-2f, 0f, profile)
        assertEquals(wrappedNegative, afterCallerMutation)
    }
}
