package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hereliesaz.graffitixr.common.model.InkUtensil
import com.hereliesaz.graffitixr.feature.editor.ink.InkAffine
import com.hereliesaz.graffitixr.feature.editor.ink.InkStrokes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A committed Ink stroke lands where [InkAffine] maps it, not at its raw world coordinates.
 * Ink's CanvasStrokeRenderer only reads its matrix as a quality hint; before the canvas carried
 * the transform, every Ink mark jumped by the world-to-bitmap translation on lift.
 */
@RunWith(AndroidJUnit4::class)
class InkCommitPlacementTest {

    @Test
    fun aCommittedInkStrokeLandsAtItsMappedPosition() {
        val brush = InkStrokes.brush(InkUtensil.MARKER, 16f, Color.BLACK, 1f)
        // A short horizontal mark around world (40, 40).
        val stroke = InkStrokes.strokeFromPoints(brush, listOf(30f, 40f, 50f, 40f), emptyList())
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val toBitmap = InkAffine.translation(100f, 90f)

        InkStrokes.draw(Canvas(bitmap), stroke, toBitmap, alphaLock = false, wrapAround = false)

        assertTrue("paint at the mapped point", Color.alpha(bitmap.getPixel(140, 130)) > 200)
        assertEquals("nothing at the raw world point", 0, Color.alpha(bitmap.getPixel(40, 40)))
    }
}
