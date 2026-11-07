package com.hereliesaz.graffitixr.ml

import com.hereliesaz.graffitixr.common.azphalt.parseManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** The MoGe-2 host solve: synthetic scenes with a known shift/focal, and hostile inputs. */
class MoGeDepthTest {
    private val w = 80
    private val h = 60
    private val diag = hypot(w.toDouble(), h.toDouble())

    private fun trueDepth(col: Int, row: Int) = 1.5f + 3f * (col.toFloat() / w) + 0.5f * (row % 7) / 7f

    private class Scene(val points: FloatArray, val mask: FloatArray)

    /** Pointmap with an affine shift: z' = d - shift, xy = uv * d / f (the contract in the listing). */
    private fun scene(shift: Float, f: Float, masked: (Int, Int) -> Boolean = { _, _ -> false }): Scene {
        val pts = FloatArray(w * h * 3)
        val mask = FloatArray(w * h) { 1f }
        for (r in 0 until h) for (c in 0 until w) {
            val i = r * w + c
            val d = trueDepth(c, r)
            val ux = ((c - (w - 1) / 2.0) * 2 / diag).toFloat()
            val uy = ((r - (h - 1) / 2.0) * 2 / diag).toFloat()
            pts[i * 3] = ux * d / f
            pts[i * 3 + 1] = uy * d / f
            pts[i * 3 + 2] = d - shift
            if (masked(c, r)) mask[i] = 0f
        }
        return Scene(pts, mask)
    }

    private val f = 1.2f

    @Test fun `known focal recovers the shift`() {
        val s = scene(shift = 0.7f, f = f)
        val sol = MoGeDepth.solve(s.points, s.mask, w, h, focalRelative = f)
        assertEquals(0.7f, sol.shift, 1e-3f)
        assertEquals(f, sol.focalRelative, 0f)
    }

    @Test fun `unknown focal recovers shift and focal jointly`() {
        val s = scene(shift = -0.4f, f = f)
        val sol = MoGeDepth.solve(s.points, s.mask, w, h, focalRelative = null)
        assertEquals(-0.4f, sol.shift, 5e-3f)
        assertEquals(f, sol.focalRelative, 5e-3f)
    }

    @Test fun `zero shift stays zero`() {
        val s = scene(shift = 0f, f = f)
        assertEquals(0f, MoGeDepth.solve(s.points, s.mask, w, h, f).shift, 1e-3f)
    }

    @Test fun `metric depth is the shifted z times scale and inverse depth orders nearer larger`() {
        val s = scene(shift = 0.7f, f = f)
        val sol = MoGeDepth.solve(s.points, s.mask, w, h, f)
        val depth = MoGeDepth.metricDepth(s.points, s.mask, sol, scale = 2f)
        assertEquals(2f * trueDepth(10, 5), depth[5 * w + 10], 5e-3f)
        val map = MoGeDepth.toDepthMap(depth, w, h)
        assertEquals(w, map.width)
        assertEquals(h, map.height)
        assertTrue(map.values.all { it.isFinite() })
        // nearer (smaller true depth) must be larger
        assertTrue(map[0, 0] > map[w - 1, 0])
        assertEquals(1f / (2f * trueDepth(10, 5)), map[10, 5], 1e-3f)
    }

    @Test fun `masked pixels become the far plane and never NaN`() {
        val s = scene(0.3f, f) { c, _ -> c > 60 } // "sky" on the right
        val map = MoGeDepth.estimate(s.points, s.mask, w, h, scale = 1f, focalRelative = f)
        assertTrue(map.values.all { it.isFinite() })
        val farthestValid = (0 until h).minOf { r -> (0..60).minOf { c -> map[c, r] } }
        assertEquals(farthestValid, map[70, 10], 1e-6f)
    }

    @Test fun `masked points are ignored by the solve even when garbage`() {
        val s = scene(0.5f, f) { c, _ -> c > 60 }
        for (r in 0 until h) for (c in 61 until w) {
            val i = (r * w + c) * 3
            s.points[i] = 9e3f; s.points[i + 1] = -9e3f; s.points[i + 2] = 123f
        }
        assertEquals(0.5f, MoGeDepth.solve(s.points, s.mask, w, h, f).shift, 2e-3f)
    }

