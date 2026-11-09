package com.hereliesaz.graffux

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chrome's dialogs, alerts, rail labels and accessibility descriptions read from string resources, not
 * from literals in the composables: a literal here is text no translation can reach and no resource lint can
 * see. Scans the files whose text was moved; add a file to [MOVED] when you move its text.
 */
class NoHardCodedChromeTextTest {

    private val moved = listOf(
        "MainActivity", "ToolRail", "LayersRail", "PhotoSheet", "PhotoTabBodies", "RailPackWheel",
        "CarouselSetWheel", "DesignSheet", "CarouselHeroControls", "CarouselHeroExpanded", "BrushCardText",
    )

    // A literal handed to something a user reads or a screen reader speaks.
    private val literal = Regex(
        """(?:\bText\(|contentDescription\s*=\s*|\btitle\s*=\s*|\bmessage\s*=\s*|\bconfirmLabel\s*=\s*|""" +
            """\blabel\s*=\s*|onClick\(label\s*=\s*|onClickLabel\s*=\s*|onLongClickLabel\s*=\s*)""" +
            """"[A-Za-z][^"$]{1}[^"]*"""",
    )

    private fun root(): File =
        listOf(File("src/main/java/com/hereliesaz/graffux"), File("app/src/main/java/com/hereliesaz/graffux"))
            .first { it.isDirectory }

    @Test
    fun `the moved files have no literal user-facing text`() {
        val offenders = moved.flatMap { name ->
            File(root(), "$name.kt").readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                if (literal.containsMatchIn(code) && "testTag" !in code) "$name.kt:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("hard-coded text:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
