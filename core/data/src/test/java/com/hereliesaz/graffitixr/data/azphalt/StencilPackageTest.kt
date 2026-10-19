package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.ExtensionKind
import com.hereliesaz.graffitixr.common.azphalt.Runtime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Assembles the Stencil effect (`extensions/stencil`) the way `build.py` does, from its real
 * manifest template, panel, licence and the test-resource copy of the guest WASM, and installs it
 * through [AzpInstaller]: the check that what the store ships is a package the app accepts.
 */
class StencilPackageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val root = File("../../extensions/stencil")

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `the stencil package installs and declares its filter`() {
        val wasm = javaClass.getResourceAsStream("/azphalt/stencil/stencil.wasm")!!.use { it.readBytes() }
        val payload = mapOf(
            "code/stencil.wasm" to wasm,
            "ui/panel.json" to File(root, "ui/panel.json").readBytes(),
            "LICENSE" to File(root, "LICENSE").readBytes(),
        )
        val files = payload.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"sha256-${sha256(v)}\"" }
        val manifest = File(root, "manifest.template.json").readText()
            .replace("@VERSION@", "1.0.0").replace("@FILES@", files)

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in payload + ("manifest.json" to manifest.toByteArray())) {
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
        }

        val installer = AzpInstaller(tmp.newFolder("extensions"))
        val installed = installer.install(ByteArrayInputStream(out.toByteArray()), nowMs = 1L)

        assertEquals("com.hereliesaz.stencil", installed.id)
        assertEquals(ExtensionKind.CODE, installed.manifest.kind)
        assertEquals(Runtime.WASM, installed.manifest.runtime)
        assertEquals(setOf("bitmap", "params"), installed.manifest.capabilities!!.map { it.wire }.toSet())
        val filter = installed.manifest.contributes!!.filters!!.single()
        assertEquals("stencil", filter.id)
        assertNotNull("the filter's entry resolves inside the install", installed.filePath(filter.entry!!))
        assertTrue(File(installed.filePath(filter.entry!!)!!).isFile)
        assertTrue(File(installed.filePath(filter.ui!!)!!).isFile)
    }
}
