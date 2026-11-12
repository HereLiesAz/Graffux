@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The relief surface (spec/3d-space.md, "Relief surfaces"): a photo's depth map as a grid of heights over
 * the photo's plane, built from SYNTHETIC depth maps (a ramp, a sphere bump, a step, noise): real model
 * weights are not needed to pin the geometry.
 */
class ReliefSurfaceTest {
    private val w = 1000f
    private val h = 800f
    private val camera = SceneCamera()
    private val cam = SceneMath.frame(camera, w, h)
    private val identity = listOf(PlanePose())

    // The photo: 400 x 300 scene pixels at (100, 100); an 800 x 600 pixel image.
    private val rect = floatArrayOf(100f, 100f, 500f, 400f)

    private fun map(width: Int, height: Int, f: (Int, Int) -> Float) =
        FloatArray(width * height) { f(it % width, it / width) }

    /** Near (1) on the right, far (0) on the left. */
    private fun ramp(width: Int = 64, height: Int = 48) = map(width, height) { x, _ -> x / (width - 1f) }

    /** A sphere cap: nearest at the centre, far at the rim and beyond. */
    private fun bump(width: Int = 64, height: Int = 48): FloatArray {
        val r = height * 0.4f
        return map(width, height) { x, y ->
            val d = hypot(x - width / 2f + 0.5f, y - height / 2f + 0.5f)
            if (d >= r) 0f else sqrt(1f - (d / r) * (d / r))
        }
    }

    /** Far on the left half, near on the right half. */
    private fun step(width: Int = 64, height: Int = 48) = map(width, height) { x, _ -> if (x < width / 2) 0f else 1f }

    private fun noise(width: Int = 64, height: Int = 48, seed: Long = 7): FloatArray {
        val rng = java.util.Random(seed)
        return FloatArray(width * height) { rng.nextFloat() }
    }

    private val raw = ReliefOptions(smoothingRadius = 0, clipLow = 0f, clipHigh = 1f)

    private fun build(
        depth: FloatArray,
        dw: Int = 64,
        dh: Int = 48,
        options: ReliefOptions = raw,
    ): ReliefSurface = ReliefSurface.build(depth, dw, dh, 800, 600, rect[0], rect[1], rect[2], rect[3], options)!!

    private fun flatRelief() = build(FloatArray(64 * 48) { 0.5f })

    // --- grid resolution policy ---------------------------------------------------------------

    @Test fun gridIsCappedAtTheVertexBudgetAndKeepsTheAspect() {
        val (c, r) = ReliefSurface.gridSize(4000, 3000, 65_536)
        assertTrue("$c x $r", c * r <= 65_536)
        assertTrue("$c x $r fills most of the budget", c * r > 60_000)
        assertEquals(4f / 3f, c.toFloat() / r, 0.03f)
    }

    @Test fun aSmallPhotoGetsNoMoreVerticesThanItHasPixels() {
        assertEquals(20 to 10, ReliefSurface.gridSize(20, 10, 65_536))
    }

    @Test fun aSliverStillGetsATwoByTwoGridAtLeast() {
        val (c, r) = ReliefSurface.gridSize(5000, 1, 65_536)
        assertTrue(c >= 2 && r >= 2)
        assertTrue(c * r <= 65_536)
    }

    @Test fun aTinyBudgetStillMakesAValidGrid() {
        assertEquals(2 to 2, ReliefSurface.gridSize(1000, 1000, 1))
    }

    @Test fun aHugeDepthMapIsBuiltAtTheCappedGrid() {
        val relief = ReliefSurface.build(
            map(1500, 1000) { x, _ -> x / 1500f }, 1500, 1000, 3000, 2000, 0f, 0f, 900f, 600f,
            ReliefOptions(maxVertices = 4096),
        )!!
        assertTrue(relief.cols * relief.rows <= 4096)
        assertTrue(relief.isValid)
    }

    // --- building -----------------------------------------------------------------------------

    @Test fun aRampBuildsMonotonicHeightsAcrossColumns() {
        val relief = build(ramp())
        for (j in 0 until relief.rows) {
            for (i in 1 until relief.cols) assertTrue(relief.height(i, j) >= relief.height(i - 1, j) - 1e-3f)
        }
        assertTrue(relief.height(0, 5) < 0.05f)
        assertTrue(relief.height(relief.cols - 1, 5) > 0.95f)
    }

