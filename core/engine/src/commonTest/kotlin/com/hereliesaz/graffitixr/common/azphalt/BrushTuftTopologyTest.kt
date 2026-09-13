package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrushTuftTopologyTest {
    private val config = BrushTuftConfig(
        enabled = true,
        count = 5,
        rootSpan = 0.8f,
        cohesion = 0.5f,
        splayResponse = 1f,
        bendDifferential = 0.2f,
    )

    @Test
    fun layoutHasStableSymmetricIdentities() {
        val first = BrushTuftTopology.layout(config)
        val second = BrushTuftTopology.layout(config)

        assertEquals(first, second)
        assertEquals(listOf(0, 1, 2, 3, 4), first.map { it.id })
        assertEquals(0f, first[2].rootLateralFraction, 1e-6f)
        assertEquals(-first[0].rootLateralFraction, first[4].rootLateralFraction, 1e-6f)
        assertEquals(-first[1].rootLateralFraction, first[3].rootLateralFraction, 1e-6f)
        assertTrue(first[0].stiffnessScale < first[2].stiffnessScale)
    }

    @Test
    fun disabledTopologyIsExactlyEmpty() {
        assertTrue(BrushTuftTopology.layout(BrushTuftConfig()).isEmpty())
        assertTrue(
            BrushTuftTopology.resolve(
                BrushMechanicalState(initialized = true),
                BrushContactState(),
                BrushTuftConfig(),
            ).isEmpty()
        )
    }

    @Test
    fun splaySeparatesOuterBundlesCoherently() {
        val state = BrushMechanicalState(initialized = true, dragAngleDeg = 0f, bend = 0.4f)
        val compact = BrushTuftTopology.resolve(
            state,
            BrushContactState(splay = 0f, bend = 0.4f),
            config,
        )
        val splayed = BrushTuftTopology.resolve(
            state,
            BrushContactState(splay = 0.8f, bend = 0.4f),
            config,
        )

        val compactSpan = abs(compact.last().offsetYFraction - compact.first().offsetYFraction)
        val splayedSpan = abs(splayed.last().offsetYFraction - splayed.first().offsetYFraction)
        assertTrue(splayedSpan > compactSpan)
        assertEquals(0f, splayed[2].offsetYFraction, 1e-6f)
    }

    @Test
    fun cohesionRestrainsSplayAndDifferentialDrag() {
        val state = BrushMechanicalState(initialized = true, dragAngleDeg = 0f, bend = 1f)
        val contact = BrushContactState(splay = 1f, bend = 1f)
        val loose = BrushTuftTopology.resolve(state, contact, config.copy(cohesion = 0f))
        val cohesive = BrushTuftTopology.resolve(state, contact, config.copy(cohesion = 1f))

        val looseSpan = abs(loose.last().offsetYFraction - loose.first().offsetYFraction)
        val cohesiveSpan = abs(cohesive.last().offsetYFraction - cohesive.first().offsetYFraction)
        assertTrue(looseSpan > cohesiveSpan)
        assertTrue(abs(loose.first().offsetXFraction) > abs(cohesive.first().offsetXFraction))
    }

    @Test
    fun dragDirectionRotatesWholeTopologyWithoutChangingIdentity() {
        val contact = BrushContactState(splay = 0.4f, bend = 0.6f)
        val horizontal = BrushTuftTopology.resolve(
            BrushMechanicalState(initialized = true, dragAngleDeg = 0f, bend = 0.6f),
            contact,
            config,
        )
        val vertical = BrushTuftTopology.resolve(
            BrushMechanicalState(initialized = true, dragAngleDeg = 90f, bend = 0.6f),
            contact,
            config,
        )

        assertEquals(horizontal.map { it.id }, vertical.map { it.id })
        horizontal.zip(vertical).forEach { (a, b) ->
            assertEquals(abs(a.offsetYFraction), abs(b.offsetXFraction), 1e-5f)
        }
    }

    @Test
    fun contactModelCarriesResolvedTuftsDeterministically() {
        val contactConfig = BrushContactConfig(
            enabled = true,
            stiffness = 0.4f,
            drag = 0.8f,
            dragSplay = 0.3f,
            pressureCoupling = 0.6f,
            pressureSplay = 0.3f,
            tufts = config,
        )
        val samples = listOf(
            BrushSample(0f, 0f, uptimeMillis = 0L, pressure = 0.3f, speedPxPerMs = 0.2f, drawingAngleDeg = 0f),
            BrushSample(10f, 0f, uptimeMillis = 16L, pressure = 0.8f, speedPxPerMs = 0.8f, drawingAngleDeg = 0f),
            BrushSample(18f, 8f, uptimeMillis = 32L, pressure = 0.9f, speedPxPerMs = 1f, drawingAngleDeg = 45f),
        )

        fun run(): List<List<BrushTuftContact>> {
            var state = BrushMechanicalState()
            return samples.map { sample ->
                BrushContactModel.step(sample, state, contactConfig).also { state = it.state }.contact.tufts
            }
        }

        assertEquals(run(), run())
        assertEquals(config.count, run().last().size)
    }
}
