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
            sheen = 0.35f,
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
        assertEquals(0.35f, decoded.mediumTiles.single().medium.sheen, 0f)
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

    @Test
    fun `a NaN in a stored channel is persisted as empty instead of losing the layer's material`() {
        val width = 8
        val height = 8
        val heights = FloatArray(width * height)
        heights[0] = 0.5f
        heights[1] = Float.NaN
        val decoded = MaterialStateCodec.decode(
            MaterialStateCodec.encode(MaterialStateCodec.Snapshot(width = width, height = height, heightMap = heights)),
        )
        requireNotNull(decoded) { "one NaN made the whole sidecar undecodable" }
        assertEquals(0.5f, decoded.heightMap!![0], 0f)
        assertEquals(0f, decoded.heightMap!![1], 0f)
    }

    private fun recipeBytes(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(width * height * 16)
        // texel (3,2): 200 of tube 0 and 55 of tube 5; texel (9,9): all basis channel 14
        fun put(x: Int, y: Int, vararg c: Pair<Int, Int>) { for ((ch,
            v) in c) bytes[(y * width + x) * 16 + ch] = v.toByte() }
        put(3, 2, 0 to 200, 5 to 55)
        put(9, 9, 14 to 255)
        return bytes
    }

    @Test
    fun `v4 round trip preserves board recipes the latent field and the slot table`() {
        val width = 70
        val height = 66
        val conc = recipeBytes(width, height)
        val latent = ByteArray(width * height * 4)
        latent[(65 * width + 69) * 4 + 1] = 255.toByte()
        val slots = listOf(MaterialStateCodec.SlotEntry("cadmium-red", 2), MaterialStateCodec.SlotEntry("ultramarine",
            1))
        val decoded = requireNotNull(
            MaterialStateCodec.decode(
                MaterialStateCodec.encode(
                    MaterialStateCodec.Snapshot(
                        width = width, height = height, tileSize = 64,
                        concentration = conc, latent = latent, slotTable = slots,
                    ),
                ),
            ),
        )
        assertArrayEquals(conc, requireNotNull(decoded.concentration))
        assertArrayEquals(latent, requireNotNull(decoded.latent))
        assertEquals(slots, decoded.slotTable)
        assertTrue(decoded.hasMaterial)
    }

    @Test
    fun `a texel that is not a recipe is refused and the layer fails closed`() {
        val width = 8
        val height = 8
        val bad = ByteArray(width * height * 16)
        bad[0] = 100 // sums to 100, not 255
        val bytes = MaterialStateCodec.encode(
            MaterialStateCodec.Snapshot(width = width, height = height, tileSize = 8, concentration = bad),
        )
        assertNull(MaterialStateCodec.decode(bytes))
    }

    @Test
    fun `a canvas layer with no board still round trips with an empty slot table`() {
        val decoded = requireNotNull(
            MaterialStateCodec.decode(
                MaterialStateCodec.encode(
                    MaterialStateCodec.Snapshot(width = 8, height = 8, tileSize = 8,
                        heightMap = FloatArray(64).also { it[3] = 0.5f }),
                ),
            ),
        )
        assertTrue(decoded.slotTable.isEmpty())
        assertNull(decoded.concentration)
        assertNull(decoded.latent)
    }

    @Test
    fun `a version 3 file without the slot table still reads`() {
        val magic = java.io.DataInputStream(
            java.util.zip.GZIPInputStream(
                java.io.ByteArrayInputStream(
                    MaterialStateCodec.encode(MaterialStateCodec.Snapshot(width = 8, height = 8, tileSize = 8,
                        heightMap = FloatArray(64).also { it[1] = 1f })),
                ),
            ),
        ).use { it.readInt() }
        val raw = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(raw).use { gz ->
            java.io.DataOutputStream(gz).use { out ->
                out.writeInt(magic); out.writeInt(3)
                out.writeInt(8); out.writeInt(8); out.writeInt(8)
                out.writeLong(0L)
                out.writeInt(1) // one tile, no slot table in v3
                out.writeInt(0); out.writeInt(0); out.writeByte(1) // height flag
                repeat(64) { out.writeFloat(if (it == 5) 0.25f else 0f) }
            }
        }
        val decoded = requireNotNull(MaterialStateCodec.decode(raw.toByteArray()))
        assertEquals(0.25f, requireNotNull(decoded.heightMap)[5], 0f)
        assertTrue(decoded.slotTable.isEmpty())
        assertNull(decoded.concentration)
    }
}
