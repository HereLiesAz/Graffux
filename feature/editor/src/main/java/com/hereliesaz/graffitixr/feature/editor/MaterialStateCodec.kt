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
 * A color-only layer writes no sidecar. When material exists, only tiles containing non-zero
 * height and/or wetness are serialized, and each tile stores only the channels it actually uses.
 * The gzip wrapper supplies integrity checking and makes smooth float fields compact in practice.
 *
 * This format intentionally stores canonical height/wetness, not lighting output. Lighting is
 * presentation state and can be reconstructed from these channels on reload.
 */
internal object MaterialStateCodec {
    data class Snapshot(
        val width: Int,
        val height: Int,
        val heightMap: FloatArray? = null,
        val wetness: FloatArray? = null,
        /**
         * Session-local monotonic time. Kept in the runtime snapshot API but deliberately not
         * serialized: Android uptime is not portable across reboot/device/archive import.
         */
        val lastWetnessUptimeMillis: Long? = null,
        /** Deduplicated spatial material-response palette. Owner id N addresses palette[N - 1]. */
        val mediumPalette: List<PaintMedium> = emptyList(),
        /** 0 = no v2 owner; positive values index [mediumPalette] + 1. */
        val mediumOwnerIds: IntArray? = null,
        val tileSize: Int = DEFAULT_TILE_SIZE,
    ) {
        val hasMaterial: Boolean
            get() = heightMap?.any { abs(it) > ZERO_EPSILON } == true ||
                wetness?.any { abs(it) > ZERO_EPSILON } == true ||
                mediumOwnerIds?.any { it != 0 } == true
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
        require(snapshot.mediumPalette.size <= MAX_MEDIA) { "Too many material media in one layer" }
        require(snapshot.mediumOwnerIds == null || snapshot.mediumOwnerIds.size == size) {
            "mediumOwnerIds must contain exactly width*height values"
        }
        require(snapshot.mediumOwnerIds?.all { it in 0..snapshot.mediumPalette.size } != false) {
            "mediumOwnerIds contains an invalid palette id"
        }

        val columns = (snapshot.width + snapshot.tileSize - 1) / snapshot.tileSize
        val rows = (snapshot.height + snapshot.tileSize - 1) / snapshot.tileSize
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
                if (snapshot.mediumOwnerIds != null &&
                    tileHasOwners(snapshot.mediumOwnerIds, snapshot.width, left, top, right, bottom)
                ) flags = flags or FLAG_MEDIUM
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
                out.writeInt(snapshot.mediumPalette.size)
                snapshot.mediumPalette.forEach { writeMedium(out, it.sanitized()) }
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
                        writeTile(out, requireNotNull(snapshot.heightMap), snapshot.width, left, top, right, bottom)
                    }
                    if (record.flags and FLAG_WETNESS != 0) {
                        writeTile(out, requireNotNull(snapshot.wetness), snapshot.width, left, top, right, bottom)
                    }
                    if (record.flags and FLAG_MEDIUM != 0) {
                        writeOwnerTile(
                            out, requireNotNull(snapshot.mediumOwnerIds),
                            snapshot.width, left, top, right, bottom,
                        )
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
            // v1 persisted Android uptime. Consume it for compatibility but intentionally discard
            // it: uptime resets on reboot and is unrelated across devices/archive imports.
            val mediumPalette = if (version == 1) {
                input.readLong()
                emptyList()
            } else {
                val count = input.readInt()
                require(count in 0..MAX_MEDIA) { "Invalid material medium palette size" }
                List(count) { readMedium(input) }
            }
            val columns = (width + tileSize - 1) / tileSize
            val rows = (height + tileSize - 1) / tileSize
            val maxTiles = columns * rows
            val tileCount = input.readInt()
            require(tileCount in 0..maxTiles) { "Invalid material tile count $tileCount" }

            var heightMap: FloatArray? = null
            var wetness: FloatArray? = null
            var mediumOwnerIds: IntArray? = null
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
                require(flags != 0 && flags and VALID_FLAGS == flags) { "Invalid material tile flags" }
                val left = tx * tileSize
                val top = ty * tileSize
                val right = minOf(width, left + tileSize)
                val bottom = minOf(height, top + tileSize)
                if (flags and FLAG_HEIGHT != 0) {
                    if (heightMap == null) heightMap = FloatArray(width * height)
                    readTile(input, heightMap!!, width, left, top, right, bottom)
                }
                if (flags and FLAG_WETNESS != 0) {
                    if (wetness == null) wetness = FloatArray(width * height)
                    readTile(input, wetness!!, width, left, top, right, bottom)
                }
                if (flags and FLAG_MEDIUM != 0) {
                    if (mediumOwnerIds == null) mediumOwnerIds = IntArray(width * height)
                    readOwnerTile(
                        input, mediumOwnerIds!!, width, left, top, right, bottom,
                        mediumPalette.size,
                    )
                }
            }

