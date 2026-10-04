@file:Suppress("MaxLineLength")

package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import com.hereliesaz.graffitixr.common.model.CarouselItemSettings
import com.hereliesaz.graffitixr.common.model.InkUtensil
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-item carousel settings: isolation, persistence encoding, apply-on-select and migration. */
class CarouselItemSettingsTest {

    private val a = "builtin.${BuiltInBrushes.presets[0].name}"
    private val b = "builtin.${BuiltInBrushes.presets[1].name}"
    private val smudge = SMUDGE_SETTINGS_KEY

    private val inputs = CarouselInputs(
        activeTool = Tool.BRUSH,
        activeBrushName = BuiltInBrushes.presets[1].name,
        builtInBrushes = BuiltInBrushes.presets,
        customBrushes = emptyList(),
        extensionBrushes = emptyList(),
        stabilizerLevel = 0,
        stabilizerAlgorithm = StabilizerAlgorithm.STREAMLINE,
        smudgeMode = ColorSmudgeEngine.Mode.DULLING,
        selectionShape = SelectionShape.ELLIPSE,
    )

    // --- persistence ---------------------------------------------------------------------------

    @Test
    fun `settings round-trip through the store encoding, keys with commas and spaces included`() {
        val map = mapOf(
            a to CarouselItemSettings(size = 12f, flow = 0.3f, opacity = 0.9f, softness = 0.2f, strength = 0.4f),
            "custom.my, brush" to CarouselItemSettings(size = 120f),
            smudge to CarouselItemSettings(strength = 0.1f),
        )
        assertEquals(map, CarouselItemSettings.decode(CarouselItemSettings.encode(map)))
    }

    @Test
    fun `malformed records are dropped and out-of-range values clamped`() {
        val raw = "ok\t300\t2\t-1\t0.5\t0.5\nbroken\t1\t2\n\tno-key\t1\t1\t1\t1\t1\nnan\tx\t1\t1\t1\t1"
        val decoded = CarouselItemSettings.decode(raw)
        assertEquals(setOf("ok"), decoded.keys)
        assertEquals(CarouselItemSettings(size = 200f, flow = 1f, opacity = 0f, softness = 0.5f, strength = 0.5f), decoded["ok"])
        assertTrue(CarouselItemSettings.decode(null).isEmpty())
    }

    // --- isolation -----------------------------------------------------------------------------

    @Test
    fun `adjusting one item's size leaves every other item's settings alone`() {
        val stored = mapOf(a to CarouselItemSettings(size = 10f), b to CarouselItemSettings(size = 90f))
        val next = stored + (a to carouselItemSettingsFor(a, stored).adjusted(HeroSetter.BRUSH_SIZE, 44f)!!)
        assertEquals(44f, next.getValue(a).size)
        assertEquals(stored.getValue(b), next.getValue(b))
    }

    @Test
    fun `global setters are not per-item`() {
        val s = CarouselItemSettings()
        assertNull(s.adjusted(HeroSetter.STABILIZER, 3f))
        assertNull(s.adjusted(HeroSetter.WAND_TOLERANCE, 3f))
        assertEquals(0.2f, s.adjusted(HeroSetter.SMUDGE_STRENGTH, 0.2f)!!.strength)
    }

    @Test
    fun `defaults come from each item's preset`() {
        assertEquals(CarouselItemSettings(), carouselItemDefaults(a))
        assertTrue(carouselItemDefaults("ink.${InkUtensil.PEN.id}").size < carouselItemDefaults("ink.${InkUtensil.MARKER.id}").size)
        assertTrue(carouselItemDefaults(TOOL_CATALOG.getValue(Tool.BLUR).id).softness > 0f)
        assertEquals(CarouselItemSettings(size = 7f), carouselItemSettingsFor(a, mapOf(a to CarouselItemSettings(size = 7f))))
    }

    @Test
    fun `keys - paint items own settings, a smudge mode edits Smudge's, options own none`() {
        fun keyOf(action: CarouselAction) = carouselSettingsKey(CarouselEntry("k", "l", action, selected = false))
        assertEquals("k", keyOf(CarouselAction.BuiltInBrush("x")))
        assertEquals("k", keyOf(CarouselAction.InkUtensilPick(InkUtensil.MARKER)))
        assertEquals("k", keyOf(CarouselAction.PickTool(Tool.BLUR)))
        assertEquals(smudge, keyOf(CarouselAction.SmudgeMode(ColorSmudgeEngine.Mode.DULLING)))
        assertNull(keyOf(CarouselAction.StabilizerLevel(25)))
    }

    @Test
    fun `the active key follows whatever is in hand`() {
        assertEquals(b, activeCarouselSettingsKey(inputs))
        assertEquals(
            "ink.${InkUtensil.MARKER.id}",
            activeCarouselSettingsKey(inputs.copy(activeInkUtensil = InkUtensil.MARKER, activeBrushName = "Ink Marker")),
        )
        assertEquals(smudge, activeCarouselSettingsKey(inputs.copy(activeTool = Tool.SMUDGE)))
        assertNull(activeCarouselSettingsKey(inputs.copy(activeTool = Tool.ERASER)))
    }

