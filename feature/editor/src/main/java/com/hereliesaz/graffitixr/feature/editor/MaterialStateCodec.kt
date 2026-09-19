package com.hereliesaz.graffitixr.feature.editor

import kotlin.math.roundToInt

/** Versioned portable snapshot for canonical material channels stored beside each layer PNG. */
internal data class MaterialStateSnapshot(
    val width: Int,
    val height: Int,
    val heightMap: FloatArray? = null,
    val wetness: FloatArray? = null,
    val structure: FloatArray? = null,
    val lastWetnessUptimeMillis: Long? = null,
)

/**
 * Compact gxmat codec.
 *
 * All current canonical channels are normalized 0..1, so unsigned 16-bit fixed point preserves
 * appearance while using half the bytes of raw Float arrays. The project ZIP compresses the
 * artifact again for fux export. Absolute filesystem paths never enter this format.
 */
internal object MaterialStateCodec {
    private const val VERSION = 1
    private const val HEADER_BYTES = 24
    private const val FLAG_HEIGHT = 1
    private const val FLAG_WETNESS = 2
    private const val FLAG_STRUCTURE = 4
    private const val NULL_TIME = Long.MIN_VALUE
    private const val MAX_PIXELS = 100_000_000

    fun encode(snapshot: MaterialStateSnapshot): ByteArray {
        require(snapshot.width > 0 && snapshot.height > 0)
        val countLong = snapshot.width.toLong() * snapshot.height.toLong()
        require(countLong in 1..MAX_PIXELS.toLong())
        val count = countLong.toInt()
        validateSize(snapshot.heightMap, count, "height")
        validateSize(snapshot.wetness, count, "wetness")
        validateSize(snapshot.structure, count, "structure")

        var flags = 0
        if (snapshot.heightMap != null) flags = flags or FLAG_HEIGHT
        if (snapshot.wetness != null) flags = flags or FLAG_WETNESS
        if (snapshot.structure != null) flags = flags or FLAG_STRUCTURE
        val channelCount = Integer.bitCount(flags)
        val bytes = ByteArray(HEADER_BYTES + count * channelCount * 2)
        var p = 0
        bytes[p++] = 'G'.code.toByte()
        bytes[p++] = 'X'.code.toByte()
        bytes[p++] = 'M'.code.toByte()
        bytes[p++] = '5'.code.toByte()
        bytes[p++] = VERSION.toByte()
        bytes[p++] = flags.toByte()
        bytes[p++] = 0
        bytes[p++] = 0
        p = putInt(bytes, p, snapshot.width)
        p = putInt(bytes, p, snapshot.height)
        p = putLong(bytes, p, snapshot.lastWetnessUptimeMillis ?: NULL_TIME)

        fun write(values: FloatArray?) {
            if (values == null) return
            for (value in values) {
                val q = (value.coerceIn(0f, 1f) * 65535f).roundToInt()
                bytes[p++] = (q and 0xFF).toByte()
                bytes[p++] = (q ushr 8 and 0xFF).toByte()
            }
        }
        write(snapshot.heightMap)
        write(snapshot.wetness)
        write(snapshot.structure)
        check(p == bytes.size)
        return bytes
    }

    fun decode(bytes: ByteArray): MaterialStateSnapshot? {
        if (bytes.size < HEADER_BYTES) return null
        if (bytes[0].toInt() != 'G'.code || bytes[1].toInt() != 'X'.code ||
            bytes[2].toInt() != 'M'.code || bytes[3].toInt() != '5'.code
        ) return null
        if ((bytes[4].toInt() and 0xFF) != VERSION) return null
        val flags = bytes[5].toInt() and 0xFF
        if (flags and (FLAG_HEIGHT or FLAG_WETNESS or FLAG_STRUCTURE).inv() != 0) return null
        val width = getInt(bytes, 8)
        val height = getInt(bytes, 12)
        if (width <= 0 || height <= 0) return null
        val countLong = width.toLong() * height.toLong()
        if (countLong !in 1..MAX_PIXELS.toLong()) return null
        val count = countLong.toInt()
        val channelCount = Integer.bitCount(flags)
        val expected = HEADER_BYTES.toLong() + countLong * channelCount * 2L
        if (expected != bytes.size.toLong()) return null
        val storedTime = getLong(bytes, 16)
        var p = HEADER_BYTES

        fun read(enabled: Boolean): FloatArray? {
            if (!enabled) return null
            val out = FloatArray(count)
            for (i in 0 until count) {
                val q = (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8)
                p += 2
                out[i] = q / 65535f
            }
            return out
        }

        val heightMap = read(flags and FLAG_HEIGHT != 0)
        val wetness = read(flags and FLAG_WETNESS != 0)
        val structure = read(flags and FLAG_STRUCTURE != 0)
        return MaterialStateSnapshot(
            width = width,
            height = height,
            heightMap = heightMap,
            wetness = wetness,
            structure = structure,
            lastWetnessUptimeMillis = storedTime.takeUnless { it == NULL_TIME },
        )
    }

    private fun validateSize(values: FloatArray?, expected: Int, label: String) {
        require(values == null || values.size == expected) {
            "$label material channel must contain width*height values"
        }
    }

    private fun putInt(out: ByteArray, offset: Int, value: Int): Int {
        var p = offset
        repeat(4) { shift -> out[p++] = (value ushr (shift * 8)).toByte() }
        return p
    }

    private fun getInt(input: ByteArray, offset: Int): Int {
        var value = 0
        repeat(4) { shift -> value = value or ((input[offset + shift].toInt() and 0xFF) shl (shift * 8)) }
        return value
    }

    private fun putLong(out: ByteArray, offset: Int, value: Long): Int {
        var p = offset
        repeat(8) { shift -> out[p++] = (value ushr (shift * 8)).toByte() }
        return p
    }

    private fun getLong(input: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(8) { shift -> value = value or ((input[offset + shift].toLong() and 0xFFL) shl (shift * 8)) }
        return value
    }
}
