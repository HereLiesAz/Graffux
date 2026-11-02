package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.is3DLayer
import com.hereliesaz.graffitixr.common.scene.ModelLayerContent
import com.hereliesaz.graffitixr.common.scene.SpatialLayerContent
import com.hereliesaz.graffitixr.design.theme.Cyan
import com.hereliesaz.graffitixr.design.theme.HotPink
import com.hereliesaz.graffitixr.design.theme.White
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The layers rail tells 3D layers from 2D ones by outline (spec/3d-space.md, "2D and 3D layers"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class LayerTile3DOutlineTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val on = RailLight(active = true, secondary = false)
    private val off = RailLight(active = false, secondary = false)

    @Test
    fun `each of the four states wears its outline`() {
        assertEquals(2.dp to HotPink, layerOutline(is3D = false, light = on, accent = HotPink))
        assertEquals(2.dp to Cyan, layerOutline(is3D = true, light = on, accent = HotPink))
        assertNull(layerOutline(is3D = false, light = off, accent = HotPink))
        assertEquals(1.dp to White, layerOutline(is3D = true, light = off, accent = HotPink))
    }

    @Test
    fun `3D tiles say so to TalkBack and 2D tiles do not`() {
        val layers = listOf(
            Layer("flat", "Flat"),
            Layer("made", "Made 3D", plane3D = true),
            Layer("placed", "Placed", positionZ = 40f),
            Layer("ink", "3D strokes", spatial = SpatialLayerContent()),
            Layer("obj", "3D model", model = ModelLayerContent("model_obj.obj")),
        )
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LayersRail(
                    layers = layers,
                    activeLayerId = "flat",
                    secondary = emptySet(),
                    expansion = emptyMap(),
                    callbacks = LayersRailCallbacks({}, {}, { _, _ -> }, { _, _ -> }, {}),
                )
            }
        }
        listOf("made", "placed", "ink", "obj").forEach {
            rule.onNodeWithTag("rail.${layerRailId(it)}").assertContentDescriptionContains(", 3D", substring = true)
        }
        assertFalse(layerThumbLabel(layers.first(), masked = false, expanded = false).contains("3D"))
    }

    @Test
    fun `a 3D model layer wears the 3D outlines`() {
        val model = Layer("obj", "3D model", model = ModelLayerContent("model_obj.obj"))
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LayersRail(
                    layers = listOf(Layer("flat", "Flat"), model),
                    activeLayerId = "obj",
                    secondary = emptySet(),
                    expansion = emptyMap(),
                    callbacks = LayersRailCallbacks({}, {}, { _, _ -> }, { _, _ -> }, {}),
                )
            }
        }
        rule.onNodeWithTag("rail.${layerRailId("obj")}").assertContentDescriptionContains(", 3D", substring = true)
        assertEquals(2.dp to Cyan, layerOutline(is3D = model.is3DLayer, light = on, accent = HotPink))
        assertEquals(1.dp to White, layerOutline(is3D = model.is3DLayer, light = off, accent = HotPink))
    }
}
