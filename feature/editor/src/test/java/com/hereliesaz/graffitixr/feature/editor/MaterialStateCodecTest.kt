package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.MaterialMixingModel
import com.hereliesaz.graffitixr.common.azphalt.PaintMedium

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialStateCodecTest {
    @Test
    fun `round trip preserves sparse channels and medium but resets session uptime`() {
        val width = 70
        val height = 66
        val size = width * height
        val heights = FloatArray(size)
        val wetness = FloatArray(size)
        heights[1 * width + 2] = 0.75f
        heights[65 * width + 69] = 0.4f
        wetness[3 * width + 4] = 0.6f
        wetness[64 * width + 68] = 1f

        val medium = PaintMedium(
            mixingModel = MaterialMixingModel.PIGMENT_RYB,
            viscosity = 0.7f,
            yieldLikeStrength = 0.4f,
            dryingRate = 0.12f,
            pickupRate = 0.3f,
            depositionRate = 0.8f,
            heightResponse = 0.9f,
            substrateResponse = 0.5f,
            levelingRate = 0.6f,
            baseRoughness = 0.45f,
            wetSpecularStrength = 0.75f,
        )
        val decoded = MaterialStateCodec.decode(
            MaterialStateCodec.encode(
                MaterialStateCodec.Snapshot(
                    width = width,
                    height = height,
                    heightMap = heights,
                    wetness = wetness,
                    lastWetnessUptimeMillis = 12345L,
                    medium = medium,
                    tileSize = 64,
                ),
            ),
        )

        requireNotNull(decoded)
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        assertEquals(64, decoded.tileSize)
        assertNull("monotonic uptime must not survive save/load", decoded.lastWetnessUptimeMillis)
        assertEquals(medium, decoded.medium)
        assertArrayEquals(heights, decoded.heightMap, 0f)
        assertArrayEquals(wetness, decoded.wetness, 0f)
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