            Snapshot(
                width = width,
                height = height,
                heightMap = heightMap,
                wetness = wetness,
                lastWetnessUptimeMillis = null,
                mediumPalette = mediumPalette,
                mediumOwnerIds = mediumOwnerIds,
                tileSize = tileSize,
            )
        }
    }.getOrNull()

    private fun writeMedium(out: DataOutputStream, medium: PaintMedium) {
        out.writeInt(medium.mixingModel.ordinal)
        out.writeFloat(medium.viscosity)
        out.writeFloat(medium.yieldLikeStrength)
        out.writeFloat(medium.dryingRate)
        out.writeFloat(medium.pickupRate)
        out.writeFloat(medium.depositionRate)
        out.writeFloat(medium.heightResponse)
        out.writeFloat(medium.substrateResponse)
        out.writeFloat(medium.levelingRate)
        out.writeFloat(medium.baseRoughness)
        out.writeFloat(medium.wetSpecularStrength)
    }

    private fun readMedium(input: DataInputStream): PaintMedium {
        val modelOrdinal = input.readInt()
        val models = MaterialMixingModel.entries
        require(modelOrdinal in models.indices) { "Invalid material mixing model" }
        fun finiteFloat(): Float = input.readFloat().also {
            require(it.isFinite()) { "Material medium contains a non-finite value" }
        }
        return PaintMedium(
            mixingModel = models[modelOrdinal],
            viscosity = finiteFloat(),
            yieldLikeStrength = finiteFloat(),
            dryingRate = finiteFloat(),
            pickupRate = finiteFloat(),
            depositionRate = finiteFloat(),
            heightResponse = finiteFloat(),
            substrateResponse = finiteFloat(),
            levelingRate = finiteFloat(),
            baseRoughness = finiteFloat(),
            wetSpecularStrength = finiteFloat(),
        ).sanitized()
    }

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

    private fun tileHasOwners(
        values: IntArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Boolean {
        for (y in top until bottom) {
            var index = y * width + left
            for (x in left until right) {
                if (values[index] != 0) return true
                index++
            }
        }
        return false
    }

    private fun writeOwnerTile(
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

    private fun readOwnerTile(
        input: DataInputStream,
        values: IntArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        paletteSize: Int,
    ) {
        for (y in top until bottom) {
            var index = y * width + left
            for (x in left until right) {
                val id = input.readInt()
                require(id in 0..paletteSize) { "Invalid material medium owner id" }
                values[index] = id
                index++
            }
        }
    }

    private fun writeTile(
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

    private fun readTile(
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

    private fun validateDimensions(width: Int, height: Int, tileSize: Int) {
        require(width > 0 && height > 0) { "Material dimensions must be positive" }
        require(tileSize in 1..MAX_TILE_SIZE) { "Invalid material tile size" }
        val pixels = width.toLong() * height.toLong()
        require(pixels in 1..MAX_PIXELS) { "Material dimensions exceed safe limits" }
    }

    private const val MAGIC = 0x47584D54 // "GXMT"
    private const val VERSION = 2
    private const val MIN_SUPPORTED_VERSION = 1
    private const val FLAG_HEIGHT = 1
    private const val FLAG_WETNESS = 2
    private const val FLAG_MEDIUM = 4
    private const val VALID_FLAGS = FLAG_HEIGHT or FLAG_WETNESS or FLAG_MEDIUM
    private const val DEFAULT_TILE_SIZE = 64
    private const val MAX_TILE_SIZE = 512
    private const val MAX_PIXELS = 16_777_216L // 4096²; above the editor's phone-first raster budget.
    private const val MAX_MEDIA = 4096
    private const val ZERO_EPSILON = 1e-6f
}
