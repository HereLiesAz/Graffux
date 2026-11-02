@file:Suppress("MagicNumber")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.scene.DepthComposite
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SurfaceProfile
import com.hereliesaz.graffitixr.feature.editor.threed.DepthCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
}
