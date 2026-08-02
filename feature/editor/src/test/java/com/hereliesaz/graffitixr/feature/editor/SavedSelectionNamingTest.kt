package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.SelectionRing
import com.hereliesaz.graffitixr.common.util.SelectionGeometry
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Saving a selection must never destroy one you already had.
 *
 * The generated name used to be `"Selection ${size + 1}"`, and [EditorViewModel.onSaveSelection]
 * de-duplicates by name — so once anything had been forgotten, the counter walked back onto a name
 * that was still in use and silently replaced it. The HUD reported success either way, and there is
 * no undo for a saved selection, so the only evidence was the missing entry.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SavedSelectionNamingTest {

    @Suppress("EXPERIMENTAL_API_USAGE")
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: EditorViewModel
    private val canvas = IntSize(48, 48)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        RenderTestBase.stubNativeLibs()
        vm = EditorViewModelFixture.build(dispatcher)
        vm.dispatchForTest(
            EditorIntent.SetLayers(listOf(Layer(id = "L", name = "L", bitmap = RenderTestBase.filled(48, 48, Color.WHITE))))
        )
        vm.onLayerActivated("L")
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvas))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    /** Arms a distinguishable region so each save stores something different. */
    private fun selectRegion(x: Float) {
        vm.dispatchForTest(
            EditorIntent.SetSelection(
                Selection(
                    rings = listOf(SelectionRing(SelectionGeometry.rectangle(Offset(x, 4f), Offset(x + 8f, 12f)))),
                    canvasSize = canvas,
                )
            )
        )
    }

    private fun saveNext() {
        vm.onSaveSelection(vm.nextSelectionName())
    }

    @Test
    fun `saving after a delete does not overwrite a surviving selection`() = runTest(dispatcher) {
        selectRegion(4f); saveNext()
        selectRegion(14f); saveNext()
        selectRegion(24f); saveNext()
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.savedSelections.size)

        // Forget the middle one, which is what makes `size + 1` walk back onto a live name.
        vm.onDeleteSavedSelection("Selection 2")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.savedSelections.size)

        selectRegion(34f); saveNext()
        advanceUntilIdle()

        val names = vm.uiState.value.savedSelections.map { it.name }
        assertEquals("the fourth save must add, not replace: $names", 3, names.size)
        assertTrue("Selection 3 must survive; got $names", "Selection 3" in names)
        assertTrue("the new one must get a free name; got $names", names.any { it !in setOf("Selection 1", "Selection 3") })
    }

    /** Repeated saves keep producing free names however many holes the list has. */
    @Test
    fun `generated names never collide with a live one`() = runTest(dispatcher) {
        repeat(5) { i -> selectRegion(2f + i * 8f); saveNext() }
        advanceUntilIdle()
        vm.onDeleteSavedSelection("Selection 1")
        vm.onDeleteSavedSelection("Selection 4")
        advanceUntilIdle()

        repeat(4) { i -> selectRegion(2f + i * 6f); saveNext() }
        advanceUntilIdle()

        val names = vm.uiState.value.savedSelections.map { it.name }
        assertEquals("every save must add one: $names", names.size, names.toSet().size)
        assertEquals("3 survivors + 4 new = 7, got $names", 7, names.size)
    }

    /** An explicitly typed name still overwrites — that is what typing an existing name means. */
    @Test
    fun `an explicit name still replaces, and says so`() = runTest(dispatcher) {
        selectRegion(4f)
        vm.onSaveSelection("Face")
        selectRegion(20f)
        vm.onSaveSelection("Face")
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.savedSelections.size)
        assertEquals("Face", vm.uiState.value.savedSelections.single().name)
    }
}
