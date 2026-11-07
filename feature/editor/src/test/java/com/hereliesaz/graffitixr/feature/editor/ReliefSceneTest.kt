@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.scene.DepthComposite
import com.hereliesaz.graffitixr.common.scene.ReliefOptions
import com.hereliesaz.graffitixr.common.scene.ReliefSurface
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A relief group draws its photo on the displaced mesh through the depth pass (spec/3d-space.md, "Relief surfaces"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LongParameterList")
class ReliefSceneTest {
    private val w = 1000f
    private val h = 800f
    private val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
    private val camera = SceneCamera(yaw = 0.5f, pitch = -0.2f)

    private fun relief(): ReliefSurface = ReliefSurface.build(
        FloatArray(16 * 12) { i -> 1f - kotlin.math.abs((i % 16) - 7.5f) / 8f }, 16, 12, 400, 300, 0f, 25f, 1000f, 775f,
        ReliefOptions(smoothingRadius = 0, clipLow = 0f, clipHigh = 1f),
    )!!

    private fun layers(relief: ReliefSurface = relief(), groupOpacity: Float = 1f) = listOf(
        Layer("g", "Relief", type = LayerType.GROUP, surface = relief.toProfile(), opacity = groupOpacity),
        Layer("photo", "Photo", parentId = "g", bitmap = bitmap),
    )

    private fun scene(layers: List<Layer>, activeId: String? = null): com.hereliesaz.graffitixr.feature.editor.threed.DepthCompositor.Scene {
        val plan = DepthComposite.plan(layers, camera)
        return DepthScene.build(layers, plan, camera, w, h, { l -> l.bitmap?.let { it to 1L } }, { null }, activeId = activeId)
    }

    @Test
    fun `the photo is drawn on the relief's triangles, not on a flat plane`() {
        val r = relief()
        val plane = scene(layers(r)).planes.single()
        val mesh = plane.surface!!
        assertTrue(plane.surfaceTriangles)
        assertFalse(plane.surfaceFlat)
        assertEquals("g", plane.surfaceGroup)
        assertEquals((r.cols - 1) * (r.rows - 1) * 2 * 3 * 5, mesh.size)
    }

    @Test
    fun `the mesh holds the displacement and the uv covers the photo exactly`() {
        val r = relief()
        val mesh = scene(layers(r)).planes.single().surface!!
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        var minU = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (i in mesh.indices step 5) {
            minZ = minOf(minZ, mesh[i + 2]); maxZ = maxOf(maxZ, mesh[i + 2])
            minU = minOf(minU, mesh[i + 3]); maxU = maxOf(maxU, mesh[i + 3])
            minV = minOf(minV, mesh[i + 4]); maxV = maxOf(maxV, mesh[i + 4])
        }
        assertTrue(minZ < -r.displacement * 0.4f && maxZ > r.displacement * 0.4f)
        assertEquals(0f, minU, 1e-3f); assertEquals(1f, maxU, 1e-3f)
        assertEquals(0f, minV, 1e-3f); assertEquals(1f, maxV, 1e-3f)
    }

    @Test
    fun `the mesh is built once per relief and photo, and again when the strength changes`() {
        val r = relief()
        val a = scene(layers(r)).planes.single().surface!!
        val b = scene(layers(r)).planes.single().surface!!
        assertSame(a, b)
        val c = scene(layers(r.withStrength(0.5f))).planes.single().surface!!
        assertNotSame(a, c)
        val d = scene(layers(r.withInverted(true))).planes.single().surface!!
        assertNotSame(a, d)
    }

    @Test
    fun `group opacity folds into an isolated group as for any surface`() {
        val s = scene(layers(groupOpacity = 0.5f), activeId = "photo")
        assertEquals(1, s.groups.size)
        assertEquals(0.5f, s.groups.single().opacity, 0f)
        assertEquals(listOf("photo"), s.groups.single().memberIds)
        assertNotNull(s.planes.single().surface)
    }
}

/** The relief in the glTF export: its mesh in scene space with the photo as texture, without touching the pass's shared mesh. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReliefGltfSceneTest {
    private val w = 1000f
    private val h = 800f
    private val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
    private val relief = ReliefSurface.build(
        FloatArray(16 * 12) { i -> 1f - kotlin.math.abs((i % 16) - 7.5f) / 8f }, 16, 12, 400, 300, 0f, 25f, 1000f, 775f,
        ReliefOptions(smoothingRadius = 0, clipLow = 0f, clipHigh = 1f),
    )
    private val layers = listOf(
        Layer("g", "Relief", type = LayerType.GROUP, surface = relief!!.toProfile(), opacity = 0.8f),
        Layer("photo", "Photo", parentId = "g", bitmap = bitmap),
    )

    private fun exported() = buildSceneGltf(
        com.hereliesaz.graffitixr.common.model.EditorUiState(layers = layers), w, h, includeCamera = false,
    )

    @Test
    fun `the photo exports as the relief mesh with the group's opacity`() {
        val quad = exported().quads.single()
        val r = relief!!
        assertEquals("Photo", quad.name)
        assertEquals(0.8f, quad.opacity, 1e-6f)
        val mesh = quad.surface!!
        assertEquals((r.cols - 1) * (r.rows - 1) * 6 * 5, mesh.size)
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (i in mesh.indices step 5) {
            minZ = minOf(minZ, mesh[i + 2]); maxZ = maxOf(maxZ, mesh[i + 2])
            assertTrue(mesh[i + 3] in -1e-3f..1.001f && mesh[i + 4] in -1e-3f..1.001f)
        }
        assertEquals(r.displacement, maxZ - minZ, r.displacement * 0.1f)
    }

    @Test
    fun `exporting does not disturb the mesh the pass draws`() {
        val plan = DepthComposite.plan(layers, SceneCamera())
        fun pass() = DepthScene.build(layers, plan, SceneCamera(), w, h, { l -> l.bitmap?.let { it to 1L } }, { null }).planes.single().surface!!
        val before = pass()
        val copy = before.copyOf()
        exported()
        val after = pass()
        assertSame(before, after)
        assertTrue(copy.contentEquals(after))
    }
}
