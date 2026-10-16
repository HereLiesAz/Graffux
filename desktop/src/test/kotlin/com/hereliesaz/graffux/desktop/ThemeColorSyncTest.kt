package com.hereliesaz.graffux.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GraffuxColors` and the dark scheme in [Theme.kt][GraffuxColors] are hand-copied duplicates of
 * `core:design`'s real theme (`core/design/.../theme/Color.kt` and `Theme.kt`) — see that object's
 * doc comment for why a direct Gradle dependency isn't feasible (`core:design` is an Android
 * library; `:desktop` is a plain-JVM Compose Desktop app).
 *
 * Nothing in the module graph can see both files at compile time, so this compares them as text:
 *  1. each brand token (`val HotPink = Color(0x...)`) must exist in the desktop copy by NAME with
 *     the same value (a name-to-value check, so swapping which name holds which colour fails);
 *  2. the arguments of Android's `DarkColorScheme` must equal the desktop `GraffuxDarkColorScheme`
 *     argument by argument, each resolved to its hex value (`Black`, `GraffuxColors.Black` and
 *     `Color(0xFF000000)` all compare equal), so surface/on-colour/outline drift is caught.
 */
class ThemeColorSyncTest {

    private val root = findRepoRoot()
    private val desktopTheme = root.resolve("desktop/src/main/kotlin/com/hereliesaz/graffux/desktop/Theme.kt")
    private val coreColor = root.resolve("core/design/src/main/java/com/hereliesaz/graffitixr/design/theme/Color.kt")
    private val coreTheme = root.resolve("core/design/src/main/java/com/hereliesaz/graffitixr/design/theme/Theme.kt")

    @Test
    fun `desktop brand tokens match core-design Color-kt by name and value`() {
        listOf(desktopTheme, coreColor).forEach { assertTrue("expected to find ${it.path}", it.isFile) }
        val core = namedTokens(coreColor.readText())
        val desktop = namedTokens(desktopTheme.readText())
        assertTrue("no Color(0x...) tokens found in ${coreColor.path}", core.isNotEmpty())
        val drifted = core.filter { (name, hex) -> desktop[name] != hex }
        assertTrue(
            "GraffuxColors in Theme.kt is missing or has drifted from core:design token(s) $drifted " +
                "(desktop has ${drifted.keys.associateWith { desktop[it] }})",
            drifted.isEmpty(),
        )
    }

    @Test
    fun `desktop dark color scheme matches core-design DarkColorScheme`() {
        listOf(desktopTheme, coreTheme, coreColor).forEach { assertTrue("expected to find ${it.path}", it.isFile) }
        val coreTokens = namedTokens(coreColor.readText())
        val desktopTokens = namedTokens(desktopTheme.readText())
        val core = schemeArguments(coreTheme.readText(), "DarkColorScheme", coreTokens)
        val desktop = schemeArguments(desktopTheme.readText(), "GraffuxDarkColorScheme", desktopTokens)
        assertTrue("no arguments parsed from core:design DarkColorScheme", core.size >= 10)
        assertEquals(
            "desktop GraffuxDarkColorScheme drifted from core:design's DarkColorScheme",
            core,
            desktop,
        )
    }

    private fun findRepoRoot(): File {
        // Gradle runs tests with the module directory (":desktop") as the working directory; walk up
        // to the checkout root, identified by settings.gradle.kts, so this works regardless of how
        // deep that working directory turns out to be.
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("Couldn't locate repo root (no settings.gradle.kts found)")
        }
        return dir
    }

    /** `val Name = Color(0xAARRGGBB)` declarations, name -> uppercase hex. */
    private fun namedTokens(source: String): Map<String, String> =
        Regex("""val\s+(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{6,8})\)""")
            .findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2].uppercase() }

    /** `name = value` arguments of `val <scheme> = ...ColorScheme(...)`, each resolved to hex. */
    private fun schemeArguments(source: String, scheme: String, tokens: Map<String, String>): Map<String, String> {
        val start = source.indexOf("val $scheme")
        require(start >= 0) { "no $scheme in source" }
        val open = source.indexOf('(', start)
        var depth = 0
        var close = open
        while (close < source.length) {
            when (source[close]) {
                '(' -> depth++
                ')' -> if (--depth == 0) break
            }
            close++
        }
        val body = source.substring(open + 1, close)
            .lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
        return Regex("""(\w+)\s*=\s*(Color\(0x[0-9A-Fa-f]{6,8}\)|[\w.]+)""")
            .findAll(body)
            .associate { m ->
                val raw = m.groupValues[2]
                val literal = Regex("""Color\(0x([0-9A-Fa-f]{6,8})\)""").matchEntire(raw)
                m.groupValues[1] to (
                    literal?.groupValues?.get(1)?.uppercase()
                        ?: tokens[raw.substringAfterLast('.')]
                        ?: error("cannot resolve '$raw' for ${m.groupValues[1]} in $scheme")
                    )
            }
    }
}
