package com.hereliesaz.graffux

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app module's chrome reads its text from string resources, not from literals in the composables: a
 * literal is text no translation can reach and no resource lint can see.
 *
 * Scans **every** `.kt` file under the app's main source (a new file cannot be missed), with comments
 * removed, across lines, for a string literal handed to: `Text(`, or a named argument `contentDescription`,
 * `stateDescription`, `title`, `message`, `subtitle`, `text`, `label`, `placeholder`, `hint`,
 * `confirmLabel`, `onClickLabel`, `onLongClickLabel`, `onClick(label =`, including after an `if (..)`
 * (`contentDescription = if (x) "A" else "B"` is caught at "A"; the else branch is not separately checked).
 * A literal counts when it starts with a letter or `$`.
 *
 * Limits: a literal passed through a variable, a `when`, a function return or a data class field
 * (ToolCatalog, the carousel plans) is not seen, and neither is an else branch. [KNOWN_LITERALS] counts the
 * literals each file still holds; the counts may only shrink. Two are not user text (animation labels in
 * CarouselStripMotion and CarouselEffectPreviews).
 */
class NoHardCodedChromeTextTest {

    private val literal = Regex(
        """(?<!val )(?<!var )(?<![A-Za-z.])""" +
            """(?:Text\(|(?:contentDescription|stateDescription|title|message|subtitle|text|label|placeholder|""" +
            """hint|confirmLabel|onClickLabel|onLongClickLabel)\s*=|onClick\(label\s*=)""" +
            """\s*(?:if\s*\([^)]*\)\s*)?"[A-Za-z$]""",
    )

    private fun root(): File =
        listOf(File("src/main/java/com/hereliesaz/graffux"), File("app/src/main/java/com/hereliesaz/graffux"))
            .first { it.isDirectory }

    private fun literalsIn(file: File): List<String> {
        val code = file.readText()
            .replace(Regex("(?s)/\\*.*?\\*/")) { m -> m.value.filter { it == '\n' } }
            .replace(Regex("(?m)(?<!:)//.*$"), "")
        return literal.findAll(code).map { m ->
            val line = code.substring(0, m.range.first).count { it == '\n' } + 1
            "${file.name}:$line: ${m.value.trim().replace('\n', ' ')}"
        }.toList()
    }

    @Test
    fun `every file holds exactly the literals it is known to hold, and new files none`() {
        val actual = root().walkTopDown().filter { it.extension == "kt" }
            .associate { it.name to literalsIn(it) }.filterValues { it.isNotEmpty() }
        val counts = actual.mapValues { it.value.size }
        assertEquals(
            "literal user-facing text changed (convert it, or update KNOWN_LITERALS downward as it is moved):\n" +
                actual.values.flatten().joinToString("\n"),
            KNOWN_LITERALS,
            counts,
        )
    }

    @Test
    fun `the scan sees the shapes it claims to`() {
        val sample = File.createTempFile("shape", ".kt").apply {
            writeText(
                "Text(\n \"A\")\nsubtitle = \"B\"\n" +
                    "contentDescription = if (x) \"C\" else \"D\"\nlabel = \"\$n items\"\n" +
                    "val text = \"not ui\"\n// Text(\"comment\")\nval u = \"https://example.org\"\n",
            )
        }
        val found = literalsIn(sample).map { it.substringAfterLast(": ").trim() }
        sample.delete()
        assertEquals(4, found.size)
    }

    private companion object {
        /** File to how many literals it still holds. Move text to resources and lower the count. */
        val KNOWN_LITERALS = mapOf(
            "LayersRail.kt" to 1, "BottomCarousel.kt" to 1, "RotationSlider.kt" to 2, "PhotoTabBodies.kt" to 1,
            "DesignPanels.kt" to 2, "CarouselCardVisuals.kt" to 1, "ToolGroupCarouselPlan.kt" to 1,
            "PhotoRetouch.kt" to 3, "ToolRail.kt" to 6, "CarouselStripMotion.kt" to 1,
            "CarouselEffectPreviews.kt" to 1, "CarouselDetent.kt" to 1, "DesignSheet.kt" to 2,
            "PhotoSheet.kt" to 1, "SurfacePrompt.kt" to 1,
        )
    }
}
