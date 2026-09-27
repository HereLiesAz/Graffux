package com.hereliesaz.graffux.desktop

import com.hereliesaz.graffitixr.common.azphalt.ArgbColor
import com.hereliesaz.graffitixr.common.azphalt.BrushColorSource
import com.hereliesaz.graffitixr.common.azphalt.Dab
import com.hereliesaz.graffitixr.common.azphalt.RoundStampCompositor
import com.hereliesaz.graffitixr.common.azphalt.wgpu.WgpuDabs
import com.hereliesaz.graffitixr.common.azphalt.wgpu.WgpuStampEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The desktop canvas's GPU path (wgpu, through JNI) against its CPU path
 * ([RoundStampCompositor.compositeMaxCombined] + [blitSrcOver]) on identical dab lists. Skipped
 * (JUnit assumption) when there is no library or adapter, so it passes on a GPU-less runner; in
 * this repo's container it runs on Mesa (lavapipe Vulkan or llvmpipe GL -- WGPU_BACKEND picks).
 *
 * The two paths are not bit-identical by construction: the CPU quantizes the stroke's alpha to 8
 * bits before a straight-alpha blend that truncates, the GPU blends premultiplied and rounds. The
 * tolerances below are measured, with headroom, not aspirational.
 */
class GpuStrokeParityTest {
    private val w = 160
    private val h = 120

    private fun dabs(seed: Int): List<Dab> {
        val r = Random(seed)
        return List(80) {
            Dab(
                x = r.nextFloat() * w,
                y = r.nextFloat() * h,
                radius = 2f + r.nextFloat() * 20f,
                alpha = 0.2f + r.nextFloat() * 0.8f,
                hardness = r.nextFloat() * 0.95f,
                flowMultiplier = 0.5f + r.nextFloat(),
            )
        }
    }

    private fun cpu(base: IntArray, dabs: List<Dab>, color: Int, flow: Float): IntArray {
        val out = base.copyOf()
        RoundStampCompositor.compositeMaxCombined(dabs, color, color, BrushColorSource.PLAIN, flow)
            ?.let { blitSrcOver(out, w, h, it) }
        return out
    }

    private fun gpu(renderer: GpuStrokeRenderer, base: IntArray, dabs: List<Dab>, color: Int, flow: Float): IntArray? {
        // Grow the stroke over several frames, like a drag, in another colour: a frame that failed
        // to restore the rows the previous one painted would leave that colour behind.
        val partial = listOf(dabs.size / 3, dabs.size / 2, (dabs.size - 5).coerceAtLeast(0))
        val grew = renderer.beginStroke(base, w, h) && partial.all { n ->
            renderer.renderStroke(dabs.take(n), color xor 0x00FFFFFF, color, BrushColorSource.PLAIN, flow) != null
        }
        val frame = if (grew) renderer.renderStroke(dabs, color, color, BrushColorSource.PLAIN, flow) else null
        return frame?.copyOf()
    }

    private fun channels(c: Int) =
        intArrayOf(ArgbColor.alpha(c), ArgbColor.red(c), ArgbColor.green(c), ArgbColor.blue(c))

    /**
     * (max alpha diff, max premultiplied-colour diff, pixels differing at all). Colour is compared
     * premultiplied: the GPU layer stores premultiplied 8-bit, so un-premultiplying a low-alpha
     * pixel magnifies one level of storage rounding by 255/alpha (7 levels at alpha 32) -- a
     * property of the format both Android and this engine use, not a shader difference.
     */
    private fun compare(a: IntArray, b: IntArray): Triple<Int, Int, Int> {
        var maxA = 0
        var maxC = 0
        var n = 0
        for (i in a.indices) {
            if (a[i] == b[i]) continue
            n++
            val x = channels(a[i])
            val y = channels(b[i])
            maxA = maxOf(maxA, abs(x[0] - y[0]))
            for (c in 1..3) maxC = maxOf(maxC, abs(x[c] * x[0] / 255 - y[c] * y[0] / 255))
        }
        return Triple(maxA, maxC, n)
    }