    @Test fun aNearerSampleSitsCloserToTheViewer() {
        val relief = build(ramp())
        val near = relief.at(rect[2] - 1f, 250f)
        val far = relief.at(rect[0] + 1f, 250f)
        // The scene's z points away from the viewer, so nearer is more negative, symmetric about the plane.
        assertTrue("near z ${near[2]} < far z ${far[2]}", near[2] < far[2])
        assertEquals(-far[2], near[2], relief.displacement * 0.05f)
    }

    @Test fun aBumpPeaksAtTheCentreAndFallsToTheRim() {
        val relief = build(bump())
        val cu = (rect[0] + rect[2]) / 2f
        val ct = (rect[1] + rect[3]) / 2f
        assertTrue(relief.heightAt(cu, ct) > 0.95f)
        assertTrue(relief.heightAt(rect[0] + 2f, rect[1] + 2f) < 0.05f)
        assertEquals(relief.heightAt(cu - 40f, ct), relief.heightAt(cu + 40f, ct), 0.05f)
    }

    @Test fun aStepKeepsItsEdgeWhenSmoothed() {
        val relief = build(step(), options = ReliefOptions(smoothingRadius = 3, clipLow = 0f, clipHigh = 1f))
        val ct = (rect[1] + rect[3]) / 2f
        assertTrue(relief.heightAt(rect[0] + 40f, ct) < 0.05f)
        assertTrue(relief.heightAt(rect[2] - 40f, ct) > 0.95f)
        // The jump stays within a few cells of the middle: edge-preserving smoothing does not blur it into a ramp.
        val cell = (rect[2] - rect[0]) / (relief.cols - 1)
        val mid = (rect[0] + rect[2]) / 2f
        assertTrue(relief.heightAt(mid - 2.5f * cell, ct) < 0.1f)
        assertTrue(relief.heightAt(mid + 2.5f * cell, ct) > 0.9f)
    }

    @Test fun smoothingCalmsNoise() {
        val rough = build(noise())
        val calm = build(noise(), options = ReliefOptions(smoothingRadius = 2, edgeSigma = 0.5f, clipLow = 0f, clipHigh = 1f))
        fun roughness(r: ReliefSurface): Float {
            var sum = 0f
            for (j in 0 until r.rows) for (i in 1 until r.cols) sum += abs(r.height(i, j) - r.height(i - 1, j))
            return sum / (r.rows * (r.cols - 1))
        }
        assertTrue("${roughness(calm)} < ${roughness(rough)}", roughness(calm) < roughness(rough) * 0.6f)
    }

    @Test fun clampingDropsOutliersSoOneBrightPixelDoesNotFlattenTheRest() {
        val depth = ramp()
        depth[10] = 50f
        val clamped = build(depth, options = ReliefOptions(smoothingRadius = 0, clipLow = 0.02f, clipHigh = 0.98f))
        val unclamped = build(depth)
        val mid = (rect[0] + rect[2]) / 2f
        assertEquals(0.5f, clamped.heightAt(mid, 250f), 0.08f)
        assertTrue("an outlier squashes the unclamped ramp", unclamped.heightAt(mid, 250f) < 0.1f)
    }

    @Test fun aFlatDepthIsTheFlatPlane() {
        for (value in listOf(0f, 0.3f, 1f)) {
            val relief = build(FloatArray(64 * 48) { value })
            for (u in listOf(100f, 250f, 500f)) assertEquals(0f, relief.at(u, 200f)[2], 1e-3f)
        }
    }

    @Test fun nonFiniteSamplesNeverReachTheGeometry() {
        val depth = ramp()
        depth[5] = Float.NaN
        depth[100] = Float.POSITIVE_INFINITY
        depth[200] = Float.NEGATIVE_INFINITY
        val relief = build(depth)
        assertTrue(relief.isValid)
        assertTrue(relief.heights.all { it.isFinite() && it in 0f..1f })
        assertTrue(ReliefMath.mesh(relief, 0f, 2000f, 0f, 2000f).all { it.isFinite() })
        val allBad = build(FloatArray(64 * 48) { Float.NaN })
        assertTrue(allBad.isValid)
        assertEquals(0f, allBad.at(300f, 200f)[2], 1e-3f)
    }