    @Test fun `non-finite points are skipped and a null mask means all valid`() {
        val s = scene(0.5f, f)
        for (k in 0 until 200) s.points[(k * 23 % (w * h)) * 3 + 2] = Float.NaN
        s.points[7] = Float.POSITIVE_INFINITY
        val sol = MoGeDepth.solve(s.points, null, w, h, f)
        assertEquals(0.5f, sol.shift, 5e-3f)
        val map = MoGeDepth.estimate(s.points, null, w, h, scale = 1f, focalRelative = f)
        assertTrue(map.values.all { it.isFinite() })
    }

    @Test fun `too few valid pixels is refused clearly`() {
        val s = scene(0.5f, f) { c, r -> !(c < 3 && r < 3) }
        assertThrows(DepthEstimationException.UnexpectedOutput::class.java) {
            MoGeDepth.solve(s.points, s.mask, w, h, f)
        }
    }

    @Test fun `all-masked and all-NaN inputs are refused`() {
        assertThrows(DepthEstimationException.UnexpectedOutput::class.java) {
            MoGeDepth.estimate(FloatArray(w * h * 3), FloatArray(w * h), w, h, 1f, f)
        }
        assertThrows(DepthEstimationException.UnexpectedOutput::class.java) {
            MoGeDepth.estimate(FloatArray(w * h * 3) { Float.NaN }, null, w, h, 1f, null)
        }
    }

    @Test fun `a flat plane at constant z solves safely`() {
        val s = scene(0f, f)
        for (i in 0 until w * h) s.points[i * 3 + 2] = 3f
        val map = MoGeDepth.estimate(s.points, s.mask, w, h, scale = 1f, focalRelative = f)
        assertTrue(map.values.all { it.isFinite() && it > 0f })
    }

    @Test fun `xy all zero with a known focal keeps shift zero, unknown focal is refused`() {
        val pts = FloatArray(w * h * 3).also { for (i in 0 until w * h) it[i * 3 + 2] = 2f }
        val map = MoGeDepth.estimate(pts, null, w, h, scale = 1f, focalRelative = f)
        assertTrue(map.values.all { it == 0.5f })
        assertThrows(DepthEstimationException.UnexpectedOutput::class.java) {
            MoGeDepth.estimate(pts, null, w, h, scale = 1f, focalRelative = null)
        }
    }

