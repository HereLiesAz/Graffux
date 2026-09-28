package com.hereliesaz.graffux.desktop

import java.awt.image.BufferedImage
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasStateExportTest {

    @Test
    fun `exports within the same second get distinct files`() {
        val dir = Files.createTempDirectory("graffux-export").toFile()
        try {
            val state = CanvasState()
            state.commitStroke(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB))

            val files = List(3) { state.exportPng(dir) }.map { assertNotNull(it); it!! }

            assertEquals("every export lands in its own file", 3, files.toSet().size)
            assertTrue(files.all { it.length() > 0 })
            assertEquals(3, dir.listFiles()!!.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
