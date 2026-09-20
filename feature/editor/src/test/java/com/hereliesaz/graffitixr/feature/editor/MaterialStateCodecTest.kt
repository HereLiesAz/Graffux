package com.hereliesaz.graffitixr.feature.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.hereliesaz.graffitixr.common.azphalt.PaintMedium
import org.junit.Test

class MaterialStateCodecTest {
    @Test
    fun `round trip preserves sparse height wetness and resets session uptime`() {
        val width = 70
        val height = 66
        val size = width * height
        val heights = FloatArray(size)
        val wetness = FloatArray(size)
        heights[1 * width + 2] = 0.75f
        heights[65 * width + 69] = 0.4f
        wetness[3 * width + 4] = 0.6f
        wetness[64 * width + 68] = 1f

        val decoded = MaterialStateCodec.decode(
            MaterialStateCodec.encode(
                MaterialStateCodec.Snapshot(
                    width = width,
                    height = height,
                    heightMap = heights,
                    wetness = wetness,
                    lastWetnessUptimeMillis = 12345L,
                    tileSize = 64,
                ),
            ),
        )

        requireNotNull(decoded)
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        assertEquals(64, decoded.tileSize)
        assertNull("Android monotonic uptime must not survive a persisted session", decoded.lastWetnessUptimeMillis)
        assertArrayEquals(heights, decoded.heightMap, 0f)
        assertArrayEquals(wetness, decoded.wetness, 0f)
    }

    @Test
    fun `v2 round trip preserves sparse raw pigment and tile-owned media`() {
        val width = 8
        val height = 4
        val raw = IntArray(width * height) { 0xFF102030.toInt() }
        val heights = FloatArray(width * height)
        heights[1 * width + 1] = 0.5f
        val medium = PaintMedium(
            viscosity = 0.7f,
            yieldLikeStrength = 0.4f,
            dryingRate = 0.3f,
            levelingRate = 0.6f,
            baseRoughness = 0.2f,
            wetSpecularStrength = 0.8f,
        )

        val decoded = requireNotNull(
            MaterialStateCodec.decode(
                MaterialStateCodec.encode(
                    MaterialStateCodec.Snapshot(
                        width = width,
                        height = height,
                        heightMap = heights,
                        tileSize = 4,
                        rawColor = raw,
                        mediumTiles = listOf(
                            ImpastoMaterialReplayState.TileMediumSnapshot(0, 0, 2f, medium),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(decoded.rawColorTiles.contains(0))
        assertEquals(raw[1 * width + 1], requireNotNull(decoded.rawColor)[1 * width + 1])
        assertEquals(1, decoded.mediumTiles.size)
        assertEquals(0.7f, decoded.mediumTiles.single().medium.viscosity, 0f)
        assertEquals(0.8f, decoded.mediumTiles.single().medium.wetSpecularStrength, 0f)
    }

    @Test
    fun `zero channels encode without allocating decoded float arrays`() {
        val decoded = MaterialStateCodec.decode(
            MaterialStateCodec.encode(
                MaterialStateCodec.Snapshot(
                    width = 8,
                    height = 8,
                    heightMap = FloatArray(64),
                    wetness = FloatArray(64),
                ),
            ),
        )

        requireNotNull(decoded)
        assertNull(decoded.heightMap)
        assertNull(decoded.wetness)
        assertTrue(!decoded.hasMaterial)
    }

    @Test
    fun `filename is deterministic safe and does not expose hostile layer ids`() {
        val hostile = "../layer/with spaces?and=stuff"
        val a = MaterialStateCodec.fileNameForLayer(hostile)
        val b = MaterialStateCodec.fileNameForLayer(hostile)

        assertEquals(a, b)
        assertTrue(a.startsWith("material_"))
        assertTrue(a.endsWith(".bin.gz"))
        assertTrue(a.matches(Regex("material_[0-9a-f]{32}\\.bin\\.gz")))
        assertTrue(!a.contains(".."))
        assertTrue(!a.contains('/'))
    }

    @Test
    fun `corrupt unknown or hostile material files fail closed`() {
        assertNull(MaterialStateCodec.decode(byteArrayOf(1, 2, 3, 4)))

        val valid = MaterialStateCodec.encode(
            MaterialStateCodec.Snapshot(width = 4, height = 4, heightMap = FloatArray(16) { 0.2f }),
        )
        val truncated = valid.copyOf(valid.size / 2)
        assertNull(MaterialStateCodec.decode(truncated))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encode rejects arrays that do not match dimensions`() {
        MaterialStateCodec.encode(
            MaterialStateCodec.Snapshot(width = 4, height = 4, heightMap = FloatArray(15)),
        )
    }
}
