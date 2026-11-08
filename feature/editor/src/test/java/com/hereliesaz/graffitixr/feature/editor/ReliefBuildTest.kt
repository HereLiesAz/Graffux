@file:Suppress("MaxLineLength", "LongParameterList")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.scene.ReliefOptions
import com.hereliesaz.graffitixr.common.scene.SurfaceMath
import com.hereliesaz.graffitixr.common.scene.SurfaceProfile
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Building a relief surface from a photo layer (spec/3d-space.md, "Relief surfaces"): the pure parts. */
class ReliefBuildTest {
    private val photo = Layer("photo", "Photo")
    private val other = Layer("other", "Other")

    private fun plan(layers: List<Layer>, id: String = "photo", bw: Int = 400, bh: Int = 300, cw: Float = 1000f, ch: Float = 800f) =
        ReliefBuild.plan(layers, id, bw, bh, cw, ch)

    private fun ok(p: ReliefPlan) = p as ReliefPlan.Ok

    private fun refusal(p: ReliefPlan) = (p as ReliefPlan.Refused).reason

    // --- the plan -----------------------------------------------------------------------------

    @Test fun aPlainPhotoCoversWhereTheCanvasDrawsIt() {
        val p = ok(plan(listOf(photo)))
        // 400 x 300 fitted into 1000 x 800: scale 2.5, 1000 x 750, centred.
        assertEquals(0f, p.left, 0.01f)
        assertEquals(25f, p.top, 0.01f)
        assertEquals(1000f, p.right, 0.01f)
        assertEquals(775f, p.bottom, 0.01f)
        assertEquals(400, p.photoWidth)
        assertEquals(300, p.photoHeight)
    }

    @Test fun theLayersOwnScaleAndOffsetMoveTheExtent() {
        val moved = photo.copy(scale = 0.5f, offset = androidx.compose.ui.geometry.Offset(40f, -10f))
        val p = ok(plan(listOf(moved)))
        // Scaled about the canvas centre (500, 400), then offset.
        assertEquals(290f, p.left, 0.5f)
        assertEquals(790f, p.right, 0.5f)
        assertEquals(202.5f, p.top, 0.5f)
        assertEquals(577.5f, p.bottom, 0.5f)
    }

    @Test fun aRotatedPhotoIsRefusedBecauseTheGridCouldNotMatchIt() {
        assertEquals(ReliefRefusal.ROTATED, refusal(plan(listOf(photo.copy(rotationZ = 12f)))))
    }

    @Test fun aPhotoPlacedInDepthIsRefused() {
        assertEquals(ReliefRefusal.PLACED_IN_3D, refusal(plan(listOf(photo.copy(positionZ = 30f)))))
        assertEquals(ReliefRefusal.PLACED_IN_3D, refusal(plan(listOf(photo.copy(rotationX = 20f)))))
    }

    @Test fun onlyAPhotoWithPixelsCanBeBuilt() {
        assertEquals(ReliefRefusal.NO_LAYER, refusal(plan(listOf(photo), id = "missing")))
        assertEquals(ReliefRefusal.NOT_A_PHOTO, refusal(plan(listOf(Layer("g", "G", type = LayerType.GROUP)), id = "g")))
        assertEquals(ReliefRefusal.NO_PIXELS, refusal(plan(listOf(photo), bw = 0, bh = 0)))
        assertEquals(ReliefRefusal.NO_CANVAS, refusal(plan(listOf(photo), cw = 0f)))
        assertEquals(ReliefRefusal.LOCKED, refusal(plan(listOf(photo.copy(isImageLocked = true)))))
    }

    @Test fun aLayerAlreadyOnASurfaceIsRefused() {
        val group = Layer("g", "G", type = LayerType.GROUP, surface = SurfaceProfile(listOf(0f, 0f, 0f, 100f, 0f, 0f)))
        assertEquals(ReliefRefusal.ON_SURFACE, refusal(plan(listOf(group, photo.copy(parentId = "g")))))
    }

    @Test fun aPerViewImageIsNotAPhoto() {
        assertEquals(ReliefRefusal.NOT_A_PHOTO, refusal(plan(listOf(photo.copy(surfaceViewOf = "other")))))
    }

    // --- assembling ---------------------------------------------------------------------------

    private val relief = com.hereliesaz.graffitixr.common.scene.ReliefSurface.build(
        FloatArray(16) { (it % 4) / 3f }, 4, 4, 400, 300, 0f, 25f, 1000f, 775f,
    )!!

    @Test fun thePhotoBecomesTheOnlyPaintOnAReliefGroup() {
        val out = ReliefBuild.assemble(listOf(other, photo), "photo", "relief", relief)
        assertEquals(listOf("other", "relief", "photo"), out.map { it.id })
        val group = out.first { it.id == "relief" }
        assertEquals(LayerType.GROUP, group.type)
        assertNull(group.parentId)
        assertEquals(relief, group.surface!!.relief)
        assertEquals("relief", out.first { it.id == "photo" }.parentId)
        assertSame(other, out.first { it.id == "other" })
    }

    @Test fun theNewGroupTakesThePhotosPlaceInItsFolder() {
        val folder = Layer("folder", "Folder", type = LayerType.GROUP)
        val out = ReliefBuild.assemble(listOf(folder, photo.copy(parentId = "folder")), "photo", "relief", relief)
        assertEquals("folder", out.first { it.id == "relief" }.parentId)
        assertEquals("relief", out.first { it.id == "photo" }.parentId)
    }

