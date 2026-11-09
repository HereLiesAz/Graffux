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
        /** Row-major material tile ids for which [rawColor] contains canonical values. */
        val rawColorTiles: Set<Int> = emptySet(),
        val mediumTiles: List<ImpastoMaterialReplayState.TileMediumSnapshot> = emptyList(),
        /**
         * Palette-board recipes (spec/painterly.md 6.2 item 4): [CONCENTRATION_CHANNELS] bytes per texel
         * (12 board tubes then 4 basis), each texel's bytes summing to 255, row-major. Null when absent.
         */
        val concentration: ByteArray? = null,
        /** Canvas basis latent: [LATENT_CHANNELS] concentration bytes per texel, allocated per touched tile. */
        val latent: ByteArray? = null,
        /** Board slot table: slot index -> pigment id and its data version at the time of writing. */
        val slotTable: List<SlotEntry> = emptyList(),
    ) {
        val hasMaterial: Boolean
            get() = heightMap?.any { abs(it) > ZERO_EPSILON } == true ||
                wetness?.any { abs(it) > ZERO_EPSILON } == true ||
                mediumTiles.isNotEmpty() ||
                concentration?.any { it.toInt() != 0 } == true ||
                latent?.any { it.toInt() != 0 } == true
    }

    data class SlotEntry(val pigmentId: String, val dataVersion: Int)

    private data class TileRecord(val tx: Int, val ty: Int, val flags: Int)

    /** A tile's pixel rectangle, `[left, right) x [top, bottom)`. */
    private class TileBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

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
                val box = TileBox(left, top, right, bottom)
                flags = flags or byteFlags(snapshot, box)
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
                writeSlotTable(out, snapshot.slotTable)
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
                        writeFloatTile(out, requireNotNull(snapshot.heightMap), snapshot.width, left, top, right,
                            bottom)
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
                    writeByteSections(out, snapshot, record.flags, TileBox(left, top, right, bottom))
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
            val slotTable = readSlotTable(input, version)
            val columns = (width + tileSize - 1) / tileSize
            val rows = (height + tileSize - 1) / tileSize
            val maxTiles = columns * rows
            val tileCount = input.readInt()
            require(tileCount in 0..maxTiles) { "Invalid material tile count $tileCount" }

            var heightMap: FloatArray? = null
            var wetness: FloatArray? = null
            var rawColor: IntArray? = null
            var concentration: ByteArray? = null
            var latent: ByteArray? = null
            val rawColorTiles = LinkedHashSet<Int>()
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
                val validFlags = validFlagsFor(version)
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
                    rawColorTiles += tileId
                }
                if (version >= 2 && flags and FLAG_MEDIUM != 0) {
                    mediumTiles += readMedium(input, tx, ty, version)
                }
                val box = TileBox(left, top, right, bottom)
                concentration = readBytesIf(input, flags, FLAG_CONCENTRATION, concentration, CONCENTRATION_CHANNELS,
                    width * height, width, box)
                latent = readBytesIf(input, flags, FLAG_LATENT, latent, LATENT_CHANNELS, width * height, width, box)
            }

            Snapshot(
                width = width,
                height = height,
                heightMap = heightMap,
                wetness = wetness,
                lastWetnessUptimeMillis = null,
                tileSize = tileSize,
                rawColor = rawColor,
                rawColorTiles = rawColorTiles,
                mediumTiles = mediumTiles,
                concentration = concentration,
                latent = latent,
                slotTable = slotTable,
            )
        }
    }.getOrNull()

    private fun validFlagsFor(version: Int): Int = when {
        version >= VERSION_BOARD -> VALID_FLAGS_V4
        version >= 2 -> VALID_FLAGS_V2
        else -> VALID_FLAGS_V1
    }

    private fun writeSlotTable(out: DataOutputStream, slots: List<SlotEntry>) {
        out.writeInt(slots.size)
        for (slot in slots) {
            out.writeUTF(slot.pigmentId)
            out.writeInt(slot.dataVersion)
        }
    }

    private fun readSlotTable(input: DataInputStream, version: Int): List<SlotEntry> {
        if (version < VERSION_BOARD) return emptyList()
        val count = input.readInt()
        require(count in 0..MAX_SLOTS) { "Invalid board slot count $count" }
        return List(count) {
            val id = input.readUTF()
            require(id.length <= MAX_ID) { "Slot id too long" }
            SlotEntry(id, input.readInt())
        }
    }

    /** The tile flags the board and latent byte fields earn for [box]. */
    private fun byteFlags(snapshot: Snapshot, box: TileBox): Int {
        var flags = 0
        val conc = snapshot.concentration
        if (conc != null && tileHasBytes(conc, CONCENTRATION_CHANNELS, snapshot.width,
            box)) flags = flags or FLAG_CONCENTRATION
        val latent = snapshot.latent
        if (latent != null && tileHasBytes(latent, LATENT_CHANNELS, snapshot.width, box)) flags = flags or FLAG_LATENT
        return flags
    }

    private fun tileHasBytes(values: ByteArray, channels: Int, width: Int, box: TileBox): Boolean {
        for (y in box.top until box.bottom) {
            val from = (y * width + box.left) * channels
            val to = (y * width + box.right) * channels
            for (i in from until to) if (values[i].toInt() != 0) return true
        }
        return false
    }

    private fun writeByteSections(out: DataOutputStream, snapshot: Snapshot, flags: Int, box: TileBox) {
        val conc = snapshot.concentration
        if (flags and FLAG_CONCENTRATION != 0 && conc != null) writeByteTile(out, conc, CONCENTRATION_CHANNELS,
            snapshot.width, box)
        val latent = snapshot.latent
        if (flags and FLAG_LATENT != 0 && latent != null) writeByteTile(out, latent, LATENT_CHANNELS, snapshot.width,
            box)
    }

    private fun writeByteTile(out: DataOutputStream, values: ByteArray, channels: Int, width: Int, box: TileBox) {
        for (y in box.top until box.bottom) {
            out.write(values, (y * width + box.left) * channels, (box.right - box.left) * channels)
        }
    }

    /** Reads this tile's bytes into [existing] (allocated on first use) when [flags] has [flag]. */
    @Suppress("LongParameterList")
    private fun readBytesIf(
        input: DataInputStream,
        flags: Int,
        flag: Int,
        existing: ByteArray?,
        channels: Int,
        pixels: Int,
        width: Int,
        box: TileBox,
    ): ByteArray? {
        if (flags and flag == 0) return existing
        val values = existing ?: ByteArray(pixels * channels)
        for (y in box.top until box.bottom) {
            val from = (y * width + box.left) * channels
            input.readFully(values, from, (box.right - box.left) * channels)
            requireRecipes(values, from, box.right - box.left, channels)
        }
        return values
    }

    /** Every non-empty texel must be a recipe summing to 255, else the file is refused. */
    private fun requireRecipes(values: ByteArray, from: Int, texels: Int, channels: Int) {
        for (x in 0 until texels) {
            var sum = 0
            for (c in 0 until channels) sum += values[from + x * channels + c].toInt() and 0xFF
            require(sum == 0 || sum == CONCENTRATION_SUM) { "Concentration texel does not sum to $CONCENTRATION_SUM" }
        }
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
                // NaN survives coerceIn and the reader rejects it, which would drop the whole
                // layer's material on reload; persist it as empty instead.
                out.writeFloat(finiteUnit(values[index]))
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
        out.writeInt(mixingModelId(m.mixingModel))
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
        out.writeFloat(m.sheen)
    }

    private fun readMedium(
        input: DataInputStream,
        tx: Int,
        ty: Int,
        version: Int,
    ): ImpastoMaterialReplayState.TileMediumSnapshot {
        val weight = input.readFloat()
        require(weight.isFinite() && weight > 0f) { "Invalid material medium weight" }
        val mixing = mixingModelFor(input.readInt()) ?: error("Invalid material mixing model")
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
            // Sheen arrived with version 3; an older sidecar has none (0 = unchanged presentation).
            sheen = if (version >= 3) next() else 0f,
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
    private const val VERSION = 4
    private const val FLAG_HEIGHT = 1
    private const val FLAG_WETNESS = 2
    private const val FLAG_RAW_COLOR = 4
    private const val FLAG_MEDIUM = 8
    private const val VALID_FLAGS_V1 = FLAG_HEIGHT or FLAG_WETNESS
    private const val VALID_FLAGS_V2 = VALID_FLAGS_V1 or FLAG_RAW_COLOR or FLAG_MEDIUM
    private const val FLAG_CONCENTRATION = 16
    private const val FLAG_LATENT = 32
    private const val VALID_FLAGS_V4 = VALID_FLAGS_V2 or FLAG_CONCENTRATION or FLAG_LATENT
    const val CONCENTRATION_CHANNELS = 16
    const val LATENT_CHANNELS = 4
    private const val CONCENTRATION_SUM = 255
    private const val VERSION_BOARD = 4
    private const val MAX_SLOTS = 64
    private const val MAX_ID = 200
    private const val NO_TIME = Long.MIN_VALUE
    // The wetness field's own tile size: the codec's tiles must line up with the field it stores.
    private const val DEFAULT_TILE_SIZE =
        com.hereliesaz.graffitixr.common.azphalt.PersistentWetnessField.DEFAULT_TILE_SIZE

    private fun finiteUnit(value: Float): Float = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)

    /**
     * The on-disk id of a mixing model. Explicit rather than the enum's ordinal, so reordering or
     * inserting a [MaterialMixingModel] can never remap files already written (these values are
     * the ordinals every existing file was written with).
     */
    private fun mixingModelId(model: MaterialMixingModel): Int = when (model) {
        MaterialMixingModel.LEGACY_RGB -> 0
        MaterialMixingModel.PIGMENT_RYB -> 1
    }

    private fun mixingModelFor(id: Int): MaterialMixingModel? = when (id) {
        0 -> MaterialMixingModel.LEGACY_RGB
        1 -> MaterialMixingModel.PIGMENT_RYB
        else -> null
    }
    private const val MAX_TILE_SIZE = 512
    private const val MAX_PIXELS = 16_777_216L // 4096²; above the editor's phone-first raster budget.
    private const val ZERO_EPSILON = 1e-6f
}