    @Test fun degenerateInputsBuildNothing() {
        fun b(depth: FloatArray, dw: Int, dh: Int, pw: Int = 800, ph: Int = 600, r: FloatArray = rect) =
            ReliefSurface.build(depth, dw, dh, pw, ph, r[0], r[1], r[2], r[3], raw)
        assertNull(b(FloatArray(0), 0, 0))
        assertNull(b(FloatArray(10), 4, 4))
        assertNull(b(ramp(), 64, 48, pw = 0))
        assertNull(b(ramp(), 64, 48, ph = -1))
        assertNull(b(ramp(), 64, 48, r = floatArrayOf(100f, 100f, 100f, 400f)))
        assertNull(b(ramp(), 64, 48, r = floatArrayOf(500f, 100f, 100f, 400f)))
        assertNull(b(ramp(), 64, 48, r = floatArrayOf(Float.NaN, 100f, 500f, 400f)))
    }

    @Test fun aOneByOneDepthMapIsAFlatRelief() {
        val relief = ReliefSurface.build(floatArrayOf(0.7f), 1, 1, 800, 600, 100f, 100f, 500f, 400f, raw)!!
        assertEquals(0f, relief.at(300f, 250f)[2], 1e-3f)
    }

    @Test fun invertingFlipsNearAndFarWithoutRebuilding() {
        val relief = build(ramp())
        val flipped = relief.withInverted(true)
        for (u in listOf(110f, 300f, 490f)) assertEquals(1f - relief.heightAt(u, 250f), flipped.heightAt(u, 250f), 1e-4f)
        assertEquals(relief.heightData, flipped.heightData)
        assertEquals(relief, flipped.withInverted(false))
    }

    @Test fun displacementScalesZAndNothingElse() {
        val relief = build(bump())
        val double = relief.withDisplacement(relief.displacement * 2f)
        val a = relief.at(300f, 250f)
        val b = double.at(300f, 250f)
        assertEquals(a[0], b[0], 0f)
        assertEquals(a[1], b[1], 0f)
        assertEquals(a[2] * 2f, b[2], 1e-3f)
    }

    @Test fun displacementIsKeptFinite() {
        val relief = build(bump())
        assertEquals(0f, relief.withDisplacement(Float.NaN).displacement, 0f)
        assertEquals(0f, relief.withDisplacement(-5f).displacement, 0f)
    }

    // --- uv -----------------------------------------------------------------------------------

    @Test fun photoPixelsAndSurfacePointsRoundTrip() {
        val relief = build(bump())
        for ((u, t) in listOf(100f to 100f, 500f to 400f, 300f to 250f, 123.4f to 387.6f)) {
            val (px, py) = relief.photoPixel(u, t)
            val (u2, t2) = relief.unrolled(px, py)
            assertEquals(u, u2, 1e-3f)
            assertEquals(t, t2, 1e-3f)
        }
        assertEquals(0f to 0f, relief.photoPixel(100f, 100f))
        assertEquals(800f to 600f, relief.photoPixel(500f, 400f))
        assertEquals(400f to 300f, relief.photoPixel(300f, 250f))
    }

    // --- mesh ---------------------------------------------------------------------------------

    @Test fun theMeshHasTwoTrianglesPerCellAndUvsInPhotoPixels() {
        val relief = build(bump(), options = raw.copy(maxVertices = 12 * 9))
        val mesh = ReliefMath.photoMesh(relief)
        val cells = (relief.cols - 1) * (relief.rows - 1)
        assertEquals(cells * 2 * 3 * 5, mesh.size)
        var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE; var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        var i = 0
        while (i < mesh.size) {
            minU = minOf(minU, mesh[i + 3]); maxU = maxOf(maxU, mesh[i + 3])
            minV = minOf(minV, mesh[i + 4]); maxV = maxOf(maxV, mesh[i + 4])
            i += 5
        }
        assertEquals(0f, minU, 1e-3f); assertEquals(800f, maxU, 1e-3f)
        assertEquals(0f, minV, 1e-3f); assertEquals(600f, maxV, 1e-3f)
    }

