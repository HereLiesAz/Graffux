package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ImpastoV2Test {

    private fun dab(
        x: Float = 8f,
        y: Float = 8f,
        radius: Float = 4f,
        contactDepth: Float = 1f,
    ) = Dab(
        x = x,
        y = y,
        radius = radius,
        alpha = 1f,
        flowMultiplier = 1f,
        contactDepth = contactDepth,
    )

    @Test
    fun `material transfer deposits height from reservoir load and depletes subsequent supply`() {
        val w = 16
        val h = 16
        val height = FloatArray(w * h)
        val medium = PaintMedium(
            depositionRate = 1f,
            heightResponse = 1f,
        )

        val result = ImpastoEngine.transferMaterialStroke(
            height = height,
            width = w,
            imgHeight = h,
            dabs = listOf(dab(), dab(x = 10f)),
            hardness = 1f,
            thicknessRate = 0.5f,
            medium = medium,
        )

        assertTrue(result.depositedHeight > 0f)
        assertTrue(result.state.reservoirLoad < 1f)
        assertTrue(height.any { it > 0f })
        assertTrue(result.dirtyRegion != null)
    }

    @Test
    fun `zero contact depth neither deposits nor picks up height`() {
        val w = 16
        val h = 16
        val height = FloatArray(w * h) { 0.4f }
        val before = height.copyOf()
        val medium = PaintMedium(
            pickupRate = 1f,
            depositionRate = 1f,
            heightResponse = 1f,
        )

        val result = ImpastoEngine.transferMaterialStroke(
            height, w, h, listOf(dab(contactDepth = 0f)),
            hardness = 1f,
            thicknessRate = 0.5f,
            medium = medium,
            initialState = ImpastoMaterialStrokeState(reservoirLoad = 0.5f),
        )

        assertArrayEquals(before, height, 0f)
        assertEquals(0f, result.depositedHeight, 0f)
        assertEquals(0f, result.pickedUpHeight, 0f)
        assertEquals(0.5f, result.state.reservoirLoad, 0f)
    }

    @Test
    fun `pickup removes existing height only when reservoir has free capacity`() {
        val w = 16
        val h = 16
        val medium = PaintMedium(
            pickupRate = 1f,
            depositionRate = 0f,
            heightResponse = 0f,
        )

        val fullHeight = FloatArray(w * h) { 0.5f }
        val full = ImpastoEngine.transferMaterialStroke(
            fullHeight, w, h, listOf(dab()),
            hardness = 1f,
            thicknessRate = 0.5f,
            medium = medium,
            initialState = ImpastoMaterialStrokeState(reservoirLoad = 1f),
        )
        assertEquals(0f, full.pickedUpHeight, 0f)

        val halfHeight = FloatArray(w * h) { 0.5f }
        val half = ImpastoEngine.transferMaterialStroke(
            halfHeight, w, h, listOf(dab()),
            hardness = 1f,
            thicknessRate = 0.5f,
            medium = medium,
            initialState = ImpastoMaterialStrokeState(reservoirLoad = 0.5f),
        )
        assertTrue(half.pickedUpHeight > 0f)
        assertTrue(halfHeight.any { it < 0.5f })
        assertTrue(half.state.reservoirLoad > 0.5f)
    }

    @Test
    fun `full reservoir can pick up capacity freed by the same dab`() {
        val w = 16
        val h = 16
        val height = FloatArray(w * h) { 0.5f }
        val medium = PaintMedium(
            pickupRate = 1f,
            depositionRate = 1f,
            heightResponse = 1f,
        )

        val result = ImpastoEngine.transferMaterialStroke(
            height = height,
            width = w,
            imgHeight = h,
            dabs = listOf(dab()),
            hardness = 1f,
            thicknessRate = 0.5f,
            medium = medium,
            initialState = ImpastoMaterialStrokeState(reservoirLoad = 1f),
        )

        assertTrue("the dab must spend some load first", result.depositedHeight > 0f)
        assertTrue(
            "pickup must be able to use capacity freed by deposition in this same dab",
            result.pickedUpHeight > 0f,
        )
        assertTrue(result.state.reservoirLoad in 0f..1f)
    }

    @Test
    fun `substrate response blocks shallow height deposition on a raised tooth`() {
        val w = 16
        val h = 16
        val blocked = FloatArray(w * h)
        val open = FloatArray(w * h)
        val tooth = SubstrateField(
            width = 1,
            height = 1,
            heightR8 = byteArrayOf(255.toByte()),
        )
        val profile = SubstrateProfile(baseHeight = 0f, heightScale = 1f)
        val medium = PaintMedium(
            depositionRate = 1f,
            heightResponse = 1f,
            substrateResponse = 1f,
        )

        ImpastoEngine.transferMaterialStroke(
            blocked, w, h, listOf(dab(contactDepth = 0.25f)),
            hardness = 1f,
            thicknessRate = 0.5f,
            medium = medium,
            substrateProfile = profile,
            substrateField = tooth,
        )
        ImpastoEngine.transferMaterialStroke(
            open, w, h, listOf(dab(contactDepth = 1f)),
            hardness = 1f,
            thicknessRate = 0.5f,
            medium = medium,
            substrateProfile = profile,
            substrateField = tooth,
        )

        assertTrue(blocked.all { it == 0f })
        assertTrue(open.any { it > 0f })
    }

    @Test
    fun `feather coverage scales height transfer instead of acting as a binary clip`() {
        val w = 16
        val h = 16
        val full = FloatArray(w * h)
        val feathered = FloatArray(w * h)
        val medium = PaintMedium(depositionRate = 1f, heightResponse = 1f)

        ImpastoEngine.transferMaterialStroke(
            full, w, h, listOf(dab()), 1f, 0.5f, medium,
            pixelCoverage = { _, _ -> 1f },
        )
        ImpastoEngine.transferMaterialStroke(
            feathered, w, h, listOf(dab()), 1f, 0.5f, medium,
            pixelCoverage = { _, _ -> 0.25f },
        )

        assertTrue(full.sum() > feathered.sum())
        assertTrue(feathered.sum() > 0f)
    }

    @Test
    fun `tile owned medium can freeze leveling even when incoming fallback is fluid`() {
        val w = 4
        val h = 1
        val height = floatArrayOf(0f, 1f, 0f, 0f)
        val before = height.copyOf()
        val wet = PersistentWetnessField(w, h, tileSize = 4)
        repeat(w) { wet.addWetness(it, 0, 1f) }
        val fallback = PaintMedium(levelingRate = 1f, viscosity = 0f)

        ImpastoEngine.levelWetHeight(
            height, w, h, wet, fallback, deltaSeconds = 1f,
            mediumAt = { _, _ -> PaintMedium(levelingRate = 1f, viscosity = 1f) },
        )

        assertArrayEquals(before, height, 0f)
    }

    @Test
    fun `tile owned optical medium controls wet specular independently per pixel`() {
        val w = 2
        val h = 1
        val height = FloatArray(2)
        val raw = intArrayOf(0xFF202020.toInt(), 0xFF202020.toInt())
        val wet = PersistentWetnessField(w, h, tileSize = 1)
        wet.addWetness(0, 0, 1f)
        wet.addWetness(1, 0, 1f)
        val out = IntArray(2)

        ImpastoEngine.shadeMaterialInto(
            out = out,
            rawColorPixels = raw,
            height = height,
            wetness = wet,
            width = w,
            imgHeight = h,
            left = 0,
            top = 0,
            right = w,
            bottom = h,
            lightAzimuthDeg = 315f,
            lightElevationDeg = 45f,
            reliefStrength = 0f,
            medium = PaintMedium(),
            mediumAt = { x, _ ->
                if (x == 0) PaintMedium(wetSpecularStrength = 0f)
                else PaintMedium(baseRoughness = 0f, wetSpecularStrength = 1f)
            },
        )

        assertEquals(raw[0], out[0])
        assertTrue((out[1] and 0x00FFFFFF) > (raw[1] and 0x00FFFFFF))
    }

    @Test
    fun `wet leveling conserves height and reduces a sharp ridge`() {
        val w = 8
        val h = 1
        val height = FloatArray(w)
        height[3] = 1f
        val beforeMass = height.sum()
        val wetness = PersistentWetnessField(w, h, tileSize = 4)
        repeat(w) { x -> wetness.addWetness(x, 0, 1f) }
        val medium = PaintMedium(
            viscosity = 0f,
            yieldLikeStrength = 0f,
            levelingRate = 1f,
        )

        val stats = ImpastoEngine.levelWetHeight(
            height, w, h, wetness, medium, deltaSeconds = 0.5f,
        )

        assertTrue(stats.edgesMoved > 0)
        assertTrue(stats.transferredHeight > 0f)
        assertEquals(beforeMass, height.sum(), 1e-5f)
        assertTrue(height[3] < 1f)
        assertTrue(height[2] > 0f || height[4] > 0f)
    }

    @Test
    fun `dry recovered yield freezes the same ridge that moves while wet`() {
        val w = 8
        val h = 1
        val wetHeight = FloatArray(w).also { it[3] = 0.12f }
        val dryHeight = wetHeight.copyOf()
        val wet = PersistentWetnessField(w, h, tileSize = 4)
        val dry = PersistentWetnessField(w, h, tileSize = 4)
        repeat(w) { x ->
            wet.addWetness(x, 0, 1f)
            dry.activate(DirtyRegion(x, 0, x + 1, 1))
        }
        val medium = PaintMedium(
            viscosity = 0f,
            yieldLikeStrength = 1f,
            levelingRate = 1f,
        )

        ImpastoEngine.levelWetHeight(wetHeight, w, h, wet, medium, 0.5f)
        ImpastoEngine.levelWetHeight(dryHeight, w, h, dry, medium, 0.5f)

        assertTrue(wetHeight[3] < 0.12f)
        assertArrayEquals(FloatArray(w).also { it[3] = 0.12f }, dryHeight, 0f)
    }

    @Test
    fun `substrate surface sends wet height toward a lower valley`() {
        val w = 2
        val h = 1
        val height = floatArrayOf(0.4f, 0.4f)
        val wetness = PersistentWetnessField(w, h, tileSize = 2)
        wetness.addWetness(0, 0, 1f)
        wetness.addWetness(1, 0, 1f)
        val field = SubstrateField(
            width = 2,
            height = 1,
            heightR8 = byteArrayOf(255.toByte(), 0),
        )
        val medium = PaintMedium(
            levelingRate = 1f,
            substrateResponse = 1f,
        )

        ImpastoEngine.levelWetHeight(
            height, w, h, wetness, medium, 0.5f,
            substrateProfile = SubstrateProfile(heightScale = 1f),
            substrateField = field,
            iterations = 1,
        )

        assertTrue("paint should leave the raised tooth", height[0] < 0.4f)
        assertTrue("paint should collect in the lower valley", height[1] > 0.4f)
        assertEquals(0.8f, height.sum(), 1e-5f)
    }

    @Test
    fun `v2 material shading preserves dry legacy result when wet specular is disabled`() {
        val w = 8
        val h = 8
        val height = FloatArray(w * h)
        height[4 * w + 4] = 1f
        val colors = IntArray(w * h) { 0xFF606060.toInt() }
        val expected = ImpastoEngine.shade(colors, height, w, h, 315f, 45f, 0.6f)
        val actual = IntArray(w * h)

        ImpastoEngine.shadeMaterialInto(
            out = actual,
            rawColorPixels = colors,
            height = height,
            wetness = null,
            width = w,
            imgHeight = h,
            left = 0,
            top = 0,
            right = w,
            bottom = h,
            lightAzimuthDeg = 315f,
            lightElevationDeg = 45f,
            reliefStrength = 0.6f,
            medium = PaintMedium(),
        )

        assertArrayEquals(expected, actual)
    }

    @Test
    fun `wetness adds glossy response without mutating canonical color or height`() {
        val w = 8
        val h = 8
        val height = FloatArray(w * h) { 0.4f }
        val heightBefore = height.copyOf()
        val colors = IntArray(w * h) { 0xFF303030.toInt() }
        val colorsBefore = colors.copyOf()
        val wet = PersistentWetnessField(w, h, tileSize = 4)
        wet.addWetness(4, 4, 1f)

        val dryOut = IntArray(w * h)
        val wetOut = IntArray(w * h)
        val medium = PaintMedium(
            baseRoughness = 0.8f,
            wetSpecularStrength = 1f,
        )
        ImpastoEngine.shadeMaterialInto(
            dryOut, colors, height, null, w, h, 0, 0, w, h,
            315f, 45f, 0.6f, medium,
        )
        ImpastoEngine.shadeMaterialInto(
            wetOut, colors, height, wet, w, h, 0, 0, w, h,
            315f, 45f, 0.6f, medium,
        )

        assertNotEquals(dryOut[4 * w + 4], wetOut[4 * w + 4])
        val dryRgb = dryOut[4 * w + 4] and 0x00FFFFFF
        val wetRgb = wetOut[4 * w + 4] and 0x00FFFFFF
        assertTrue(wetRgb > dryRgb)
        assertArrayEquals(colorsBefore, colors)
        assertArrayEquals(heightBefore, height, 0f)
    }

    @Test
    fun `paint medium sanitizes new Impasto coefficients and legacy default stays material-free`() {
        val raw = PaintMedium(
            levelingRate = 2f,
            baseRoughness = -1f,
            wetSpecularStrength = 3f,
        )
        val sanitized = raw.sanitized()

        assertEquals(1f, sanitized.levelingRate, 0f)
        assertEquals(0f, sanitized.baseRoughness, 0f)
        assertEquals(1f, sanitized.wetSpecularStrength, 0f)
        assertTrue(raw.usesMaterialPath)
        assertTrue(!PaintMedium().usesMaterialPath)
    }
}
