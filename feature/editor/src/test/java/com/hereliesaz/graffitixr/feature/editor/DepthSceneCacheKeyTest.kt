@file:Suppress("MagicNumber", "MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.scene.DepthComposite
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SurfaceProfile
import com.hereliesaz.graffitixr.feature.editor.threed.DepthCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The 3D inactive-layer cache's keys and the uncached draw order it must agree with. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DepthSceneCacheKeyTest {
    private val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    private val layers = listOf(
        Layer("far", "far", bitmap = bmp, positionZ = 300f),
        Layer("active", "active", bitmap = bmp, rotationY = 10f),
        Layer("glass", "glass", bitmap = bmp, positionZ = -100f, opacity = 0.5f),
    )
    private val camera = SceneCamera(yaw = 0.2f)

    private fun scene(layers: List<Layer>, w: Float, h: Float, active: String? = "active") = DepthScene.build(
        layers, DepthComposite.plan(layers, camera), camera, w, h,
        pixels = { l -> l.bitmap?.let { it to 0L } }, vectors = { null }, activeId = active, drawing = true,
    )

    @Test
    fun `a resize keeps every key, so the renders are scaled rather than rebuilt`() {
        val a = scene(layers, 1000f, 800f)
        val b = scene(layers, 1200f, 900f)
        assertEquals(a.planes.map { it.key }, b.planes.map { it.key })
        assertEquals(a.viewKey, b.viewKey)
    }

    @Test
    fun `content, opacity and transform changes change the key, and the camera the view key`() {
        val base = scene(layers, 1000f, 800f).planes.first { it.id == "far" }.key
        for (changed in listOf(
            layers[0].copy(opacity = 0.7f),
            layers[0].copy(positionZ = 320f),
            layers[0].copy(bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)),
        )) {
            val k = scene(listOf(changed) + layers.drop(1), 1000f, 800f).planes.first { it.id == "far" }.key
            assertNotEquals(base, k)
        }
        val orbited = DepthScene.build(
            layers, DepthComposite.plan(layers, camera), camera.copy(yaw = 0.4f), 1000f, 800f,
            pixels = { l -> l.bitmap?.let { it to 0L } }, vectors = { null }, activeId = "active",
        )
        assertNotEquals(scene(layers, 1000f, 800f).viewKey, orbited.viewKey)
    }

    @Test
    fun `a change to the owning group's surface changes its children's keys`() {
        val bmpChild = Layer("child", "child", bitmap = bmp, parentId = "wall")
        val group = Layer(
            "wall", "wall", type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP,
            surface = SurfaceProfile(listOf(0f, 0f, 0f, 1000f, 0f, 0f)),
        )
        val reshaped = group.copy(surface = SurfaceProfile(listOf(0f, 0f, 0f, 1000f, 0f, 400f)))
        fun key(g: Layer) = scene(listOf(g, bmpChild) + layers, 1000f, 800f).planes.first { it.id == "child" }.key
        assertNotEquals(key(group), key(reshaped))
    }

    @Test
    fun `the export caches nothing`() {
        val s = scene(layers, 1000f, 800f, active = null)
        assertNull(s.viewKey)
        s.planes.forEach { assertNull(it.key) }
    }

    @Test
    fun `the draw order is opaque farthest first, then translucent`() {
        val order = DepthCompositor.drawOrder(scene(layers, 1000f, 800f))
        assertEquals(listOf("far", "active", "glass"), order.map { it.id })
        assertEquals(listOf(false, false, true), order.map { it.blended })
    }

    private val wall = SurfaceProfile(listOf(0f, 0f, 0f, 500f, 0f, 0f, 1000f, 0f, 400f))

    private fun group(opacity: Float, blend: androidx.compose.ui.graphics.BlendMode = androidx.compose.ui.graphics.BlendMode.SrcOver) = Layer(
        "wall", "wall", type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP, opacity = opacity, blendMode = blend, surface = wall,
    )

    private val kids = listOf(
        Layer("c1", "c1", bitmap = bmp, parentId = "wall"),
        Layer("c2", "c2", bitmap = bmp, parentId = "wall", opacity = 0.8f),
    )

    private fun wallScene(opacity: Float, blend: androidx.compose.ui.graphics.BlendMode = androidx.compose.ui.graphics.BlendMode.SrcOver, active: String? = "c1") =
        scene(listOf(group(opacity, blend)) + kids, 1000f, 800f, active)

    @Test
    fun `a translucent surface group's layers keep only their own opacity, the group's goes on the merged image`() {
        val s = wallScene(0.5f)
        assertEquals(listOf(1f, 0.8f), s.planes.map { it.alpha })
        val g = s.groups.single()
        assertEquals("wall", g.id)
        assertEquals(listOf("c1", "c2"), g.memberIds)
        assertEquals(0.5f, g.opacity, 1e-6f)
    }

    @Test
    fun `a full-opacity surface group has no unit, so no offscreen pass`() {
        val s = wallScene(1f)
        assertTrue(s.groups.isEmpty())
        assertEquals(listOf(1f, 0.8f), s.planes.map { it.alpha })
        val order = DepthCompositor.drawOrder(s)
        assertEquals(listOf("c1", "c2"), order.map { it.id })
        assertTrue(order.none { it.member is DepthCompositor.Isolated })
        assertEquals(listOf(false, false), order.map { it.blended })
    }

    @Test
    fun `a translucent group draws as one unit among the surface groups, after the opaque flat planes`() {
        val order = DepthCompositor.drawOrder(scene(listOf(group(0.5f)) + kids + layers, 1000f, 800f, "c1"))
        val ids = order.map { it.id }
        // The layers are inside the unit, never drawn singly.
        assertTrue("c1" !in ids && "c2" !in ids)
        val unit = order.single { it.member is DepthCompositor.Isolated }
        assertEquals("wall", unit.id)
        assertFalse(unit.blended)
        assertEquals(listOf("c1", "c2"), (unit.member as DepthCompositor.Isolated).planes.map { it.id })
        assertTrue(ids.indexOf("wall") > ids.indexOf("far") && ids.indexOf("wall") > ids.indexOf("active"))
    }

    @Test
    fun `the group's opacity and blend are in the cache keys, as are its members'`() {
        val base = wallScene(0.5f)
        val faded = wallScene(0.6f)
        assertNotEquals(base.groups.single().key, faded.groups.single().key)
        assertNotEquals(base.planes.map { it.key }, faded.planes.map { it.key })
        val blended = wallScene(0.5f, androidx.compose.ui.graphics.BlendMode.Multiply)
        // A blend mode on a curved surface is not applied, but a change of it still rebuilds a render
        // that holds the group.
        assertNotEquals(base.groups.single().key, blended.groups.single().key)
        assertEquals(base.groups.single().key, wallScene(0.5f).groups.single().key)
        // The export caches nothing.
        assertNull(wallScene(0.5f, active = null).groups.single().key)
    }
}
