@file:Suppress("MaxLineLength", "LongParameterList")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.scene.ReliefOptions
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SurfaceMath
import com.hereliesaz.graffitixr.common.scene.SurfaceProfile
import com.hereliesaz.graffitixr.common.scene.SurfaceVisibility
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

    private val ids = ReliefIds("relief", "skin", "flat")
    private val names = ReliefNames("Relief", "Flat surface", "Photo skin")

    /** [layers] after "Build surface from image" on [id] (400 x 300 bitmap, 1000 x 800 canvas). */
    private fun built(layers: List<Layer>, id: String = "photo"): List<Layer> {
        val leaf = layers.first { it.id == id }
        val flat = ReliefBuild.flatSheet(leaf, 400, 300, 1000f, 800f)!!
        return ReliefBuild.assemble(layers, id, ids, names, relief, flat, leaf)
    }

    @Test fun thePhotoIsDuplicatedOntoTheReliefAndKeptOnAHiddenFlatSurface() {
        val out = built(listOf(other, photo))
        // Stack order: the relief group, its first child the skin, then the hidden flat group and the original.
        assertEquals(listOf("other", "relief", "skin", "flat", "photo"), out.map { it.id })
        val group = out.first { it.id == "relief" }
        assertEquals(LayerType.GROUP, group.type)
        assertEquals("Relief", group.name)
        assertNull(group.parentId)
        assertTrue(group.isVisible)
        assertEquals(relief, group.surface!!.relief)
        val skin = out.first { it.id == "skin" }
        assertEquals("relief", skin.parentId)
        assertEquals("Photo skin", skin.name)
        assertEquals(listOf("skin"), out.filter { it.parentId == "relief" }.map { it.id })
        val flat = out.first { it.id == "flat" }
        assertEquals(LayerType.GROUP, flat.type)
        assertEquals("Flat surface", flat.name)
        assertFalse(flat.isVisible)
        assertNull(flat.parentId)
        assertNull(flat.surface!!.relief)
        assertTrue(flat.surface!!.isValid)
        assertEquals(listOf("photo"), out.filter { it.parentId == "flat" }.map { it.id })
        assertSame(other, out.first { it.id == "other" })
    }

    @Test fun theSkinIsACopyThatKeepsThePhotosPose() {
        val posed = photo.copy(scale = 0.5f, offset = androidx.compose.ui.geometry.Offset(40f, -10f), opacity = 0.8f)
        val out = built(listOf(posed))
        val skin = out.first { it.id == "skin" }
        assertEquals(posed.offset, skin.offset)
        assertEquals(posed.scale, skin.scale, 0f)
        assertEquals(0.8f, skin.opacity, 0f)
    }

    @Test fun theFlatSurfaceIsSizedToThePhotoAndTheOriginalStartsAtItsCorner() {
        val out = built(listOf(photo))
        val sheet = ReliefBuild.flatSheet(photo, 400, 300, 1000f, 800f)!!
        // 400 x 300 fitted into 1000 x 800: 1000 x 750 at (0, 25), grown by EDGE_PAD on each side.
        val pad = com.hereliesaz.graffitixr.common.scene.ArtSheet.EDGE_PAD
        assertEquals(-pad, sheet.shift.x, 1e-3f)
        assertEquals(25f - pad, sheet.shift.y, 1e-3f)
        val profile = out.first { it.id == "flat" }.surface!!
        assertEquals(1000f + 2 * pad, profile.length, 0.01f)
        assertEquals(750f + 2 * pad, profile.reach!!, 0.01f)
        assertEquals(photo.offset - sheet.shift, out.first { it.id == "photo" }.offset)
        assertTrue(profile.isFlat)
    }

    @Test fun theFlatSurfaceFollowsThePhotosOwnScaleAndOffset() {
        val moved = photo.copy(scale = 0.5f, offset = androidx.compose.ui.geometry.Offset(40f, -10f))
        val sheet = ReliefBuild.flatSheet(moved, 400, 300, 1000f, 800f)!!
        // The same rectangle ReliefBuild.plan gives (290..790 x 202.5..577.5), with the pad.
        assertEquals(290f, sheet.shift.x, 0.5f)
        assertEquals(202.5f, sheet.shift.y, 0.5f)
        assertEquals(500f, sheet.profile.length, 0.5f)
        assertEquals(375f, sheet.profile.reach!!, 0.5f)
    }

    @Test fun noFlatSurfaceIsMadeForAnUnusableCanvasOrBitmap() {
        assertNull(ReliefBuild.flatSheet(photo, 400, 300, 0f, 800f))
        assertNull(ReliefBuild.flatSheet(photo, 0, 0, 1000f, 800f))
    }

    @Test fun theOriginalIsPaintOnTheFlatSurfaceAndTheSkinOnTheRelief() {
        val out = built(listOf(photo))
        assertEquals("flat", SurfaceMath.ownerOf(out, "photo")!!.group.id)
        assertTrue(SurfaceMath.ownerOf(out, "photo")!!.profile.isFlat)
        assertEquals("relief", SurfaceMath.ownerOf(out, "skin")!!.group.id)
    }

    @Test fun theHiddenOriginalIsNotDrawnAndShowingItsGroupDrawsIt() {
        val out = built(listOf(photo))
        val byId = out.associateBy { it.id }
        assertFalse(SurfaceVisibility.drawn(byId, byId.getValue("photo")))
        assertFalse(SurfaceVisibility.drawn(byId, byId.getValue("flat")))
        assertTrue(SurfaceVisibility.drawn(byId, byId.getValue("skin")))
        val shown = out.map { if (it.id == "flat") it.copy(isVisible = true) else it }.associateBy { it.id }
        assertTrue(SurfaceVisibility.drawn(shown, shown.getValue("photo")))
    }

    @Test fun aHiddenFlatSurfaceIsNotADrawnGroupAndShowingItMakesItOne() {
        // Only the flat group and the original: hidden, nothing in the scene can be hit or hidden by it.
        val flatOnly = built(listOf(photo)).filter { it.id == "flat" || it.id == "photo" }
        val cam = SceneCamera()
        assertTrue(SurfaceVisibility.of(flatOnly, cam, 1000f, 800f).isEmpty)
        val shown = flatOnly.map { if (it.id == "flat") it.copy(isVisible = true) else it }
        assertFalse(SurfaceVisibility.of(shown, cam, 1000f, 800f).isEmpty)
    }

    @Test fun theNewGroupsTakeThePhotosPlaceInItsFolder() {
        val folder = Layer("folder", "Folder", type = LayerType.GROUP)
        val out = built(listOf(folder, photo.copy(parentId = "folder")))
        assertEquals("folder", out.first { it.id == "relief" }.parentId)
        assertEquals("folder", out.first { it.id == "flat" }.parentId)
        assertEquals("relief", out.first { it.id == "skin" }.parentId)
        assertEquals("flat", out.first { it.id == "photo" }.parentId)
    }

    @Test fun theSkinIsThenPaintOnTheReliefSurface() {
        val out = built(listOf(photo))
        val owner = SurfaceMath.ownerOf(out, "skin")
        assertNotNull(owner)
        assertEquals("relief", owner!!.group.id)
        assertNotNull(owner.profile.relief)
        assertFalse(owner.profile.isFlat)
    }

    @Test fun aPhotoDerivedGroupIsMeshedFromItsDepthEvenWithViewsDrawn() {
        val out = built(listOf(photo))
        val profile = SurfaceMath.ownerOf(out, "skin")!!.profile
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
        val flat = ReliefBuild.flatSheet(photo, 400, 300, 1000f, 800f)!!
        assertSame(layers, ReliefBuild.assemble(layers, "nope", ids, names, relief, flat, photo))
    }


    @Test fun aPhotoInAMovedFolderIsRefusedButOneInAnUnmovedFolderIsNot() {
        val still = Layer("folder", "Folder", type = LayerType.GROUP)
        assertTrue(plan(listOf(still, photo.copy(parentId = "folder"))) is ReliefPlan.Ok)
        for (moved in listOf(still.copy(scale = 0.5f), still.copy(offset = androidx.compose.ui.geometry.Offset(10f, 0f)), still.copy(rotationZ = 30f))) {
            assertEquals(ReliefRefusal.IN_POSED_FOLDER, refusal(plan(listOf(moved, photo.copy(parentId = "folder")))))
        }
    }

    @Test fun theSkinKeepsNeitherTheComponentNorTheInstanceNorTheLinkOfThePhoto() {
        val main = photo.copy(componentId = "c", isLinked = true, clipToLayerBelow = true, originX = 0)
        val instance = Layer("inst", "Instance", instanceOf = "c")
        val flat = ReliefBuild.flatSheet(main, 400, 300, 1000f, 800f)!!
        // The skin's own pixels differ (originX), as a copy painted on later would.
        val out = ReliefBuild.assemble(listOf(main, instance), "photo", ids, names, relief, flat, main.copy(originX = 99))
        val skin = out.first { it.id == "skin" }
        assertNull(skin.componentId)
        assertNull(skin.instanceOf)
        assertFalse(skin.isLinked)
        assertFalse(skin.clipToLayerBelow)
        val original = out.first { it.id == "photo" }
        assertEquals("c", original.componentId)
        // The instance takes the ORIGINAL's pixels back, not the skin's (which comes first in the list).
        val back = com.hereliesaz.graffitixr.common.model.reinheritPixels(out, "inst").first { it.id == "inst" }
        assertEquals(0, back.originX)
    }

    // --- tuning -------------------------------------------------------------------------------

    @Test fun strengthAndInvertEditTheReliefInPlace() {
        val built = built(listOf(photo))
        val stronger = ReliefBuild.tuned(built, "relief") { it.withStrength(0.5f) }
        assertEquals(0.5f, stronger.first { it.id == "relief" }.surface!!.relief!!.strength, 1e-5f)
        val flipped = ReliefBuild.tuned(stronger, "relief") { it.withInverted(!it.inverted) }
        assertTrue(flipped.first { it.id == "relief" }.surface!!.relief!!.inverted)
        assertEquals(stronger.first { it.id == "skin" }, flipped.first { it.id == "skin" })
    }

    @Test fun tuningALayerWithoutAReliefChangesNothing() {
        val layers = listOf(photo)
        assertSame(layers, ReliefBuild.tuned(layers, "photo") { it.withStrength(1f) })
    }

    @Test fun theActiveLayersReliefIsFoundFromThePhotoOrItsGroup() {
        val built = built(listOf(photo))
        assertEquals("relief", ReliefBuild.reliefGroupOf(built, "skin")?.id)
        assertNull(ReliefBuild.reliefGroupOf(built, "photo"))
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