    @Test fun everyMeshVertexSitsOnTheReliefAtItsOwnBasePoint() {
        val relief = build(bump())
        val mesh = ReliefMath.mesh(relief, 0f, 2000f, 0f, 2000f)
        var i = 0
        while (i < mesh.size) {
            val p = relief.at(mesh[i + 3], mesh[i + 4])
            assertEquals(p[0], mesh[i], 1e-3f)
            assertEquals(p[1], mesh[i + 1], 1e-3f)
            assertEquals(p[2], mesh[i + 2], 1e-2f)
            i += 5
        }
    }

    @Test fun theTrianglesTileTheExtentExactly() {
        val relief = build(bump())
        val mesh = ReliefMath.mesh(relief, 0f, 2000f, 0f, 2000f)
        var area = 0f
        var i = 0
        while (i < mesh.size) {
            val ax = mesh[i + 3]; val ay = mesh[i + 4]
            val bx = mesh[i + 8]; val by = mesh[i + 9]
            val cx = mesh[i + 13]; val cy = mesh[i + 14]
            area += abs((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)) / 2f
            i += 15
        }
        assertEquals((rect[2] - rect[0]) * (rect[3] - rect[1]), area, 30f)
    }

    @Test fun aWindowOfTheMeshOnlyHoldsTheCellsItTouches() {
        val relief = build(bump())
        val all = ReliefMath.mesh(relief, 0f, 2000f, 0f, 2000f)
        val some = ReliefMath.mesh(relief, 100f, 300f, 100f, 250f)
        assertTrue(some.isNotEmpty())
        assertTrue(some.size < all.size)
        assertEquals(0, ReliefMath.mesh(relief, 600f, 900f, 0f, 2000f).size)
        assertEquals(0, ReliefMath.mesh(relief, 300f, 300f, 0f, 2000f).size)
    }

    @Test fun aLargePhotoStaysUnderTheVertexCap() {
        val relief = ReliefSurface.build(
            map(256, 256) { x, y -> (x + y) / 512f }, 256, 256, 4000, 3000, 0f, 0f, 1000f, 750f,
        )!!
        assertTrue(relief.cols * relief.rows <= ReliefSurface.MAX_VERTICES)
    }

    @Test fun aStepIsNotSlicedAcrossByTheDiagonals() {
        // A 2-cell-wide strip with a step between its columns: every cell's diagonal runs along the smaller depth
        // difference, so no triangle spans both plateaus with all three corners on different heights.
        val relief = build(step(8, 8), 8, 8, raw.copy(maxVertices = 9))
        assertEquals(3 to 3, relief.cols to relief.rows)
        val mesh = ReliefMath.photoMesh(relief)
        var i = 0
        var tall = 0
        while (i < mesh.size) {
            val zs = listOf(mesh[i + 2], mesh[i + 7], mesh[i + 12])
            if (zs.max() - zs.min() > relief.displacement * 0.6f) tall++
            i += 15
        }
        // Only the triangles that must touch the step do; a bad diagonal choice would add more.
        assertTrue("$tall steep triangles", tall <= 4)
    }

    // --- the ray hit --------------------------------------------------------------------------

    private fun owner(relief: ReliefSurface): SurfaceOwner {
        val group = Layer("g", "G", type = LayerType.GROUP, surface = relief.toProfile())
        return SurfaceMath.ownerOf(listOf(group, Layer("c", "C", parentId = "g")), "c")!!
    }

    private fun hit(relief: ReliefSurface, x: Float, y: Float, frame: CameraFrame = cam) =
        SurfaceMath.hit(x, y, relief.toProfile(), SceneMath.chainModel(identity, w, h), frame)

    @Test fun aFlatReliefIsHitWhereThePlaneIs() {
        val hit = hit(flatRelief(), 300f, 250f)
        assertNotNull(hit)
        assertEquals(300f, hit!!.u, 0.05f)
        assertEquals(250f, hit.t, 0.05f)
        assertEquals(cam.focal / cam.rayDirection(300f, 250f)[2], hit.distance, cam.focal * 0.001f)
    }

