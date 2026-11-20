@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.CURRENT_FORMAT_VERSION
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.scene.DefaultSurface
import com.hereliesaz.graffitixr.common.scene.OrbitPivot
import com.hereliesaz.graffitixr.common.scene.SceneRect
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A saved project's pivot (spec/3d-space.md, "The orbit point"): measured at rest, so a project whose poses were edited
 * loads with the pivot it had before the edits, and one with no edits loads with the pivot it always had.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OrbitPivotPersistenceTest {
    private val w = 1000f
    private val h = 1600f
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun sheet(id: String, r: SceneRect) = Layer(
        id = id, name = id, type = LayerType.GROUP, uri = android.net.Uri.parse(""),
        surface = DefaultSurface.profile(r, w, h, emptyList()),
    )

    private fun scene(posed: Boolean): List<Layer> {
        val edit = { l: Layer ->
            if (!posed) l else l.copy(offset = Offset(210f, -140f), scale = 1.6f, rotationX = 40f, rotationY = -25f, rotationZ = 70f, positionZ = 130f)
        }
        return listOf(
            edit(Layer(id = "base", name = "base", uri = android.net.Uri.parse(""))),
            edit(sheet("a", DefaultSurface.rect(w, h)!!)),
            edit(Layer(id = "pa", name = "pa", uri = android.net.Uri.parse(""), parentId = "a")),
            edit(sheet("b", SceneRect(600f, 100f, 300f, 500f))),
        )
    }

    private fun roundTrip(layers: List<Layer>): List<Layer> {
        val project = GraffitiProject(id = "p", layers = layers.map { it.toOverlayLayer() })
        val back = json.decodeFromString(GraffitiProject.serializer(), json.encodeToString(GraffitiProject.serializer(), project))
        assertEquals(CURRENT_FORMAT_VERSION, back.formatVersion)
        return back.layers.map { it.toLayer() }
    }

    @Test
    fun `a project with no pose edit loads with the pivot it always had, and the format is still 14`() {
        assertEquals(14, CURRENT_FORMAT_VERSION)
        val layers = scene(posed = false)
        val before = OrbitPivot.resolve(layers, w, h)
        assertEquals(500f, before[0], 0.01f)
        assertEquals(800f, before[1], 0.01f)
        assertEquals(454.17f, before[2], 0.01f)
        val after = OrbitPivot.resolve(roundTrip(layers), w, h)
        for (i in 0..2) assertEquals(before[i], after[i], 1e-3f)
    }

    @Test
    fun `a project saved with edited poses loads with the same pivot as before the edits, and as it was saved`() {
        val rest = OrbitPivot.resolve(scene(posed = false), w, h)
        val posed = scene(posed = true)
        val saved = OrbitPivot.resolve(posed, w, h)
        val loaded = roundTrip(posed)
        assertEquals("the poses survived the round trip", 130f, loaded.first { it.id == "a" }.positionZ, 0f)
        val after = OrbitPivot.resolve(loaded, w, h)
        for (i in 0..2) {
            assertEquals("saved [$i]", rest[i], saved[i], 1e-3f)
            assertEquals("loaded [$i]", rest[i], after[i], 1e-3f)
        }
    }
}