    @Test fun `a bad scale or focal or size is refused`() {
        val s = scene(0.2f, f)
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(DepthEstimationException.UnexpectedOutput::class.java) {
                MoGeDepth.estimate(s.points, s.mask, w, h, scale = bad, focalRelative = f)
            }
        }
        for (bad in listOf(0f, -1f, Float.NaN)) {
            assertThrows(DepthEstimationException.InvalidInput::class.java) {
                MoGeDepth.solve(s.points, s.mask, w, h, focalRelative = bad)
            }
        }
        assertThrows(DepthEstimationException.UnexpectedOutput::class.java) {
            MoGeDepth.solve(FloatArray(10), null, w, h, f)
        }
    }

    @Test fun `points behind the camera after the shift are far plane not NaN`() {
        val s = scene(0f, f)
        s.points[2] = -5f // one pixel with z far behind
        val map = MoGeDepth.estimate(s.points, s.mask, w, h, scale = 1f, focalRelative = f)
        assertTrue(map.values.all { it.isFinite() })
    }

    @Test fun `tiny image is subsampled without index errors`() {
        val tw = 10
        val th = 8
        val pts = FloatArray(tw * th * 3) { if (it % 3 == 2) 2f else 0.1f * (it % 5) }
        val sol = runCatching { MoGeDepth.solve(pts, null, tw, th, f) }
        assertTrue(sol.isSuccess || sol.exceptionOrNull() is DepthEstimationException.UnexpectedOutput)
    }

    // ---- manifest wiring ----

    private fun mogeAsset() = parseManifest(
        checkNotNull(javaClass.getResourceAsStream("/manifests/moge-2-vits-metric-depth.json"))
            .bufferedReader().readText(),
    ).assets.single()

    @Test fun `the MoGe listing is identified and resolves to a point-map spec`() {
        val s = DepthModelSpecs.fromAsset(mogeAsset())
        val pm = assertNotNull(s.pointMap).let { s.pointMap!! }
        assertEquals("image", s.inputName)
        assertEquals(4, s.rank)
        assertEquals(TensorLayout.NCHW, s.layout)
        assertTrue(s.size is SizePolicy.Dynamic)
        assertEquals(1800, pm.numTokens)
        assertEquals("num_tokens", pm.numTokensName)
        assertEquals("points", pm.pointsName)
        assertEquals("mask", pm.maskName)
        assertEquals("scale", pm.scaleName)
        // [0,1] input: mean 0 std 1 on the unit scale
        assertEquals(1f, s.normalization.apply(0, 1f), 1e-6f)
    }

    @Test fun `other listings have no point map`() {
        for (n in listOf("midas", "depth-anything-v2-small", "depth-anything-v3-small")) {
            val a = parseManifest(
                checkNotNull(javaClass.getResourceAsStream("/manifests/$n.json")).bufferedReader().readText(),
            ).assets.single()
            assertNull(DepthModelSpecs.fromAsset(a).pointMap)
        }
    }

    @Test fun `a MoGe listing without a points output is refused`() {
        val a = mogeAsset()
        val bad = a.copy(io = a.io!!.copy(outputs = a.io!!.outputs!!.filter { it.name != "points" }))
        assertThrows(DepthEstimationException.UnsupportedModel::class.java) { DepthModelSpecs.fromAsset(bad) }
    }

    // ---- pipeline ----

    @Test fun `estimatePointMap runs preprocess, the runner and the solve end to end`() {
        val spec = DepthModelSpecs.fromAsset(mogeAsset())
        val sw = 140
        val sh = 98
        val box = Letterbox.compute(sw, sh, spec.size)
        val bw = box.inputWidth
        val bh = box.inputHeight
        val bdiag = hypot(bw.toDouble(), bh.toDouble())
        val runner = PointMapRunner { _, b, _ ->
            val pts = FloatArray(b.inputWidth * b.inputHeight * 3)
            for (r in 0 until b.inputHeight) for (c in 0 until b.inputWidth) {
                val i = r * b.inputWidth + c
                val d = 2f + c.toFloat() / b.inputWidth
                pts[i * 3] = ((c - (bw - 1) / 2.0) * 2 / bdiag).toFloat() * d / 1.1f
                pts[i * 3 + 1] = ((r - (bh - 1) / 2.0) * 2 / bdiag).toFloat() * d / 1.1f
                pts[i * 3 + 2] = d - 0.6f
            }
            RawPointMap(pts, FloatArray(bw * bh) { 1f }, 1.5f, b.inputWidth, b.inputHeight)
        }
        val map = DepthPipeline.estimatePointMap(IntArray(sw * sh) { -1 }, sw, sh, spec, runner)
        assertEquals(bw, map.width)
        assertEquals(bh, map.height)
        assertTrue(map.values.all { it.isFinite() && it > 0f })
        assertEquals(1f / (1.5f * 2f), map[0, 0], 1e-2f)
    }

    @Test fun `estimatePointMap refuses an extreme aspect ratio and a mismatched output`() {
        val spec = DepthModelSpecs.fromAsset(mogeAsset())
        val ok = PointMapRunner { _, b, _ ->
            RawPointMap(FloatArray(b.inputWidth * b.inputHeight * 3), null, 1f, b.inputWidth, b.inputHeight)
        }
        assertThrows(DepthEstimationException.InvalidInput::class.java) {
            DepthPipeline.estimatePointMap(IntArray(400 * 100), 400, 100, spec, ok)
        }
        val wrong = PointMapRunner { _, _, _ -> RawPointMap(FloatArray(3 * 4 * 4), null, 1f, 4, 4) }
        assertThrows(DepthEstimationException.UnexpectedOutput::class.java) {
            DepthPipeline.estimatePointMap(IntArray(100 * 100), 100, 100, spec, wrong)
        }
    }
}
