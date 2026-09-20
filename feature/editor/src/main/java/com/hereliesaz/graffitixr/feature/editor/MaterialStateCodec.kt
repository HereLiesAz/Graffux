package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.MaterialMixingModel
import com.hereliesaz.graffitixr.common.azphalt.PaintMedium
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs

/**
 * Versioned sparse persistence for canonical material channels.
 *
 * Version 2 keeps the original sparse height/wetness tiles and adds unlit pigment + effective
 * medium ownership for tiles that actually contain material. Android uptime is intentionally not
 * portable state: a monotonic clock epoch ends on reboot/device transfer, so persisted v1 uptime is
 * consumed for stream compatibility and discarded on decode.
 */
internal object MaterialStateCodec {
    data class Snapshot(
        val width: Int,
        val height: Int,
        val heightMap: FloatArray? = null,
        val wetness: FloatArray? = null,
        /** Kept for source compatibility; v2 never serializes this monotonic-session value. */
        val lastWetnessUptimeMillis: Long? = null,
        val tileSize: Int = DEFAULT_TILE_SIZE,
        val rawColor: IntArray? = null,
        val mediumTiles: List<ImpastoMaterialReplayState.TileMediumSnapshot> = emptyList(),
    ) {
        val hasMaterial: Boolean
            get() = heightMap?.any { abs(it) > ZERO_EPSILON } == true ||
                wetness?.any { abs(it) > ZERO_EPSILON } == true ||
                mediumTiles.isNotEmpty()
    }

    private data class TileRecord(val tx: Int, val ty: Int, val flags: Int)

    fun fileNameForLayer(layerId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(layerId.toByteArray(Charsets.UTF_8))
        val hex = buildString(32) {
            for (i in 0 until 16) append((digest[i].toInt() and 0xFF).toString(16).padStart(2, '0'))
        }
        return "material_$hex.bin.gz"
    }

