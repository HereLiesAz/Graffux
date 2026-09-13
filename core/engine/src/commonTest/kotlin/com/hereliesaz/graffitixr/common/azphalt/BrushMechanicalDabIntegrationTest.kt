package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BrushMechanicalDabIntegrationTest {
    private val telemetry = BrushTelemetryMetadata(
        profile = BrushTelemetryProfile.STYLUS_HIGH_QUALITY,
        pressureConfidence = 0.98f,
        pressureSource = BrushSignalSource.STYLUS_SENSOR,
        tiltConfidence = 0.95f,
        tiltSource = BrushSignalSource.STYLUS_SENSOR,
        orientationConfidence = 0.9f,
        orientationSource = BrushSignalSource.STYLUS_SENSOR,
    )

    private val brush = AzphaltBrush(
        name = "Mechanical",
        spacing = 0.5f,
        tipRatio = 0.8f,
        contact = BrushContactConfig(
            enabled = true,
            stiffness = 0.4f,
            drag = 0.75f,
            hysteresis = 0.5f,
            maxDragOffset = 0.2f,
            dragSplay = 0.15f,
            dragElongation = 0.2f,
            pressureCoupling = 0.7f,
            tiltCoupling = 0.6f,
            orientationCoupling = 0.5f,
            pressureSplay = 0.25f,
            tiltElongation = 0.25f,
        ),
    )

    private val samples = listOf(
        BrushSample(
            x = 0f,
            y = 0f,
            uptimeMillis = 0L,
            pressure = 0.8f,
            tiltRadians = 0.35f,
            orientationRadians = 0f,
            distancePx = 0f,
            speedPxPerMs = 0f,
            drawingAngleDeg = 0f,
            telemetry = telemetry,
        ),
        BrushSample(
            x = 20f,
            y = 0f,
            uptimeMillis = 20L,
            pressure = 0.85f,
            tiltRadians = 0.45f,
            orientationRadians = (PI / 6.0).toFloat(),
            distancePx = 20f,
            speedPxPerMs = 1f,
            drawingAngleDeg = 0f,
            telemetry = telemetry,
        ),
        BrushSample(
            x = 40f,
            y = 0f,
            uptimeMillis = 40L,
            pressure = 0.9f,
            tiltRadians = 0.5f,
            orientationRadians = (PI / 4.0).toFloat(),
            distancePx = 40f,
            speedPxPerMs = 1f,
            drawingAngleDeg = 0f,
            telemetry = telemetry,
        ),
    )

    private val cornerSamples = listOf(
        BrushSample(
            x = 0f,
            y = 0f,
            uptimeMillis = 0L,
            pressure = 0.8f,
            distancePx = 0f,
            speedPxPerMs = 0f,
            drawingAngleDeg = 0f,
            telemetry = telemetry,
        ),
        BrushSample(
            x = 20f,
            y = 0f,
            uptimeMillis = 20L,
            pressure = 0.85f,
            distancePx = 20f,
            speedPxPerMs = 1f,
            drawingAngleDeg = 0f,
            telemetry = telemetry,
        ),
        BrushSample(
            x = 20f,
            y = 20f,
            uptimeMillis = 40L,
            pressure = 0.85f,
            distancePx = 40f,
            speedPxPerMs = 1f,
            drawingAngleDeg = 90f,
            telemetry = telemetry,
        ),
        BrushSample(
            x = 0f,
            y = 20f,
            uptimeMillis = 60L,
            pressure = 0.8f,
            distancePx = 60f,
            speedPxPerMs = 1f,
            drawingAngleDeg = 180f,
            telemetry = telemetry,
        ),
    )

    @Test
    fun mechanicsOnlyBrushDoesNotFallBackToStaticDabs() {
        val dynamic = BrushStamps.dynamicDabs(samples, 10f, brush, 77L)
        val static = BrushStamps.dabs(listOf(0f, 0f, 20f, 0f, 40f, 0f), 10f, brush.copy(contact = BrushContactConfig()), 77L)

        assertTrue(dynamic.isNotEmpty())
        assertTrue(static.isNotEmpty())
        assertNotEquals(static.first().radius, dynamic.first().radius)
        assertTrue(dynamic.first().radius > static.first().radius)
    }

    @Test
    fun liveAndCanonicalGeneratorsShareMechanicalStateResolution() {
        val expected = BrushStamps.dynamicDabs(samples, 10f, brush, 77L)
        val generator = IncrementalDynamicDabGenerator(10f, brush, 77L)
        val total = samples.last().distancePx
        val actual = samples.flatMap { generator.append(it, predictedTotal = total) }

        assertDabsEqual(expected, actual)
    }

    @Test
    fun telemetryConfidenceSurvivesDabInterpolation() {
        val lowerTrust = telemetry.copy(
            profile = BrushTelemetryProfile.STYLUS_BASIC,
            pressureConfidence = 0.5f,
            tiltConfidence = 0.2f,
            orientationConfidence = 0.1f,
        )
        val mixedSamples = samples.toMutableList().also {
            it[0] = it[0].copy(telemetry = lowerTrust)
        }
        val lowTrust = BrushStamps.dynamicDabs(mixedSamples, 10f, brush, 77L)
        val highTrust = BrushStamps.dynamicDabs(samples, 10f, brush, 77L)

        assertTrue(lowTrust.isNotEmpty())
        assertTrue(highTrust.isNotEmpty())
        assertTrue(highTrust.first().radius > lowTrust.first().radius)
    }

    @Test
    fun topologyFoundationDoesNotAlterDabsBeforeSplitRenderingIsEnabled() {
        val baseline = BrushStamps.dynamicDabs(samples, 10f, brush, 77L)
        val withTopology = BrushStamps.dynamicDabs(
            samples,
            10f,
            brush.copy(
                contact = brush.contact.copy(
                    tufts = BrushTuftConfig(
                        enabled = true,
                        count = 7,
                        rootSpan = 0.9f,
                        cohesion = 0.4f,
                        splayResponse = 1.2f,
                        bendDifferential = 0.2f,
                        emitTuftDabs = false,
                    )
                )
            ),
            77L,
        )

        assertEquals(baseline, withTopology)
    }

    @Test
    fun emittedTuftsProduceStableSeparatedContacts() {
        val parent = BrushStamps.dynamicDabs(samples, 10f, brush, 77L)
        val tuftBrush = brush.copy(
            contact = brush.contact.copy(
                tufts = BrushTuftConfig(
                    enabled = true,
                    count = 5,
                    rootSpan = 0.8f,
                    cohesion = 0.45f,
                    splayResponse = 1.1f,
                    bendDifferential = 0.2f,
                    emitTuftDabs = true,
                )
            )
        )
        val split = BrushStamps.dynamicDabs(samples, 10f, tuftBrush, 77L)

        assertTrue(parent.isNotEmpty())
        assertEquals(parent.size * 5, split.size)
        val firstBundle = split.take(5)
        assertEquals(5, firstBundle.map { it.y }.distinct().size)
        assertTrue(firstBundle.zipWithNext().all { (a, b) -> a.y < b.y })
        assertTrue(firstBundle.all { it.radius < parent.first().radius })
    }

    @Test
    fun emittedTuftsStayIdenticalBetweenLiveAndCanonicalGenerators() {
        val tuftBrush = brush.copy(
            contact = brush.contact.copy(
                tufts = BrushTuftConfig(
                    enabled = true,
                    count = 5,
                    rootSpan = 0.8f,
                    cohesion = 0.35f,
                    splayResponse = 1.25f,
                    bendDifferential = 0.22f,
                    emitTuftDabs = true,
                )
            )
        )
        val expected = BrushStamps.dynamicDabs(samples, 10f, tuftBrush, 77L)
        val generator = IncrementalDynamicDabGenerator(10f, tuftBrush, 77L)
        val total = samples.last().distancePx
        val actual = samples.flatMap { generator.append(it, predictedTotal = total) }

        assertTrue(expected.isNotEmpty())
        assertDabsEqual(expected, actual)
    }

    @Test
    fun statefulTuftLagAtCornersIsIdenticalLiveAndCanonical() {
        val tuftBrush = brush.copy(
            contact = brush.contact.copy(
                stiffness = 0.8f,
                hysteresis = 0.55f,
                tufts = BrushTuftConfig(
                    enabled = true,
                    count = 5,
                    rootSpan = 0.82f,
                    cohesion = 0.3f,
                    splayResponse = 1.3f,
                    bendDifferential = 0.24f,
                    deformationResponse = 0.65f,
                    hysteresis = 0.7f,
                    recovery = 0.75f,
                    maxLagDeg = 35f,
                    emitTuftDabs = true,
                )
            )
        )
        val expected = BrushStamps.dynamicDabs(cornerSamples, 10f, tuftBrush, 991L)
        val generator = IncrementalDynamicDabGenerator(10f, tuftBrush, 991L)
        val total = cornerSamples.last().distancePx
        val actual = cornerSamples.flatMap { generator.append(it, predictedTotal = total) }

        assertTrue(expected.isNotEmpty())
        assertTrue(expected.map { it.angleDeg }.distinct().size > 1)
        assertDabsEqual(expected, actual)
    }

    @Test
    fun splitBreakawayAndRelaxationRemainLiveReplayIdentical() {
        val splitSamples = listOf(
            BrushSample(0f, 0f, 0L, speedPxPerMs = 1f, drawingAngleDeg = 0f),
            BrushSample(20f, 0f, 20L, distancePx = 20f, speedPxPerMs = 1f, drawingAngleDeg = 0f),
            BrushSample(40f, 0f, 40L, distancePx = 40f, speedPxPerMs = 1f, drawingAngleDeg = 0f),
            BrushSample(60f, 0f, 60L, distancePx = 60f, speedPxPerMs = 1f, drawingAngleDeg = 0f),
            BrushSample(80f, 0f, 80L, distancePx = 80f, speedPxPerMs = 1f, drawingAngleDeg = 0f),
            BrushSample(100f, 0f, 180L, distancePx = 100f, speedPxPerMs = 0.08f, drawingAngleDeg = 0f),
            BrushSample(120f, 0f, 280L, distancePx = 120f, speedPxPerMs = 0.03f, drawingAngleDeg = 0f),
            BrushSample(140f, 0f, 380L, distancePx = 140f, speedPxPerMs = 0f, drawingAngleDeg = 0f),
        )
        val tuftBrush = AzphaltBrush(
            name = "Split transition",
            spacing = 0.35f,
            contact = BrushContactConfig(
                enabled = true,
                stiffness = 0.9f,
                drag = 1f,
                fullBendSpeedPxPerMs = 0.5f,
                recovery = 0.95f,
                dragSplay = 0.9f,
                pressureCoupling = 0f,
                tufts = BrushTuftConfig(
                    enabled = true,
                    count = 5,
                    rootSpan = 0.7f,
                    cohesion = 0f,
                    splayResponse = 0.7f,
                    bendDifferential = 0.18f,
                    deformationResponse = 0.8f,
                    recovery = 0.95f,
                    splitThreshold = 0.25f,
                    rejoinThreshold = 0.12f,
                    splitResponse = 1f,
                    splitSeparation = 0.24f,
                    splitBendWeight = 0.5f,
                    emitTuftDabs = true,
                ),
            ),
        )

        val expected = BrushStamps.dynamicDabs(splitSamples, 12f, tuftBrush, 444L)
        val generator = IncrementalDynamicDabGenerator(12f, tuftBrush, 444L)
        val actual = splitSamples.flatMap { generator.append(it, predictedTotal = splitSamples.last().distancePx) }

        assertDabsEqual(expected, actual)
        val groups = expected.chunked(5).filter { it.size == 5 }
        assertTrue(groups.size > 4)
        fun span(group: List<Dab>): Float = group.maxOf { it.y } - group.minOf { it.y }
        val firstSpan = span(groups.first())
        val maxSpan = groups.maxOf(::span)
        val lastSpan = span(groups.last())
        assertTrue(maxSpan > firstSpan)
        assertTrue(lastSpan < maxSpan)
    }

    private fun assertDabsEqual(expected: List<Dab>, actual: List<Dab>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEachIndexed { index, (canonical, live) ->
            assertEquals(canonical.x, live.x, 1e-4f, "x[$index]")
            assertEquals(canonical.y, live.y, 1e-4f, "y[$index]")
            assertEquals(canonical.radius, live.radius, 1e-4f, "radius[$index]")
            assertEquals(canonical.tipRatio, live.tipRatio, 1e-4f, "tipRatio[$index]")
            assertEquals(canonical.angleDeg, live.angleDeg, 1e-4f, "angle[$index]")
            assertEquals(canonical.alpha, live.alpha, 1e-4f, "alpha[$index]")
            assertEquals(canonical.mask?.x, live.mask?.x, "mask.x[$index]")
            assertEquals(canonical.mask?.y, live.mask?.y, "mask.y[$index]")
            assertEquals(canonical.mask?.radius, live.mask?.radius, "mask.radius[$index]")
        }
    }
}
