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
