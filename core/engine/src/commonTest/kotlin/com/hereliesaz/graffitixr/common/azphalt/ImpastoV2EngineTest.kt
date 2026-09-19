package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImpastoV2EngineTest {
    @Test
    fun legacyDisabledConfigDoesNotChangeHeight() {
        val w = 24
        val h = 24
        val height = FloatArray(w * h)
        val structure = FloatArray(w * h) { 1f }
        val wetness = PersistentWetnessField(w, h)
        val dab = Dab(12f, 12f, 5f, 1f, contactDepth = 1f)

        val stats = ImpastoV2Engine.applyContactStroke(
            height, structure, w, h, listOf(dab), 1f, thicknessRate = 0f,
            config = ImpastoV2Config(), wetness = wetness,
        )

        assertEquals(0f, stats.depositedHeight)
        assertEquals(0f, stats.pickedUpHeight)
        assertTrue(height.all { it == 0f })
        assertTrue(wetness.isIdle)
    }

    @Test
    fun contactDepositsWetVolumeAndSoftensStructureDeterministically() {
        val w = 24
        val h = 24
        fun run(): Triple<FloatArray, FloatArray, FloatArray> {
            val height = FloatArray(w * h)
            val structure = FloatArray(w * h) { 1f }
            val wetness = PersistentWetnessField(w, h)
            ImpastoV2Engine.applyContactStroke(
                height, structure, w, h,
                listOf(Dab(12f, 12f, 5f, 1f, contactDepth = 0.8f)),
                hardness = 1f,
                thicknessRate = 0.5f,
                config = ImpastoV2Config(wetnessDeposit = 0.7f, body = 0.6f),
                wetness = wetness,
            )
            return Triple(height, structure, wetness.snapshot())
        }

        val a = run()
        val b = run()
        assertTrue(a.first.contentEquals(b.first))
        assertTrue(a.second.contentEquals(b.second))
        assertTrue(a.third.contentEquals(b.third))
        val centre = 12 * w + 12
        assertTrue(a.first[centre] > 0f)
        assertTrue(a.second[centre] < 1f)
        assertTrue(a.third[centre] > 0f)
    }

    @Test
    fun wetContactCanPickUpExistingHeight() {
        val w = 16
        val h = 16
        val height = FloatArray(w * h)
        val structure = FloatArray(w * h) { 1f }
        val wetness = PersistentWetnessField(w, h)
        val centre = 8 * w + 8
        height[centre] = 0.8f
        wetness.addWetness(8, 8, 1f)

        val stats = ImpastoV2Engine.applyContactStroke(
            height, structure, w, h,
            listOf(Dab(8f, 8f, 3f, 1f, contactDepth = 1f)),
            hardness = 1f,
            thicknessRate = 0f,
            config = ImpastoV2Config(pickupRate = 1f),
            wetness = wetness,
        )

        assertTrue(stats.pickedUpHeight > 0f)
        assertTrue(height[centre] < 0.8f)
    }

    @Test
    fun levelingConservesHeightAndStaysInsideActiveTiles() {
        val w = 8
        val h = 1
        val height = FloatArray(w)
        height[2] = 1f
        height[5] = 0.8f
        val structure = FloatArray(w) { 0f }
        val wetness = PersistentWetnessField(w, h, tileSize = 4)
        repeat(4) { wetness.addWetness(it, 0, 1f) } // only left tile active
        val workspace = ImpastoV2Workspace(w, h)

        val before = height.sum()
        val rightBefore = height.copyOfRange(4, 8)
        val stats = ImpastoV2Engine.advanceWetHeight(
            height, structure, w, h, wetness, workspace,
            deltaSeconds = 1f,
            config = ImpastoV2Config(levelingRate = 1f, body = 0f),
            iterations = 2,
        )

        assertEquals(before, height.sum(), 1e-5f)
        assertTrue(rightBefore.contentEquals(height.copyOfRange(4, 8)))
        assertEquals(1, stats.activeTilesProcessed)
        assertTrue(height[1] > 0f || height[3] > 0f)
    }

    @Test
    fun recoveredBodyFreezesSmallHeightGradientUntilSheared() {
        val w = 4
        val h = 1
        val wetness = PersistentWetnessField(w, h, tileSize = 4)
        repeat(w) { wetness.addWetness(it, 0, 1f) }
        val workspace = ImpastoV2Workspace(w, h)

        val frozen = floatArrayOf(0.50f, 0.51f, 0.50f, 0.50f)
        val stiff = FloatArray(w) { 1f }
        ImpastoV2Engine.advanceWetHeight(
            frozen, stiff, w, h, wetness, workspace, 1f,
            ImpastoV2Config(levelingRate = 1f, body = 1f), iterations = 2,
        )
        assertEquals(0.51f, frozen[1], 1e-6f)

        val mobile = floatArrayOf(0.50f, 0.51f, 0.50f, 0.50f)
        val sheared = FloatArray(w) { 0f }
        ImpastoV2Engine.advanceWetHeight(
            mobile, sheared, w, h, wetness, workspace, 1f,
            ImpastoV2Config(levelingRate = 1f, body = 0f), iterations = 2,
        )
        assertTrue(mobile[1] < 0.51f)
    }

    @Test
    fun roughAbsorbentSubstrateReducesLevelingMobility() {
        val w = 4
        val h = 1
        fun run(interaction: Float): FloatArray {
            val height = floatArrayOf(0f, 1f, 0f, 0f)
            val structure = FloatArray(w) { 0f }
            val wetness = PersistentWetnessField(w, h, tileSize = 4)
            repeat(w) { wetness.addWetness(it, 0, 1f) }
            val field = SubstrateField(
                width = 1, height = 1,
                heightR8 = byteArrayOf(0xFF.toByte()),
                absorbencyR8 = byteArrayOf(0xFF.toByte()),
            )
            ImpastoV2Engine.advanceWetHeight(
                height, structure, w, h, wetness, ImpastoV2Workspace(w, h), 1f,
                ImpastoV2Config(levelingRate = 1f, body = 0f, substrateInteraction = interaction),
                substrateProfile = SubstrateProfile(heightScale = 1f, absorbency = 1f),
                substrateField = field,
                iterations = 1,
            )
            return height
        }

        val smooth = run(0f)
        val rough = run(1f)
        assertTrue(smooth[1] < rough[1], "substrate resistance should retain more of the peak")
    }
}
