package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import com.hereliesaz.graffitixr.common.model.EditorUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Pins the bundled GPU Round as the main brush everywhere it has to be named. */
class BuiltInBrushesTest {
    @Test
    fun `ui state default names the bundled round`() {
        assertEquals(BuiltInBrushes.DEFAULT_NAME, EditorUiState().activeBrushName)
    }

    @Test
    fun `round is the first preset so desktop and rail default to it`() {
        assertSame(BuiltInBrushes.round, BuiltInBrushes.presets.first())
        assertEquals(1, BuiltInBrushes.presets.count { it.name == BuiltInBrushes.DEFAULT_NAME })
    }

    @Test
    fun `round is a plain stamp round with no character of its own`() {
        val round = BuiltInBrushes.round
        assertNull(round.shapePath)
        assertNull(round.grainPath)
        assertNull(round.maskedBrush)
        assertEquals(com.hereliesaz.graffitixr.common.azphalt.BrushBlot(), round.blot)
        assertFalse(round.buildUp)
        assertEquals(1f, round.tipRatio)
        assertEquals(0f, round.airbrushDabsPerSecond)
    }

    @Test
    fun `every bundled brush has a unique name and exactly one set`() {
        val names = BuiltInBrushes.presets.map { it.name }
        assertEquals(names.size, names.toSet().size)
        names.forEach { assertEquals(it, 1, BuiltInBrushes.sets.count { set -> set.brushes.any { b -> b.name == it } }) }
        assertEquals(BuiltInBrushes.sets.flatMap { it.brushes }, BuiltInBrushes.presets)
        assertEquals("Basics", BuiltInBrushes.setOf(BuiltInBrushes.DEFAULT_NAME)?.name)
    }

    @Test
    fun `bundled tuning is already in range, so sanitizing changes nothing`() {
        BuiltInBrushes.presets.forEach { assertEquals(it.name, it, it.sanitized()) }
    }

    @Test
    fun `every pressure route leaves a usable mark at zero pressure`() {
        BuiltInBrushes.presets.flatMap { b -> b.dynamics.map { b.name to it } }
            .filter { (_, d) -> d.parameter == com.hereliesaz.graffitixr.common.azphalt.BrushParameter.SIZE ||
                d.parameter == com.hereliesaz.graffitixr.common.azphalt.BrushParameter.OPACITY }
            .forEach { (name, d) -> org.junit.Assert.assertTrue(name, minOf(d.map(0f), d.map(1f)) > 0f) }
    }
}
