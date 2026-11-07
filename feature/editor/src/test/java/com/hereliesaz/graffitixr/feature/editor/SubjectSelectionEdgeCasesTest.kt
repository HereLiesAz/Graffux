package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.SelectionOp
import com.hereliesaz.graffitixr.common.model.SelectionRing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Subject isolation beyond the happy path ([SubjectSelectionTest] covers that): the pure mask rules,
 * the "nothing / unavailable / no layer" outcomes and their notices, Cancel, a second tap
 * superseding the first, and how the result composes with an existing selection (Add / Remove).
 * Subject isolation never touches pixels, so layers and the undo stack are left alone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SubjectSelectionEdgeCasesTest {

    @Suppress("EXPERIMENTAL_API_USAGE")
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: EditorViewModel
    private val canvas = IntSize(200, 200)

    private class Box(val l: Float, val t: Float, val r: Float, val b: Float) : SubjectSegmenter {
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun segment(bitmap: Bitmap): FloatArray {
            calls++
            gate?.await()
            val w = bitmap.width
            val h = bitmap.height
            return FloatArray(w * h) { i ->
                val x = (i % w).toFloat() / w
                val y = (i / w).toFloat() / h
                if (x in l..<r && y in t..<b) 0.9f else 0.1f
            }
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        RenderTestBase.stubNativeLibs()
        vm = EditorViewModelFixture.build(dispatcher)
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvas))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun usePhoto() {
        val layer = Layer(id = "photo", name = "Photo", bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888))
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(layer)))
        vm.dispatchForTest(EditorIntent.ActivateLayer("photo"))
    }

    private fun notices(block: () -> Unit): List<EditorNotice> {
        val seen = mutableListOf<EditorNotice>()
        val job = CoroutineScope(dispatcher).launch { vm.notices.collect { seen += it } }
        block()
        job.cancel()
        return seen
    }

    private fun area(s: Selection): Float {
        // Shoelace over the outer rings only; enough to tell "bigger" from "smaller".
        return s.rings.filter { it.additive }.sumOf { ring ->
            val p = ring.path
            var a = 0.0
            for (i in p.indices) {
                val q = p[(i + 1) % p.size]
                a += p[i].x * q.y - q.x * p[i].y
            }
            kotlin.math.abs(a / 2)
        }.toFloat()
    }

    // ---- SubjectMask ----

    @Test
    fun `the threshold is inclusive at one half`() {
        val mask = SubjectMask.threshold(floatArrayOf(0f, 0.49f, 0.5f, 0.51f, 1f))
        assertEquals(listOf(false, false, true, true, true), mask.toList())
    }

    @Test
    fun `pictures that fit the long edge are not scaled, larger ones are scaled to it`() {
        assertEquals(1f, SubjectMask.scaleFor(1280, 720), 0f)
        assertEquals(1f, SubjectMask.scaleFor(10, 1280), 0f)
        assertEquals(0.5f, SubjectMask.scaleFor(2560, 1000), 1e-6f)
        assertEquals(0.5f, SubjectMask.scaleFor(1000, 2560), 1e-6f)
    }

    // ---- outcomes and notices ----

    @Test
    fun `a mask with no confident pixel says no subject and selects nothing`() {
        usePhoto()
        vm.subjectSegmenter = SubjectSegmenter { b -> FloatArray(b.width * b.height) { 0.2f } }

        val seen = notices { vm.onSelectSubject() }

        assertNull(vm.uiState.value.selection)
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(NoticeKind.NOTICE, seen.single().kind)
        assertEquals("No subject found on this layer", seen.single().text)
    }

    @Test
    fun `no model warns that subject isolation is unavailable`() {
        usePhoto()
        vm.subjectSegmenter = SubjectSegmenter { null }

        val seen = notices { vm.onSelectSubject() }

        assertEquals(NoticeKind.WARNING, seen.single().kind)
        assertEquals("Subject isolation isn't available on this device yet", seen.single().text)
    }

    @Test
    fun `without a layer bitmap it says so and never starts`() {
        val fake = Box(0f, 0f, 1f, 1f)
        vm.subjectSegmenter = fake
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(Layer(id = "empty", name = "Empty", bitmap = null))))
        vm.dispatchForTest(EditorIntent.ActivateLayer("empty"))

        val seen = notices { vm.onSelectSubject() }

        assertEquals("Subject needs a paint or photo layer", seen.single().text)
        assertEquals(0, fake.calls)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `with no active layer it says so`() {
        val seen = notices { vm.onSelectSubject() }
        assertEquals("Subject needs a paint or photo layer", seen.single().text)
    }

    @Test
    fun `an unsized canvas returns quietly without segmenting`() {
        usePhoto()
        vm.dispatchForTest(EditorIntent.SetCanvasSize(IntSize(0, 0)))
        val fake = Box(0f, 0f, 1f, 1f)
        vm.subjectSegmenter = fake

        val seen = notices { vm.onSelectSubject() }

        assertTrue(seen.isEmpty())
        assertEquals(0, fake.calls)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `a stale canvas result tells the user to tap again`() {
        usePhoto()
        val fake = Box(0.25f, 0.25f, 0.75f, 0.75f).apply { gate = CompletableDeferred() }
        vm.subjectSegmenter = fake

        val seen = notices {
            vm.onSelectSubject()
            vm.dispatchForTest(EditorIntent.SetCanvasSize(IntSize(300, 120)))
            fake.gate!!.complete(Unit)
        }

        assertEquals("The canvas changed while finding the subject. Tap Subject again.", seen.single().text)
    }

    // ---- cancellation ----

    @Test
    fun `Cancel offers itself, then stops the work, drops the spinner and publishes nothing`() {
        usePhoto()
        val fake = Box(0.25f, 0.25f, 0.75f, 0.75f).apply { gate = CompletableDeferred() }
        vm.subjectSegmenter = fake

        val seen = notices {
            vm.onSelectSubject()
            assertTrue(vm.uiState.value.isLoading)
            assertTrue(vm.uiState.value.loadingCancellable)

            vm.cancelLongOperations()
            // Releasing the gate afterwards must not resurrect the cancelled result.
            fake.gate!!.complete(Unit)
        }

        assertFalse(vm.uiState.value.isLoading)
        assertNull(vm.uiState.value.selection)
        assertTrue("a cancel is silent: $seen", seen.isEmpty())
    }

    @Test
    fun `a second tap supersedes the first, which is cancelled`() {
        usePhoto()
        val first = Box(0f, 0f, 0.25f, 0.25f).apply { gate = CompletableDeferred() }
        vm.subjectSegmenter = first
        vm.onSelectSubject()

        val second = Box(0.5f, 0.5f, 1f, 1f)
        vm.subjectSegmenter = second
        vm.onSelectSubject()
        first.gate!!.complete(Unit)

        val pts = vm.uiState.value.selection!!.rings.flatMap { it.path }
        // Only the second (lower-right) subject was published.
        assertTrue(pts.minOf { it.x } > canvas.width / 4f)
        assertFalse(vm.uiState.value.isLoading)
    }

    // ---- interaction with an existing selection ----

    private fun elsewhere() = Selection(
        rings = listOf(SelectionRing(listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 10f), Offset(0f, 10f)))),
        canvasSize = canvas,
    )

    @Test
    fun `New replaces an existing selection`() {
        usePhoto()
        vm.dispatchForTest(EditorIntent.SetSelection(elsewhere()))
        vm.subjectSegmenter = Box(0.5f, 0.5f, 1f, 1f)

        vm.onSelectSubject()

        val pts = vm.uiState.value.selection!!.rings.flatMap { it.path }
        assertTrue("the old region survived: ${pts.minOf { it.x }}", pts.minOf { it.x } > 20f)
    }

    @Test
    fun `Add keeps the existing selection and grows it`() {
        usePhoto()
        vm.dispatchForTest(EditorIntent.SetSelection(elsewhere()))
        val before = area(vm.uiState.value.selection!!)
        vm.dispatchForTest(EditorIntent.SetSelectionOp(SelectionOp.ADD))
        vm.subjectSegmenter = Box(0.5f, 0.5f, 1f, 1f)

        vm.onSelectSubject()

        val after = vm.uiState.value.selection!!
        assertTrue("existing region dropped", after.rings.flatMap { it.path }.any { it.x <= 10f && it.y <= 10f })
        assertTrue(area(after) > before)
    }

    @Test
    fun `Remove cuts the subject out of an existing selection`() {
        usePhoto()
        val whole = Selection(
            rings = listOf(
                SelectionRing(listOf(Offset(0f, 0f), Offset(200f, 0f), Offset(200f, 200f), Offset(0f, 200f))),
            ),
            canvasSize = canvas,
        )
        vm.dispatchForTest(EditorIntent.SetSelection(whole))
        val before = area(vm.uiState.value.selection!!)
        vm.dispatchForTest(EditorIntent.SetSelectionOp(SelectionOp.REMOVE))
        vm.subjectSegmenter = Box(0.25f, 0.25f, 0.75f, 0.75f)

        vm.onSelectSubject()

        val after = vm.uiState.value.selection
        assertNotNull(after)
        assertNotEquals(whole.rings, after!!.rings)
        // The subject's area is removed from the outer region (or left as a hole).
        assertTrue(area(after) < before || after.rings.any { !it.additive })
    }

    // ---- no side effects on pixels ----

    @Test
    fun `selecting the subject leaves the layers alone`() {
        usePhoto()
        val layersBefore = vm.uiState.value.layers
        vm.subjectSegmenter = Box(0.25f, 0.25f, 0.75f, 0.75f)

        vm.onSelectSubject()

        assertNotNull(vm.uiState.value.selection)
        assertEquals(layersBefore, vm.uiState.value.layers)
        assertEquals("photo", vm.uiState.value.activeLayerId)
    }
}
