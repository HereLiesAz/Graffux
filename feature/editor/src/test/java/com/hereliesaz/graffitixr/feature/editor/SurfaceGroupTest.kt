@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.CURRENT_FORMAT_VERSION
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import com.hereliesaz.graffitixr.common.model.is3DIn
import com.hereliesaz.graffitixr.common.scene.PlanePose
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SurfaceMath
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Paint on a group surface (spec/3d-space.md, "Group surfaces"): mapping, misses and saving. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SurfaceGroupTest {
    private val w = 1000f
    private val h = 1600f
    private val floor = SurfaceMath.capture(
        (0..20).map { (200f + it * 30f) to 1000f }, SceneCamera(), w, h, listOf(PlanePose()),
    )!!
    private val layers = listOf(
        Layer("g", "G", type = LayerType.GROUP, surface = floor),
        Layer("c", "C", parentId = "g"),
    )
    private val state = EditorUiState(layers = layers, activeLayerId = "c", canvasSize = IntSize(1000, 1600),
        viewportOffset = Offset.Zero, viewportZoom = 1f, viewportRotation = 0f,
    )

    @Test
    fun `a touch on the floor paints at its unrolled point and a miss is skipped`() {
        val plane = ActivePlaneMapping.of(state)
        val hit = plane.toFlat(Offset(500f, 1000f))!!
        assertEquals(300f, hit.x, 1f)
        assertEquals(0f, hit.y, 1f)
        // Above the horizon: nothing of the surface is there.
        assertNull(plane.toFlat(Offset(500f, 300f)))
        val started = mutableListOf<Offset>()
        var cancelled = 0
        val feed = PlaneStrokeFeed<Offset>(plane::toFlat, { started += it }, {}, {}, { cancelled++ })
        feed.onStart(Offset(500f, 300f))
        feed.onPoint(Offset(500f, 950f))
        feed.onEnd()
        assertEquals(1, started.size)
        assertEquals(0, cancelled)
        val missing = PlaneStrokeFeed<Offset>(plane::toFlat, { started += it }, {}, {}, { cancelled++ })
        missing.onStart(Offset(500f, 300f))
        missing.onPoint(Offset(520f, 310f))
        missing.onEnd()
        assertEquals(1, cancelled)
    }

    @Test
    fun `the profile is saved and read back at the new format version`() {
        assertEquals(9, CURRENT_FORMAT_VERSION)
        val saved = layers.first().copy(uri = android.net.Uri.parse("")).toOverlayLayer()
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val text = json.encodeToString(OverlayLayer.serializer(), saved)
        val restored = json.decodeFromString(OverlayLayer.serializer(), text).toLayer()
        assertEquals(floor, restored.surface)
        assertTrue(restored.surface!!.isFlat)
        assertEquals(floor.length, restored.surface!!.length, 1e-3f)
    }

    @Test
    fun `a layer saved with the retired 3D flag loads as a flat layer`() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val text = json.encodeToString(OverlayLayer.serializer(), Layer("old", "Old", uri = android.net.Uri.parse("")).toOverlayLayer())
            .replaceFirst("{", "{\"plane3D\":true,")
        val restored = json.decodeFromString(OverlayLayer.serializer(), text).toLayer()
        assertEquals(false, restored.is3DIn(listOf(restored)))
        assertTrue(com.hereliesaz.graffitixr.common.scene.SceneRenderPath.isFlat(listOf(restored), SceneCamera()))
    }
}
