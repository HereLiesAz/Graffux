package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val step = BrushTuftTopology.step(
            previous = emptyList(),
            state = BrushMechanicalState(initialized = true),
            contact = BrushContactState(),
            config = BrushTuftConfig(),
            dtMs = 16f,
        )
        assertTrue(step.states.isEmpty())
        assertTrue(step.contacts.isEmpty())
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

        fun run(): List<BrushMechanicalStep> {
            var state = BrushMechanicalState()
            return samples.map { sample ->
                BrushContactModel.step(sample, state, contactConfig).also { state = it.state }
            }
        }

        val first = run()
        val second = run()
        assertEquals(first, second)
        assertEquals(config.count, first.last().contact.tufts.size)
        assertEquals(config.count, first.last().state.tufts.size)
        assertEquals(listOf(0, 1, 2, 3, 4), first.last().state.tufts.map { it.id })
    }

    @Test
    fun softerOuterTuftsLagCenterTuftAtCorner() {
        val contactConfig = BrushContactConfig(
            enabled = true,
            stiffness = 1f,
            drag = 1f,
            hysteresis = 0f,
            dragSplay = 0.25f,
            tufts = config.copy(
                deformationResponse = 0.8f,
                hysteresis = 0.6f,
                maxLagDeg = 40f,
            ),
        )
        val first = BrushContactModel.step(
            BrushSample(0f, 0f, uptimeMillis = 0L, speedPxPerMs = 1f, drawingAngleDeg = 0f),
            BrushMechanicalState(),
            contactConfig,
        )
        val corner = BrushContactModel.step(
            BrushSample(10f, 10f, uptimeMillis = 16L, speedPxPerMs = 1f, drawingAngleDeg = 90f),
            first.state,
            contactConfig,
        )

        val outer = corner.state.tufts.first()
        val center = corner.state.tufts[2]
        assertTrue(center.dragAngleDeg > outer.dragAngleDeg)
        assertTrue(abs(corner.contact.tufts.first().angleOffsetDeg) > 0.1f)
        assertTrue(abs(corner.contact.tufts[2].angleOffsetDeg) > 0.1f)
    }

    @Test
    fun tuftSeparationAndBendRecoverAfterMotionStops() {
        val contactConfig = BrushContactConfig(
            enabled = true,
            stiffness = 0.7f,
            drag = 1f,
            recovery = 0.9f,
            dragSplay = 0.5f,
            tufts = config.copy(
                cohesion = 0.2f,
                splayResponse = 1.4f,
                recovery = 0.9f,
            ),
        )
        var step = BrushContactModel.step(
            BrushSample(0f, 0f, uptimeMillis = 0L, speedPxPerMs = 1f, drawingAngleDeg = 0f),
            BrushMechanicalState(),
            contactConfig,
        )
        val loadedOuter = step.state.tufts.last()
        repeat(24) { index ->
            step = BrushContactModel.step(
                BrushSample(
                    0f,
                    0f,
                    uptimeMillis = (index + 1L) * 16L,
                    speedPxPerMs = 0f,
                    drawingAngleDeg = 0f,
                ),
                step.state,
                contactConfig,
            )
        }
        val recoveredOuter = step.state.tufts.last()
        assertTrue(abs(recoveredOuter.separationFraction) < abs(loadedOuter.separationFraction))
        assertTrue(recoveredOuter.bend < loadedOuter.bend)
        assertTrue(recoveredOuter.trailingFraction < loadedOuter.trailingFraction)
    }

    @Test
    fun splitRequiresSustainedLoadingAndKeepsCenterAsAnchor() {
        val splitConfig = config.copy(
            cohesion = 0f,
            splitThreshold = 0.35f,
            rejoinThreshold = 0.18f,
            splitResponse = 1f,
            splitSeparation = 0.2f,
            splitBendWeight = 0.5f,
        )
        val global = BrushMechanicalState(initialized = true, dragAngleDeg = 0f, bend = 1f)
        val loaded = BrushContactState(splay = 1f, bend = 1f)

        var step = BrushTuftTopology.step(emptyList(), global, loaded, splitConfig, 16f)
        assertTrue(step.states.none { it.splitLatched })
        assertTrue(step.states.all { it.splitAmount == 0f })

        repeat(8) {
            step = BrushTuftTopology.step(step.states, global, loaded, splitConfig, 16f)
        }

        assertTrue(step.states.first().splitLatched)
        assertTrue(step.states.last().splitLatched)
        assertTrue(step.states.first().splitAmount > 0f)
        assertTrue(step.states.last().splitAmount > 0f)
        assertFalse(step.states[2].splitLatched)
        assertEquals(0f, step.states[2].splitAmount, 1e-6f)
        assertTrue(step.contacts.first().splitAmount > 0f)
    }

    @Test
    fun splitLatchSurvivesMidLoadThenRejoinsBelowLowerThreshold() {
        val splitConfig = config.copy(
            cohesion = 0f,
            splitThreshold = 0.35f,
            rejoinThreshold = 0.18f,
            splitResponse = 1f,
            splitSeparation = 0.2f,
            splitBendWeight = 0.5f,
            recovery = 1f,
        )
        val global = BrushMechanicalState(initialized = true, dragAngleDeg = 0f, bend = 1f)
        var step = BrushTuftTopology.step(
            emptyList(),
            global,
            BrushContactState(splay = 1f, bend = 1f),
            splitConfig,
            16f,
        )
        repeat(10) {
            step = BrushTuftTopology.step(
                step.states,
                global,
                BrushContactState(splay = 1f, bend = 1f),
                splitConfig,
                16f,
            )
        }
        assertTrue(step.states.last().splitLatched)
        val splitSeparation = abs(step.states.last().separationFraction)

        repeat(12) {
            step = BrushTuftTopology.step(
                step.states,
                global.copy(bend = 0.25f),
                BrushContactState(splay = 0.25f, bend = 0.25f),
                splitConfig,
                16f,
            )
        }
        val middle = step.states.last()
        assertTrue(middle.splitLatched)
        assertTrue(middle.splitDrive < splitConfig.splitThreshold)
        assertTrue(middle.splitDrive > splitConfig.rejoinThreshold)
        assertTrue(abs(middle.separationFraction) > 0f)

        repeat(40) {
            step = BrushTuftTopology.step(
                step.states,
                global.copy(bend = 0f),
                BrushContactState(splay = 0f, bend = 0f),
                splitConfig,
                16f,
            )
        }
        val rejoined = step.states.last()
        assertFalse(rejoined.splitLatched)
        assertTrue(rejoined.splitAmount < 0.02f)
        assertTrue(abs(rejoined.separationFraction) < splitSeparation)
    }

    @Test
    fun cohesionRaisesResistanceToBreakaway() {
        val base = config.copy(
            splitThreshold = 0.62f,
            rejoinThreshold = 0.3f,
            splitResponse = 1f,
            splitBendWeight = 0.5f,
        )
        val global = BrushMechanicalState(initialized = true, dragAngleDeg = 0f, bend = 1f)
        val loaded = BrushContactState(splay = 1f, bend = 1f)

        fun run(cfg: BrushTuftConfig): BrushTuftMechanicalState {
            var step = BrushTuftTopology.step(emptyList(), global, loaded, cfg, 16f)
            repeat(16) {
                step = BrushTuftTopology.step(step.states, global, loaded, cfg, 16f)
            }
            return step.states.last()
        }

        val loose = run(base.copy(cohesion = 0f))
        val cohesive = run(base.copy(cohesion = 1f))
        assertTrue(loose.splitLatched)
        assertFalse(cohesive.splitLatched)
        assertTrue(loose.splitDrive > cohesive.splitDrive)
    }
}
