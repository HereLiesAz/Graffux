@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hereliesaz.graffitixr.common.model.AutoLayout
import com.hereliesaz.graffitixr.common.model.ColorStyle
import com.hereliesaz.graffitixr.common.model.ConstraintAnchor
import com.hereliesaz.graffitixr.common.model.Constraints
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.FrameOps
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayoutDirection
import com.hereliesaz.graffitixr.common.model.Rect
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.TextLayerParams
import com.hereliesaz.graffitixr.common.model.VectorShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The constraints, styles and components panels, in the sheet and the side panel. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class DesignPanelsUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class Recorder : DesignLibraryActions {
        val calls = mutableListOf<String>()
        override fun setConstraints(constraints: Constraints) { calls += "constraints ${constraints.horizontal} ${constraints.vertical}" }
        override fun newColorStyle(name: String) { calls += "new colour $name" }
        override fun applyFillStyle(styleId: String?) { calls += "fill $styleId" }
        override fun applyStrokeStyle(styleId: String?) { calls += "stroke $styleId" }
        override fun updateColorStyle(styleId: String) { calls += "update $styleId" }
        override fun deleteColorStyle(styleId: String) { calls += "delete $styleId" }
        override fun newTextStyle(name: String) { calls += "new text $name" }
        override fun applyTextStyle(styleId: String?) { calls += "text $styleId" }
        override fun updateTextStyle(styleId: String) { calls += "update text $styleId" }
        override fun deleteTextStyle(styleId: String) { calls += "delete text $styleId" }
        override fun makeComponent() { calls += "make" }
        override fun placeInstance(componentId: String) { calls += "place $componentId" }
        override fun detachInstance() { calls += "detach" }
        override fun releaseComponent() { calls += "release" }
        override fun selectLayer(layerId: String) { calls += "select $layerId" }
    }

    private val noDesign = object : DesignActions {
        override fun setBox(x: Float, y: Float, width: Float, height: Float) = Unit
        override fun setRotation(degrees: Float) = Unit
        override fun setOpacityPercent(percent: Float) = Unit
        override fun setRadius(radius: Float) = Unit
        override fun setClip(clips: Boolean) = Unit
        override fun setFill(argb: Long?) = Unit
        override fun fillWithColorInHand() = Unit
        override fun addFrame() = Unit
    }

    private val surface = ColorStyle(id = "surface", name = "Surface", argb = 0xFF26262AL)
    private val pay = FrameOps.create("pay", "Pay", Rect(0f, 0f, 190f, 340f))
    private val card = Layer(
        id = "card", name = "Card", parentId = "pay",
        constraints = Constraints(ConstraintAnchor.STRETCH, ConstraintAnchor.END),
        shapes = listOf(VectorShape(ShapeKind.RECTANGLE, width = 218f, height = 70f, fillStyleId = "surface")),
    )
    private val button = Layer(id = "button", name = "Button", componentId = "btn")
    private val instance = Layer(id = "i", name = "Button instance", instanceOf = "btn")
    private val layers = listOf(button, instance, pay, card)
    private val state = EditorUiState(layers = layers, colorStyles = listOf(surface))

    private fun sheet(tab: DesignTab, selected: Layer, recorder: Recorder) {
        val target = designTargetOf(selected, Rect(0f, 0f, 10f, 10f)).copy(links = designLinksOf(selected, layers))
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0E0E0F))) {
                    DesignSheetBody(
                        tab, showingControls = true, target = target, actions = noDesign, onSelectTab = {},
                        library = designLibraryOf(state), libraryActions = recorder,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }

    @Test
    fun `constraints pin a layer in its frame`() {
        val recorder = Recorder()
        sheet(DesignTab.CONSTRAINTS, card, recorder)
        rule.onNodeWithTag("design.pinx.l+r").assertIsSelected()
        rule.onNodeWithTag("design.piny.bottom").assertIsSelected()
        rule.onNodeWithTag("design.pinx.center").performClick()
        rule.onNodeWithTag("design.piny.scale").performClick()
        assertEquals(listOf("constraints CENTER END", "constraints STRETCH SCALE"), recorder.calls)
    }

    @Test
    fun `a layer with no frame offers no constraints`() {
        sheet(DesignTab.CONSTRAINTS, button, Recorder())
        rule.onNodeWithTag("design.constraints.none").assertIsDisplayed()
    }

    @Test
    fun `the fill tab binds, detaches and makes colour styles`() {
        val recorder = Recorder()
        sheet(DesignTab.FILL, card, recorder)
        rule.onNodeWithTag("design.fill.value").assertTextEquals("Surface")
        rule.onNodeWithTag("design.style.Surface.apply").assertIsSelected()
        rule.onNodeWithTag("design.style.Surface.apply").performClick()
        rule.onNodeWithTag("design.fill.newStyle").performScrollTo().performClick()
        assertEquals(listOf("fill null", "new colour Colour 2"), recorder.calls)
    }

    @Test
    fun `the stroke tab binds the stroke`() {
        val recorder = Recorder()
        sheet(DesignTab.STROKE, card, recorder)
        rule.onNodeWithTag("design.style.Surface.apply").performClick()
        assertEquals(listOf("stroke surface"), recorder.calls)
    }

    @Test
    fun `the components tab lists mains and places instances`() {
        val recorder = Recorder()
        sheet(DesignTab.COMPONENTS, instance, recorder)
        rule.onNodeWithTag("design.component.main").performClick()
        rule.onNodeWithTag("design.component.detach").performClick()
        rule.onNodeWithTag("design.component.Button.place").performClick()
        assertEquals(listOf("select button", "detach", "place btn"), recorder.calls)
    }

    @Test
    fun `a plain layer can become a component`() {
        val recorder = Recorder()
        sheet(DesignTab.COMPONENTS, card, recorder)
        rule.onNodeWithTag("design.component.make").performClick()
        assertEquals(listOf("make"), recorder.calls)
    }

    @Test
    fun `text styles need a text layer`() {
        val recorder = Recorder()
        sheet(DesignTab.TEXT, card, recorder)
        rule.onNodeWithTag("design.text.none").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1180dp-h820dp-mdpi")
    fun `the side panel stacks frame, constraints, fill, stroke and components`() {
        val target = designTargetOf(card, Rect(0f, 0f, 218f, 70f)).copy(links = designLinksOf(card, layers))
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize()) {
                    PropertiesPanel(
                        target, noDesign, PropertiesPlacement.SIDE_PANEL, {},
                        modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight(),
                        library = designLibraryOf(state), libraryActions = Recorder(),
                    )
                }
            }
        }
        listOf("frame", "constraints", "fill", "stroke", "components").forEach {
            rule.onNodeWithTag("design.section.$it").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `links read the layer's style, constraints and component`() {
        val text = Layer(id = "t", name = "Title", textParams = TextLayerParams(text = "Hi", styleId = "h1"))
        assertEquals("h1", designLinksOf(text, listOf(text)).textStyleId)
        assertNull(designLinksOf(text, listOf(text)).constraints)
        // A parent that lays its children out owns their placement: no constraints to set.
        val row = pay.copy(autoLayout = AutoLayout(direction = LayoutDirection.HORIZONTAL))
        assertNull(designLinksOf(card, listOf(row, card)).constraints)
        assertEquals(listOf(ComponentEntry("button", "btn", "Button", 1)), designLibraryOf(state).components)
    }
}
