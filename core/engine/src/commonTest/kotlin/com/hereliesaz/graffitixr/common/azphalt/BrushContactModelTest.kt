package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrushContactModelTest {
    private val config = BrushContactConfig(
        enabled = true,
        stiffness = 0.35f,
        drag = 0.9f,
        fullBendSpeedPxPerMs = 1f,
        hysteresis = 0.7f,
        recovery = 0.8f,
        maxLagDeg = 80f,
        maxDragOffset = 0.25f,
        dragSplay = 0.2f,
        dragElongation = 0.3f,
        pressureCoupling = 0.4f,
        tiltCoupling = 0.4f,
        orientationCoupling = 0.4f,
        pressureSplay = 0.2f,
        tiltElongation = 0.25f,
    )

    private fun sample(
        time: Long,
        heading: Float,
        speed: Float,
        pressure: Float = 1f,
        tilt: Float = 0f,
        orientation: Float = 0f,
    ) = BrushSample(
        x = 0f,
        y = 0f,
        uptimeMillis = time,
        pressure = pressure,
        tiltRadians = tilt,
        orientationRadians = orientation,
        speedPxPerMs = speed,
        drawingAngleDeg = heading,
    )

    @Test
    fun disabledIsIdentity() {
        val result = BrushContactModel.step(
            sample(10L, 90f, 3f),
            BrushMechanicalState(),
            BrushContactConfig(),
        )
        assertEquals(BrushMechanicalState(), result.state)
        assertEquals(BrushContactState(), result.contact)
    }

    @Test
    fun speedIncreasesMechanicalDeformation() {
        val slow = BrushContactModel.step(sample(0L, 0f, 0.1f), BrushMechanicalState(), config)
        val fast = BrushContactModel.step(sample(0L, 0f, 1f), BrushMechanicalState(), config)
        assertTrue(fast.contact.bend > slow.contact.bend)
        assertTrue(fast.contact.splay > slow.contact.splay)
        assertTrue(fast.contact.widthMultiplier > slow.contact.widthMultiplier)
        assertTrue(fast.contact.tipRatioMultiplier < slow.contact.tipRatioMultiplier)
        assertTrue(abs(fast.contact.offsetXFraction) > abs(slow.contact.offsetXFraction))
    }

    @Test
    fun cornerRetainsMechanicalLag() {
        val first = BrushContactModel.step(sample(0L, 0f, 1f, tilt = 0f), BrushMechanicalState(), config)
        val corner = BrushContactModel.step(sample(16L, 90f, 1f, tilt = 0f), first.state, config)
        assertTrue(corner.state.dragAngleDeg > 0f)
        assertTrue(corner.state.dragAngleDeg < 90f)
        assertTrue(corner.contact.angleOffsetDeg < 0f)
    }

    @Test
    fun slowingRecoversBend() {
        var step = BrushContactModel.step(sample(0L, 0f, 1f), BrushMechanicalState(), config)
        val initial = step.contact.bend
        repeat(12) { i ->
            step = BrushContactModel.step(sample((i + 1) * 16L, 0f, 0f), step.state, config)
        }
        assertTrue(step.contact.bend < initial)
    }

    @Test
    fun pressureFeedsStatefulCompressionAndSplay() {
        val light = BrushContactModel.step(
            sample(0L, 0f, 0.5f, pressure = 0.1f),
            BrushMechanicalState(),
            config,
        )
        val heavy = BrushContactModel.step(
            sample(0L, 0f, 0.5f, pressure = 1f),
            BrushMechanicalState(),
            config,
        )
        assertTrue(heavy.state.compression > light.state.compression)
        assertTrue(heavy.contact.widthMultiplier > light.contact.widthMultiplier)
        assertTrue(heavy.contact.contactDepth > light.contact.contactDepth)
        assertTrue(heavy.state.intent.pressure > light.state.intent.pressure)
    }

    @Test
    fun tiltFeedsStatefulLeanRatherThanDirectRendererMapping() {
        val upright = BrushContactModel.step(
            sample(0L, 0f, 0.5f, tilt = 0f),
            BrushMechanicalState(),
            config,
        )
        val leaned = BrushContactModel.step(
            sample(0L, 0f, 0.5f, tilt = (PI / 2.0).toFloat()),
            BrushMechanicalState(),
            config,
        )
        assertTrue(leaned.state.lean > upright.state.lean)
        assertTrue(leaned.contact.tipRatioMultiplier < upright.contact.tipRatioMultiplier)
        assertTrue(leaned.state.intent.tilt > upright.state.intent.tilt)
    }

    @Test
    fun orientationIsTiltGatedAndMediatedByMechanicalState() {
        val verticalA = BrushContactModel.step(
            sample(0L, 35f, 0.75f, tilt = 0f, orientation = 0f),
            BrushMechanicalState(),
            config,
        )
        val verticalB = BrushContactModel.step(
            sample(0L, 35f, 0.75f, tilt = 0f, orientation = PI.toFloat()),
            BrushMechanicalState(),
            config,
        )
        assertEquals(verticalA.state.dragAngleDeg, verticalB.state.dragAngleDeg, 0.0001f)

        val leaned = BrushContactModel.step(
            sample(
                0L,
                35f,
                0.75f,
                tilt = (PI / 2.0).toFloat(),
                orientation = PI.toFloat(),
            ),
            BrushMechanicalState(),
            config,
        )
        assertTrue(abs(leaned.state.dragAngleDeg - verticalA.state.dragAngleDeg) > 1f)
        assertTrue(leaned.state.intent.orientationDeg > 170f)
    }

    @Test
    fun replayIsDeterministic() {
        val sequence = listOf(
            sample(0L, 0f, 0.2f, pressure = 0.3f),
            sample(16L, 0f, 0.8f, pressure = 0.6f),
            sample(32L, 45f, 1f, pressure = 0.8f, tilt = 0.3f),
            sample(48L, 90f, 0.7f, pressure = 0.7f, tilt = 0.5f, orientation = 1f),
            sample(64L, 180f, 0.4f, pressure = 0.4f, tilt = 0.2f),
            sample(80L, 180f, 0f, pressure = 0.1f),
        )
        fun run(): List<BrushMechanicalStep> {
            var state = BrushMechanicalState()
            return sequence.map { point ->
                BrushContactModel.step(point, state, config).also { state = it.state }
            }
        }
        assertEquals(run(), run())
    }
}
