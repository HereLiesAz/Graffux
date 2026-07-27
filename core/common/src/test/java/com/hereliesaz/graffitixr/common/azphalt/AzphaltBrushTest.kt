package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AzphaltBrushTest {

    private fun params(json: String) = AzphaltJson.decodeFromString(kotlinx.serialization.json.JsonObject.serializer(), json)

    @Test
    fun nullParamsGiveTheBuiltInRoundTip() {
        val b = AzphaltBrush.fromParams("Pen", null)
        assertEquals("Pen", b.name)
        assertEquals(0.1f, b.spacing, 0f)
        assertEquals(1f, b.opacity, 0f)
        assertEquals(1f, b.hardness, 0f)
        assertEquals(0f, b.sizeJitter, 0f)
        assertNull(b.shapePath)
        assertNull(b.grainPath)
        assertFalse(b.followStroke)
    }

    @Test
    fun readsDeclaredDynamics() {
        val b = AzphaltBrush.fromParams(
            "Inker",
            params("""{"spacing":0.03,"opacity":0.8,"hardness":0.5,"sizeJitter":0.2,"scatter":1.5,"followStroke":true}"""),
        )
        assertEquals(0.03f, b.spacing, 1e-6f)
        assertEquals(0.8f, b.opacity, 1e-6f)
        assertEquals(0.5f, b.hardness, 1e-6f)
        assertEquals(0.2f, b.sizeJitter, 1e-6f)
        assertEquals(1.5f, b.scatter, 1e-6f)
        assertTrue(b.followStroke)
    }

    @Test
    fun clampsOutOfRangeValuesToSaneBounds() {
        val b = AzphaltBrush.fromParams(
            "Wild",
            params("""{"spacing":99,"opacity":5,"hardness":-1,"sizeJitter":2,"scatter":-3}"""),
        )
        assertEquals(4f, b.spacing, 0f)       // spacing capped at 4
        assertEquals(1f, b.opacity, 0f)       // opacity capped at 1
        assertEquals(0f, b.hardness, 0f)      // hardness floored at 0
        assertEquals(1f, b.sizeJitter, 0f)    // jitter capped at 1
        assertEquals(0f, b.scatter, 0f)       // scatter floored at 0
    }

    @Test
    fun acceptsEitherStampKeySpelling() {
        val a = AzphaltBrush.fromParams("A", params("""{"shape":"stamps/chalk.png","grain":"grain/paper.png"}"""))
        assertEquals("stamps/chalk.png", a.shapePath)
        assertEquals("grain/paper.png", a.grainPath)
        val b = AzphaltBrush.fromParams("B", params("""{"shapePath":"s.png","grainPath":"g.png"}"""))
        assertEquals("s.png", b.shapePath)
        assertEquals("g.png", b.grainPath)
    }

    @Test
    fun blankStampPathIsTreatedAsAbsent() {
        val b = AzphaltBrush.fromParams("C", params("""{"shape":"  "}"""))
        assertNull(b.shapePath)
    }

    @Test
    fun malformedValuesFallBackToDefaults() {
        // A string where a number is expected must not throw — it degrades to the default.
        val b = AzphaltBrush.fromParams("D", params("""{"spacing":"lots"}"""))
        assertEquals(0.1f, b.spacing, 0f)
    }

    // ── sanitized(): the Brush Studio construction path ──────────────────────────────────────

    @Test
    fun sanitizedClampsEveryParameter() {
        val wild = AzphaltBrush(
            name = "  ",
            spacing = 99f,
            opacity = 5f,
            hardness = -3f,
            sizeJitter = 2f,
            opacityJitter = -1f,
            scatter = -4f,
        ).sanitized()

        assertEquals("Custom Brush", wild.name)
        assertEquals(4f, wild.spacing, 0f)
        assertEquals(1f, wild.opacity, 0f)
        assertEquals(0f, wild.hardness, 0f)
        assertEquals(1f, wild.sizeJitter, 0f)
        assertEquals(0f, wild.opacityJitter, 0f)
        assertEquals(0f, wild.scatter, 0f)
    }

    @Test
    fun sanitizedAgreesWithFromParamsOnTheSameInputs() {
        // The two construction paths must not drift: a brush dialled in Brush Studio and the same
        // brush arriving as an installed extension have to land on identical values.
        val viaParser = AzphaltBrush.fromParams(
            "P",
            params("""{"spacing":9.0,"opacity":2.0,"hardness":-1.0,"sizeJitter":3.0,"scatter":-4.0}"""),
        )
        val viaStudio = AzphaltBrush(
            name = "P", spacing = 9f, opacity = 2f, hardness = -1f, sizeJitter = 3f, scatter = -4f,
        ).sanitized()
        assertEquals(viaParser, viaStudio)
    }

    @Test
    fun sanitizedLeavesAnAlreadyValidBrushUntouched() {
        val ok = AzphaltBrush(name = "Inker", spacing = 0.05f, opacity = 0.9f, hardness = 0.8f)
        assertEquals(ok, ok.sanitized())
    }
}
