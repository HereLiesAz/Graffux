@file:Suppress("MaxLineLength", "MagicNumber", "CyclomaticComplexMethod", "ReturnCount")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.CURRENT_FORMAT_VERSION
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.isAdjustment
import com.hereliesaz.graffitixr.common.model.isContainer
import com.hereliesaz.graffitixr.common.scene.ArtSheet
import com.hereliesaz.graffitixr.common.scene.DefaultSurface
import com.hereliesaz.graffitixr.common.scene.OrbitPivot
import com.hereliesaz.graffitixr.common.scene.SceneMath
import com.hereliesaz.graffitixr.common.scene.SceneRect
import com.hereliesaz.graffitixr.common.scene.SurfaceMath
import com.hereliesaz.graffitixr.common.scene.SurfaceProfile
import com.hereliesaz.graffitixr.common.scene.planeChain
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A saved project's pivot (spec/3d-space.md, "The orbit point"), against the OLD derivation as the reference: a project
 * nothing was moved in loads with exactly the pivot the old code gave it, and one that was edited loads with the rest pivot,
 * which differs from the old one by the edit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OrbitPivotPersistenceTest {
    private val w = 1000f
    private val h = 1600f
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** The derivation as it was on main before the pivot stopped following poses, copied as the reference. */
    private object Legacy {
        fun of(layers: List<Layer>, width: Float, height: Float): FloatArray {
            ofSurfaces(layers, width, height)?.let { return it }
            val d = max(width, height)
            val content = layers.filter { !it.isContainer && !it.isAdjustment }
            val base = content.firstOrNull() ?: return floatArrayOf(width / 2f, height / 2f, d / 2f)
            val baseModel = SceneMath.chainModel(planeChain(layers, base.id), width, height)
            if (content.size == 1) return baseModel.apply(width / 2f, height / 2f, d / 2f)
            val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (layer in content) {
                val m = SceneMath.chainModel(planeChain(layers, layer.id), width, height)
                for ((u, v) in listOf(0f to 0f, width to 0f, 0f to height, width to height)) {
                    val p = m.apply(u, v, 0f)
                    for (i in 0..2) { lo[i] = min(lo[i], p[i]); hi[i] = max(hi[i], p[i]) }
                }
            }
            val flat = d * 1e-3f
            val box = FloatArray(3) { Float.MAX_VALUE } to FloatArray(3) { -Float.MAX_VALUE }
            for (z in listOf(0f, d)) for ((u, v) in listOf(0f to 0f, width to 0f, 0f to height, width to height)) {
                val p = baseModel.apply(u, v, z)
                for (i in 0..2) { box.first[i] = min(box.first[i], p[i]); box.second[i] = max(box.second[i], p[i]) }
            }
            return FloatArray(3) { i ->
                if (abs(hi[i] - lo[i]) <= flat) (box.first[i] + box.second[i]) / 2f else (lo[i] + hi[i]) / 2f
            }
        }

        private fun sheetDepth(profile: SurfaceProfile): FloatArray? {
            val limit = profile.sweepLimit
            if (!profile.isFlat || limit == null) return null
            val a = profile.point(0)
            val b = profile.point(profile.count - 1)
            val e = SceneMath.normalize(floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2]))
            val normal = SceneMath.normalize(SceneMath.cross(e, profile.sweep))
            val d = 2f * ArtSheet.pivotDepth(profile.length, limit)
            return floatArrayOf(normal[0] * d, normal[1] * d, normal[2] * d)
        }

        fun ofSurfaces(layers: List<Layer>, width: Float, height: Float): FloatArray? {
            val surfaces = layers.filter { it.isContainer && it.surface?.isValid == true }
            if (surfaces.isEmpty()) return null
            val byId = layers.associateBy { it.id }
            val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            fun grow(p: FloatArray) { for (i in 0..2) { lo[i] = min(lo[i], p[i]); hi[i] = max(hi[i], p[i]) } }
            for (group in surfaces) {
                val (a, b) = SurfaceMath.localBounds(group.surface!!) ?: continue
                val m = SceneMath.chainModel(planeChain(layers, group.id), width, height)
                val depth = sheetDepth(group.surface!!)
                for (mask in 0 until 8) {
                    val x = if (mask and 1 == 0) a[0] else b[0]
                    val y = if (mask and 2 == 0) a[1] else b[1]
                    val z = if (mask and 4 == 0) a[2] else b[2]
                    grow(m.apply(x, y, z))
                    depth?.let { d -> grow(m.apply(x + d[0], y + d[1], z + d[2])) }
                }
            }
            for (layer in layers.filter { !it.isContainer && !it.isAdjustment }) {
                if (SurfaceMath.ownerOf(byId, layer.id) != null) continue
                val m = SceneMath.chainModel(planeChain(layers, layer.id), width, height)
                for ((u, v) in listOf(0f to 0f, width to 0f, 0f to height, width to height)) grow(m.apply(u, v, 0f))
            }
            if (lo[0] > hi[0]) return null
            return FloatArray(3) { (lo[it] + hi[it]) / 2f }
        }
    }

    private fun uri() = android.net.Uri.parse("")

    private fun sheet(id: String, r: SceneRect) = Layer(
        id = id, name = id, type = LayerType.GROUP, uri = uri(), surface = DefaultSurface.profile(r, w, h, emptyList()),
    )

    private fun base() = Layer(id = "base", name = "base", uri = uri())

    /** The scenes a user could have saved, none of them moved. */
    private fun scenes(): Map<String, List<Layer>> = mapOf(
        "default sheet alone" to listOf(sheet("a", DefaultSurface.rect(w, h)!!)),
        "default sheet with a base layer and paint" to listOf(base(), sheet("a", DefaultSurface.rect(w, h)!!), Layer(id = "p", name = "p", uri = uri(), parentId = "a")),
        "two sheets and a base layer" to listOf(base(), sheet("a", DefaultSurface.rect(w, h)!!), sheet("b", SceneRect(600f, 100f, 300f, 500f))),
        "a drawn floor" to listOf(Layer(id = "f", name = "f", type = LayerType.GROUP, uri = uri(), surface = SurfaceProfile(listOf(200f, 1000f, 0f, 800f, 1000f, 0f), 0f, 0f, 1f))),
        "no surface, one layer" to listOf(base()),
        "no surface, two layers" to listOf(base(), Layer(id = "two", name = "two", uri = uri())),
    )

    /** The real file path: the editor layers to the project's own layers, to JSON text, and back through the decoder. */
    private fun loaded(layers: List<Layer>): List<Layer> {
        val project = GraffitiProject(id = "p", layers = layers.map { it.toOverlayLayer() })
        val back = json.decodeFromString(GraffitiProject.serializer(), json.encodeToString(GraffitiProject.serializer(), project))
        assertEquals(CURRENT_FORMAT_VERSION, back.formatVersion)
        assertEquals(15, back.formatVersion)
        return back.layers.map { it.toLayer() }
    }

    private fun assertEqualPivots(a: FloatArray, b: FloatArray, what: String) {
        for (i in 0..2) assertEquals("$what [$i]", a[i], b[i], 1e-3f)
    }

    @Test
    fun `a saved project nothing was moved in loads with exactly the pivot the old derivation gave it`() {
        for ((name, layers) in scenes()) {
            assertEqualPivots(Legacy.of(layers, w, h), OrbitPivot.resolve(loaded(layers), w, h), name)
        }
    }

    @Test
    fun `an edited saved project loads at the rest pivot, which differs from the old one by the edit`() {
        val rest = OrbitPivot.resolve(listOf(sheet("a", DefaultSurface.rect(w, h)!!)), w, h)
        // The example in the spec: the default sheet, later slid 300 px right with the Move tool.
        val slid = listOf(sheet("a", DefaultSurface.rect(w, h)!!).copy(offset = Offset(300f, 0f)))
        val old = Legacy.of(slid, w, h)
        assertEquals(800f, old[0], 0.01f)
        assertEquals(454.17f, old[2], 0.01f)
        val now = OrbitPivot.resolve(loaded(slid), w, h)
        assertEquals(500f, now[0], 0.01f)
        assertEquals(800f, now[1], 0.01f)
        assertEquals(454.17f, now[2], 0.01f)
        assertEqualPivots(rest, now, "stays where it was made")
        // Every kind of edit changes the old answer and leaves the new one: a sheet turned, a base layer tilted, layer 1 slid.
        val edits = listOf(
            "sheet turned" to listOf(base(), sheet("a", DefaultSurface.rect(w, h)!!).copy(rotationX = 40f, positionZ = 120f)),
            "base layer slid beside a sheet" to listOf(base().copy(offset = Offset(400f, 0f)), sheet("a", SceneRect(100f, 100f, 200f, 200f))),
            "lone layer 1 tilted and slid" to listOf(base().copy(offset = Offset(200f, 0f), rotationX = 30f, positionZ = 60f)),
        )
        for ((name, layers) in edits) {
            val unedited = layers.map { it.copy(offset = Offset.Zero, rotationX = 0f, rotationY = 0f, rotationZ = 0f, positionZ = 0f, scale = 1f) }
            val oldPivot = Legacy.of(layers, w, h)
            val newPivot = OrbitPivot.resolve(loaded(layers), w, h)
            assertTrue("$name: the old derivation followed the edit", (0..2).any { abs(oldPivot[it] - newPivot[it]) > 1f })
            assertEqualPivots(Legacy.of(unedited, w, h), newPivot, "$name: rest pivot is the unedited project's old pivot")
        }
    }
}
