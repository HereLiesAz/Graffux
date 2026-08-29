package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [BrushTipMaskCache.tipMask] has two branches: a generated round tip when the brush has no source
 * texture, and [BrushTipMaskCache.scaledSource] when it does. Only the generated branch applied
 * `hardness`'s radial falloff -- the textured branch just scaled the source and ignored the
 * parameter, so every azphalt/stamp brush with a real tip image (the normal case) rendered
 * identically at hardness 0 and hardness 1.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BrushTipMaskCacheTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    @Test
    fun `a textured tip fades near its edge at low hardness but stays solid at high hardness`() {
        val source = RenderTestBase.filled(64, 64, Color.WHITE)

        val soft = BrushTipMaskCache.tipMask(source, 64, 64, hardness = 0f)
        val hard = BrushTipMaskCache.tipMask(source, 64, 64, hardness = 1f)

        // 90% of the way from centre to edge along the horizontal axis.
        val edgeX = (32 + 32 * 0.9f).toInt()
        val softEdgeAlpha = Color.alpha(soft.getPixel(edgeX, 32))
        val hardEdgeAlpha = Color.alpha(hard.getPixel(edgeX, 32))

        assertTrue(
            "hardness=0 should fade well before the tip edge, was alpha=$softEdgeAlpha",
            softEdgeAlpha < 128,
        )
        assertTrue(
            "hardness=1 should stay opaque almost to the tip edge, was alpha=$hardEdgeAlpha",
            hardEdgeAlpha > 200,
        )
    }

    @Test
    fun `a textured tip stays fully opaque at its centre regardless of hardness`() {
        val source = RenderTestBase.filled(64, 64, Color.WHITE)

        val soft = BrushTipMaskCache.tipMask(source, 64, 64, hardness = 0f)
        val hard = BrushTipMaskCache.tipMask(source, 64, 64, hardness = 1f)

        assertTrue(Color.alpha(soft.getPixel(32, 32)) > 200)
        assertTrue(Color.alpha(hard.getPixel(32, 32)) > 200)
    }
}
