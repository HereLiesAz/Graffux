package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.azphalt.AzphaltManifest
import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import com.hereliesaz.graffitixr.common.azphalt.Contributes
import com.hereliesaz.graffitixr.common.azphalt.Contribution
import com.hereliesaz.graffitixr.common.azphalt.ExtensionKind
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.data.azphalt.InstalledExtension
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarouselExtensionEffectsTest {

    private val base = CarouselInputs(
        activeTool = Tool.BRUSH,
        activeBrushName = null,
        builtInBrushes = BuiltInBrushes.presets,
        customBrushes = emptyList(),
        extensionBrushes = emptyList(),
        stabilizerLevel = 0,
        stabilizerAlgorithm = StabilizerAlgorithm.STREAMLINE,
        smudgeMode = ColorSmudgeEngine.Mode.DULLING,
        selectionShape = SelectionShape.ELLIPSE,
    )

    private fun ext(id: String, kind: ExtensionKind, contributes: Contributes, description: String? = null) =
        InstalledExtension(
            manifest = AzphaltManifest(
                azphalt = "0.1", id = id, name = "Name $id", version = "1.0.0", kind = kind,
                license = "MIT", compat = "*", description = description, contributes = contributes,
            ),
            dir = "/x/$id",
            installedAt = 0L,
        )

    private fun c(prefix: String, id: String) = Contribution(id, "$prefix $id", "$id.js")

    private val installed = listOf(
        ext(
            "fx",
            ExtensionKind.CODE,
            Contributes(
                filters = listOf(c("F", "glow"), c("F", "grain")),
                tools = listOf(c("T", "warp")),
                commands = listOf(c("C", "export")),
            ),
        ),
        ext("grade", ExtensionKind.ASSET, Contributes(), description = "Teal and orange"),
    )

    private fun installedEntries(extra: List<ExtensionEffect> = emptyList()) =
        carouselEntries(CarouselCategory.EFFECTS, base.copy(extensionEffects = effects + extra))
    private val effects = extensionEffectsOf(installed) { it.id == "grade" }

    @Test
    fun `each filter and tool gets its own effect, then the LUT, and commands are left out`() {
        assertEquals(
            listOf("ext-filter:fx:glow", "ext-filter:fx:grain", "ext-tool:fx:warp", "ext-lut:grade"),
            effects.map(::extensionEffectKey),
        )
    }

    @Test
    fun `installed effects follow the built-in effect tools on the Effects page`() {
        val entries = carouselEntries(CarouselCategory.EFFECTS, base.copy(extensionEffects = effects))
        assertEquals(EFFECT_TOOLS.size + effects.size, entries.size)
        entries.take(EFFECT_TOOLS.size).forEach { assertTrue(it.action is CarouselAction.PickTool) }
        val tail = entries.drop(EFFECT_TOOLS.size)
        assertEquals(CarouselAction.ExtensionContribution("fx", "glow", ExtensionEffectKind.FILTER), tail[0].action)
        assertEquals(CarouselAction.ExtensionContribution("fx", "warp", ExtensionEffectKind.TOOL), tail[2].action)
        assertEquals(CarouselAction.ExtensionLut("grade"), tail[3].action)
        assertEquals("F glow", tail[0].label)
        assertTrue(tail.none { it.selected })
    }

    @Test
    fun `keys are unique, including with a duplicate effect`() {
        val entries = installedEntries(extra = listOf(effects.first()))
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
    }

    @Test
    fun `no installed extension kind runs from a scroll, only from a tap`() {
        val entries = installedEntries().drop(EFFECT_TOOLS.size)
        entries.forEach {
            assertFalse(it.key, carouselAutoActivates(it.action))
            assertFalse(it.key, carouselSettleSelects(it, byTap = false))
            assertTrue(it.key, carouselSettleSelects(it, byTap = true))
        }
    }

    @Test
    fun `favorites include installed effects`() {
        val favs = listOf("ext-lut:grade", "ext-filter:fx:glow")
        val input = base.copy(extensionEffects = effects, favorites = favs)
        assertEquals(favs, carouselEntries(CarouselCategory.FAVORITES, input).map { it.key })
        val starred = carouselEntries(CarouselCategory.EFFECTS, input).filter { it.favorite }.map { it.key }
        assertEquals(favs.toSet(), starred.toSet())
    }

    @Test
    fun `hero shows extension name, kind and description, and the tip is the preview`() {
        val entries = installedEntries().drop(EFFECT_TOOLS.size)
        assertEquals(listOf("Name fx · Filter"), carouselHeroDetails(entries[0]))
        assertEquals(listOf("Name fx · Tool"), carouselHeroDetails(entries[2]))
        assertEquals(listOf("Name grade · LUT", "Teal and orange"), carouselHeroDetails(entries[3]))
        assertEquals("fx", (carouselTip(entries[0]) as CarouselTip.Preview).extensionId)
        assertEquals("grade", (carouselTip(entries[3]) as CarouselTip.Preview).extensionId)
    }
}