    @Test fun aRayAtTheBumpPeakHitsItsTop() {
        val relief = build(bump())
        val hit = hit(relief, 300f, 250f)!!
        val top = relief.at(hit.u, hit.t)
        assertTrue("the top is towards the viewer: z ${top[2]}", top[2] < -relief.displacement * 0.4f)
        // The distance is the eye's to the displaced point (the default view looks straight down z).
        assertEquals((cam.focal + top[2]) / cam.rayDirection(300f, 250f)[2], hit.distance, cam.focal * 0.01f)
    }

    @Test fun theHitPointShowsWhereTheRayWent() {
        val relief = build(bump())
        val model = SceneMath.chainModel(identity, w, h)
        val tilted = SceneMath.frame(SceneCamera(yaw = 0.5f, pitch = -0.25f), w, h)
        var checked = 0
        for ((x, y) in listOf(300f to 250f, 260f to 230f, 340f to 280f, 200f to 160f, 420f to 330f)) {
            val hit = hit(relief, x, y, tilted) ?: continue
            val p = relief.at(hit.u, hit.t)
            val screen = tilted.project(model.apply(p[0], p[1], p[2]))!!
            assertEquals(x, screen.first, 0.6f)
            assertEquals(y, screen.second, 0.6f)
            checked++
        }
        assertTrue("$checked rays landed", checked >= 3)
    }

    @Test fun anObliqueRayHitsARamp() {
        val relief = build(ramp()).withDisplacement(400f)
        val tilted = SceneMath.frame(SceneCamera(yaw = -0.6f), w, h)
        val model = SceneMath.chainModel(identity, w, h)
        val target = relief.at(300f, 250f)
        val (x, y) = tilted.project(model.apply(target[0], target[1], target[2]))!!
        val hit = hit(relief, x, y, tilted)
        assertNotNull(hit)
        val p = relief.at(hit!!.u, hit.t)
        assertEquals(x, tilted.project(model.apply(p[0], p[1], p[2]))!!.first, 0.6f)
    }

    @Test fun aRayPastTheEdgeMissesAndOneOnTheEdgeHits() {
        val relief = flatRelief()
        assertNull(hit(relief, 50f, 250f))
        assertNull(hit(relief, 600f, 250f))
        assertNull(hit(relief, 300f, 50f))
        assertNull(hit(relief, 300f, 450f))
        assertNotNull(hit(relief, 100.2f, 250f))
        assertNotNull(hit(relief, 499.8f, 399.8f))
        assertNotNull(hit(relief, 100f, 100f))
    }

    @Test fun aSurfaceBehindTheEyeIsNotHit() {
        val behind = SceneMath.chainModel(listOf(PlanePose(positionZ = -5000f)), w, h)
        assertNull(SurfaceMath.hit(300f, 250f, flatRelief().toProfile(), behind, cam))
    }

    @Test fun anEdgeOnViewHitsNothingAndDoesNotHang() {
        // The eye in the photo's plane: the ray runs along it. It may graze it or miss it, but it must return.
        val edgeOn = SceneMath.frame(SceneCamera(pitch = SceneCamera.MAX_PITCH, yaw = 1.5707f), w, h)
        for (x in listOf(100f, 500f, 900f)) hit(build(bump()), x, 400f, edgeOn)
    }

    @Test fun aStepIsHitOnItsNearerPlateauFromTheSide() {
        val relief = build(step()).withDisplacement(300f)
        val tilted = SceneMath.frame(SceneCamera(yaw = 0.7f), w, h)
        val model = SceneMath.chainModel(identity, w, h)
        val hit = hit(relief, 320f, 250f, tilted)
        assertNotNull(hit)
        val p = relief.at(hit!!.u, hit.t)
        assertEquals(320f, tilted.project(model.apply(p[0], p[1], p[2]))!!.first, 0.8f)
    }

    // --- the footprint contract ---------------------------------------------------------------

    @Test fun aFootprintTouchThatHitsMapsExactlyAsTheRayDoes() {
        val owner = owner(build(bump()))
        val exact = SurfaceMath.worldToFlat(300f, 250f, owner, camera, w, h)!!
        val foot = SurfaceFootprint.worldToFlat(300f, 250f, 30f, owner, camera, w, h)!!
        assertEquals(exact.first, foot.first, 1e-3f)
        assertEquals(exact.second, foot.second, 1e-3f)
    }

