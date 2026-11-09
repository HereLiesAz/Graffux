package com.hereliesaz.graffitixr.feature.editor

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stroke feeds must take the surface-stroke guards (finger guard, visibility) as required
 * parameters. The type system enforces it for callers; this pins the declarations so nobody
 * re-adds a permissive default (`= { ... -> true }`) that would let a path skip the guards.
 */
class StrokeFeedGuardsRequiredTest {
    private val source: String by lazy {
        val rel = "src/main/java/com/hereliesaz/graffitixr/feature/editor/SceneRender.kt"
        listOf(File(rel), File("feature/editor/$rel")).first { it.exists() }.readText()
    }

    private val guardNames = listOf("sampleOk", "joinOk")

    @Test
    fun `no guard parameter in SceneRender has a default`() {
        val offenders = source.lines().filter { line ->
            guardNames.any { Regex("""\b$it\s*:\s*\(.*\)\s*->\s*Boolean\s*=""").containsMatchIn(line) }
        }
        assertTrue("Guard parameters must be required, found defaults: $offenders", offenders.isEmpty())
    }

    @Test
    fun `both feeds and planeSegments still declare the guards`() {
        for (decl in listOf("class PlaneStrokeFeed<S>(", "class ViewStrokeFeed<S>(", "fun planeSegments(")) {
            val start = source.indexOf(decl)
            assertTrue("$decl missing", start >= 0)
            val header = source.substring(start, source.indexOf("\n)", start).let { if (it < 0) start + 2000 else it })
            guardNames.forEach { assertTrue("$decl must declare $it", header.contains("$it:")) }
        }
    }
}
