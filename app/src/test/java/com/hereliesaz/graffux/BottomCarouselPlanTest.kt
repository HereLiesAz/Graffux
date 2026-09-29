package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomCarouselPlanTest {

    private val builtIn = BuiltInBrushes.presets

    private val base = CarouselInputs(
        activeTool = Tool.BRUSH,
        activeBrushName = null,
        builtInBrushes = builtIn,
        customBrushes = emptyList(),
        extensionBrushes = emptyList(),
        stabilizerLevel = 0,
        stabilizerAlgorithm = StabilizerAlgorithm.STREAMLINE,
        smudgeMode = ColorSmudgeEngine.Mode.DULLING,
        selectionShape = SelectionShape.ELLIPSE,
        toolOptionsOpen = false,
    )

    private fun inputs(tool: Tool = Tool.BRUSH, brushName: String? = null, stabilizer: Int = 0) =
        base.copy(activeTool = tool, activeBrushName = brushName, stabilizerLevel = stabilizer)

    @Test
    fun `brushes page lists built-in then custom then extension brushes`() {
        val custom = listOf("c1" to builtIn.first().copy(name = "Mine"))
        val entries = carouselEntries(
            CarouselCategory.BRUSHES,
            base.copy(customBrushes = custom, extensionBrushes = listOf("e:1" to "Ext")),
        )
        assertEquals(builtIn.size + 2, entries.size)
        assertEquals(CarouselAction.BuiltInBrush(builtIn.first().name), entries.first().action)
        assertEquals(CarouselAction.CustomBrush("c1"), entries[builtIn.size].action)
        assertEquals(CarouselAction.ExtensionBrush("e:1"), entries[builtIn.size + 1].action)
    }

    @Test
    fun `active brush is the selected and centred item`() {
        val target = builtIn.last().name
        val entries = carouselEntries(CarouselCategory.BRUSHES, inputs(brushName = target))
        assertEquals(1, entries.count { it.selected })
        assertEquals(builtIn.size - 1, selectedCarouselIndex(entries))
    }

    @Test
    fun `a brush name shared across sources lights only the first match`() {
        val dup = builtIn.first()
        val entries = carouselEntries(
            CarouselCategory.BRUSHES,
            base.copy(activeBrushName = dup.name, customBrushes = listOf("c" to dup)),
        )
        assertEquals(1, entries.count { it.selected })
        assertEquals(0, selectedCarouselIndex(entries))
    }

    @Test
    fun `brushes page holds only stamp brushes and the Ink page holds only the Ink utensils`() {
        val withAll = base.copy(
            customBrushes = listOf("c1" to builtIn.first().copy(name = "Mine")),
            extensionBrushes = listOf("e:1" to "Ext"),
        )
        val brushes = carouselEntries(CarouselCategory.BRUSHES, withAll)
        assertTrue(brushes.none { it.action is CarouselAction.InkUtensilPick })
        assertTrue(
            brushes.all {
                it.action is CarouselAction.BuiltInBrush ||
                    it.action is CarouselAction.CustomBrush ||
                    it.action is CarouselAction.ExtensionBrush
            },
        )
        val ink = carouselEntries(CarouselCategory.INK, withAll)
        assertEquals(INK_UTENSIL_CATALOG.map { CarouselAction.InkUtensilPick(it.utensil) }, ink.map { it.action })
    }

    @Test
    fun `an Ink utensil in hand lights only its entry, not the same-named stamp brush`() {
        val pen = com.hereliesaz.graffitixr.common.model.InkUtensil.PEN
        // Selecting a utensil sets activeBrushName to its display name, which the built-in shares.
        assertTrue(builtIn.any { it.name == pen.displayName })
        val inputs = base.copy(activeBrushName = pen.displayName, activeInkUtensil = pen)
        assertNull(selectedCarouselIndex(carouselEntries(CarouselCategory.BRUSHES, inputs)))
        val entries = carouselEntries(CarouselCategory.INK, inputs)
        assertEquals(1, entries.count { it.selected })
        val index = selectedCarouselIndex(entries)!!
        assertEquals(CarouselAction.InkUtensilPick(pen), entries[index].action)
    }

    @Test
    fun `no active brush leaves nothing to centre on`() {
        assertNull(selectedCarouselIndex(carouselEntries(CarouselCategory.BRUSHES, inputs(brushName = null))))
    }

    @Test
    fun `effects page is the effect tools and reflects the armed one`() {
        val entries = carouselEntries(CarouselCategory.EFFECTS, inputs(tool = Tool.BURN))
        assertEquals(EFFECT_TOOLS.map { CarouselAction.PickTool(it) }, entries.map { it.action })
        assertEquals(EFFECT_TOOLS.indexOf(Tool.BURN), selectedCarouselIndex(entries))
        assertNull(selectedCarouselIndex(carouselEntries(CarouselCategory.EFFECTS, inputs(tool = Tool.BRUSH))))
    }

    @Test
    fun `options always end with the full Tool Options window`() {
        Tool.entries.forEach { tool ->
            val entries = carouselEntries(CarouselCategory.OPTIONS, inputs(tool = tool))
            assertEquals(CarouselAction.OpenToolOptions, entries.last().action)
        }
        val open = carouselEntries(CarouselCategory.OPTIONS, base.copy(activeTool = Tool.NONE, toolOptionsOpen = true))
        assertEquals(listOf(true), open.map { it.selected })
    }

    @Test
    fun `smudge options lead with its modes`() {
        val entries = carouselEntries(CarouselCategory.OPTIONS, inputs(tool = Tool.SMUDGE))
        assertEquals(CarouselAction.SmudgeMode(ColorSmudgeEngine.Mode.SMEAR), entries[0].action)
        assertEquals(1, selectedCarouselIndex(entries))
    }

    @Test
    fun `select options are the selection shapes`() {
        val entries = carouselEntries(CarouselCategory.OPTIONS, inputs(tool = Tool.SELECT))
        assertEquals(SelectionShape.entries.size + 1, entries.size)
        val selected = entries[selectedCarouselIndex(entries)!!]
        assertEquals(CarouselAction.SelectShape(SelectionShape.ELLIPSE), selected.action)
    }

    @Test
    fun `stabilizer algorithms appear only while the stabilizer is on`() {
        val off = carouselEntries(CarouselCategory.OPTIONS, inputs(tool = Tool.BRUSH, stabilizer = 0))
        assertFalse(off.any { it.action is CarouselAction.Stabilizer })
        assertEquals(CarouselAction.StabilizerLevel(0), off[selectedCarouselIndex(off)!!].action)

        val on = carouselEntries(CarouselCategory.OPTIONS, inputs(tool = Tool.BRUSH, stabilizer = 50))
        assertTrue(on.any { it.action == CarouselAction.Stabilizer(StabilizerAlgorithm.STREAMLINE) && it.selected })
        assertEquals(CarouselAction.StabilizerLevel(50), on[selectedCarouselIndex(on)!!].action)
    }

    @Test
    fun `entry keys are unique on every page`() {
        CarouselCategory.entries.forEach { category ->
            Tool.entries.forEach { tool ->
                val keys = carouselEntries(category, inputs(tool = tool, stabilizer = 25)).map { it.key }
                assertEquals(keys.size, keys.toSet().size)
            }
        }
    }

    @Test
    fun `tab row is Undo, Favorites, Brushes, Ink, Effects, Options, Redo`() {
        assertEquals(
            listOf("Undo", "Favorites", "Brushes", "Ink", "Effects", "Options", "Redo"),
            CAROUSEL_TABS.map { it.label },
        )
    }

    @Test
    fun `undo and redo tabs are not pages and are enabled only with history`() {
        assertEquals(CarouselTab.Undo, CAROUSEL_TABS.first())
        assertEquals(CarouselTab.Redo, CAROUSEL_TABS.last())
        // Every page category has exactly one tab, and nothing else in the row is a page.
        assertEquals(
            CarouselCategory.entries.toList(),
            CAROUSEL_TABS.filterIsInstance<CarouselTab.Page>().map { it.category },
        )
        assertEquals(2, CAROUSEL_TABS.count { it !is CarouselTab.Page })

        assertFalse(carouselTabEnabled(CarouselTab.Undo, undoCount = 0, redoCount = 3))
        assertTrue(carouselTabEnabled(CarouselTab.Undo, undoCount = 1, redoCount = 0))
        assertFalse(carouselTabEnabled(CarouselTab.Redo, undoCount = 3, redoCount = 0))
        assertTrue(carouselTabEnabled(CarouselTab.Redo, undoCount = 0, redoCount = 1))
        CarouselCategory.entries.forEach {
            assertTrue(carouselTabEnabled(CarouselTab.Page(it), undoCount = 0, redoCount = 0))
        }
    }

    @Test
    fun `toggling a favorite appends, and un-starring keeps the rest in place`() {
        var favs = emptyList<String>()
        favs = toggleCarouselFavorite(favs, "ink.pen")
        favs = toggleCarouselFavorite(favs, "blur")
        favs = toggleCarouselFavorite(favs, "stabilizer.25")
        assertEquals(listOf("ink.pen", "blur", "stabilizer.25"), favs)
        favs = toggleCarouselFavorite(favs, "blur")
        assertEquals(listOf("ink.pen", "stabilizer.25"), favs)
        favs = toggleCarouselFavorite(favs, "blur")
        assertEquals(listOf("ink.pen", "stabilizer.25", "blur"), favs)
    }

    @Test
    fun `favorites page lists starred entries from every page in starring order`() {
        val brush = "builtin.${builtIn.first().name}"
        val ink = "ink.${INK_UTENSIL_CATALOG.last().utensil.id}"
        val effect = carouselEntries(CarouselCategory.EFFECTS, base).first().key
        // A Smudge mode, starred while holding a brush: it must still appear.
        val option = "smudge.${ColorSmudgeEngine.Mode.SMEAR.name}"
        val favorites = listOf(option, ink, "custom.deleted", effect, brush)
        val entries = carouselEntries(CarouselCategory.FAVORITES, base.copy(favorites = favorites))
        assertEquals(listOf(option, ink, effect, brush), entries.map { it.key })
        assertTrue(entries.all { it.favorite })
    }

    @Test
    fun `favorites are starred on their home pages and nowhere else`() {
        val ink = "ink.${INK_UTENSIL_CATALOG.first().utensil.id}"
        val inputs = base.copy(favorites = listOf(ink))
        assertEquals(listOf(ink), carouselEntries(CarouselCategory.INK, inputs).filter { it.favorite }.map { it.key })
        assertTrue(carouselEntries(CarouselCategory.BRUSHES, inputs).none { it.favorite })
    }

    @Test
    fun `favorites page is empty with no favorites`() {
        assertTrue(carouselEntries(CarouselCategory.FAVORITES, base).isEmpty())
    }

    @Test
    fun `tip kind follows the item kind`() {
        val withAll = base.copy(
            customBrushes = listOf("c1" to builtIn.first().copy(name = "Mine", hardness = 0.25f)),
            extensionBrushes = listOf("e::1" to "Ext"),
        )
        val brushes = carouselEntries(CarouselCategory.BRUSHES, withAll)
        brushes.filter { it.action is CarouselAction.BuiltInBrush }.forEach { entry ->
            val tip = carouselTip(entry) as CarouselTip.Round
            assertEquals(entry.brush!!.hardness.coerceIn(0f, 1f), tip.hardness)
        }
        val custom = brushes.single { it.action is CarouselAction.CustomBrush }
        assertEquals(0.25f, (carouselTip(custom) as CarouselTip.Round).hardness)
        val ext = brushes.single { it.action is CarouselAction.ExtensionBrush }
        assertEquals(CarouselTip.Stamp("e::1"), carouselTip(ext))

        carouselEntries(CarouselCategory.INK, base).forEach { entry ->
            val utensil = (entry.action as CarouselAction.InkUtensilPick).utensil
            assertEquals(CarouselTip.Ink(utensil, entry.icon!!), carouselTip(entry))
        }
        (carouselEntries(CarouselCategory.EFFECTS, base) + carouselEntries(CarouselCategory.OPTIONS, base))
            .forEach { entry -> assertEquals(CarouselTip.Glyph(entry.icon!!), carouselTip(entry)) }
    }

    @Test
    fun `tier comes from the laid-out size`() {
        val hero = 400f
        val smallMax = 150f
        assertEquals(CarouselTier.HERO, carouselTier(400f, hero, smallMax))
        assertEquals(CarouselTier.HERO, carouselTier(370f, hero, smallMax))
        assertEquals(CarouselTier.MEDIUM, carouselTier(250f, hero, smallMax))
        assertEquals(CarouselTier.SMALL, carouselTier(150f, hero, smallMax))
        assertEquals(CarouselTier.SMALL, carouselTier(100f, hero, smallMax))
    }

    @Test
    fun `hero shows name and details, medium the name, small the tip alone`() {
        val brush = carouselEntries(CarouselCategory.BRUSHES, base).first()
        val hero = carouselCardContent(brush, CarouselTier.HERO)
        assertEquals(brush.label, hero.name)
        assertEquals(
            listOf(
                "Built-in · round tip",
                "Hardness ${(brush.brush!!.hardness * 100).toInt()}% · " +
                    "Spacing ${(brush.brush!!.spacing * 100).toInt()}%",
            ),
            hero.details,
        )
        assertEquals(CarouselCardContent(brush.label, emptyList()), carouselCardContent(brush, CarouselTier.MEDIUM))
        assertEquals(CarouselCardContent(null, emptyList()), carouselCardContent(brush, CarouselTier.SMALL))

        val pen = carouselEntries(CarouselCategory.INK, base).first()
        assertEquals(
            listOf("Jetpack Ink", "Pressure-sensitive width"),
            carouselCardContent(pen, CarouselTier.HERO).details,
        )
        val effect = carouselEntries(CarouselCategory.EFFECTS, base).first()
        assertEquals(listOf("Effect"), carouselCardContent(effect, CarouselTier.HERO).details)
        val withExt = base.copy(extensionBrushes = listOf("e::1" to "Ext"))
        val ext = carouselEntries(CarouselCategory.BRUSHES, withExt).last()
        // No parameters are invented for an installed brush: its family only.
        assertEquals(listOf("Installed · stamp brush"), carouselCardContent(ext, CarouselTier.HERO).details)
    }

    @Test
    fun `rail Undo and Redo show only while the open sheet is not already offering them`() {
        // Open sheet on screen: its tab row has Undo/Redo, so the rail must not duplicate them.
        assertFalse(railHistoryItemsVisible(carouselOnScreen = true, sheetOpen = true))
        // Sheet shut: the rail carries them.
        assertTrue(railHistoryItemsVisible(carouselOnScreen = true, sheetOpen = false))
        // Carousel off screen (hidden from the areas dropdown, a panel up, UI hidden): rail carries them
        // whatever the sheet's remembered state.
        assertTrue(railHistoryItemsVisible(carouselOnScreen = false, sheetOpen = true))
        assertTrue(railHistoryItemsVisible(carouselOnScreen = false, sheetOpen = false))
    }

    @Test
    fun `sheet settles by fling first, then by the nearer side`() {
        assertTrue(carouselSheetSettlesOpen(fractionShut = 0.9f, velocityDpPerSec = -1_000f))
        assertFalse(carouselSheetSettlesOpen(fractionShut = 0.1f, velocityDpPerSec = 1_000f))
        assertTrue(carouselSheetSettlesOpen(fractionShut = 0.3f, velocityDpPerSec = 50f))
        assertFalse(carouselSheetSettlesOpen(fractionShut = 0.7f, velocityDpPerSec = -50f))
    }
}