    @Test fun thePhotoIsThenPaintOnTheReliefSurface() {
        val out = ReliefBuild.assemble(listOf(photo), "photo", "relief", relief)
        val owner = SurfaceMath.ownerOf(out, "photo")
        assertNotNull(owner)
        assertEquals("relief", owner!!.group.id)
        assertNotNull(owner.profile.relief)
        assertFalse(owner.profile.isFlat)
    }

    @Test fun aPhotoDerivedGroupIsMeshedFromItsDepthEvenWithViewsDrawn() {
        val out = ReliefBuild.assemble(listOf(photo), "photo", "relief", relief)
        val profile = SurfaceMath.ownerOf(out, "photo")!!.profile
        val square = listOf(0f, 0f, 0f, 100f, 0f, 0f, 100f, 100f, 0f, 0f, 100f, 0f)
        val drawn = profile.copy(side = SurfaceProfile(square, 1f, 0f, 0f), top = SurfaceProfile(square, 0f, 1f, 0f))
        val cam = com.hereliesaz.graffitixr.common.scene.SceneMath.frame(com.hereliesaz.graffitixr.common.scene.SceneCamera(), 1000f, 800f)
        val m = com.hereliesaz.graffitixr.common.scene.Affine3.IDENTITY
        val expected = com.hereliesaz.graffitixr.common.scene.ReliefMath
            .gridLines(profile.relief!!, m, cam, SurfaceMath.GRID_SPACING).map { it.toList() }
        assertTrue(expected.isNotEmpty())
        assertEquals(expected, SurfaceMath.gridLines(profile, m, cam).map { it.toList() })
        assertEquals(expected, SurfaceMath.gridLines(drawn, m, cam).map { it.toList() })
    }

    @Test fun assemblingForAMissingLayerChangesNothing() {
        val layers = listOf(photo)
        assertSame(layers, ReliefBuild.assemble(layers, "nope", "relief", relief))
    }

    // --- tuning -------------------------------------------------------------------------------

    @Test fun strengthAndInvertEditTheReliefInPlace() {
        val built = ReliefBuild.assemble(listOf(photo), "photo", "relief", relief)
        val stronger = ReliefBuild.tuned(built, "relief") { it.withStrength(0.5f) }
        assertEquals(0.5f, stronger.first { it.id == "relief" }.surface!!.relief!!.strength, 1e-5f)
        val flipped = ReliefBuild.tuned(stronger, "relief") { it.withInverted(!it.inverted) }
        assertTrue(flipped.first { it.id == "relief" }.surface!!.relief!!.inverted)
        assertEquals(stronger.first { it.id == "photo" }, flipped.first { it.id == "photo" })
    }

    @Test fun tuningALayerWithoutAReliefChangesNothing() {
        val layers = listOf(photo)
        assertSame(layers, ReliefBuild.tuned(layers, "photo") { it.withStrength(1f) })
    }

    @Test fun theActiveLayersReliefIsFoundFromThePhotoOrItsGroup() {
        val built = ReliefBuild.assemble(listOf(photo), "photo", "relief", relief)
        assertEquals("relief", ReliefBuild.reliefGroupOf(built, "photo")?.id)
        assertEquals("relief", ReliefBuild.reliefGroupOf(built, "relief")?.id)
        assertNull(ReliefBuild.reliefGroupOf(listOf(photo), "photo"))
        assertNull(ReliefBuild.reliefGroupOf(built, null))
    }

    // --- estimating ---------------------------------------------------------------------------

    private class FakeSource(
        val depth: ReliefDepth? = null,
        val failure: ReliefDepthException? = null,
    ) : ReliefDepthSource {
        var calls = 0
        override suspend fun availability(): ReliefDepthAvailability = ReliefDepthAvailability.Ready
        override suspend fun estimate(bitmap: Bitmap): ReliefDepth {
            calls++
            failure?.let { throw it }
            return depth!!
        }
    }

    private val planOk = ReliefPlan.Ok(0f, 25f, 1000f, 775f, 400, 300)

    @Test fun theModelsDepthBecomesAReliefOverThePlansExtent() = runBlocking {
        val source = FakeSource(ReliefDepth(8, 6, FloatArray(48) { (it % 8) / 7f }))
        val built = ReliefBuild.estimate(source, mockk<Bitmap>(), planOk, ReliefOptions(smoothingRadius = 0))
        assertEquals(1, source.calls)
        assertEquals(1000f, built.spanU, 0f)
        assertEquals(750f, built.spanT, 0f)
        assertEquals(400, built.photoWidth)
        assertTrue(built.heightAt(900f, 400f) > built.heightAt(100f, 400f))
    }

    @Test fun aDepthMapThatIsUnusableIsAnErrorNotAFlatSurface() = runBlocking {
        val source = FakeSource(ReliefDepth(0, 0, FloatArray(0)))
        try {
            ReliefBuild.estimate(source, mockk<Bitmap>(), planOk, ReliefOptions())
            fail("expected a failure")
        } catch (e: ReliefDepthException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test fun theModelsFailureReachesTheCaller() = runBlocking {
        val source = FakeSource(failure = ReliefDepthException("The depth model could not run."))
        try {
            ReliefBuild.estimate(source, mockk<Bitmap>(), planOk, ReliefOptions())
            fail("expected a failure")
        } catch (e: ReliefDepthException) {
            assertEquals("The depth model could not run.", e.message)
        }
    }
}
