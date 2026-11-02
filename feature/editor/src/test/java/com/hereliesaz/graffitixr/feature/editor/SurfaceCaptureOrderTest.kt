package com.hereliesaz.graffitixr.feature.editor

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Draw surface" armed on a group must take the next stroke over every tool's input overlay
 * (spec/3d-space.md, "Group surfaces"). In a Compose Box the later child gets pointer input first,
 * so the capture has to be composed after every tool canvas in [EditorScreen]'s stack.
 */
class SurfaceCaptureOrderTest {
    private val source = File("src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorScreen.kt").readText()

    @Test
    fun `the surface capture sits above every tool input overlay`() {
        val capture = source.indexOf("testTag(\"canvas.surfaceCapture\")")
        assertTrue("capture call not found", capture >= 0)
        val overlays = listOf(
            "SelectionCanvas(", "WarpHandles(", "PenCanvas(", "FrameToolCanvas(", "Scene3DCanvas(", "ModelPaintCanvas(",
        )
        for (overlay in overlays) {
            val at = source.indexOf(overlay)
            assertTrue("$overlay not found", at >= 0)
            assertTrue("$overlay is composed above the surface capture and takes its stroke", at < capture)
        }
    }
}
