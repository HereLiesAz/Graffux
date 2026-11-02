@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import com.hereliesaz.graffitixr.common.mesh.ObjParser
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.scene.ModelLayerContent
import com.hereliesaz.graffitixr.common.scene.ModelMesh
import com.hereliesaz.graffitixr.common.scene.ModelSpace
import com.hereliesaz.graffitixr.common.scene.PlanePose
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SceneMath
import com.hereliesaz.graffitixr.feature.editor.export.ExportManager
import com.hereliesaz.graffitixr.feature.editor.threed.DepthCompositor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Model layers in the depth pass's draw order and in exports (Codex review of #684). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ModelDepthOrderTest {
    private val quad = ModelMesh(ObjParser.parse("v -1 -1 0\nv 1 -1 0\nv 1 1 0\nv -1 1 0\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\nf 1/1 2/2 3/3 4/4"))

    @Test
    fun `translucent models draw farthest first`() {
        val w = 400f
        val h = 400f
        val cam = SceneMath.frame(SceneCamera(), w, h)
        fun at(id: String, z: Float) = DepthCompositor.Model(
            id, quad.mesh, ModelSpace.meshToScene(listOf(PlanePose(positionZ = z)), quad, w, h), null, 0L, 0.5f,
        )
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val plane = DepthCompositor.Plane(
            "plane", bitmap, 0L, SceneMath.model(PlanePose(positionZ = 0f), w, h), floatArrayOf(0f, 0f, w, h), null, 0.5f,
        )
        // A translucent plane sorts among the translucent models (Codex #686): a model behind it
        // draws before it, so it shows through the plane instead of failing the depth test.
        val order = DepthCompositor.translucentOrder(listOf(plane), listOf(at("near", -100f), at("far", 300f), at("mid", 50f)), cam)
        assertEquals(
            listOf("far", "mid", "plane", "near"),
            order.map { (it as? DepthCompositor.Model)?.id ?: (it as DepthCompositor.Plane).id },
        )
    }

    @Test
    fun `a composite without a camera still renders a model layer through the scene`() {
        val export = ExportManager()
        var passed: DepthCompositor.Scene? = null
        export.depthRenderer = { scene, _, _ -> passed = scene; null }
        val bg = Layer("bg", "BG", bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) })
        val model = Layer("m", "M", plane3D = true, model = ModelLayerContent("model_m.obj", mesh = quad))
        export.compositeLayers(listOf(bg, model), 400, 400)
        assertEquals(listOf("m"), passed!!.models.map { it.id })
    }
}
