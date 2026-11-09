package com.hereliesaz.graffitixr.data.repository

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.hereliesaz.graffitixr.common.model.GestureAction
import com.hereliesaz.graffitixr.common.model.GestureSlot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import androidx.compose.ui.graphics.toArgb
import com.hereliesaz.graffitixr.common.model.DEFAULT_CANVAS_BACKGROUND
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
    fun `setRightHanded persists false then true`() = runTest {
        repo.setRightHanded(false)
        assertFalse(repo.isRightHanded.first())

        repo.setRightHanded(true)
        assertTrue(repo.isRightHanded.first())
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
    fun `toggleCarouselFavorite keeps starring order, survives a new instance, and allows commas in keys`() = runTest {
        repo.toggleCarouselFavorite("ink.pen")
        repo.toggleCarouselFavorite("builtin.Soft, Round")
        repo.toggleCarouselFavorite("blur")
        assertEquals(listOf("ink.pen", "builtin.Soft, Round", "blur"), repo.carouselFavorites.first())

        repo.toggleCarouselFavorite("ink.pen")
        assertEquals(listOf("builtin.Soft, Round", "blur"), repo.carouselFavorites.first())

        // Persisted, not held in memory: a fresh repository (a relaunch) reads the same list back.
        val relaunched = SettingsRepositoryImpl(RuntimeEnvironment.getApplication())
        assertEquals(listOf("builtin.Soft, Round", "blur"), relaunched.carouselFavorites.first())

        // Blank keys are ignored rather than stored as an empty favourite.
        repo.toggleCarouselFavorite(" ")
        assertEquals(listOf("builtin.Soft, Round", "blur"), repo.carouselFavorites.first())
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
        assertEquals(DEFAULT_CANVAS_BACKGROUND.toArgb(), repo.backgroundColor.first())
    }

    @Test
    fun `setBrushTipHidden adds and removes ids independently`() = runTest {
        repo.setBrushTipHidden("ext::0", hidden = true)
        repo.setBrushTipHidden("ext::1", hidden = true)
        assertEquals(setOf("ext::0", "ext::1"), repo.hiddenBrushTipIds.first())

        repo.setBrushTipHidden("ext::0", hidden = false)
        assertEquals(setOf("ext::1"), repo.hiddenBrushTipIds.first())
    }

    @Test
    fun `retired jetpack_ink_brush key is dropped and every other key survives`() = runTest {
        val ink = booleanPreferencesKey("jetpack_ink_brush")
        val fixed = booleanPreferencesKey("brush_size_fixed_on_screen")
        val lang = stringPreferencesKey("language")
        val old = mutablePreferencesOf(ink to true, fixed to true, lang to "fr")

        assertTrue(RetiredSettingsMigration.shouldMigrate(old))
        val migrated = RetiredSettingsMigration.migrate(old)

        assertFalse(migrated.contains(ink))
        assertEquals(true, migrated[fixed])
        assertEquals("fr", migrated[lang])
        // Idempotent: nothing left to migrate, so DataStore won't rewrite the file again.
        assertFalse(RetiredSettingsMigration.shouldMigrate(migrated))
    }

    @Test
    fun `retired key migration is a no-op on a store that never had the toggle`() = runTest {
        val fresh = mutablePreferencesOf(booleanPreferencesKey("is_right_handed") to false)
        assertFalse(RetiredSettingsMigration.shouldMigrate(fresh))
        assertFalse(RetiredSettingsMigration.shouldMigrate(emptyPreferences()))
    }

    @Test
    fun `the settings store still reads after the migration is installed`() = runTest {
        // The migration runs on the store's first read; a repository must still round-trip values.
        repo.setBrushSizeFixedOnScreen(true)
        assertTrue(SettingsRepositoryImpl(RuntimeEnvironment.getApplication()).brushSizeFixedOnScreen.first())
        repo.setBrushSizeFixedOnScreen(false)
    }

    @Test
    fun `concurrent swatch saves do not lose each other`() = runTest {
        repo.setSavedPalette(emptyList())
        val colours = (1..40).map { 0xFF000000.toInt() or (it * 0x010203) }
        kotlinx.coroutines.coroutineScope {
            colours.map { c -> launch(kotlinx.coroutines.Dispatchers.Default) { repo.addSavedSwatch(c) } }
        }
        assertEquals(colours.toSet(), repo.savedPalette.first().toSet())
        kotlinx.coroutines.coroutineScope {
            colours.take(20).map { c -> launch(kotlinx.coroutines.Dispatchers.Default) { repo.removeSavedSwatch(c) } }
        }
        assertEquals(colours.drop(20).toSet(), repo.savedPalette.first().toSet())
    }

    @Test
    fun `re-saving a swatch already there changes nothing`() = runTest {
        repo.setSavedPalette(listOf(0xFF112233.toInt()))
        repo.addSavedSwatch(0xFF112233.toInt())
        assertEquals(listOf(0xFF112233.toInt()), repo.savedPalette.first())
    }

    @Test
    fun `two corrupt files in the same millisecond are both kept, never overwritten or skipped`() = runTest {
        val dir = java.io.File(RuntimeEnvironment.getApplication().filesDir, "corrupt-test-same-ms")
        dir.mkdirs()
        val bad = java.io.File(dir, "settings.preferences_pb")
        val frozen = { 1_700_000_000_000L } // the same millisecond every time
        bad.writeBytes(byteArrayOf(1, 2, 3))
        val first = SettingsCorruptionHandler.keepCopy(bad, frozen)
        bad.writeBytes(byteArrayOf(9, 8, 7, 6)) // a second, different corrupt file
        val second = SettingsCorruptionHandler.keepCopy(bad, frozen)
        bad.writeBytes(byteArrayOf(5))
        val third = SettingsCorruptionHandler.keepCopy(bad, frozen)
        assertTrue(first != null && second != null && third != null)
        assertEquals(3, setOf(first, second, third).size)
        assertTrue(first!!.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(second!!.readBytes().contentEquals(byteArrayOf(9, 8, 7, 6)))
        assertTrue(third!!.readBytes().contentEquals(byteArrayOf(5)))
    }

    @Test
    fun `a failed copy is reported as null, not silently pretended`() = runTest {
        val dir = java.io.File(RuntimeEnvironment.getApplication().filesDir, "corrupt-test-fail")
        dir.mkdirs()
        val bad = java.io.File(dir, "settings.preferences_pb").also { it.writeBytes(byteArrayOf(1)) }
        assertEquals(null, SettingsCorruptionHandler.keepCopy(java.io.File(dir, "missing")))
        val locked = java.io.File(dir, "ro").also { it.mkdirs() }
        val inLocked = java.io.File(locked, "settings.preferences_pb").also { it.writeBytes(byteArrayOf(1)) }
        locked.setWritable(false)
        val result = SettingsCorruptionHandler.keepCopy(inLocked, { 5L })
        locked.setWritable(true)
        // as root the write may still succeed: only a null (reported) or a real copy are acceptable
        assertTrue(result == null || result.readBytes().contentEquals(byteArrayOf(1)))
        assertTrue(bad.exists())
    }

    @Test
    fun `the handler keeps a copy and returns defaults`() = runTest {
        val dir = java.io.File(RuntimeEnvironment.getApplication().filesDir, "corrupt-test")
        dir.mkdirs()
        val bad = java.io.File(dir, "settings.preferences_pb")
        bad.writeBytes(byteArrayOf(0x7F, 0x00, 0x13, 0x37))
        SettingsCorruptionHandler.file = bad
        val fresh = SettingsCorruptionHandler.handler.handleCorruption(androidx.datastore.core.CorruptionException("x"))
        assertTrue(fresh.asMap().isEmpty())
        val copies = dir.listFiles { f -> f.name.contains(".corrupt-") }!!
        assertTrue(copies.isNotEmpty() && copies.all { it.readBytes().contentEquals(bad.readBytes()) })
    }

    @Test
    fun `renaming carousel keys moves favourites in order without duplicating`() = runTest {
        listOf("builtin.Heavy Oil", "custom.x", "builtin.Round").forEach { repo.toggleCarouselFavorite(it) }
        val before = repo.carouselFavorites.first()
        assertTrue("builtin.Heavy Oil" in before)
        repo.renameCarouselKeys(mapOf("builtin.Heavy Oil" to "builtin.Round"))
        val after = repo.carouselFavorites.first()
        assertEquals(1, after.count { it == "builtin.Round" })
        assertTrue("builtin.Heavy Oil" !in after)
        assertTrue("custom.x" in after)
        listOf("custom.x", "builtin.Round").forEach { repo.toggleCarouselFavorite(it) } // tidy
    }

    @Test
    fun `renaming with nothing to rename changes nothing`() = runTest {
        val before = repo.carouselFavorites.first()
        repo.renameCarouselKeys(emptyMap())
        assertEquals(before, repo.carouselFavorites.first())
    }
}