    @Test fun aFootprintReachingTheReliefMapsToItsNearestEdgePoint() {
        val owner = owner(flatRelief())
        assertNull(SurfaceMath.worldToFlat(90f, 250f, owner, camera, w, h))
        val foot = SurfaceFootprint.worldToFlat(90f, 250f, 30f, owner, camera, w, h)!!
        assertEquals(100f, foot.first, 0.5f)
        assertEquals(250f, foot.second, 0.5f)
    }

    @Test fun aFootprintTouchingNothingIsAMiss() {
        val owner = owner(flatRelief())
        assertNull(SurfaceFootprint.worldToFlat(10f, 700f, 20f, owner, camera, w, h))
        assertNull(SurfaceFootprint.worldToFlat(90f, 250f, 0f, owner, camera, w, h))
    }

    @Test fun paintMapsToPhotoPixelsAndBackToTheSameScreenPoint() {
        val relief = build(bump())
        val owner = owner(relief)
        for ((x, y) in listOf(300f to 250f, 180f to 140f, 440f to 360f)) {
            val flat = SurfaceMath.worldToFlat(x, y, owner, camera, w, h)!!
            val (px, py) = relief.photoPixel(flat.first, flat.second)
            assertTrue(px in 0f..800f && py in 0f..600f)
            val back = SurfaceMath.flatToWorld(flat.first, flat.second, owner, camera, w, h)!!
            assertEquals(x, back.first, 0.6f)
            assertEquals(y, back.second, 0.6f)
        }
    }

    // --- the profile it lives in --------------------------------------------------------------

    @Test fun aReliefProfileIsAValidSurfaceThatIsNotFlat() {
        val profile = build(bump()).toProfile()
        assertTrue(profile.isValid)
        assertFalse(profile.isFlat)
        assertFalse(profile.hasHull)
        assertNotNull(profile.relief)
        assertTrue(profile.length >= rect[2])
    }

    @Test fun aReliefWithBrokenDataMakesAnInvalidProfile() {
        val relief = build(bump())
        assertFalse(relief.copy(heightData = "!!not base64!!").isValid)
        assertFalse(relief.copy(cols = relief.cols + 1).isValid)
        assertFalse(relief.copy(spanU = 0f).isValid)
        assertFalse(relief.copy(photoWidth = 0).isValid)
        assertFalse(relief.toProfile().copy(relief = relief.copy(heightData = "")).isValid)
    }

