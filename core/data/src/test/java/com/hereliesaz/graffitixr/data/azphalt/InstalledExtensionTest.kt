package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.AzphaltManifest
import com.hereliesaz.graffitixr.common.azphalt.ExtensionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [InstalledExtension.filePath] is the resolver every manifest-declared path (an asset, a brush
 * stamp/grain image, [AzphaltManifest.entry] itself) goes through before being read or executed.
 * Unlike the zip-entry names [AzpInstaller] validates at install time, these strings are read back
 * out of an already-installed manifest and are just as attacker-controlled — including in a package
 * that was never signed at all. A traversal here is a read of, or code execution from, anywhere the
 * app's own process can reach on disk.
 */
class InstalledExtensionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun extension(dir: String) = InstalledExtension(
        manifest = AzphaltManifest(
            azphalt = "1.0", id = "com.test.ext", name = "Test", version = "1.0",
            kind = ExtensionKind.ASSET, license = "MIT", compat = "1.0",
        ),
        dir = dir,
        installedAt = 0L,
    )

    @Test
    fun `filePath resolves an ordinary relative path inside the extension`() {
        val root = tmp.newFolder("com.test.ext")
        File(root, "assets").mkdirs()
        File(root, "assets/grade.cube").writeText("x")
        val ext = extension(root.canonicalPath)

        val resolved = ext.filePath("assets/grade.cube")

        assertEquals(File(root, "assets/grade.cube").canonicalPath, resolved)
    }

    @Test
    fun `filePath refuses a manifest entry that walks out of the extension directory`() {
        val root = tmp.newFolder("com.test.ext")
        val ext = extension(root.canonicalPath)

        assertNull(ext.filePath("../../../../../../etc/passwd"))
        assertNull(ext.filePath("../sibling-extension/manifest.json"))
    }

    @Test
    fun `a leading slash does not jump to the filesystem root`() {
        // File(parent, child)'s two-argument form treats a leading separator on `child` as part of
        // the path under `parent`, not an absolute override — java.io.File itself defuses this one,
        // and the containment check confirms it: the result must still land inside the extension.
        val root = tmp.newFolder("com.test.ext")
        val ext = extension(root.canonicalPath)

        val resolved = ext.filePath("/etc/passwd")

        assertEquals(File(root, "etc/passwd").canonicalPath, resolved)
        assertTrue(resolved!!.startsWith(root.canonicalPath))
    }

    @Test
    fun `filePath accepts a path that merely starts with the same prefix as a sibling directory`() {
        // Regression guard for a naive startsWith(root.path) check: "extensions/com.test.ext-evil"
        // starts with the string "extensions/com.test.ext" without being inside it at all.
        val parent = tmp.newFolder("extensions")
        val root = File(parent, "com.test.ext").apply { mkdirs() }
        val sibling = File(parent, "com.test.ext-evil").apply { mkdirs() }
        File(sibling, "payload.wasm").writeText("evil")
        val ext = extension(root.canonicalPath)

        assertNull(ext.filePath("../com.test.ext-evil/payload.wasm"))
    }

    @Test
    fun `filePath allows the extension's own root`() {
        val root = tmp.newFolder("com.test.ext")
        val ext = extension(root.canonicalPath)

        assertEquals(root.canonicalPath, ext.filePath("."))
        assertTrue(File(ext.filePath(".")!!).isDirectory)
    }
}
