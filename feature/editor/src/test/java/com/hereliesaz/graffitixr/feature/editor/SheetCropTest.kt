package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.scene.DefaultSurface
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SceneMath
import com.hereliesaz.graffitixr.common.scene.SurfaceMath
import com.hereliesaz.graffitixr.common.scene.SurfaceProfile
import com.hereliesaz.graffitixr.feature.editor.export.SceneExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** A layer drawn through a flat sheet's model is cropped to the sheet, on the canvas and in the export. */
class SheetCropTest {
    private val w = 1000f
    private val h = 1600f
    private val camera = SceneCamera()
    private val frame = SceneMath.frame(camera, w, h)

    private fun layers(profile: SurfaceProfile) = listOf(
        Layer(id = "g", name = "g", type = LayerType.GROUP, surface = profile),
        Layer(id = "c", name = "c", parentId = "g"),
    )

    private fun context(layers: List<Layer>) = SceneContext(
        camera, frame, setOf("c"),
        flatSurfaceModels = SurfaceMath.flatSurfaceModels(layers, w, h),
        flatSurfaceCrops = SurfaceMath.flatSurfaceCrops(layers, w, h),
    )

    private val sheet = DefaultSurface.profile(DefaultSurface.rect(w, h), w, h, emptyList())!!

    @Test
    fun `the canvas path crops a layer on the default sheet to the sheet`() {
        val l = layers(sheet)
        val clip = context(l).placementFor(l[1])!!.clip
        assertNotNull(clip)
        assertEquals(0f, clip!!.minOf { it.first }, 0.01f)
        assertEquals(sheet.length, clip.maxOf { it.first }, 0.01f)
        assertEquals(sheet.reach!!, clip.maxOf { it.second }, 0.01f)
    }

    @Test
    fun `a layer on a drawn flat surface keeps no crop`() {
        val drawn = SurfaceProfile(listOf(125f, 267f, 0f, 875f, 267f, 0f), 0f, 1f, 0f)
        val l = layers(drawn)
        assertNull(context(l).placementFor(l[1])!!.clip)
    }

    @Test
    fun `the export path crops the same way and leaves an uncropped layer alone`() {
        val l = layers(sheet)
        val hm = SceneMath.homography(SurfaceMath.flatSurfaceModels(l, w, h)["c"]!!, frame)
        val crop = SurfaceMath.flatSurfaceCrops(l, w, h)["c"]
        val clip = SceneExport.frontClip(hm, frame, w.toInt(), h.toInt(), crop)
        assertEquals(sheet.reach!!, clip!!.maxOf { it.second }, 0.01f)
        assertNull(SceneExport.frontClip(hm, frame, w.toInt(), h.toInt(), null))
    }

    @Test
    fun `a layer whose plane crosses the eye is cut to the part in front and to the sheet`() {
        val l = layers(sheet)
        // A plane turned edge-on through the eye: the in-front polygon is not null.
        val model = SceneMath.model(
            com.hereliesaz.graffitixr.common.scene.PlanePose(positionZ = -400f, rotationY = 80f), w, h,
        )
        val hm = SceneMath.homography(model, frame)
        val front = SceneMath.visiblePolygon(hm, frame, 8f * 1600f)
        assertNotNull(front)
        val crop = SurfaceMath.flatSurfaceCrops(l, w, h)["c"]!!
        val both = SceneExport.frontClip(hm, frame, w.toInt(), h.toInt(), crop)!!
        assertEquals(SceneMath.clipConvex(front!!, crop), both)
    }
}
