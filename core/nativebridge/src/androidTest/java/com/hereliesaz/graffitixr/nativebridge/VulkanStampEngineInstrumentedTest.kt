package com.hereliesaz.graffitixr.nativebridge

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VulkanStampEngineInstrumentedTest {

    private val engines = mutableListOf<VulkanStampEngine>()

    @After
    fun tearDown() {
        engines.forEach { it.destroy() }
        engines.clear()
        VulkanStampEngine.trimPool()
    }

    private fun engine(): VulkanStampEngine = VulkanStampEngine().also { engines += it }

    private fun initializedEngine(width: Int = SIZE, height: Int = SIZE): VulkanStampEngine {
        val engine = engine()
        assumeTrue("Device does not expose a usable Vulkan compute path", engine.init(width, height))
        return engine
    }

    @Test
    fun uninitializedEngineRejectsOperationsAndDestroyIsIdempotent() {
        val engine = engine()
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)

        assertFalse(engine.isInitialized)
        assertFalse(engine.upload(bitmap))
        assertFalse(engine.readback(bitmap))
        assertFalse(engine.stampDabs(listOf(DAB), COLOR_RED, 1f))
        assertFalse(engine.stampDabs(emptyList(), COLOR_RED, 1f))

        engine.destroy()
        engine.destroy()
        assertFalse(engine.isInitialized)
    }

    @Test
    fun initAndDestroyTrackLifecycle() {
        val engine = initializedEngine()
        assertTrue(engine.isInitialized)

        engine.destroy()

        assertFalse(engine.isInitialized)
        assertFalse(engine.stampDabs(listOf(DAB), COLOR_RED, 1f))
    }

    @Test
    fun uploadThenReadbackPreservesPixels() {
        val engine = initializedEngine()
        val input = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        input.eraseColor(COLOR_BLUE)
        input.setPixel(3, 5, COLOR_GREEN)
        input.setPixel(SIZE - 4, SIZE - 6, COLOR_RED)

        assertTrue(engine.upload(input))

        val output = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        assertTrue(engine.readback(output))

        assertEquals(COLOR_BLUE, output.getPixel(0, 0))
        assertEquals(COLOR_GREEN, output.getPixel(3, 5))
        assertEquals(COLOR_RED, output.getPixel(SIZE - 4, SIZE - 6))
    }

    @Test
    fun stampDabsActuallyChangesTargetPixels() {
        val engine = initializedEngine()
        val blank = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        blank.eraseColor(0x00000000)
        assertTrue(engine.upload(blank))

        assertTrue(engine.stampDabs(listOf(DAB), COLOR_RED, 1f))

        val output = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        assertTrue(engine.readback(output))

        val center = output.getPixel(SIZE / 2, SIZE / 2)
        val corner = output.getPixel(0, 0)
        assertNotEquals("The dab did not affect its center pixel", 0x00000000, center)
        assertEquals("A distant pixel changed outside the dab", 0x00000000, corner)
    }

    @Test
    fun secondMaskWithSameDimensionsButDifferentContentIsNotStale() {
        // A glee audit found stampMaskedDabs()'s mask/grain/secondary-mask staleness checks used
        // only width/height, not content -- so a pooled engine reused across strokes/brushes could
        // silently keep stamping with a previous tip's texture whenever the new one happened to
        // share dimensions (common: built-in tips and grain tiles are normalized to a handful of
        // standard sizes). This pins the fix: a same-size mask with genuinely different content
        // must actually re-upload and take effect.
        val engine = initializedEngine()
        val blank = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        blank.eraseColor(0x00000000)
        assertTrue(engine.upload(blank))

        val maskSize = 8
        val opaqueMask = ByteArray(maskSize * maskSize) { 0xFF.toByte() }
        val firstDab = MaskedBrushDab(
            x = 16f, y = 16f, radius = 10f, alpha = 1f, angleDeg = 0f,
            colorArgb = COLOR_RED, flow = 1f, tipRatio = 1f,
        )
        assertTrue(
            engine.stampMaskedDabs(
                listOf(firstDab), hardness = 1f, maskAlpha8 = opaqueMask, maskWidth = maskSize, maskHeight = maskSize,
            ),
        )

        val afterFirst = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        assertTrue(engine.readback(afterFirst))
        assertNotEquals("The opaque mask should have painted its dab", 0x00000000, afterFirst.getPixel(16, 16))

        // Same dimensions (8x8) as the first mask, but fully transparent content -- a staleness
        // check that only compares width/height would wrongly reuse the first (opaque) mask
        // texture here and paint this dab anyway.
        val transparentMask = ByteArray(maskSize * maskSize) { 0 }
        val secondDab = MaskedBrushDab(
            x = 48f, y = 48f, radius = 10f, alpha = 1f, angleDeg = 0f,
            colorArgb = COLOR_GREEN, flow = 1f, tipRatio = 1f,
        )
        assertTrue(
            engine.stampMaskedDabs(
                listOf(secondDab), hardness = 1f, maskAlpha8 = transparentMask, maskWidth = maskSize, maskHeight = maskSize,
            ),
        )

        val afterSecond = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        assertTrue(engine.readback(afterSecond))
        assertEquals(
            "A fully-transparent mask must not paint anything, even when it shares dimensions " +
                "with the previously-uploaded mask",
            0x00000000,
            afterSecond.getPixel(48, 48),
        )
    }

    @Test
    fun separateEngineInstancesDoNotShareNativeState() {
        val first = initializedEngine()
        val second = initializedEngine()

        val redSeed = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(COLOR_RED) }
        val blueSeed = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(COLOR_BLUE) }
        assertTrue(first.upload(redSeed))
        assertTrue(second.upload(blueSeed))

        first.destroy()

        val secondOutput = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        assertTrue("Destroying one engine invalidated another engine's handle", second.readback(secondOutput))
        assertEquals(COLOR_BLUE, secondOutput.getPixel(SIZE / 2, SIZE / 2))

        assertTrue(second.stampDabs(listOf(DAB), COLOR_GREEN, 1f))
        val afterStamp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        assertTrue(second.readback(afterStamp))
        assertNotEquals(COLOR_BLUE, afterStamp.getPixel(SIZE / 2, SIZE / 2))
    }

    @Test
    fun reinitReplacesOldNativeHandleWithoutBreakingEngine() {
        val engine = initializedEngine()
        assertTrue(engine.init(SIZE / 2, SIZE / 2))
        assertTrue(engine.isInitialized)

        val bitmap = Bitmap.createBitmap(SIZE / 2, SIZE / 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(COLOR_GREEN)
        assertTrue(engine.upload(bitmap))

        val output = Bitmap.createBitmap(SIZE / 2, SIZE / 2, Bitmap.Config.ARGB_8888)
        assertTrue(engine.readback(output))
        assertEquals(COLOR_GREEN, output.getPixel(1, 1))
    }

    @Test
    fun destroyThenSameSizeInitReusesNativeEngineAndClearsLayer() {
        VulkanStampEngine.trimPool()
        val before = VulkanStampEngine.nativeCreationCountForTesting()

        val first = initializedEngine()
        val afterFirstInit = VulkanStampEngine.nativeCreationCountForTesting()
        assertTrue("The first init did not create a native engine", afterFirstInit > before)

        val red = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(COLOR_RED) }
        assertTrue(first.upload(red))
        first.destroy()

        val second = engine()
        assertTrue(second.init(SIZE, SIZE))
        assertEquals(
            "Same-size engine recreation reached native Vulkan init instead of the reuse pool",
            afterFirstInit,
            VulkanStampEngine.nativeCreationCountForTesting(),
        )

        val cleared = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        assertTrue(second.readback(cleared))
        assertEquals("Pooled layer leaked pixels from its previous owner", 0x00000000, cleared.getPixel(SIZE / 2, SIZE / 2))
    }

    @Test
    fun hardwareBufferBackedEngineIsAlsoReusedWhenSupported() {
        VulkanStampEngine.trimPool()
        val first = engine()
        assumeTrue(
            "Device does not support the AHardwareBuffer-backed Vulkan path",
            first.initHardwareBufferBacked(SIZE, SIZE),
        )
        val afterFirstInit = VulkanStampEngine.nativeCreationCountForTesting()
        first.destroy()

        val second = engine()
        assertTrue(second.initHardwareBufferBacked(SIZE, SIZE))
        assertEquals(
            "AHardwareBuffer-backed engine was recreated instead of reused",
            afterFirstInit,
            VulkanStampEngine.nativeCreationCountForTesting(),
        )
    }


    @Test
    fun substrateHeightGatesResolvedAndMaskedDabsAndRefreshesSameSizeTileContent() {
        val engine = initializedEngine()
        val blank = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(0x00000000) }
        val substrate = VulkanSubstrateParams(heightScale = 1f, textureScale = 1f)
        val shallow = ResolvedBrushDab(
            x = SIZE / 2f, y = SIZE / 2f, radius = 8f, alpha = 1f, angleDeg = 0f,
            colorArgb = COLOR_RED, flow = 1f, hardness = 1f,
            contactDepth = 0.2f, reservoirLoad = 1f, depositionRate = 1f, substrateResponse = 1f,
        )

        assertTrue(engine.upload(blank))
        assertTrue(engine.uploadSubstrateHeight(byteArrayOf(0xFF.toByte()), 1, 1))
        assertTrue(engine.stampResolvedDabs(listOf(shallow), substrate = substrate))
        val blocked = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        assertTrue(engine.readback(blocked))
        assertEquals("full-height tooth should block shallow resolved contact", 0x00000000, blocked.getPixel(SIZE / 2, SIZE / 2))

        assertTrue(engine.upload(blank))
        val deep = shallow.copy(contactDepth = 1f)
        assertTrue(engine.stampResolvedDabs(listOf(deep), substrate = substrate))
        val penetrated = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        assertTrue(engine.readback(penetrated))
        assertNotEquals("full contact should penetrate full-height tooth", 0x00000000, penetrated.getPixel(SIZE / 2, SIZE / 2))

        // Same dimensions as the previous tile, different bytes: verifies content hashing prevents
        // pooled/static texture staleness, not merely dimension-based reuse.
        assertTrue(engine.upload(blank))
        assertTrue(engine.uploadSubstrateHeight(byteArrayOf(0), 1, 1))
        assertTrue(engine.stampResolvedDabs(listOf(shallow), substrate = substrate))
        val refreshed = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        assertTrue(engine.readback(refreshed))
        assertNotEquals("same-size lower tooth tile was not refreshed", 0x00000000, refreshed.getPixel(SIZE / 2, SIZE / 2))

        // The independent masked pipeline must consume the exact same substrate tile/contract.
        assertTrue(engine.upload(blank))
        assertTrue(engine.uploadSubstrateHeight(byteArrayOf(0xFF.toByte()), 1, 1))
        val maskSize = 8
        val opaqueMask = ByteArray(maskSize * maskSize) { 0xFF.toByte() }
        val masked = MaskedBrushDab(
            x = SIZE / 2f, y = SIZE / 2f, radius = 8f, alpha = 1f, angleDeg = 0f,
            colorArgb = COLOR_GREEN, flow = 1f, tipRatio = 1f,
            contactDepth = 0.2f, reservoirLoad = 1f, depositionRate = 1f, substrateResponse = 1f,
        )
        assertTrue(
            engine.stampMaskedDabs(
                listOf(masked), hardness = 1f, maskAlpha8 = opaqueMask,
                maskWidth = maskSize, maskHeight = maskSize, substrate = substrate,
            ),
        )
        val maskedBlocked = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        assertTrue(engine.readback(maskedBlocked))
        assertEquals("masked shader ignored substrate gate", 0x00000000, maskedBlocked.getPixel(SIZE / 2, SIZE / 2))
    }

    @Test
    fun existingPaintHeightLowersSubstrateBarrierForResolvedAndMaskedDabs() {
        val engine = initializedEngine()
        val blank = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(0x00000000) }
        val substrate = VulkanSubstrateParams(heightScale = 1f, textureScale = 1f)
        val shallow = ResolvedBrushDab(
            x = SIZE / 2f, y = SIZE / 2f, radius = 8f, alpha = 1f, angleDeg = 0f,
            colorArgb = COLOR_RED, flow = 1f, hardness = 1f,
            contactDepth = 0.2f, reservoirLoad = 1f, depositionRate = 1f, substrateResponse = 1f,
        )
        assertTrue(engine.uploadSubstrateHeight(byteArrayOf(0xFF.toByte()), 1, 1))

        val filledValley = FloatArray(SIZE * SIZE)
        filledValley[(SIZE / 2) * SIZE + SIZE / 2] = 0.9f
        assertTrue(engine.uploadPaintHeight(filledValley, SIZE, SIZE))
        assertTrue(engine.upload(blank))
        assertTrue(engine.stampResolvedDabs(listOf(shallow), substrate = substrate))
        val resolved = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        assertTrue(engine.readback(resolved))
        assertNotEquals(
            "existing paint height did not lower the full-tooth barrier for resolved dabs",
            0x00000000, resolved.getPixel(SIZE / 2, SIZE / 2),
        )

        val maskSize = 8
        val opaqueMask = ByteArray(maskSize * maskSize) { 0xFF.toByte() }
        val masked = MaskedBrushDab(
            x = SIZE / 2f, y = SIZE / 2f, radius = 8f, alpha = 1f, angleDeg = 0f,
            colorArgb = COLOR_GREEN, flow = 1f, tipRatio = 1f,
            contactDepth = 0.2f, reservoirLoad = 1f, depositionRate = 1f, substrateResponse = 1f,
        )
        assertTrue(engine.upload(blank))
        assertTrue(
            engine.stampMaskedDabs(
                listOf(masked), hardness = 1f, maskAlpha8 = opaqueMask,
                maskWidth = maskSize, maskHeight = maskSize, substrate = substrate,
            ),
        )
        val maskedResult = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        assertTrue(engine.readback(maskedResult))
        assertNotEquals(
            "masked shader did not consume the existing paint-height barrier reduction",
            0x00000000, maskedResult.getPixel(SIZE / 2, SIZE / 2),
        )

        // Same canvas dimensions, changed FloatArray contents: the GPU mirror must refresh.
        assertTrue(engine.uploadPaintHeight(FloatArray(SIZE * SIZE), SIZE, SIZE))
        assertTrue(engine.upload(blank))
        assertTrue(engine.stampResolvedDabs(listOf(shallow), substrate = substrate))
        val refreshed = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        assertTrue(engine.readback(refreshed))
        assertEquals(
            "same-size paint-height content refresh left stale valley fill on the GPU",
            0x00000000, refreshed.getPixel(SIZE / 2, SIZE / 2),
        )
    }

    @Test
    fun pooledWrapperDoesNotReusePreviousOwnersPaintHeight() {
        VulkanStampEngine.trimPool()
        val first = initializedEngine()
        val filled = FloatArray(SIZE * SIZE) { 1f }
        assertTrue(first.uploadPaintHeight(filled, SIZE, SIZE))
        first.destroy()

        val second = engine()
        assertTrue(second.init(SIZE, SIZE))
        val blank = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(0x00000000) }
        assertTrue(second.upload(blank))
        assertTrue(second.uploadSubstrateHeight(byteArrayOf(0xFF.toByte()), 1, 1))
        val shallow = ResolvedBrushDab(
            x = SIZE / 2f, y = SIZE / 2f, radius = 8f, alpha = 1f, angleDeg = 0f,
            colorArgb = COLOR_RED, flow = 1f, hardness = 1f,
            contactDepth = 0.2f, reservoirLoad = 1f, depositionRate = 1f, substrateResponse = 1f,
        )
        assertTrue(second.stampResolvedDabs(listOf(shallow), substrate = VulkanSubstrateParams(heightScale = 1f)))
        val result = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        assertTrue(second.readback(result))
        assertEquals(
            "new wrapper sampled the previous owner's paint-height mirror",
            0x00000000, result.getPixel(SIZE / 2, SIZE / 2),
        )
    }

    @Test
    fun pooledWrapperRequiresFreshSubstrateUploadBeforeEnablingIt() {
        VulkanStampEngine.trimPool()
        val first = initializedEngine()
        assertTrue(first.uploadSubstrateHeight(byteArrayOf(0xFF.toByte()), 1, 1))
        first.destroy()

        val second = engine()
        assertTrue(second.init(SIZE, SIZE))
        val dab = ResolvedBrushDab(
            x = SIZE / 2f, y = SIZE / 2f, radius = 8f, alpha = 1f, angleDeg = 0f,
            colorArgb = COLOR_RED, flow = 1f, hardness = 1f,
            contactDepth = 1f, substrateResponse = 1f,
        )
        assertFalse(
            "new wrapper silently reused the previous owner's substrate tile",
            second.stampResolvedDabs(listOf(dab), substrate = VulkanSubstrateParams(heightScale = 1f)),
        )
    }

    companion object {
        private const val SIZE = 64
        private const val COLOR_RED = 0xFFFF0000.toInt()
        private const val COLOR_GREEN = 0xFF00FF00.toInt()
        private const val COLOR_BLUE = 0xFF0000FF.toInt()
        private val DAB = BrushDab(
            x = SIZE / 2f,
            y = SIZE / 2f,
            radius = 10f,
            alpha = 1f,
            angleDeg = 0f,
        )
    }
}
