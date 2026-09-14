package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrushMorphologyTest {
    private fun config(morphology: BrushMorphology, count: Int = 7) = BrushTuftConfig(
        enabled = true,
        count = count,
        rootSpan = 0.8f,
        cohesion = 0.45f,
        splayResponse = 1f,
        bendDifferential = 0.2f,
        deformationResponse = 0.9f,
        recovery = 0.9f,
        splitThreshold = 0.35f,
        rejoinThreshold = 0.15f,
        splitResponse = 1f,
        splitSeparation = 0.2f,
        morphology = morphology,
    )

    @Test
    fun customMorphologyPreservesHistoricalLinearLayout() {
        val layout = BrushTuftTopology.layout(config(BrushMorphology.CUSTOM, count = 5))

        assertEquals(listOf(-0.4f, -0.2f, 0f, 0.2f, 0.4f), layout.map { it.rootLateralFraction })
        assertTrue(layout.all { it.rootLongitudinalFraction == 0f })
        assertTrue(layout.all { it.widthScale == 1f })
        assertTrue(layout.all { it.angleBiasDeg == 0f })
        assertEquals(0.8f, layout.first().stiffnessScale, 1e-6f)
        assertEquals(1f, layout[2].stiffnessScale, 1e-6f)
    }

    @Test
    fun everyMorphologyIsDeterministicAndSymmetricInIdentity() {
        BrushMorphology.entries.forEach { morphology ->
            val cfg = config(morphology)
            val first = BrushTuftTopology.layout(cfg)
            val second = BrushTuftTopology.layout(cfg)

            assertEquals(first, second, morphology.name)
            assertEquals(first.map { it.id }, (0 until cfg.count).toList(), morphology.name)
            assertEquals(-first.first().rootLateralFraction, first.last().rootLateralFraction, 1e-6f, morphology.name)
        }
    }

    @Test
    fun roundAndFilbertHaveRoundedCenterForwardContact() {
        val round = BrushTuftTopology.layout(config(BrushMorphology.ROUND))
        val filbert = BrushTuftTopology.layout(config(BrushMorphology.FILBERT))
        val center = round.lastIndex / 2

        assertTrue(round[center].rootLongitudinalFraction > round.first().rootLongitudinalFraction)
        assertTrue(round[center].widthScale > round.first().widthScale)
        assertTrue(filbert[center].rootLongitudinalFraction > filbert.first().rootLongitudinalFraction)
        assertTrue(filbert[center].widthScale > filbert.first().widthScale)
        assertTrue(filbert[center].rootLongitudinalFraction > round[center].rootLongitudinalFraction)
        assertTrue(filbert.first().stiffnessScale < filbert[center].stiffnessScale)
    }

    @Test
    fun flatIsStraightAndEvenWhileRiggerIsNarrowFlexibleAndTrailing() {
        val flatCfg = config(BrushMorphology.FLAT)
        val riggerCfg = config(BrushMorphology.RIGGER)
        val flat = BrushTuftTopology.layout(flatCfg)
        val rigger = BrushTuftTopology.layout(riggerCfg)

        val flatSpan = flat.last().rootLateralFraction - flat.first().rootLateralFraction
        val riggerSpan = rigger.last().rootLateralFraction - rigger.first().rootLateralFraction
        assertTrue(flat.all { it.rootLongitudinalFraction == 0f })
        assertTrue(flat.all { abs(it.widthScale - 1f) < 1e-6f })
        assertTrue(riggerSpan < flatSpan * 0.4f)
        assertTrue(rigger.first().stiffnessScale < flat.first().stiffnessScale)

        val state = BrushMechanicalState(initialized = true, dragAngleDeg = 0f, bend = 1f)
        val contact = BrushContactState(splay = 0.5f, bend = 1f)
        val flatContacts = BrushTuftTopology.resolve(state, contact, flatCfg)
        val riggerContacts = BrushTuftTopology.resolve(state, contact, riggerCfg)
        assertTrue(abs(riggerContacts.first().offsetXFraction) > abs(flatContacts.first().offsetXFraction))
    }

    @Test
    fun fanRadiatesAndRakeMaintainsSeparatedTeeth() {
        val fan = BrushTuftTopology.layout(config(BrushMorphology.FAN))
        val flat = BrushTuftTopology.layout(config(BrushMorphology.FLAT))
        val rake = BrushTuftTopology.layout(config(BrushMorphology.RAKE))

        val fanSpan = fan.last().rootLateralFraction - fan.first().rootLateralFraction
        val flatSpan = flat.last().rootLateralFraction - flat.first().rootLateralFraction
        assertTrue(fanSpan > flatSpan)
        assertTrue(fan.first().angleBiasDeg < 0f)
        assertTrue(fan.last().angleBiasDeg > 0f)
        assertEquals(-fan.first().angleBiasDeg, fan.last().angleBiasDeg, 1e-6f)

        assertTrue(rake.all { abs(it.widthScale - 0.54f) < 1e-6f })
        assertTrue(rake.zipWithNext().all { (a, b) -> a.rootLongitudinalFraction != b.rootLongitudinalFraction })
        assertTrue(rake.all { it.angleBiasDeg == 0f })
    }

    @Test
    fun fanAndRakeBreakAwayMoreReadilyThanFlat() {
        fun outerSplitDrive(morphology: BrushMorphology): Float {
            val cfg = config(morphology)
            val global = BrushMechanicalState(
                initialized = true,
                dragAngleDeg = 0f,
                bend = 1f,
                intent = BrushIntentObservation(contactPhase = BrushContactPhase.CONTACT),
            )
            val loaded = BrushContactState(splay = 1f, bend = 1f)
            var step = BrushTuftTopology.step(emptyList(), global, loaded, cfg, 16f)
            repeat(10) {
                step = BrushTuftTopology.step(step.states, global, loaded, cfg, 16f)
            }
            return step.states.last().splitDrive
        }

        val flat = outerSplitDrive(BrushMorphology.FLAT)
        val fan = outerSplitDrive(BrushMorphology.FAN)
        val rake = outerSplitDrive(BrushMorphology.RAKE)
        val round = outerSplitDrive(BrushMorphology.ROUND)

        assertTrue(fan > flat)
        assertTrue(rake > flat)
        assertTrue(flat < round)
    }

    @Test
    fun resolvedContactsCarryMorphologySpecificGeometry() {
        val state = BrushMechanicalState(initialized = true, dragAngleDeg = 0f, bend = 0.6f)
        val contact = BrushContactState(splay = 0.4f, bend = 0.6f)
        val fan = BrushTuftTopology.resolve(state, contact, config(BrushMorphology.FAN))
        val filbert = BrushTuftTopology.resolve(state, contact, config(BrushMorphology.FILBERT))

        assertTrue(fan.all { it.morphology == BrushMorphology.FAN })
        assertTrue(filbert.all { it.morphology == BrushMorphology.FILBERT })
        assertTrue(fan.first().angleOffsetDeg < 0f)
        assertTrue(fan.last().angleOffsetDeg > 0f)
        assertTrue(filbert[filbert.lastIndex / 2].offsetXFraction > filbert.first().offsetXFraction)
    }
}