    fun encode(snapshot: Snapshot): ByteArray {
        validateDimensions(snapshot.width, snapshot.height, snapshot.tileSize)
        val size = snapshot.width * snapshot.height
        require(snapshot.heightMap == null || snapshot.heightMap.size == size) {
            "heightMap must contain exactly width*height values"
        }
        require(snapshot.wetness == null || snapshot.wetness.size == size) {
            "wetness must contain exactly width*height values"
        }
        require(snapshot.rawColor == null || snapshot.rawColor.size == size) {
            "rawColor must contain exactly width*height values"
        }

        val columns = (snapshot.width + snapshot.tileSize - 1) / snapshot.tileSize
        val rows = (snapshot.height + snapshot.tileSize - 1) / snapshot.tileSize
        val mediumById = HashMap<Int, ImpastoMaterialReplayState.TileMediumSnapshot>(
            snapshot.mediumTiles.size * 2,
        )
        for (entry in snapshot.mediumTiles) {
            require(entry.tx in 0 until columns && entry.ty in 0 until rows) {
                "Material medium tile coordinate out of range"
            }
            require(entry.weight.isFinite() && entry.weight > 0f) { "Invalid material medium weight" }
            val id = entry.ty * columns + entry.tx
            require(mediumById.put(id, entry) == null) { "Duplicate material medium tile" }
        }

        val records = ArrayList<TileRecord>()
        for (ty in 0 until rows) {
            for (tx in 0 until columns) {
                val left = tx * snapshot.tileSize
                val top = ty * snapshot.tileSize
                val right = minOf(snapshot.width, left + snapshot.tileSize)
                val bottom = minOf(snapshot.height, top + snapshot.tileSize)
                var flags = 0
                if (snapshot.heightMap != null &&
                    tileHasValues(snapshot.heightMap, snapshot.width, left, top, right, bottom)
                ) flags = flags or FLAG_HEIGHT
                if (snapshot.wetness != null &&
                    tileHasValues(snapshot.wetness, snapshot.width, left, top, right, bottom)
                ) flags = flags or FLAG_WETNESS
                if (mediumById.containsKey(ty * columns + tx)) flags = flags or FLAG_MEDIUM
                if (flags != 0 && snapshot.rawColor != null) flags = flags or FLAG_RAW_COLOR
                if (flags != 0) records += TileRecord(tx, ty, flags)
            }
        }

        val raw = ByteArrayOutputStream()
        GZIPOutputStream(raw).use { gzip ->
            DataOutputStream(gzip).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(snapshot.width)
                out.writeInt(snapshot.height)
                out.writeInt(snapshot.tileSize)
                // Never persist Android uptime across process/device epochs.
                out.writeLong(NO_TIME)
                out.writeInt(records.size)
                for (record in records) {
                    out.writeInt(record.tx)
                    out.writeInt(record.ty)
                    out.writeByte(record.flags)
                    val left = record.tx * snapshot.tileSize
                    val top = record.ty * snapshot.tileSize
                    val right = minOf(snapshot.width, left + snapshot.tileSize)
                    val bottom = minOf(snapshot.height, top + snapshot.tileSize)
                    if (record.flags and FLAG_HEIGHT != 0) {
                        writeFloatTile(out, requireNotNull(snapshot.heightMap), snapshot.width, left, top, right, bottom)
                    }
                    if (record.flags and FLAG_WETNESS != 0) {
                        writeFloatTile(out, requireNotNull(snapshot.wetness), snapshot.width, left, top, right, bottom)
                    }
                    if (record.flags and FLAG_RAW_COLOR != 0) {
                        writeIntTile(out, requireNotNull(snapshot.rawColor), snapshot.width, left, top, right, bottom)
                    }
                    if (record.flags and FLAG_MEDIUM != 0) {
                        writeMedium(out, requireNotNull(mediumById[record.ty * columns + record.tx]))
                    }
                }
            }
        }
        return raw.toByteArray()
    }

    fun decode(bytes: ByteArray): Snapshot? = runCatching {
        DataInputStream(GZIPInputStream(ByteArrayInputStream(bytes))).use { input ->
            require(input.readInt() == MAGIC) { "Not a Graffux material-state file" }
            val version = input.readInt()
            require(version in MIN_SUPPORTED_VERSION..VERSION) {
                "Unsupported material-state version $version"
            }
            val width = input.readInt()
            val height = input.readInt()
            val tileSize = input.readInt()
            validateDimensions(width, height, tileSize)
            // v1 persisted Android uptime. Read it to keep the stream aligned, but never reuse it
            // across a new process/device monotonic epoch.
            input.readLong()
            val columns = (width + tileSize - 1) / tileSize
            val rows = (height + tileSize - 1) / tileSize
            val maxTiles = columns * rows
            val tileCount = input.readInt()
            require(tileCount in 0..maxTiles) { "Invalid material tile count $tileCount" }

            var heightMap: FloatArray? = null
            var wetness: FloatArray? = null
            var rawColor: IntArray? = null
            val mediumTiles = ArrayList<ImpastoMaterialReplayState.TileMediumSnapshot>()
            val seen = HashSet<Int>(tileCount * 2)
            repeat(tileCount) {
                val tx = input.readInt()
                val ty = input.readInt()
                require(tx in 0 until columns && ty in 0 until rows) {
                    "Material tile coordinate out of range"
                }
                val tileId = ty * columns + tx
                require(seen.add(tileId)) { "Duplicate material tile" }
                val flags = input.readUnsignedByte()
                val validFlags = if (version >= 2) VALID_FLAGS_V2 else VALID_FLAGS_V1
                require(flags != 0 && flags and validFlags == flags) { "Invalid material tile flags" }
                val left = tx * tileSize
                val top = ty * tileSize
                val right = minOf(width, left + tileSize)
                val bottom = minOf(height, top + tileSize)
                if (flags and FLAG_HEIGHT != 0) {
                    if (heightMap == null) heightMap = FloatArray(width * height)
                    readFloatTile(input, heightMap!!, width, left, top, right, bottom)
                }
                if (flags and FLAG_WETNESS != 0) {
                    if (wetness == null) wetness = FloatArray(width * height)
                    readFloatTile(input, wetness!!, width, left, top, right, bottom)
                }
                if (version >= 2 && flags and FLAG_RAW_COLOR != 0) {
                    if (rawColor == null) rawColor = IntArray(width * height)
                    readIntTile(input, rawColor!!, width, left, top, right, bottom)
                }
                if (version >= 2 && flags and FLAG_MEDIUM != 0) {
                    mediumTiles += readMedium(input, tx, ty)
                }
            }

            Snapshot(
                width = width,
                height = height,
                heightMap = heightMap,
                wetness = wetness,
                lastWetnessUptimeMillis = null,
                tileSize = tileSize,
                rawColor = rawColor,
                mediumTiles = mediumTiles,
            )
        }
    }.getOrNull()

    private fun tileHasValues(
        values: FloatArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Boolean {
        for (y in top until bottom) {
            var index = y * width + left
            for (x in left until right) {
                if (abs(values[index]) > ZERO_EPSILON) return true
                index++
            }
        }
        return false
    }

    private fun writeFloatTile(
        out: DataOutputStream,
        values: FloatArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        for (y in top until bottom) {
            var index = y * width + left
            for (x in left until right) {
                out.writeFloat(values[index].coerceIn(0f, 1f))
                index++
            }
        }
    }

    private fun readFloatTile(
        input: DataInputStream,
        values: FloatArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        for (y in top until bottom) {
            var index = y * width + left
            for (x in left until right) {
                val value = input.readFloat()
                require(value.isFinite()) { "Material channel contains a non-finite value" }
                values[index] = value.coerceIn(0f, 1f)
                index++
            }
        }
    }

    private fun writeIntTile(
        out: DataOutputStream,
        values: IntArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        for (y in top until bottom) {
            var index = y * width + left
            for (x in left until right) {
                out.writeInt(values[index])
                index++
            }
        }
    }

    private fun readIntTile(
        input: DataInputStream,
        values: IntArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        for (y in top until bottom) {
            var index = y * width + left
            for (x in left until right) {
                values[index] = input.readInt()
                index++
            }
        }
    }

    private fun writeMedium(
        out: DataOutputStream,
        entry: ImpastoMaterialReplayState.TileMediumSnapshot,
    ) {
        val m = entry.medium.sanitized()
        out.writeFloat(entry.weight)
        out.writeInt(m.mixingModel.ordinal)
        out.writeFloat(m.viscosity)
        out.writeFloat(m.yieldLikeStrength)
        out.writeFloat(m.dryingRate)
        out.writeFloat(m.pickupRate)
        out.writeFloat(m.depositionRate)
        out.writeFloat(m.heightResponse)
        out.writeFloat(m.substrateResponse)
        out.writeFloat(m.levelingRate)
        out.writeFloat(m.baseRoughness)
        out.writeFloat(m.wetSpecularStrength)
    }

    private fun readMedium(
        input: DataInputStream,
        tx: Int,
        ty: Int,
    ): ImpastoMaterialReplayState.TileMediumSnapshot {
        val weight = input.readFloat()
        require(weight.isFinite() && weight > 0f) { "Invalid material medium weight" }
        val mixingOrdinal = input.readInt()
        val mixing = MaterialMixingModel.entries.getOrNull(mixingOrdinal)
            ?: error("Invalid material mixing model")
        fun next(): Float = input.readFloat().also {
            require(it.isFinite()) { "Material medium contains a non-finite value" }
        }
        val medium = PaintMedium(
            mixingModel = mixing,
            viscosity = next(),
            yieldLikeStrength = next(),
            dryingRate = next(),
            pickupRate = next(),
            depositionRate = next(),
            heightResponse = next(),
            substrateResponse = next(),
            levelingRate = next(),
            baseRoughness = next(),
            wetSpecularStrength = next(),
        ).sanitized()
        return ImpastoMaterialReplayState.TileMediumSnapshot(tx, ty, weight, medium)
    }

    private fun validateDimensions(width: Int, height: Int, tileSize: Int) {
        require(width > 0 && height > 0) { "Material dimensions must be positive" }
        require(tileSize in 1..MAX_TILE_SIZE) { "Invalid material tile size" }
        val pixels = width.toLong() * height.toLong()
        require(pixels in 1..MAX_PIXELS) { "Material dimensions exceed safe limits" }
    }

    private const val MAGIC = 0x47584D54 // "GXMT"
    private const val MIN_SUPPORTED_VERSION = 1
    private const val VERSION = 2
    private const val FLAG_HEIGHT = 1
    private const val FLAG_WETNESS = 2
    private const val FLAG_RAW_COLOR = 4
    private const val FLAG_MEDIUM = 8
    private const val VALID_FLAGS_V1 = FLAG_HEIGHT or FLAG_WETNESS
    private const val VALID_FLAGS_V2 = VALID_FLAGS_V1 or FLAG_RAW_COLOR or FLAG_MEDIUM
    private const val NO_TIME = Long.MIN_VALUE
    private const val DEFAULT_TILE_SIZE = 64
    private const val MAX_TILE_SIZE = 512
    private const val MAX_PIXELS = 16_777_216L // 4096²; above the editor's phone-first raster budget.
    private const val ZERO_EPSILON = 1e-6f
}
