package com.hereliesaz.graffux.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GraffuxColors` (in [Theme.kt][GraffuxColors]) is a hand-copied duplicate of `core:design`'s real
 * color tokens (`core/design/.../theme/Color.kt`) — see that object's doc comment for why a direct
 * Gradle dependency isn't feasible (`core:design` is an Android library; `:desktop` is a plain-JVM
 * Compose Desktop app).
 *
 * Since nothing in the module graph can see both files at compile time, this compares them as text
 * instead: it extracts every `0xAARRGGBB`-style hex literal from each source file, in file order,
 * and fails the moment the two lists diverge — catching drift a compiler never would.
 */
class ThemeColorSyncTest {

    @Test
    fun `desktop GraffuxColors literals match core-design Color-kt`() {
        val desktopFile = findRepoRoot().resolve(
            "desktop/src/main/kotlin/com/hereliesaz/graffux/desktop/Theme.kt"
        )
        val coreDesignFile = findRepoRoot().resolve(
            "core/design/src/main/java/com/hereliesaz/graffitixr/design/theme/Color.kt"
        )

        assertTrue("expected to find ${desktopFile.path}", desktopFile.isFile)
        assertTrue("expected to find ${coreDesignFile.path}", coreDesignFile.isFile)

        val desktopHex = extractHexColorLiterals(desktopFile.readText())
        val coreDesignHex = extractHexColorLiterals(coreDesignFile.readText())

        assertTrue("no Color(0x...) literals found in ${desktopFile.path}", desktopHex.isNotEmpty())
        assertEquals(
            "GraffuxColors in Theme.kt has drifted from core:design's Color.kt — update the copy " +
                "in Theme.kt (or this test, if core:design's palette is the one that changed on " +
                "purpose) so the two stay in sync.",
            coreDesignHex,
            desktopHex,
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

    private fun extractHexColorLiterals(source: String): List<String> =
        Regex("""Color\(0x([0-9A-Fa-f]{6,8})\)""")
            .findAll(source)
            .map { it.groupValues[1].uppercase() }
            .toList()
}
