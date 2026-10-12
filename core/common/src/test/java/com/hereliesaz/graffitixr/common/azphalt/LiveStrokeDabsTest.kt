package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStrokeDabsTest {

    private val samples = (0 until 40).map { i ->
        val t = i * 8L
        BrushSample(
            x = i * 6f,
            y = 20f + (i % 7) * 3f,
            uptimeMillis = t,
            pressure = 0.4f + (i % 5) * 0.1f,
            distancePx = i * 6.5f,
            speedPxPerMs = 0.2f + (i % 9) * 0.15f,
        )
    }

    private val dynamicBrush = AzphaltBrush(
        name = "dynamic",
        spacing = 0.2f,
        sizeJitter = 0.3f,
        taper = BrushTaper(startLengthPx = 30f, minSize = 0.2f, minOpacity = 0.3f),
        dynamics = listOf(
            BrushSensorBinding(
                sensor = BrushSensor.PRESSURE,
                parameter = BrushParameter.SIZE,
                outputMin = 0.4f,
                outputMax = 1f,
            ),
        ),
    )

    /** What EditorViewModel.renderStampLive does: frame-sized batches, predictor fed per sample. */
    private fun liveDabs(brush: AzphaltBrush, batch: Int): List<Dab> {
        val generator = IncrementalDynamicDabGenerator(24f, brush, 9L)
        val predictor = StrokeLengthPredictor()
        val out = ArrayList<Dab>()
        samples.chunked(batch).forEach { frame ->
            frame.forEach { s ->
                predictor.record(s)
                out.addAll(generator.append(s, predictor.predictedTotal))
            }
        }
        return out
    }

    @Test
    fun `replay reproduces the live stroke's dabs whatever the frame batching`() {
        val replayed = LiveStrokeDabs.movementDabs(samples, 24f, dynamicBrush, 9L)
        assertTrue(replayed.isNotEmpty())
        for (batch in listOf(1, 3, 7, 40)) {
            assertEquals(liveDabs(dynamicBrush, batch), replayed)
        }
    }

    @Test
    fun `a static brush replays the live point placement`() {
        val brush = AzphaltBrush(name = "static", spacing = 0.25f)
        val generator = IncrementalStaticDabGenerator(24f, brush, 4L)
        val live = samples.flatMap { generator.appendPoint(it.x, it.y) }
        assertEquals(live, LiveStrokeDabs.movementDabs(samples, 24f, brush, 4L))
    }
}
