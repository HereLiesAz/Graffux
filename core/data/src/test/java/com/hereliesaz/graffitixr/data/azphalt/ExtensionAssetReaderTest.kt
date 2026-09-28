package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.AzphaltManifest
import com.hereliesaz.graffitixr.common.azphalt.ExtensionKind
import com.hereliesaz.graffitixr.data.azphalt.sandbox.AzphaltSandboxHost
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ExtensionAssetReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ext(root: File, id: String, files: Set<String>) = InstalledExtension(
        manifest = AzphaltManifest(
            azphalt = "1.0", id = id, name = id, version = "1.0",
            kind = ExtensionKind.CODE, license = "MIT", compat = "1.0",
            files = files.associateWith { "sha256-x" },
        ),
        dir = root.canonicalPath,
        installedAt = 0L,
    )

    private fun write(root: File, path: String, bytes: ByteArray): File =
        File(root, path).apply { parentFile?.mkdirs(); writeBytes(bytes) }

    @Test
    fun `reads a listed asset from the extension's own dir`() {
        val root = tmp.newFolder("extensions", "a")
        write(root, "assets/data.bin", byteArrayOf(1, 2, 3))
        val reader = ExtensionAssetReader(ext(root, "a", setOf("assets/data.bin")))
        assertArrayEquals(byteArrayOf(1, 2, 3), reader.read("assets/data.bin"))
    }

    @Test
    fun `refuses a file present on disk but not listed in manifest files`() {
        val root = tmp.newFolder("extensions", "a")
        write(root, "assets/secret.bin", byteArrayOf(9))
        val reader = ExtensionAssetReader(ext(root, "a", emptySet()))
        assertNull(reader.read("assets/secret.bin"))
    }

    @Test
    fun `refuses traversal, absolute, backslash, NUL, empty and encoded paths`() {
        val root = tmp.newFolder("extensions", "a")
        val outside = write(tmp.root, "outside.txt", byteArrayOf(7))
        val bad = listOf(
            "../outside.txt", "../../outside.txt", "assets/../../outside.txt",
            outside.absolutePath, "/etc/passwd", "C:/Windows/win.ini",
            "..\\outside.txt", "assets\\data.bin", "assets/data.bin\u0000.png", "",
            "%2e%2e/outside.txt", "..%2foutside.txt",
        )
        // Even listing them in the manifest must not make them readable.
        val reader = ExtensionAssetReader(ext(root, "a", bad.toSet()))
        for (p in bad) assertNull("expected null for '$p'", reader.read(p))
    }

    @Test
    fun `cannot read another extension's file`() {
        val extensions = tmp.newFolder("extensions")
        val a = File(extensions, "a").apply { mkdirs() }
        val b = File(extensions, "b").apply { mkdirs() }
        write(b, "assets/b.bin", byteArrayOf(5))
        write(a, "assets/a.bin", byteArrayOf(4))
        val reader = ExtensionAssetReader(ext(a, "a", setOf("assets/a.bin", "../b/assets/b.bin", "assets/b.bin")))
        assertNull(reader.read("../b/assets/b.bin"))
        assertNull(reader.read("assets/b.bin"))
        assertArrayEquals(byteArrayOf(4), reader.read("assets/a.bin"))
    }

    @Test
    fun `a symlink escaping the root is refused`() {
        val root = tmp.newFolder("extensions", "a")
        val outside = write(tmp.root, "outside.txt", byteArrayOf(7))
        File(root, "assets").mkdirs()
        val link = File(root, "assets/link.bin")
        val made = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        assumeTrue("symlinks unsupported here", made)
        val reader = ExtensionAssetReader(ext(root, "a", setOf("assets/link.bin")))
        assertNull(reader.read("assets/link.bin"))
    }

    @Test
    fun `an oversize file is refused and one at the cap is served`() {
        val root = tmp.newFolder("extensions", "a")
        val cap = ExtensionAssetReader.MAX_ASSET_READ_BYTES.toInt()
        write(root, "big.bin", ByteArray(cap + 1))
        write(root, "max.bin", ByteArray(cap))
        val reader = ExtensionAssetReader(ext(root, "a", setOf("big.bin", "max.bin")))
        assertNull(reader.read("big.bin"))
        assertEquals(cap, reader.read("max.bin")?.size)
    }

    @Test
    fun `a missing listed file or a directory returns null`() {
        val root = tmp.newFolder("extensions", "a")
        File(root, "assets").mkdirs()
        val reader = ExtensionAssetReader(ext(root, "a", setOf("assets/gone.bin", "assets")))
        assertNull(reader.read("assets/gone.bin"))
        assertNull(reader.read("assets"))
    }

    @Test
    fun `scoped host serves assetRead and delegates everything else`() {
        val root = tmp.newFolder("extensions", "a")
        write(root, "x.bin", byteArrayOf(8))
        val delegate = object : AzphaltSandboxHost {
            override fun requestRedraw() = Unit
            override fun canvasWidth() = 11
            override fun canvasHeight() = 0
            override fun canvasDpi() = 0
            override fun paramNumber(key: String): Double? = null
            override fun paramBool(key: String): Boolean? = null
            override fun paramString(key: String): String? = null
            override fun colorActive() = 0
            override fun colorSetActive(rgba: Int) = Unit
            override fun assetRead(path: String): ByteArray? = null
            override fun selectionSize() = 0
            override fun selectionRead() = ByteArray(0)
            override fun layerCount() = 0
        }
        val host = ExtensionScopedSandboxHost(delegate, ExtensionAssetReader(ext(root, "a", setOf("x.bin"))))
        assertArrayEquals(byteArrayOf(8), host.assetRead("x.bin"))
        assertEquals(11, host.canvasWidth())
    }

    @Test
    fun `shared path rules`() {
        assertFalse(ExtensionPaths.isUnsafePath("assets/a.png"))
        assertFalse(ExtensionPaths.isUnsafePath("a..b/c"))
        listOf("", "/a", "a\\b", "\\a", "a/../b", "..", "a\u0000", "c:x").forEach {
            assertTrue("expected unsafe: '$it'", ExtensionPaths.isUnsafePath(it))
        }
    }
}
