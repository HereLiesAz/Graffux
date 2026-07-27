package com.hereliesaz.graffitixr.data.figma

import androidx.compose.ui.graphics.BlendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundle is a contract with a separate JavaScript codebase (figma-plugin/), so its field names
 * and blend-mode strings are load-bearing in a way a normal internal data class isn't.
 */
class FigmaBundleTest {

    /** Compose's BlendMode is a value class, not an enum, so the full set is listed explicitly. */
    private val allComposeModes = listOf(
        BlendMode.Clear, BlendMode.Src, BlendMode.Dst, BlendMode.SrcOver, BlendMode.DstOver,
        BlendMode.SrcIn, BlendMode.DstIn, BlendMode.SrcOut, BlendMode.DstOut, BlendMode.SrcAtop,
        BlendMode.DstAtop, BlendMode.Xor, BlendMode.Plus, BlendMode.Modulate, BlendMode.Screen,
        BlendMode.Overlay, BlendMode.Darken, BlendMode.Lighten, BlendMode.ColorDodge,
        BlendMode.ColorBurn, BlendMode.Hardlight, BlendMode.Softlight, BlendMode.Difference,
        BlendMode.Exclusion, BlendMode.Multiply, BlendMode.Hue, BlendMode.Saturation,
        BlendMode.Color, BlendMode.Luminosity,
    )

    @Test
    fun `every blend mode maps to a name Figma accepts`() {
        // Figma's own BlendMode union. A typo here would silently fail inside the plugin.
        val figmaModes = setOf(
            "NORMAL", "DARKEN", "MULTIPLY", "LINEAR_BURN", "COLOR_BURN", "LIGHTEN", "SCREEN",
            "LINEAR_DODGE", "COLOR_DODGE", "OVERLAY", "SOFT_LIGHT", "HARD_LIGHT", "DIFFERENCE",
            "EXCLUSION", "HUE", "SATURATION", "COLOR", "LUMINOSITY",
        )
        allComposeModes.forEach { mode ->
            val mapped = figmaBlendMode(mode)
            assertTrue("$mode mapped to unknown Figma mode '$mapped'", mapped in figmaModes)
        }
    }

    @Test
    fun `the separable and non-separable modes map one-to-one`() {
        assertEquals("NORMAL", figmaBlendMode(BlendMode.SrcOver))
        assertEquals("MULTIPLY", figmaBlendMode(BlendMode.Multiply))
        assertEquals("COLOR_DODGE", figmaBlendMode(BlendMode.ColorDodge))
        // Compose spells these Hardlight/Softlight; Figma wants HARD_LIGHT/SOFT_LIGHT.
        assertEquals("SOFT_LIGHT", figmaBlendMode(BlendMode.Softlight))
        assertEquals("HARD_LIGHT", figmaBlendMode(BlendMode.Hardlight))
        assertEquals("LUMINOSITY", figmaBlendMode(BlendMode.Luminosity))
    }

    @Test
    fun `Porter-Duff operators degrade to NORMAL rather than an invalid name`() {
        // Figma has no equivalent for these at all; NORMAL is the honest fallback.
        listOf(BlendMode.SrcIn, BlendMode.DstOut, BlendMode.Xor, BlendMode.Clear, BlendMode.Plus)
            .forEach { assertEquals("NORMAL", figmaBlendMode(it)) }
    }

    @Test
    fun `serializes with the exact field names the plugin reads`() {
        val json = FigmaBundle.encode(
            FigmaBundle(
                name = "Proj",
                documentWidth = 1080,
                documentHeight = 720,
                layers = listOf(
                    FigmaBundleLayer(
                        name = "Background",
                        pngBase64 = "AAAA",
                        opacity = 0.5f,
                        blendMode = "MULTIPLY",
                        visible = false,
                    ),
                ),
            ),
        )
        // These key names are consumed by figma-plugin/code.js; renaming one breaks the plugin.
        listOf(
            "\"version\"", "\"name\"", "\"documentWidth\"", "\"documentHeight\"", "\"layers\"",
            "\"pngBase64\"", "\"opacity\"", "\"blendMode\"", "\"visible\"",
        ).forEach { key ->
            assertTrue("missing $key in $json", json.contains(key))
        }
    }

    @Test
    fun `round-trips through JSON`() {
        val bundle = FigmaBundle(
            name = "Proj",
            documentWidth = 100,
            documentHeight = 200,
            layers = listOf(FigmaBundleLayer(name = "L", pngBase64 = "AAAA")),
        )
        assertEquals(bundle, FigmaBundle.decode(FigmaBundle.encode(bundle)))
    }

    @Test
    fun `encodes defaults so the plugin never has to infer a missing field`() {
        // A layer left at its defaults must still emit opacity/blendMode/visible — the plugin reads
        // them directly rather than filling in Kotlin's defaults.
        val json = FigmaBundle.encode(
            FigmaBundle(name = "P", documentWidth = 1, documentHeight = 1,
                layers = listOf(FigmaBundleLayer(name = "L", pngBase64 = "AAAA"))),
        )
        assertTrue(json, json.contains("\"opacity\""))
        assertTrue(json, json.contains("\"blendMode\""))
        assertTrue(json, json.contains("\"visible\""))
        assertTrue(json, json.contains("\"version\":1"))
    }

    @Test
    fun `the file suffix is the one the plugin's picker expects`() {
        assertTrue(FigmaBundle.FILE_SUFFIX.endsWith(".json"))
    }
}