    @Test
    fun gpuMatchesCpuCompositor() {
        val renderer = GpuStrokeRenderer()
        val probe = gpu(renderer, IntArray(w * h), emptyList(), 0, 1f)
        assumeTrue("no wgpu adapter: ${renderer.description}", probe != null)
        println("GpuStrokeParityTest on ${renderer.description}")
        val rng = Random(9)
        val opaque = IntArray(w * h) {
            ArgbColor.argb(255, rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))
        }
        val transparent = IntArray(w * h)
        for ((label, base) in listOf("opaque base" to opaque, "transparent base" to transparent)) {
            for ((color, flow) in listOf(0xFFE04020.toInt() to 1f, 0xB02080C0.toInt() to 0.6f)) {
                val d = dabs(color xor base.size)
                val want = cpu(base, d, color, flow)
                val got = gpu(renderer, base, d, color, flow)!!
                val (maxA, maxC, n) = compare(got, want)
                println(
                    "  $label color=${Integer.toHexString(color)} flow=$flow: $n/${w * h} px differ, " +
                        "alpha max $maxA, premultiplied colour max $maxC",
                )
                assertTrue("$label alpha diff $maxA", maxA <= ALPHA_TOLERANCE)
                assertTrue("$label colour diff $maxC", maxC <= COLOR_TOLERANCE)
            }
        }
        renderer.close()
    }

    /**
     * Resident canvas vs the old whole-canvas upload per stroke: the same strokes, with an undo in
     * between, through one renderer that never passes a content key (so every stroke uploads, the
     * pre-residency path) and one that keeps the canvas resident. Every frame must be identical,
     * pixel for pixel, and the resident renderer must upload only for the first stroke and the one
     * after the undo.
     */
    @Test
    fun residentCanvasMatchesFullUploadAcrossUndo() {
        val old = GpuStrokeRenderer()
        val new = GpuStrokeRenderer()
        assumeTrue("no wgpu adapter: ${old.description}", gpu(old, IntArray(w * h), emptyList(), 0, 1f) != null)
        val rng = Random(21)
        // Semi-transparent base: low-alpha pixels are where straight ARGB and the premultiplied
        // layer can disagree, so this is the base that would expose a stale or unrefreshed copy.
        val base0 = IntArray(w * h) {
            ArgbColor.argb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))
        }
        val history = ArrayList<IntArray>()
        var committed = base0 // the "committed image" object; its identity is the content key
        val strokes = listOf(
            dabs(1).take(30) to (0xFFE04020.toInt() to 1f),
            dabs(2).take(25) to (0xB02080C0.toInt() to 0.6f),
            dabs(3).take(40) to (0xFF10A050.toInt() to 0.9f),
            dabs(4).take(20) to (0x80FFFFFF.toInt() to 1f),
        )
        var next = 0
        for (step in listOf("s", "s", "undo", "s", "s")) {
            if (step == "undo") {
                committed = history.removeAt(history.lastIndex)
                continue
            }
            val (d, paint) = strokes[next++]
            val want = strokeFrames(old, committed, null, d, paint)
            val got = strokeFrames(new, committed, committed, d, paint)
            for (i in want.indices) {
                assertArrayEquals("stroke $next frame $i differs from the full-upload path", want[i], got[i])
            }
            val frame = got.last()
            history.add(committed)
            committed = frame
            old.commitStroke(frame)
            new.commitStroke(frame)
        }
        println(
            "GpuStrokeParityTest resident: ${new.residentHits} hits, ${new.residentUploads} uploads; " +
                "old path uploads every stroke",
        )
        assertTrue("resident hits ${new.residentHits}", new.residentHits == 2)
        assertTrue("resident uploads ${new.residentUploads}", new.residentUploads == 2)
        old.close()
        new.close()
    }

    /** Every frame of one stroke (a drag in three growing steps plus the final frame), copied. */
    private fun strokeFrames(
        renderer: GpuStrokeRenderer,
        base: IntArray,
        key: Any?,
        dabs: List<Dab>,
        paint: Pair<Int, Float>,
    ): List<IntArray> {
        val (color, flow) = paint
        assertTrue(renderer.beginStroke(base, w, h, key))
        return listOf(dabs.size / 3, dabs.size / 2, dabs.size).map { n ->
            renderer.renderStroke(dabs.take(n), color, color, BrushColorSource.PLAIN, flow)!!.copyOf()
        }
    }

    @Test
    fun premultiplyRoundTripsOpaquePixels() {
        val px = IntArray(256) { ArgbColor.argb(255, it, 255 - it, (it * 7) and 0xFF) }
        assertArrayEquals(px, WgpuDabs.straightArgb(WgpuDabs.premultipliedRgba(px)))
    }

    @Test
    fun engineCreationFailsGracefullyForBadSizes() {
        assertTrue(WgpuStampEngine.create(0, 10) == null)
        assertTrue(WgpuStampEngine.create(10, -1) == null)
    }

    private companion object {
        const val ALPHA_TOLERANCE = 2
        const val COLOR_TOLERANCE = 3
    }
}
