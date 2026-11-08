@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.data.azphalt

import android.content.Context
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.scene.SurfaceParse
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Packs of surfaces as an installed host sees them: discovery, and a strict read of each payload. */
class InstalledSurfacesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun repository(): ExtensionRepository {
        val context = mockk<Context>()
        every { context.filesDir } returns tmp.newFolder("files")
        every { context.cacheDir } returns tmp.newFolder("cache")
        val idle: CoroutineDispatcher = StandardTestDispatcher()
        val dispatchers = object : DispatcherProvider {
            override val main = idle
            override val io = idle
            override val default = idle
            override val unconfined = idle
        }
        return ExtensionRepository(context, dispatchers).also { it.enforceRootEndorsements = false }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val slab = """{"format":"azphalt.surface","version":1,"name":"Slab","front":[[0.05,0.25],[0.95,0.25],[0.95,0.75],[0.05,0.75]]}"""
    private val bad = """{"format":"azphalt.surface","version":1,"name":"Bad","front":[[0,0],[9,1]]}"""

    private fun pack(id: String, assets: String, payload: Map<String, String>): ByteArray {
        val bytes = payload.mapValues { it.value.toByteArray() } + ("LICENSE" to "CC0-1.0\n".toByteArray())
        val files = bytes.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"sha256-${sha(v)}\"" }
        val manifest = """{"azphalt":"0.1","id":"$id","name":"Pack $id","version":"1.0.0","kind":"asset","license":"CC0-1.0","compat":">=0.1","assets":$assets,"files":$files}"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((n, b) in bytes + ("manifest.json" to manifest.toByteArray())) {
                z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `installed surfaces are listed by name and each loads`() {
        val repo = repository()
        assertTrue(repo.installedSurfaceAssets().isEmpty())
        repo.installFromStream(
            ByteArrayInputStream(pack("com.x.surf", """[{"type":"surface","path":"assets/slab.json","params":{"name":"Slab"}},{"type":"brush","path":"assets/t.png"},{"type":"surface","path":"assets/bad.json","params":{"name":"Bad"}}]""",
                mapOf("assets/slab.json" to slab, "assets/bad.json" to bad, "assets/t.png" to "x"))),
            nowMs = 1L,
        )
        val listed = repo.installedSurfaceAssets()
        assertEquals(listOf("Slab", "Bad"), listed.map { it.name })
        assertEquals(listOf(0, 1), listed.map { it.assetIndex })
        val ok = repo.loadSurface("com.x.surf", 0)
        assertTrue(ok is SurfaceParse.Ok)
        assertEquals("Slab", (ok as SurfaceParse.Ok).payload.name)
        // A package that signs off on a malformed payload is still rejected on read.
        assertTrue(repo.loadSurface("com.x.surf", 1) is SurfaceParse.Rejected)
        assertTrue(repo.loadSurface("com.x.surf", 5) is SurfaceParse.Rejected)
        assertTrue(repo.loadSurface("com.nope", 0) is SurfaceParse.Rejected)
    }

    @Test
    fun `a remote or non-json surface entry is not offered`() {
        val repo = repository()
        repo.installFromStream(
            ByteArrayInputStream(pack("com.x.remote", """[{"type":"surface","path":"","remoteUrl":"https://example.com/s.json","checksum":"sha256-${"0".repeat(64)}"},{"type":"surface","path":"assets/s.bin"}]""",
                mapOf("assets/s.bin" to slab))),
            nowMs = 1L,
        )
        assertTrue(repo.installedSurfaceAssets().isEmpty())
    }
}
