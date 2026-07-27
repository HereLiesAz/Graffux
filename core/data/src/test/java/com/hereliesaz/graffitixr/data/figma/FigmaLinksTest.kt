package com.hereliesaz.graffitixr.data.figma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The link parsing users actually exercise: they paste whatever "Copy link" gave them, which over
 * the years has been several different path shapes.
 */
class FigmaLinksTest {

    @Test
    fun `extracts the key from every path prefix Figma has used`() {
        val key = "abcDEF123456"
        listOf("file", "design", "proto", "board", "slides").forEach { prefix ->
            assertEquals(
                "prefix $prefix",
                key,
                FigmaLinks.fileKey("https://www.figma.com/$prefix/$key/My-Design"),
            )
        }
    }

    @Test
    fun `ignores query strings and fragments`() {
        // "Copy link" appends node-id and other params; they must not end up in the key.
        assertEquals(
            "KEY123",
            FigmaLinks.fileKey("https://www.figma.com/design/KEY123/Name?node-id=1-2&t=abc#section"),
        )
    }

    @Test
    fun `accepts a bare key and trims surrounding whitespace`() {
        assertEquals("KEY123", FigmaLinks.fileKey("  KEY123  "))
    }

    @Test
    fun `rejects input with nothing key-shaped in it`() {
        assertNull(FigmaLinks.fileKey(""))
        assertNull(FigmaLinks.fileKey("   "))
        assertNull(FigmaLinks.fileKey("https://www.figma.com/"))
        // A known prefix with no segment after it.
        assertNull(FigmaLinks.fileKey("https://www.figma.com/design/"))
        // A figma URL that isn't a file link at all.
        assertNull(FigmaLinks.fileKey("https://www.figma.com/settings"))
    }

    @Test
    fun `rejects a key containing illegal characters`() {
        assertNull(FigmaLinks.fileKey("not a key"))
    }

    // ── frames() ─────────────────────────────────────────────────────────────────────────────

    private fun node(
        id: String,
        name: String,
        type: String,
        children: List<FigmaNode> = emptyList(),
        w: Float = 100f,
        h: Float = 100f,
    ) = FigmaNode(id, name, type, children, FigmaRect(0f, 0f, w, h))

    @Test
    fun `lists renderable top-level nodes tagged with their page`() {
        val doc = node(
            "0:0", "Document", "DOCUMENT",
            listOf(
                node("1:0", "Page One", "CANVAS", listOf(node("1:1", "Cover", "FRAME", w = 800f, h = 600f))),
                node("2:0", "Page Two", "CANVAS", listOf(node("2:1", "Cover", "COMPONENT"))),
            ),
        )
        val frames = FigmaLinks.frames(doc)
        assertEquals(2, frames.size)
        // Two frames share a name; the page is what keeps them apart in the picker.
        assertEquals("Page One", frames[0].pageName)
        assertEquals("Page Two", frames[1].pageName)
        assertEquals(800f, frames[0].width, 0f)
        assertEquals(600f, frames[0].height, 0f)
    }

    @Test
    fun `skips node types that are not worth offering as a frame`() {
        val doc = node(
            "0:0", "Document", "DOCUMENT",
            listOf(
                node(
                    "1:0", "Page", "CANVAS",
                    listOf(
                        node("1:1", "Frame", "FRAME"),
                        node("1:2", "A stray label", "TEXT"),
                        node("1:3", "A stray line", "VECTOR"),
                    ),
                ),
            ),
        )
        assertEquals(listOf("Frame"), FigmaLinks.frames(doc).map { it.name })
    }

    @Test
    fun `skips zero-area nodes that Figma could not render`() {
        val doc = node(
            "0:0", "Document", "DOCUMENT",
            listOf(node("1:0", "Page", "CANVAS", listOf(node("1:1", "Empty", "FRAME", w = 0f, h = 0f)))),
        )
        assertEquals(emptyList<FigmaFrame>(), FigmaLinks.frames(doc))
    }

    @Test
    fun `a node with no bounding box is skipped rather than crashing`() {
        val doc = FigmaNode(
            "0:0", "Document", "DOCUMENT",
            listOf(
                FigmaNode(
                    "1:0", "Page", "CANVAS",
                    listOf(FigmaNode("1:1", "No box", "FRAME", emptyList(), null)),
                ),
            ),
        )
        assertEquals(emptyList<FigmaFrame>(), FigmaLinks.frames(doc))
    }

    @Test
    fun `an unnamed frame still gets a usable label`() {
        val doc = node(
            "0:0", "Document", "DOCUMENT",
            listOf(node("1:0", "", "CANVAS", listOf(node("1:1", "", "FRAME")))),
        )
        val frame = FigmaLinks.frames(doc).single()
        assertEquals("Untitled", frame.name)
        assertEquals("Page", frame.pageName)
    }

    @Test
    fun `an empty document yields no frames`() {
        assertEquals(emptyList<FigmaFrame>(), FigmaLinks.frames(null))
    }
}
