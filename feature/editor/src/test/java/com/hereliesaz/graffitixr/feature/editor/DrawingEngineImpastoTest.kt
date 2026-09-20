package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushParameter
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.azphalt.ImpastoMaterialConfig
import com.hereliesaz.graffitixr.common.azphalt.BrushSensor
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorBinding
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [DrawingEngine]'s stamp-brush commit path actually depositing into and shading against a
 * caller-supplied height map (roadmap item 12: Impasto) -- the real integration point, not a
 * re-test of [com.hereliesaz.graffitixr.common.azphalt.ImpastoEngine] itself (covered elsewhere).
 * Commit/replay-only, same scoping as Airbrush (item 13): not reachable from the live incremental
 * preview.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DrawingEngineImpastoTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    private val slamManager: SlamManager = mockk(relaxed = true)
    private val engine = DrawingEngine(slamManager)

    private val w = 40
    private val h = 40

    /** A no-op dynamics binding, present only so the sensor-aware `dynamicDabs` path is taken --
     *  mirrors [AirbrushWiringTest]'s identical need. */
    private val noOpDynamics = BrushSensorBinding(
        BrushSensor.PRESSURE, BrushParameter.OPACITY, outputMin = 1f, outputMax = 1f,
    )

    private fun straightStroke(brush: AzphaltBrush) = StrokeCommand(
        path = List(12) { Offset(10f + it * 1.5f, 20f) },
        canvasSize = IntSize(w, h),
        tool = Tool.BRUSH,
        stampBrush = brush,
        brushSize = 10f,
        brushColor = Color.RED,
        intensity = 1f,
        flow = 1f,
        seed = 5L,
    )

    private fun base(): android.graphics.Bitmap = RenderTestBase.filled(w, h, Color.TRANSPARENT)

    @Test
    fun `a positive thickness rate deposits into the supplied height map`() = runTest {
        val brush = AzphaltBrush(name = "impasto", spacing = 0.2f, hardness = 1f, impastoThicknessRate = 0.5f)
        val heightMap = FloatArray(w * h)

        engine.applySingleStroke(base(), straightStroke(brush), heightMap = heightMap)

        assertTrue("expected some height to have been deposited under the stroke", heightMap.any { it > 0f })
    }

    @Test
    fun `impastoThicknessRate = 0 never touches the supplied height map`() = runTest {
        val brush = AzphaltBrush(name = "flat", spacing = 0.2f, impastoThicknessRate = 0f)
        val heightMap = FloatArray(w * h)

        engine.applySingleStroke(base(), straightStroke(brush), heightMap = heightMap)

        assertTrue(heightMap.all { it == 0f })
    }

    @Test
    fun `a null height map renders identically to impastoThicknessRate = 0`() = runTest {
        val brush = AzphaltBrush(name = "impasto", spacing = 0.2f, impastoThicknessRate = 0.5f)

        val withoutHeightMap = engine.applySingleStroke(base(), straightStroke(brush), heightMap = null)
        val flat = engine.applySingleStroke(
            base(), straightStroke(brush.copy(impastoThicknessRate = 0f)), heightMap = null,
        )

        val a = IntArray(w * h); withoutHeightMap.getPixels(a, 0, w, 0, 0, w, h)
        val b = IntArray(w * h); flat.getPixels(b, 0, w, 0, 0, w, h)
        assertEquals(
            "no shading should be applied when no height map is supplied, even with a positive rate",
            a.toList(), b.toList(),
        )
    }

    @Test
    fun `shading visibly perturbs colour where height was deposited`() = runTest {
        val brush = AzphaltBrush(name = "impasto", spacing = 0.2f, hardness = 1f, impastoThicknessRate = 0.9f)
        val heightMap = FloatArray(w * h)

        val shaded = engine.applySingleStroke(base(), straightStroke(brush), heightMap = heightMap)
        val plain = engine.applySingleStroke(base(), straightStroke(brush.copy(impastoThicknessRate = 0f)))

        val shadedPixels = IntArray(w * h); shaded.getPixels(shadedPixels, 0, w, 0, 0, w, h)
        val plainPixels = IntArray(w * h); plain.getPixels(plainPixels, 0, w, 0, 0, w, h)
        assertTrue(
            "expected Impasto shading to change at least some painted pixels relative to the flat stroke",
            shadedPixels.toList() != plainPixels.toList(),
        )
    }

    @Test
    fun `impasto also deposits height on the sensor-aware (dynamics) dab path`() = runTest {
        val brush = AzphaltBrush(
            name = "impasto-dynamic", spacing = 0.2f, hardness = 1f, impastoThicknessRate = 0.5f,
            dynamics = listOf(noOpDynamics),
        )
        val samples = List(6) { com.hereliesaz.graffitixr.common.azphalt.BrushSample(x = 10f + it * 3f, y = 20f, uptimeMillis = it * 20L, pressure = 1f) }
        val stroke = straightStroke(brush).copy(brushSamples = samples, path = samples.map { Offset(it.x, it.y) })
        val heightMap = FloatArray(w * h)

        engine.applySingleStroke(base(), stroke, heightMap = heightMap)

        assertTrue(heightMap.any { it > 0f })
    }
    @Test
    fun `impasto v2 sequential commit and full replay match pixels height and wetness`() = runTest {
        val brush = AzphaltBrush(
            name = "v2 oil",
            spacing = 0.2f,
            hardness = 1f,
            impastoThicknessRate = 0.7f,
            impastoMaterial = ImpastoMaterialConfig(
                version = 2,
                enabled = true,
                initialLoad = 1f,
                wetness = 0.8f,
                pickupRate = 0.35f,
                levelingRate = 0.6f,
                viscosity = 0.25f,
                yieldLikeStrength = 0.4f,
                baseRoughness = 0.5f,
                wetSpecularStrength = 0.7f,
            ),
        )
        fun timedStroke(y: Float, start: Long, seed: Long): StrokeCommand {
            val points = List(12) { Offset(10f + it * 1.5f, y) }
            val samples = points.mapIndexed { index, p ->
                BrushSample(
                    x = p.x,
                    y = p.y,
                    uptimeMillis = start + index * 20L,
                    pressure = 1f,
                )
            }
            return straightStroke(brush).copy(
                path = points,
                brushSamples = samples,
                seed = seed,
            )
        }

        val first = timedStroke(18f, 1_000L, 31L)
        val second = timedStroke(21f, 1_500L, 32L)

        val commitHeight = FloatArray(w * h)
        val commitWetness = WetnessReplayState.empty(w, h)
        val commitMedium = MaterialMediumReplayState.empty(w, h)
        val afterFirst = engine.applySingleStroke(
            base(), first, heightMap = commitHeight, wetnessState = commitWetness,
            materialMediumState = commitMedium,
        )
        val committed = engine.applySingleStroke(
            afterFirst, second, heightMap = commitHeight, wetnessState = commitWetness,
            materialMediumState = commitMedium,
        )

        val replayHeight = FloatArray(w * h)
        val replayWetness = WetnessReplayState.empty(w, h)
        val replayMedium = MaterialMediumReplayState.empty(w, h)
        val replayed = engine.composite(
            base(), listOf(first, second),
            heightMap = replayHeight,
            wetnessState = replayWetness,
            materialMediumState = replayMedium,
        )

        val committedPixels = IntArray(w * h)
        val replayedPixels = IntArray(w * h)
        committed.getPixels(committedPixels, 0, w, 0, 0, w, h)
        replayed.getPixels(replayedPixels, 0, w, 0, 0, w, h)

        assertArrayEquals(committedPixels, replayedPixels)
        assertArrayEquals(commitHeight, replayHeight, 0f)
        assertArrayEquals(commitWetness.snapshot(), replayWetness.snapshot(), 0f)
        assertArrayEquals(commitMedium.ownerIdSnapshot(), replayMedium.ownerIdSnapshot())
        assertEquals(commitMedium.paletteSnapshot(), replayMedium.paletteSnapshot())
        assertTrue(commitMedium.hasOwners)
        assertTrue(commitHeight.any { it > 0f })
        assertTrue(commitWetness.field.activeTileCount > 0)
    }

    @Test
    fun `impasto v2 disabled config stays on exact v1 height path`() = runTest {
        val v1 = AzphaltBrush(
            name = "v1",
            spacing = 0.2f,
            hardness = 1f,
            impastoThicknessRate = 0.5f,
        )
        val explicitlyDisabledV2 = v1.copy(
            impastoMaterial = ImpastoMaterialConfig(
                version = 2,
                enabled = false,
                wetness = 1f,
                pickupRate = 1f,
                levelingRate = 1f,
                wetSpecularStrength = 1f,
            ),
        )
        val aHeight = FloatArray(w * h)
        val bHeight = FloatArray(w * h)

        val a = engine.applySingleStroke(base(), straightStroke(v1), heightMap = aHeight)
        val b = engine.applySingleStroke(base(), straightStroke(explicitlyDisabledV2), heightMap = bHeight)

        val ap = IntArray(w * h)
        val bp = IntArray(w * h)
        a.getPixels(ap, 0, w, 0, 0, w, h)
        b.getPixels(bp, 0, w, 0, 0, w, h)

        assertArrayEquals(ap, bp)
        assertArrayEquals(aHeight, bHeight, 0f)
    }


    @Test
    fun `existing material keeps its owning medium when a later brush has different physics`() = runTest {
        val firstBrush = AzphaltBrush(
            name = "slow oil",
            spacing = 0.2f,
            hardness = 1f,
            impastoThicknessRate = 0.6f,
            impastoMaterial = ImpastoMaterialConfig(
                version = 2,
                enabled = true,
                wetness = 0.8f,
                viscosity = 0.85f,
                dryingRate = 0.03f,
                levelingRate = 0.2f,
                baseRoughness = 0.65f,
                wetSpecularStrength = 0.3f,
            ),
        )
        val secondBrush = firstBrush.copy(
            name = "fast acrylic",
            impastoMaterial = firstBrush.impastoMaterial.copy(
                viscosity = 0.1f,
                dryingRate = 0.8f,
                levelingRate = 0.9f,
                baseRoughness = 0.2f,
                wetSpecularStrength = 0.9f,
            ),
        )
        val mediumState = MaterialMediumReplayState.empty(w, h)
        val height = FloatArray(w * h)
        val wetness = WetnessReplayState.empty(w, h)

        val first = straightStroke(firstBrush).copy(
            brushSamples = straightStroke(firstBrush).path.mapIndexed { index, p ->
                BrushSample(p.x, p.y, uptimeMillis = 1_000L + index * 20L, pressure = 1f)
            },
        )
        val afterFirst = engine.applySingleStroke(
            base(), first, heightMap = height, wetnessState = wetness,
            materialMediumState = mediumState,
        )
        val owner = requireNotNull(mediumState.mediumAt(15, 15))

        val second = straightStroke(secondBrush).copy(
            path = straightStroke(secondBrush).path.map { Offset(it.x, 22f) },
            brushSamples = straightStroke(secondBrush).path.mapIndexed { index, p ->
                BrushSample(p.x, 22f, uptimeMillis = 2_000L + index * 20L, pressure = 1f)
            },
        )
        engine.applySingleStroke(
            afterFirst, second, heightMap = height, wetnessState = wetness,
            materialMediumState = mediumState,
        )

        assertEquals(firstBrush.impastoMaterial.toMedium(), owner)
        assertEquals(
            "later brushes must not retroactively replace the owning material response",
            owner,
            mediumState.mediumAt(15, 15),
        )
    }

}