    @Test fun aReliefProfileSurvivesTheProjectJson() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val profile = build(bump()).withInverted(true).withDisplacement(123f).toProfile()
        val back = json.decodeFromString(SurfaceProfile.serializer(), json.encodeToString(SurfaceProfile.serializer(), profile))
        assertEquals(profile, back)
        assertEquals(profile.relief!!.heights.toList(), back.relief!!.heights.toList())
        assertTrue(back.relief!!.inverted)
        assertEquals(123f, back.relief!!.displacement, 0f)
    }

    @Test fun aProfileFromBeforeReliefsLoadsUnchanged() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """{"points":[0.0,0.0,0.0,10.0,0.0,0.0],"sweepX":0.0,"sweepY":0.0,"sweepZ":1.0}"""
        val profile = json.decodeFromString(SurfaceProfile.serializer(), old)
        assertNull(profile.relief)
        assertTrue(profile.isValid)
        assertTrue(profile.isFlat)
    }

    @Test fun theStoredHeightsAreSmallEnoughForTheProjectFile() {
        val relief = ReliefSurface.build(
            map(512, 512) { x, y -> (x + y) / 1024f }, 512, 512, 4000, 3000, 0f, 0f, 1000f, 750f,
        )!!
        // 16-bit samples as base64: far less than the same grid as a JSON list of floats.
        assertTrue("${relief.heightData.length} chars", relief.heightData.length <= relief.cols * relief.rows * 2 * 4 / 3 + 8)
    }

    // --- the wire mesh ------------------------------------------------------------------------

    @Test fun aPhotoDepthSurfaceIsMeshedFromTheReliefNotTheDrawnViews() {
        // Precedence: photo depth (relief) > multi-view hull > single-profile sheet.
        fun sq(place: (Float, Float) -> FloatArray) =
            listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f).flatMap { place(it.first, it.second).toList() }
        val relief = build(bump()).toProfile()
        val withViews = relief.copy(
            side = SurfaceProfile(sq { z, y -> floatArrayOf(0f, y, z) }, 1f, 0f, 0f),
            top = SurfaceProfile(sq { x, z -> floatArrayOf(x, 0f, z) }, 0f, 1f, 0f),
        )
        assertTrue(withViews.hasHull)
        val cam = SceneMath.frame(SceneCamera(yaw = 0.4f, pitch = -0.2f), w, h)
        val model = SceneMath.chainModel(identity, w, h)
        fun flat(l: List<FloatArray>) = l.map { it.toList() }
        assertEquals(flat(SurfaceMath.gridLines(relief, model, cam)), flat(SurfaceMath.gridLines(withViews, model, cam)))
        assertEquals(flat(ReliefMath.gridLines(relief.relief!!, model, cam, SurfaceMath.GRID_SPACING)), flat(SurfaceMath.gridLines(withViews, model, cam)))
    }

    @Test fun theWireMeshFollowsTheRelief() {
        val tilted = SceneMath.frame(SceneCamera(yaw = 0.5f, pitch = -0.3f), w, h)
        val model = SceneMath.chainModel(identity, w, h)
        val a = SurfaceMath.gridLines(flatRelief().toProfile(), model, tilted)
        val b = SurfaceMath.gridLines(build(bump()).toProfile(), model, tilted)
        assertTrue(a.isNotEmpty() && b.isNotEmpty())
        assertTrue(a.flatMap { it.toList() } != b.flatMap { it.toList() })
        assertTrue(b.all { l -> l.all { it.isFinite() } })
    }

    @Test fun aReliefSurfaceIsFilledWithItsDisplacedMesh() {
        val tilted = SceneMath.frame(SceneCamera(yaw = 0.5f, pitch = -0.3f), w, h)
        val model = SceneMath.chainModel(identity, w, h)
        val flat = SurfaceMath.fillTriangles(flatRelief().toProfile(), model, tilted)
        val bumpy = SurfaceMath.fillTriangles(build(bump()).toProfile(), model, tilted)
        assertTrue(flat.isNotEmpty() && bumpy.isNotEmpty())
        assertTrue(bumpy.all { t -> t.all { it.isFinite() } })
        assertTrue(flat.flatMap { it.toList() } != bumpy.flatMap { it.toList() })
    }

    @Test fun theWireMeshStopsAtThePhotosEdge() {
        val lines = SurfaceMath.gridLines(build(bump()).toProfile(), SceneMath.chainModel(identity, w, h), cam, spacing = 64f)
        val xs = lines.flatMap { listOf(it[0], it[2]) }
        val ys = lines.flatMap { listOf(it[1], it[3]) }
        // Seen straight on, near-plane displacement magnifies a little, but nothing is drawn far outside the photo.
        assertTrue("${xs.min()}", xs.min() > rect[0] - 80f)
        assertTrue("${xs.max()}", xs.max() < rect[2] + 80f)
        assertTrue(ys.min() > rect[1] - 80f && ys.max() < rect[3] + 80f)
    }
}

class ReliefStrengthTest {
    private fun relief(spanU: Float = 400f, spanT: Float = 300f) = ReliefSurface.build(
        FloatArray(16) { (it % 4) / 3f }, 4, 4, 800, 600, 0f, 0f, spanU, spanT,
    )!!

    @Test fun strengthIsTheDisplacementAsAFractionOfTheLongerSide() {
        val r = relief()
        assertEquals(r.displacement / 400f, r.strength, 1e-6f)
        assertEquals(0.25f, r.strength, 1e-6f)
        assertEquals(0.5f * 400f, r.withStrength(0.5f).displacement, 1e-3f)
        assertEquals(0.5f, r.withStrength(0.5f).strength, 1e-6f)
    }

    @Test fun strengthIsClampedAndKeptFinite() {
        val r = relief()
        assertEquals(0f, r.withStrength(-1f).displacement, 0f)
        assertEquals(0f, r.withStrength(Float.NaN).displacement, 0f)
        assertEquals(ReliefSurface.MAX_STRENGTH, r.withStrength(50f).strength, 1e-6f)
    }

    @Test fun strengthDoesNotTouchTheStoredHeights() {
        val r = relief()
        assertEquals(r.heightData, r.withStrength(0.4f).heightData)
    }
}
