package com.hereliesaz.graffitixr.feature.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialStateCodecTest {
    @Test
    fun roundTripPreservesAllChannelsWithinU16Precision() {
        val height = floatArrayOf(0f, 0.1f, 0.5f, 1f)
        val wet = floatArrayOf(1f, 0.75f, 0.25f, 0f)
        val structure = floatArrayOf(1f, 0.8f, 0.2f, 0f)
        val bytes = MaterialStateCodec.encode(
            MaterialStateSnapshot(
                2, 2, height, wet, structure, lastWetnessUptimeMillis = 123456789L,
            ),
        )

        val restored = MaterialStateCodec.decode(bytes)!!
        assertEquals(2, restored.width)
        assertEquals(2, restored.height)
        assertEquals(123456789L, restored.lastWetnessUptimeMillis)
        val epsilon = 1f / 65535f + 1e-7f
        assertArrayEquals(height, restored.heightMap!!, epsilon)
        assertArrayEquals(wet, restored.wetness!!, epsilon)
        assertArrayEquals(structure, restored.structure!!, epsilon)
    }

    @Test
    fun optionalChannelsStayAbsentInsteadOfAllocatingDryState() {
        val bytes = MaterialStateCodec.encode(
            MaterialStateSnapshot(2, 1, heightMap = floatArrayOf(0.2f, 0.4f)),
        )
        val restored = MaterialStateCodec.decode(bytes)!!
        assertTrue(restored.heightMap != null)
        assertNull(restored.wetness)
        assertNull(restored.structure)
        assertNull(restored.lastWetnessUptimeMillis)
    }

    @Test
    fun corruptOrUnsupportedPayloadIsRejected() {
        assertNull(MaterialStateCodec.decode(byteArrayOf(1, 2, 3)))
        val valid = MaterialStateCodec.encode(
            MaterialStateSnapshot(1, 1, heightMap = floatArrayOf(0.5f)),
        )
        valid[4] = 99
        assertNull(MaterialStateCodec.decode(valid))
    }

    @Test
    fun dimensionsMustMatchPayloadExactly() {
        val valid = MaterialStateCodec.encode(
            MaterialStateSnapshot(2, 2, wetness = FloatArray(4) { 0.5f }),
        )
        assertNull(MaterialStateCodec.decode(valid + byteArrayOf(0)))
    }
}
