package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class IncrementalRoundStampCompositorTest {
    @Test
    fun chunkedAppendMatchesCanonicalMaxCombine() {
        val dabs = listOf(
            Dab(x = 20f, y = 20f, radius = 12f, alpha = 0.8f, hardness = 0.25f),
            Dab(x = 27f, y = 20f, radius = 12f, alpha = 0.7f, hardness = 0.25f),
            Dab(x = 35f, y = 23f, radius = 10f, alpha = 0.9f, hardness = 0.5f),
        )
        val incremental = IncrementalRoundStampCompositor(64, 64, tileSize = 64)
        incremental.append(
            dabs.take(1), 0xFF3366CC.toInt(), 0xFF000000.toInt(), BrushColorSource.PLAIN, 0.9f,
        )
        val finalTiles = incremental.append(
            dabs.drop(1), 0xFF3366CC.toInt(), 0xFF000000.toInt(), BrushColorSource.PLAIN, 0.9f,
        )
        assertTrue(finalTiles.isNotEmpty())

        val incrementalPixels = finalTiles.single().pixels
        val canonical = RoundStampCompositor.compositeMaxCombinedForRegion(
            dabs,
            DirtyRegion(0, 0, 64, 64),
            0xFF3366CC.toInt(),
            0xFF000000.toInt(),
            BrushColorSource.PLAIN,
            0.9f,
        ) ?: error("canonical compositor produced no pixels")
        assertContentEquals(canonical.pixels, incrementalPixels)
    }

    @Test
    fun weakerLaterDabDoesNotDirtyTile() {
        val incremental = IncrementalRoundStampCompositor(64, 64, tileSize = 64)
        val strong = Dab(x = 20f, y = 20f, radius = 10f, alpha = 1f, hardness = 1f)
        val weak = strong.copy(alpha = 0.2f)
        val first = incremental.append(
            listOf(strong), 0xFFFFFFFF.toInt(), 0, BrushColorSource.PLAIN, 1f,
        )
        val second = incremental.append(
            listOf(weak), 0xFFFFFFFF.toInt(), 0, BrushColorSource.PLAIN, 1f,
        )
        assertTrue(first.isNotEmpty())
        assertTrue(second.isEmpty())
    }
}
