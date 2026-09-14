package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrushTuftContactPhaseTest {
    private val tuftConfig = BrushTuftConfig(
        enabled = true,
        count = 5,
        rootSpan = 0.8f,
        cohesion = 0.15f,
        splayResponse = 1.3f,
        bendDifferential = 0.22f,
        deformationResponse = 0.85f,
        hysteresis = 0.65f,
        recovery = 0.9f,
        maxLagDeg = 28f,
        splitThreshold = 0.28f,
        rejoinThreshold = 0.12f,
        splitResponse = 1f,
        splitSeparation = 0.2f,
        touchdownCompression = 0.3f,
        touchdownHoldMs = 64f,
        stabToDragBend = 0.2f,
        liftReleaseResponse = 1f,
        liftRadiusFloor = 0.2f,
        reversalThresholdDeg = 90f,
        reversalPersistence = 0.9f,
        reversalMaxLagDeg = 150f,
    )

    private fun global(
        angle: Float,
        bend: Float,
        phase: BrushContactPhase,
        pressure: Float = 0.8f,
    ) = BrushMechanicalState(
        initialized = true,
        dragAngleDeg = angle,
        bend = bend,
        compression = pressure,
        intent = BrushIntentObservation(
            pressure = pressure,
            pressureConfidence = 1f,
            contactPhase = phase,
        ),
    )

    @Test
    fun touchdownBeginsCompressedThenTransitionsIntoDrag() {
        val touchdown = BrushTuftTopology.step(
            previous = emptyList(),
            state = global(0f, 0f, BrushContactPhase.TOUCHDOWN),
            contact = BrushContactState(bend = 0f, compression = 0.8f, splay = 0.15f),
            config = tuftConfig,
            dtMs = 0f,
        )
        val outerStart = touchdown.states.last()
        val outerStartContact = touchdown.contacts.last()
        assertTrue(outerStart.touchdownAmount > 0.99f)
        assertTrue(outerStartContact.touchdownAmount > 0.99f)

        var drag = touchdown
        repeat(8) {
            drag = BrushTuftTopology.step(
                previous = drag.states,
                state = global(0f, 0.85f, BrushContactPhase.CONTACT),
                contact = BrushContactState(bend = 0.85f, compression = 0.7f, splay = 0.65f),
                config = tuftConfig,
                dtMs = 16f,
            )
        }
        val outerDrag = drag.states.last()
        assertTrue(outerDrag.touchdownAmount < outerStart.touchdownAmount)
        assertTrue(outerDrag.bend > outerStart.bend)
        assertTrue(outerDrag.trailingFraction > outerStart.trailingFraction)
    }

    @Test
    fun reversalRetainsEstablishedSplitBeforeSnappingTowardNewDirection() {
        var step = BrushTuftTopology.step(
            emptyList(),
            global(0f, 1f, BrushContactPhase.CONTACT),
            BrushContactState(bend = 1f, compression = 0.7f, splay = 1f),
            tuftConfig,
            16f,
        )
        repeat(12) {
            step = BrushTuftTopology.step(
                step.states,
                global(0f, 1f, BrushContactPhase.CONTACT),
                BrushContactState(bend = 1f, compression = 0.7f, splay = 1f),
                tuftConfig,
                16f,
            )
        }
        val before = step.states.last()
        assertTrue(before.splitLatched)
        assertTrue(before.splitAmount > 0f)

        val reversed = BrushTuftTopology.step(
            step.states,
            global(180f, 1f, BrushContactPhase.CONTACT),
            BrushContactState(bend = 1f, compression = 0.7f, splay = 0.3f),
            tuftConfig,
            16f,
        )
        val outerReversed = reversed.states.last()
        assertTrue(outerReversed.reversalImpulse > 0.99f)
        assertTrue(outerReversed.splitLatched)
        assertTrue(outerReversed.splitAmount > 0f)
        val lagImmediatelyAfterReversal = abs(wrap(outerReversed.dragAngleDeg - 180f))
        assertTrue(lagImmediatelyAfterReversal > tuftConfig.maxLagDeg)

        var settled = reversed
        repeat(28) {
            settled = BrushTuftTopology.step(
                settled.states,
                global(180f, 0.7f, BrushContactPhase.CONTACT),
                BrushContactState(bend = 0.7f, compression = 0.5f, splay = 0.15f),
                tuftConfig,
                16f,
            )
        }
        val outerSettled = settled.states.last()
        assertTrue(outerSettled.reversalImpulse < outerReversed.reversalImpulse)
        assertTrue(abs(wrap(outerSettled.dragAngleDeg - 180f)) < lagImmediatelyAfterReversal)
    }

    @Test
    fun explicitLiftOffCollapsesContactAndClearsSplitLatch() {
        var loaded = BrushTuftTopology.step(
            emptyList(),
            global(0f, 1f, BrushContactPhase.CONTACT),
            BrushContactState(bend = 1f, compression = 0.9f, splay = 1f),
            tuftConfig,
            16f,
        )
        repeat(12) {
            loaded = BrushTuftTopology.step(
                loaded.states,
                global(0f, 1f, BrushContactPhase.CONTACT),
                BrushContactState(bend = 1f, compression = 0.9f, splay = 1f),
                tuftConfig,
                16f,
            )
        }
        val beforeState = loaded.states.last()
        val beforeContact = loaded.contacts.last()
        assertTrue(beforeState.splitLatched)

        var lifted = BrushTuftTopology.step(
            loaded.states,
            global(0f, 0f, BrushContactPhase.LIFT_OFF, pressure = 0f),
            BrushContactState(bend = 0f, compression = 0f, splay = 0f),
            tuftConfig,
            16f,
        )
        repeat(12) {
            lifted = BrushTuftTopology.step(
                lifted.states,
                global(0f, 0f, BrushContactPhase.LIFT_OFF, pressure = 0f),
                BrushContactState(bend = 0f, compression = 0f, splay = 0f),
                tuftConfig,
                16f,
            )
        }
        val afterState = lifted.states.last()
        val afterContact = lifted.contacts.last()
        assertFalse(afterState.splitLatched)
        assertTrue(afterState.liftAmount > 0.8f)
        assertTrue(afterState.bend < beforeState.bend)
        assertTrue(afterState.trailingFraction < beforeState.trailingFraction)
        assertTrue(abs(afterState.separationFraction) < abs(beforeState.separationFraction))
        assertTrue(afterContact.radiusScale < beforeContact.radiusScale)
    }

    private fun wrap(value: Float): Float {
        var v = value % 360f
        if (v > 180f) v -= 360f
        if (v < -180f) v += 360f
        return v
    }
}
