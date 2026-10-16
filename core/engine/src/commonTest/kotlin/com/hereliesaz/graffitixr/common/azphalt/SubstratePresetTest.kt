package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubstratePresetTest {
    private val textured = SubstratePreset.entries.filterNot { it.isSmooth }

    @Test
    fun ships_the_four_presets_with_stable_ids() {
        assertEquals(
            listOf("smooth", "cold_press", "canvas_weave", "brick_concrete"),
            SubstratePreset.entries.map { it.id },
        )
        assertEquals(SubstratePreset.SMOOTH, SubstratePreset.DEFAULT)
    }

    @Test
    fun id_round_trips_and_unknown_or_missing_is_smooth() {
        SubstratePreset.entries.forEach { assertEquals(it, SubstratePreset.fromId(it.id)) }
        assertEquals(SubstratePreset.SMOOTH, SubstratePreset.fromId(null))
        assertEquals(SubstratePreset.SMOOTH, SubstratePreset.fromId(""))
        assertEquals(SubstratePreset.SMOOTH, SubstratePreset.fromId("velvet"))
        assertEquals(SubstratePreset.SMOOTH, SubstratePreset.fromId("COLD_PRESS"))
    }

    @Test
    fun smooth_has_no_tile_and_the_historical_profile() {
        assertNull(SubstratePreset.SMOOTH.field())
        assertNull(SubstratePreset.SMOOTH.heightR8())
        assertEquals(SubstrateProfile.SMOOTH, SubstratePreset.SMOOTH.profile)
    }

    @Test
    fun textured_tiles_are_the_documented_size() {
        val n = SubstratePreset.TILE_SIZE
        textured.forEach { preset ->
            val field = assertNotNull(preset.field(), preset.id)
            assertEquals(n, field.width)
            assertEquals(n, field.height)
            assertEquals(n * n, assertNotNull(preset.heightR8()).size)
        }
    }

    @Test
    fun tiles_are_deterministic() {
        textured.forEach { preset ->
            val a = SubstratePreset.generateHeightR8(preset)
            val b = SubstratePreset.generateHeightR8(preset)
            assertContentEquals(a, b, preset.id)
            assertContentEquals(a, preset.heightR8(), preset.id)
        }
    }

    @Test
    fun heightR8_hands_out_a_copy() {
        val first = assertNotNull(SubstratePreset.COLD_PRESS.heightR8())
        first.fill(0)
        assertFalse(assertNotNull(SubstratePreset.COLD_PRESS.heightR8()).all { it == 0.toByte() })
    }

    @Test
    fun textured_tiles_have_real_tooth_and_differ_from_each_other() {
        val tiles = textured.map { SubstratePreset.generateHeightR8(it) }
        tiles.forEachIndexed { i, tile ->
            val values = tile.map { it.toInt() and 0xFF }
            assertTrue(values.max() - values.min() > 100, "${textured[i].id} range too small")
        }
        for (i in tiles.indices) for (j in i + 1 until tiles.size) {
            assertFalse(tiles[i].contentEquals(tiles[j]), "${textured[i].id} == ${textured[j].id}")
        }
    }

    @Test
    fun textured_profiles_gate_deposition() {
        textured.forEach { preset ->
            val profile = preset.profile.sanitized()
            assertTrue(profile.heightScale > 0f, preset.id)
            assertEquals(profile, preset.profile, "${preset.id} profile should already be sanitized")
        }
    }
}