    // --- sync: apply on select, save on edit ---------------------------------------------------

    @Test
    fun `nothing happens until the store has loaded`() {
        val sync = CarouselItemSettingsSync()
        assertEquals(ItemSettingsDecision.None, sync.observe(a, CarouselItemSettings(), null))
    }

    @Test
    fun `selecting an item applies its saved settings, then edits save to it alone`() {
        val stored = mutableMapOf(a to CarouselItemSettings(size = 10f), b to CarouselItemSettings(size = 90f))
        val sync = CarouselItemSettingsSync()
        var live = CarouselItemSettings(size = 10f)
        // First item seen already has settings: nothing to migrate, nothing to apply.
        assertEquals(ItemSettingsDecision.None, sync.observe(a, live, stored))

        // Pick b: its own size is applied.
        val apply = sync.observe(b, live, stored)
        assertEquals(ItemSettingsDecision.Apply(CarouselItemSettings(size = 90f), includeStrength = false), apply)
        // Before the applied values land, a stale observation must not be saved as b's.
        assertEquals(ItemSettingsDecision.None, sync.observe(b, live, stored))
        live = CarouselItemSettings(size = 90f)
        assertEquals(ItemSettingsDecision.None, sync.observe(b, live, stored))

        // Tool Options / a hero slider changes the live size: saved as b's, a untouched.
        live = live.copy(size = 33f)
        val save = sync.observe(b, live, stored) as ItemSettingsDecision.Save
        assertEquals(b, save.key)
        assertEquals(33f, save.settings.size)
        stored[save.key] = save.settings
        assertEquals(10f, stored.getValue(a).size)

        // Back to a: a's own 10 comes back.
        assertEquals(ItemSettingsDecision.Apply(CarouselItemSettings(size = 10f), false), sync.observe(a, live, stored))
    }

    @Test
    fun `an unsaved item selected later gets its preset, not the previous item's values`() {
        val sync = CarouselItemSettingsSync()
        val stored = mapOf(a to CarouselItemSettings(size = 10f))
        sync.observe(a, CarouselItemSettings(size = 10f), stored)
        val pen = "ink.${InkUtensil.PEN.id}"
        assertEquals(
            ItemSettingsDecision.Apply(carouselItemDefaults(pen), false),
            sync.observe(pen, CarouselItemSettings(size = 10f), stored),
        )
    }

    @Test
    fun `migration - the old global values seed only the item in hand`() {
        val sync = CarouselItemSettingsSync()
        val global = CarouselItemSettings(size = 77f, flow = 0.4f, opacity = 0.8f, softness = 0.3f)
        assertEquals(ItemSettingsDecision.Save(b, global), sync.observe(b, global, emptyMap()))
        // Any other item picked afterwards starts from its preset instead.
        assertEquals(
            ItemSettingsDecision.Apply(carouselItemDefaults(a), false),
            sync.observe(a, global, mapOf(b to global)),
        )
    }

    @Test
    fun `strength belongs to Smudge alone`() {
        val sync = CarouselItemSettingsSync()
        val stored = mapOf(a to CarouselItemSettings(), smudge to CarouselItemSettings(strength = 0.2f))
        sync.observe(a, CarouselItemSettings(), stored)
        // A smudge-rate change while a brush is in hand is not the brush's edit.
        assertEquals(ItemSettingsDecision.None, sync.observe(a, CarouselItemSettings(strength = 0.9f), stored))
        // Picking Smudge applies its own strength.
        val apply = sync.observe(smudge, CarouselItemSettings(strength = 0.9f), stored) as ItemSettingsDecision.Apply
        assertTrue(apply.includeStrength)
        assertEquals(0.2f, apply.settings.strength)
    }

    @Test
    fun `nothing in hand - live edits belong to nobody`() {
        val sync = CarouselItemSettingsSync()
        assertEquals(ItemSettingsDecision.None, sync.observe(null, CarouselItemSettings(size = 5f), emptyMap()))
        assertEquals(ItemSettingsDecision.None, sync.observe(null, CarouselItemSettings(size = 6f), emptyMap()))
    }

    // --- collapse while drawing -----------------------------------------------------------------

    @Test
    fun `the sheet shuts for a stroke and reopens only if the user had it open`() {
        assertTrue(carouselSheetShownOpen(userOpen = true, hiddenForDrawing = false))
        assertFalse(carouselSheetShownOpen(userOpen = true, hiddenForDrawing = true))
        assertFalse(carouselSheetShownOpen(userOpen = false, hiddenForDrawing = true))
        assertFalse(carouselSheetShownOpen(userOpen = false, hiddenForDrawing = false))
    }
}
