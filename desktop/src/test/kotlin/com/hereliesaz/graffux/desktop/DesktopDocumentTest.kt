package com.hereliesaz.graffux.desktop

import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import java.util.prefs.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopDocumentTest {

    private fun painted(argb: Int, w: Int = 4, h: Int = 4) =
        BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).apply { for (y in 0 until h) for (x in 0 until w) setRGB(x, y, argb) }

    private fun document(): CanvasState = CanvasState().apply { ensureSize(4, 4) }

    @Test
    fun `the first layout makes one clear layer and a clean document`() {
        val state = document()
        assertEquals(1, state.layers.size)
        assertEquals("Layer 1", state.activeLayer?.name)
        assertFalse(state.isDirty)
        assertFalse(state.canUndo)
    }

    @Test
    fun `layer edits are undo steps and redo replays them`() {
        val state = document()
        state.addLayer()
        assertEquals(listOf("Layer 1", "Layer 2"), state.layers.map { it.name })
        assertEquals("Layer 2", state.activeLayer?.name)
        state.commitStroke(painted(0xFFFF0000.toInt()))
        state.setVisible(state.activeLayerId!!, false)
        assertTrue(state.isDirty)

        state.undo()
        assertTrue(state.activeLayer!!.visible)
        state.undo()
        state.undo()
        assertEquals(1, state.layers.size)
        state.redo()
        assertEquals(2, state.layers.size)
    }

    @Test
    fun `strokes paint the active layer only and the flattened image stacks them`() {
        val state = document()
        state.commitStroke(painted(0xFFFF0000.toInt()))
        state.addLayer()
        state.commitStroke(painted(0xFF0000FF.toInt()))
        assertEquals(0xFFFF0000.toInt(), state.layers[0].image.getRGB(0, 0))
        assertEquals(0xFF0000FF.toInt(), state.flatten()!!.getRGB(0, 0))

        state.setVisible(state.activeLayerId!!, false)
        assertEquals(0xFFFF0000.toInt(), state.flatten()!!.getRGB(0, 0))
        assertEquals(0xFFFF0000.toInt(), state.compositeBelowActive()!!.getRGB(0, 0))
        assertNull(state.compositeAboveActive())
    }

    @Test
    fun `an opacity drag is one undo step`() {
        val state = document()
        val id = state.activeLayerId!!
        listOf(0.9f, 0.6f, 0.3f).forEach { state.setOpacity(id, it) }
        state.endContinuousEdit()
        assertEquals(0.3f, state.activeLayer!!.opacity)
        state.undo()
        assertEquals(1f, state.activeLayer!!.opacity)
        assertFalse(state.canUndo)
    }

    @Test
    fun `reorder, merge down and delete keep a layer to paint on`() {
        val state = document()
        state.commitStroke(painted(0xFFFF0000.toInt()))
        state.addLayer()
        val top = state.activeLayerId!!
        state.moveLayer(top, -1)
        assertEquals(top, state.layers.first().id)
        state.moveLayer(top, 1)
        state.mergeDown(top)
        assertEquals(1, state.layers.size)
        state.deleteLayer(state.activeLayerId!!)
        assertEquals("the last layer is cleared, not removed", 1, state.layers.size)
        assertEquals(0, state.layers.single().image.getRGB(0, 0))
    }

    @Test
    fun `a resize keeps every layer the canvas size without an undo step`() {
        val state = document()
        state.addLayer()
        state.ensureSize(8, 6)
        assertTrue(state.layers.all { it.image.width == 8 && it.image.height == 6 })
        state.undo()
        state.ensureSize(8, 6)
        assertTrue(state.layers.all { it.image.width == 8 && it.image.height == 6 })
    }

    @Test
    fun `a project round-trips its layers, names, visibility, opacity and active layer`() {
        val dir = Files.createTempDirectory("graffux-project").toFile()
        try {
            val state = document()
            state.commitStroke(painted(0xFF00FF00.toInt()))
            state.addLayer()
            state.rename(state.activeLayerId!!, "Ink")
            state.setOpacity(state.activeLayerId!!, 0.5f)
            state.addLayer()
            state.setVisible(state.activeLayerId!!, false)
            state.setActive(state.layers[1].id)
            val file = java.io.File(dir, "mural.${ProjectFile.EXTENSION}")

            ProjectFile.save(state.currentSnapshot(), file)
            val loaded = ProjectFile.load(file)

            assertEquals(listOf("Layer 1", "Ink", "Layer 3"), loaded.layers.map { it.name })
            assertEquals(listOf(1f, 0.5f, 1f), loaded.layers.map { it.opacity })
            assertEquals(listOf(true, true, false), loaded.layers.map { it.visible })
            assertEquals(loaded.layers[1].id, loaded.activeId)
            assertEquals(0xFF00FF00.toInt(), loaded.layers[0].image.getRGB(2, 2))
            assertEquals("no temp file left behind", listOf(file.name), dir.list()!!.toList())

            val reopened = CanvasState().apply { load(loaded) }
            assertFalse(reopened.isDirty)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(expected = IOException::class)
    fun `a file that is not a project is refused`() {
        val file = Files.createTempFile("not-a-project", ".${ProjectFile.EXTENSION}").toFile()
        try {
            file.writeText("hello")
            ProjectFile.load(file)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `recent projects are newest first, unique, and drop missing files`() {
        val node = Preferences.userRoot().node("com/hereliesaz/graffux/desktop/test-${System.nanoTime()}")
        val dir = Files.createTempDirectory("graffux-recent").toFile()
        try {
            val recent = RecentProjects(node)
            val a = java.io.File(dir, "a.graffuxd").apply { writeText("a") }
            val b = java.io.File(dir, "b.graffuxd").apply { writeText("b") }
            recent.add(a)
            recent.add(b)
            recent.add(a)
            assertEquals(listOf(a.absoluteFile, b.absoluteFile), recent.list())
            b.delete()
            assertEquals(listOf(a.absoluteFile), recent.list())
        } finally {
            node.removeNode()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `shortcut labels name the platform's command key`() {
        val label = Shortcuts.Redo.label
        assertEquals(if (IsMac) "⇧⌘Z" else "⇧^Z", label)
        assertNotEquals(Shortcuts.Save.keyShortcut(), Shortcuts.SaveAs.keyShortcut())
    }
}
