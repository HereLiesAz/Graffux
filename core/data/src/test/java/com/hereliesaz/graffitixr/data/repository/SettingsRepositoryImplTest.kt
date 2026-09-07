package com.hereliesaz.graffitixr.data.repository

import com.hereliesaz.graffitixr.common.model.AppLanguage
import com.hereliesaz.graffitixr.common.model.ArScanMode
import com.hereliesaz.graffitixr.common.model.GestureAction
import com.hereliesaz.graffitixr.common.model.GestureSlot
import com.hereliesaz.graffitixr.common.model.Tool
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Exercises [SettingsRepositoryImpl] against a real (Robolectric-backed) Preferences DataStore --
 * not a mock -- so these tests actually fail if the encode/decode, coercion, or default-value logic
 * in the implementation regresses.
 *
 * The `settings` DataStore is a process-wide singleton (a private top-level `by preferencesDataStore`
 * delegate in the impl file), so its state persists across test methods in this run. Each test below
 * either exercises a key no other test in this file touches (safe to assert a fresh default on), or
 * only asserts a round trip on a value it itself just wrote (order-independent either way).
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryImplTest {

    private val repo = SettingsRepositoryImpl(RuntimeEnvironment.getApplication())

    @Test
    fun `setLanguage persists and is read back through the language flow`() = runTest {
        repo.setLanguage(AppLanguage.FRENCH)
        assertEquals(AppLanguage.FRENCH, repo.language.first())

        repo.setLanguage(AppLanguage.SYSTEM)
        assertEquals(AppLanguage.SYSTEM, repo.language.first())
    }

    @Test
    fun `setRightHanded persists false then true`() = runTest {
        repo.setRightHanded(false)
        assertFalse(repo.isRightHanded.first())

        repo.setRightHanded(true)
        assertTrue(repo.isRightHanded.first())
    }

    @Test
    fun `setArScanMode and setMuralMethod round trip`() = runTest {
        repo.setArScanMode(ArScanMode.CLOUD_POINTS)
        assertEquals(ArScanMode.CLOUD_POINTS, repo.arScanMode.first())

        repo.setMuralMethod(com.hereliesaz.graffitixr.common.model.MuralMethod.SURFACE_MESH)
        assertEquals(
            com.hereliesaz.graffitixr.common.model.MuralMethod.SURFACE_MESH,
            repo.muralMethod.first(),
        )
    }

    @Test
    fun `savedPalette round trips through the real PaletteCodec`() = runTest {
        val palette = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        repo.setSavedPalette(palette)
        assertEquals(palette, repo.savedPalette.first())

        repo.setSavedPalette(emptyList())
        assertEquals(emptyList<Int>(), repo.savedPalette.first())
    }

    @Test
    fun `gestureMapping falls back to each slot's own default until explicitly set`() = runTest {
        val slot = GestureSlot.entries.first()
        val untouchedDefault = repo.gestureMapping.first()[slot]
        assertEquals(slot.defaultAction, untouchedDefault)

        val chosen = GestureAction.entries.first { it != slot.defaultAction }
        repo.setGestureAction(slot, chosen)
        assertEquals(chosen, repo.gestureMapping.first()[slot])

        // Other slots are unaffected by setting this one.
        val otherSlot = GestureSlot.entries.first { it != slot }
        assertEquals(otherSlot.defaultAction, repo.gestureMapping.first()[otherSlot])
    }

    @Test
    fun `recordToolUse updates toolUsage but Tool NONE is a no-op`() = runTest {
        val before = repo.toolUsage.first()
        repo.recordToolUse(Tool.NONE, atMs = 1_000L)
        assertEquals(before, repo.toolUsage.first())

        repo.recordToolUse(Tool.BRUSH, atMs = 5_000L)
        val after = repo.toolUsage.first()
        assertTrue(after != before)
    }

    @Test
    fun `toggleFavoriteTool pins in order, then unpins, preserving remaining order`() = runTest {
        repo.toggleFavoriteTool(Tool.BRUSH)
        repo.toggleFavoriteTool(Tool.ERASER)
        assertEquals(listOf(Tool.BRUSH.name, Tool.ERASER.name), repo.favoriteTools.first())

        // Unpin the first; the second keeps its position rather than the list resorting.
        repo.toggleFavoriteTool(Tool.BRUSH)
        assertEquals(listOf(Tool.ERASER.name), repo.favoriteTools.first())

        // Re-pinning appends at the end, not back at the front.
        repo.toggleFavoriteTool(Tool.BRUSH)
        assertEquals(listOf(Tool.ERASER.name, Tool.BRUSH.name), repo.favoriteTools.first())

        // Tool.NONE is a no-op, same as recordToolUse.
        val before = repo.favoriteTools.first()
        repo.toggleFavoriteTool(Tool.NONE)
        assertEquals(before, repo.favoriteTools.first())
    }

    @Test
    fun `setCanvasRenderScale coerces out-of-range values into 0point25 to 1`() = runTest {
        repo.setCanvasRenderScale(5f)
        assertEquals(1f, repo.canvasRenderScale.first())

        repo.setCanvasRenderScale(-1f)
        assertEquals(0.25f, repo.canvasRenderScale.first())

        repo.setCanvasRenderScale(0.5f)
        assertEquals(0.5f, repo.canvasRenderScale.first())
    }

    @Test
    fun `setInputSampleRateHz coerces out-of-range values into 0 to 240`() = runTest {
        repo.setInputSampleRateHz(1_000)
        assertEquals(240, repo.inputSampleRateHz.first())

        repo.setInputSampleRateHz(-50)
        assertEquals(0, repo.inputSampleRateHz.first())

        repo.setInputSampleRateHz(90)
        assertEquals(90, repo.inputSampleRateHz.first())
    }

    @Test
    fun `unset boolean and numeric preferences read back their documented defaults`() = runTest {
        // These keys are not touched by any other test in this file, so on first access in this
        // run they still reflect the implementation's default fallback rather than a stored value.
        assertFalse(repo.isImperialUnits.first())
        assertEquals(0xFF000000.toInt(), repo.backgroundColor.first())
        assertEquals(4.0f, repo.parallaxMinDegrees.first())
        assertEquals(60, repo.cameraTargetFps.first())
        assertTrue(repo.throttleOnLag.first())
        assertEquals(-1, repo.stereoCapability.first())
    }

    @Test
    fun `setBrushTipHidden adds and removes ids independently`() = runTest {
        repo.setBrushTipHidden("ext::0", hidden = true)
        repo.setBrushTipHidden("ext::1", hidden = true)
        assertEquals(setOf("ext::0", "ext::1"), repo.hiddenBrushTipIds.first())

        repo.setBrushTipHidden("ext::0", hidden = false)
        assertEquals(setOf("ext::1"), repo.hiddenBrushTipIds.first())
    }
}
