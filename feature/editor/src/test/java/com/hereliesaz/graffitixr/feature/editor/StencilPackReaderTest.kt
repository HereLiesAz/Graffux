package com.hereliesaz.graffitixr.feature.editor

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class StencilPackReaderTest {
    private val redPng = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==",
        Base64.DEFAULT,
    )
    private val bluePng = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYPj/HwADAgH/5ncLrgAAAABJRU5ErkJggg==",
        Base64.DEFAULT,
    )
    private val manifest = """{"format":"com.hereliesaz.stencilpack","version":1,"width":1,"height":1,"order":"bottom-to-top","coverage":"overpaint","layers":[{"name":"Base","file":"stencil-sheet-001.png","color":"#ffff0000","visible":true},{"name":"Detail","file":"stencil-sheet-002.png","color":"#ff0000ff","visible":false}]}"""

    private fun archive(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun pack(json: String = manifest) = archive(
        listOf("manifest.json" to json.toByteArray(), "stencil-sheet-001.png" to redPng,
            "stencil-sheet-002.png" to bluePng),
    )

    @Test
    fun readsAlignedSheetsWithNamesPaintOrderAndVisibility() {
        val doc = StencilPackReader.read(pack())!!
        assertEquals(1, doc.width)
        assertEquals(1, doc.height)
        assertEquals(listOf("Base", "Detail"), doc.layers.map { it.name })
        assertEquals(listOf(true, false), doc.layers.map { it.isVisible })
        assertEquals(0xFFFF0000.toInt(), doc.layers[0].pixel(0, 0))
        assertEquals(0xFF0000FF.toInt(), doc.layers[1].pixel(0, 0))
    }

    @Test
    fun rejectsIncompatibleVersionAndTraversal() {
        assertNull(StencilPackReader.read(pack(manifest.replace("\"version\":1", "\"version\":2"))))
        val invalid = archive(
            listOf("manifest.json" to manifest.toByteArray(), "../stencil-sheet-001.png" to redPng,
                "stencil-sheet-002.png" to bluePng),
        )
        assertNull(StencilPackReader.read(invalid))
    }
}
