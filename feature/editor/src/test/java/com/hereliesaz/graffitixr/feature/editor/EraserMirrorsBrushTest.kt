package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import com.hereliesaz.graffitixr.common.azphalt.GrainBehavior
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Eraser is the Brush in reverse: the same recorded stamp stroke, composited as erase. */
class EraserMirrorsBrushTest {

    private fun stroke(tool: Tool) = StrokeCommand(
        path = listOf(Offset(1f, 1f), Offset(20f, 20f)),
        canvasSize = IntSize(100, 100),
        tool = tool,
        brushSize = 12f,
        brushColor = 0x80FF0000.toInt(),
        intensity = 1f,
        stampBrush = BuiltInBrushes.round,
    )

    @Test
    fun `an Eraser stamp stroke erases and a Brush one paints, with the same brush`() {
        val brush = BuiltInBrushes.round
        val erase = StampPaint.of(stroke(Tool.ERASER), brush)
        val paint = StampPaint.of(stroke(Tool.BRUSH), brush)
        assertTrue(erase.erase)
        assertEquals(GpuStampEngine.STAMP_ERASE, erase.stampMode)
        assertFalse(paint.erase)
        assertEquals(GpuStampEngine.STAMP_OVER, paint.stampMode)
        // Everything but the composite is the brush's own.
        assertEquals(paint.copy(erase = true), erase)
    }

    @Test
    fun `the zoom-crisp overlay replays Eraser stamp strokes exactly when it replays the Brush's`() {
        val plain = stroke(Tool.ERASER)
        assertTrue(HighResOverlayEligibility.strokeReplayable(plain))
        val grained = plain.copy(
            stampBrush = BuiltInBrushes.round.copy(grainPath = "g.png", grainBehavior = GrainBehavior.MOVING),
            stampGrain = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ALPHA_8),
        )
        assertEquals(
            HighResOverlayEligibility.strokeReplayable(grained.copy(tool = Tool.BRUSH)),
            HighResOverlayEligibility.strokeReplayable(grained),
        )
    }
}
