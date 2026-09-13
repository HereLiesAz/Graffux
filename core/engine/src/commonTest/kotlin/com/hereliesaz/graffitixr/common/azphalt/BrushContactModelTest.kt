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
        val first = BrushContactModel.step(sample(0L, 0f, 1f), BrushMechanicalState(), config)
        val corner = BrushContactModel.step(sample(16L, 90f, 1f), first.state, config)
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
    fun expressiveTelemetryDoesNotYetDriveIntrinsicMechanics() {
        val a = sample(16L, 35f, 0.75f, pressure = 0.1f)
        val b = sample(
            16L,
            35f,
            0.75f,
            pressure = 1f,
            tilt = (PI / 2.0).toFloat(),
            orientation = PI.toFloat(),
        )
        assertEquals(
            BrushContactModel.step(a, BrushMechanicalState(), config),
            BrushContactModel.step(b, BrushMechanicalState(), config),
        )
    }

    @Test
    fun replayIsDeterministic() {
        val sequence = listOf(
            sample(0L, 0f, 0.2f),
            sample(16L, 0f, 0.8f),
            sample(32L, 45f, 1f),
            sample(48L, 90f, 0.7f),
            sample(64L, 180f, 0.4f),
            sample(80L, 180f, 0f),
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
