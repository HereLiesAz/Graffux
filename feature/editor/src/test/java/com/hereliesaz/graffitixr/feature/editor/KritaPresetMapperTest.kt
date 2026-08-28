package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import com.hereliesaz.graffitixr.feature.editor.util.KritaPresetMapper
import com.hereliesaz.graffitixr.feature.editor.util.KritaPresetParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures below are realistic excerpts of the actual param key/value pairs found in
 * `plugins/paintops/defaultpresets/colorsmudge.kpp` (a real preset shipped in the KDE/krita
 * repository, `invent.kde.org/graphics/krita`, `master` branch), decompressed and inspected
 * directly (its "preset" text chunk is `zTXt`-compressed, not plain `tEXt`/`iTXt` -- see the note
 * on `KritaPresetParser`'s own container-format completion state). `SmudgeRateSmearAlpha` is not
 * present in that real file (see [KritaPresetMapper.toColorSmudgeSettings]'s doc for why) so its
 * test coverage below is source-only, not cross-checked against a live file.
 */
class KritaPresetMapperTest {

    private fun preset(paintopId: String, name: String, paramsXml: String): KritaPresetParser.Preset {
        val xml = """<Preset paintopid="$paintopId" name="$name">$paramsXml</Preset>"""
        return KritaPresetParser.parsePresetXml(xml)
    }

    @Test
    fun `maps a real-shaped colorsmudge preset to ColorSmudgeEngine Settings`() {
        // Realistic excerpt: SmudgeRateMode/Value, ColorRateValue, OpacityValue, PressureOpacity
        // are verbatim key names as they appear in the real colorsmudge.kpp default preset.
        val p = preset(
            "colorsmudge",
            "defaultSmudge",
            """
            <param name="SmudgeRateMode">0</param>
            <param name="SmudgeRateValue">0.5</param>
            <param name="ColorRateValue">0.35</param>
            <param name="OpacityValue">1</param>
            <param name="PressureOpacity">true</param>
            """.trimIndent(),
        )

        val settings = KritaPresetMapper.toColorSmudgeSettings(p)

        requireNotNull(settings)
        assertEquals(ColorSmudgeEngine.Mode.SMEAR, settings.mode)
        assertEquals(0.5f, settings.smudgeRate)
        assertEquals(0.35f, settings.colorRate)
        assertEquals(1f, settings.opacity)
    }

    @Test
    fun `SmudgeRateMode 1 maps to DULLING`() {
        val p = preset("colorsmudge", "x", """<param name="SmudgeRateMode">1</param>""")

        val settings = KritaPresetMapper.toColorSmudgeSettings(p)

        assertEquals(ColorSmudgeEngine.Mode.DULLING, requireNotNull(settings).mode)
    }

    @Test
    fun `smearAlpha maps from SmudgeRateSmearAlpha`() {
        val p = preset(
            "colorsmudge",
            "x",
            """<param name="SmudgeRateSmearAlpha">false</param>""",
        )

        val settings = KritaPresetMapper.toColorSmudgeSettings(p)

        assertEquals(false, requireNotNull(settings).smearAlpha)
    }

    @Test
    fun `smudgeRadius divides by 100 when SmudgeRadiusVersion is absent (legacy percent storage)`() {
        val p = preset(
            "colorsmudge",
            "x",
            """<param name="SmudgeRadiusValue">50</param>""",
        )

        val settings = KritaPresetMapper.toColorSmudgeSettings(p)

        assertEquals(0.5f, requireNotNull(settings).smudgeRadius, 0.0001f)
    }

    @Test
    fun `smudgeRadius is used as-is when SmudgeRadiusVersion is 2 or newer`() {
        val p = preset(
            "colorsmudge",
            "x",
            """
            <param name="SmudgeRadiusVersion">2</param>
            <param name="SmudgeRadiusValue">0.5</param>
            """.trimIndent(),
        )

        val settings = KritaPresetMapper.toColorSmudgeSettings(p)

        assertEquals(0.5f, requireNotNull(settings).smudgeRadius, 0.0001f)
    }

    @Test
    fun `unmapped fields keep ColorSmudgeEngine Settings defaults`() {
        val p = preset("colorsmudge", "x", """<param name="SmudgeRateValue">0.9</param>""")

        val settings = KritaPresetMapper.toColorSmudgeSettings(p)

        val defaults = ColorSmudgeEngine.Settings()
        requireNotNull(settings)
        assertEquals(defaults.radiusPx, settings.radiusPx)
        assertEquals(defaults.feathering, settings.feathering)
        assertEquals(defaults.wrapAround, settings.wrapAround)
        assertEquals(defaults.dilution, settings.dilution)
        assertEquals(defaults.chargeDecayRate, settings.chargeDecayRate)
        assertEquals(defaults.dynamics, settings.dynamics)
        assertEquals(defaults.sampleMerged, settings.sampleMerged)
    }

    @Test
    fun `returns null for a non-colorsmudge paintopId`() {
        val p = preset("paintbrush", "x", """<param name="OpacityValue">1</param>""")

        assertNull(KritaPresetMapper.toColorSmudgeSettings(p))
    }

    @Test
    fun `toAzphaltBrush maps generic OpacityValue and leaves everything else default`() {
        val p = preset("paintbrush", "My Brush", """<param name="OpacityValue">0.7</param>""")

        val brush = KritaPresetMapper.toAzphaltBrush(p)

        assertEquals("My Brush", brush.name)
        assertEquals(0.7f, brush.opacity)
        // Untouched defaults -- nothing else was guessed at.
        assertEquals(0.1f, brush.spacing)
        assertEquals(1f, brush.tipRatio)
        assertTrue(brush.dynamics.isEmpty())
    }

    @Test
    fun `toAzphaltBrush without OpacityValue keeps default opacity`() {
        val p = preset("paintbrush", "x", "")

        val brush = KritaPresetMapper.toAzphaltBrush(p)

        assertEquals(1f, brush.opacity)
    }
}
