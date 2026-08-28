package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.SavedSelection
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.SelectionOp
import com.hereliesaz.graffitixr.common.model.SelectionRing
import com.hereliesaz.graffitixr.common.util.SelectionGeometry
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A glee audit found `onAutoSelect`/`onLoadSelection` both fell back to substituting the whole
 * traced/saved selection whenever `SelectionGeometry.compose` returned null -- which it does, by
 * documented contract (and `SelectionGeometryTest`'s own coverage), exactly when Remove is applied
 * with nothing currently selected: "Removing from nothing leaves nothing." The fallback turned that
 * no-op into a de facto New, silently creating a selection the user asked to shrink. The hand-drawn
 * lasso path (`onSelectionEnd`) never had this bug -- it dispatches compose()'s raw result, including
 * null, with no fallback. This drives the real `onLoadSelection` (the simpler of the two fixed call
 * sites -- fully synchronous, no bitmap/wand tracing needed) to prove the fix.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SelectionRemoveFromNothingTest {

    @Suppress("EXPERIMENTAL_API_USAGE")
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: EditorViewModel
    private val canvas = IntSize(100, 100)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        RenderTestBase.stubNativeLibs()
        vm = EditorViewModelFixture.build(dispatcher)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loading a saved selection with Remove and nothing currently selected is a no-op`() {
        val saved = Selection(
            rings = listOf(SelectionRing(SelectionGeometry.rectangle(Offset(10f, 10f), Offset(50f, 50f)))),
            canvasSize = canvas,
        )
        vm.dispatchForTest(EditorIntent.SetSavedSelections(listOf(SavedSelection("A", saved))))
        vm.dispatchForTest(EditorIntent.SetSelectionOp(SelectionOp.REMOVE))
        // No SetSelection dispatched yet -- state.selection is null, matching "nothing selected".

        vm.onLoadSelection("A")

        assertNull(
            "Remove with nothing selected must stay a no-op, not load the saved selection as New",
            vm.uiState.value.selection,
        )
    }
}
