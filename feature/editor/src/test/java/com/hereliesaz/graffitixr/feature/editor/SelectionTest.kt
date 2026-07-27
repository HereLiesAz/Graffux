package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The lasso selection: reducer semantics, and how a move routes through [DrawingEngine]. */
class SelectionTest {

    private val canvas = IntSize(100, 100)
    private val square = Selection(
        path = listOf(Offset(10f, 10f), Offset(50f, 10f), Offset(50f, 50f), Offset(10f, 50f)),
        canvasSize = canvas,
    )

    // ── Reducer ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `SetSelection adopts a usable polygon`() {
        val state = EditorReducer.reduce(EditorUiState(), EditorIntent.SetSelection(square))
        assertEquals(square, state.selection)
    }

    @Test
    fun `SetSelection with null deselects`() {
        val selected = EditorReducer.reduce(EditorUiState(), EditorIntent.SetSelection(square))
        val cleared = EditorReducer.reduce(selected, EditorIntent.SetSelection(null))
        assertNull(cleared.selection)
    }

    @Test
    fun `a polygon too small to enclose anything is treated as a deselect`() {
        // Otherwise it would silently clip every subsequent stroke to nothing.
        val sliver = Selection(listOf(Offset.Zero, Offset(1f, 1f)), canvas)
        val state = EditorReducer.reduce(EditorUiState(), EditorIntent.SetSelection(sliver))
        assertNull(state.selection)
    }

    @Test
    fun `InvertSelection flips the region and flips back`() {
        val selected = EditorReducer.reduce(EditorUiState(), EditorIntent.SetSelection(square))
        val inverted = EditorReducer.reduce(selected, EditorIntent.InvertSelection)
        assertTrue(inverted.selection!!.inverted)
        assertFalse(EditorReducer.reduce(inverted, EditorIntent.InvertSelection).selection!!.inverted)
    }

    @Test
    fun `InvertSelection with nothing selected is a no-op`() {
        assertNull(EditorReducer.reduce(EditorUiState(), EditorIntent.InvertSelection).selection)
    }

    @Test
    fun `sample rate clamps into range and keeps zero as unlimited`() {
        fun rate(hz: Int) = EditorReducer.reduce(EditorUiState(), EditorIntent.SetInputSampleRateHz(hz)).inputSampleRateHz
        assertEquals(60, rate(60))
        assertEquals(0, rate(0))       // 0 is the explicit "unthrottled" value, not an error
        assertEquals(0, rate(-10))
        assertEquals(240, rate(10_000))
    }

    @Test
    fun `canvas render scale clamps to a usable range`() {
        fun scale(v: Float) = EditorReducer.reduce(EditorUiState(), EditorIntent.SetCanvasRenderScale(v)).canvasRenderScale
        assertEquals(1f, scale(1f), 0.001f)
        assertEquals(0.5f, scale(0.5f), 0.001f)
        // A zero or negative scale would allocate a zero-pixel layer; the floor prevents it.
        assertEquals(0.25f, scale(0f), 0.001f)
        assertEquals(0.25f, scale(-1f), 0.001f)
        assertEquals(1f, scale(4f), 0.001f)
    }

    @Test
    fun `SetQuickMenu opens at a point and closes with null`() {
        val at = Offset(120f, 340f)
        val open = EditorReducer.reduce(EditorUiState(), EditorIntent.SetQuickMenu(at))
        assertEquals(at, open.quickMenuAt)
        assertNull(EditorReducer.reduce(open, EditorIntent.SetQuickMenu(null)).quickMenuAt)
    }

    // ── Replay ───────────────────────────────────────────────────────────────────────────

    @Before
    fun setup() {
        mockkObject(NativeLibLoader)
        every { NativeLibLoader.loadAll() } returns Unit
        mockkObject(ImageProcessor)
        mockkObject(SelectionMask)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun bitmap(): Bitmap = mockk(relaxed = true) {
        every { width } returns 100
        every { height } returns 100
        every { copy(any(), any()) } returns this
    }

    private fun moveCommand(delta: Offset? = Offset(12f, -4f)) = StrokeCommand(
        path = square.path,
        canvasSize = canvas,
        tool = Tool.SELECT,
        brushSize = 0f,
        brushColor = 0,
        intensity = 0f,
        selection = square,
        moveDelta = delta,
    )

    @Test
    fun `a SELECT command replays as a region move, not as paint`() = runTest {
        val base = bitmap()
        val clip = mockk<android.graphics.Path>(relaxed = true)
        every { SelectionMask.bitmapPath(any(), any(), any(), any(), any(), any()) } returns clip
        every { SelectionMask.mapDelta(any(), any(), any(), any(), any(), any(), any(), any()) } returns Offset(6f, -2f)
        every { SelectionMask.moveRegion(any(), any(), any(), any()) } returns base

        DrawingEngine(mockk<SlamManager>(relaxed = true)).composite(base, listOf(moveCommand()))

        verify { SelectionMask.moveRegion(any(), eq(clip), eq(6f), eq(-2f)) }
        coVerify(exactly = 0) {
            ImageProcessor.applyToolToBitmap(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `a SELECT command with no recorded delta leaves the bitmap alone`() = runTest {
        val base = bitmap()
        every { SelectionMask.bitmapPath(any(), any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        DrawingEngine(mockk<SlamManager>(relaxed = true)).composite(base, listOf(moveCommand(delta = null)))

        verify(exactly = 0) { SelectionMask.moveRegion(any(), any(), any(), any()) }
    }

    @Test
    fun `a clear command wipes rather than painting`() = runTest {
        val base = bitmap()
        every { SelectionMask.bitmapPath(any(), any(), any(), any(), any(), any()) } returns null

        val clear = StrokeCommand(
            path = emptyList(),
            canvasSize = canvas,
            tool = Tool.ERASER,
            brushSize = 0f,
            brushColor = 0,
            intensity = 0f,
            clearAll = true,
        )
        DrawingEngine(mockk<SlamManager>(relaxed = true)).composite(base, listOf(clear))

        // The clear branch is taken before any paint path — an empty-path eraser stroke would
        // otherwise fall through to applyToolToBitmap and do nothing at all.
        coVerify(exactly = 0) {
            ImageProcessor.applyToolToBitmap(any(), any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { SelectionMask.moveRegion(any(), any(), any(), any()) }
    }

    @Test
    fun `a clear command inside a selection clips to it`() = runTest {
        val base = bitmap()
        val clip = mockk<android.graphics.Path>(relaxed = true)
        every { SelectionMask.bitmapPath(any(), any(), any(), any(), any(), any()) } returns clip
        every { SelectionMask.clip(any(), any()) } returns Unit

        val clear = StrokeCommand(
            path = emptyList(),
            canvasSize = canvas,
            tool = Tool.ERASER,
            brushSize = 0f,
            brushColor = 0,
            intensity = 0f,
            selection = square,
            clearAll = true,
        )
        DrawingEngine(mockk<SlamManager>(relaxed = true)).composite(base, listOf(clear))

        verify { SelectionMask.clip(any(), eq(clip)) }
    }

    @Test
    fun `a stroke carries its selection into replay so the clip matches the original paint`() = runTest {
        val base = bitmap()
        every { SelectionMask.bitmapPath(any(), any(), any(), any(), any(), any()) } returns mockk(relaxed = true)
        every {
            ImageProcessor.mapScreenToBitmap(any(), any(), any(), any(), any(), any(), any(), any())
        } returns listOf(Offset(1f, 1f))

        val brush = StrokeCommand(
            path = listOf(Offset(20f, 20f)),
            canvasSize = canvas,
            tool = Tool.BRUSH,
            brushSize = 5f,
            brushColor = 0,
            intensity = 0.5f,
            selection = square,
        )
        DrawingEngine(mockk<SlamManager>(relaxed = true)).composite(base, listOf(brush))

        // The clip is derived from the command's own selection, not from live editor state.
        verify { SelectionMask.bitmapPath(eq(square), eq(100), eq(100), any(), any(), any()) }
    }
}
