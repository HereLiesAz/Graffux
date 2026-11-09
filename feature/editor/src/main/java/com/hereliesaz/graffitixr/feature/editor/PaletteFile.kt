package com.hereliesaz.graffitixr.feature.editor

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * The saved palette as ONE file (`palette.gxpl`), so the board and the brushes are written and read together:
 * `int magic 0x4758504C ("GXPL"), int version 1, int boardLen, board bytes (material sidecar v4), int brushLen,
 * brush bytes (brush file v1), long CRC32 of everything before it`. A truncated or damaged file reads as null.
 */
internal object PaletteFile {
    const val COMBINED_FILE = "palette.gxpl"
    private const val COMBINED_MAGIC = 0x4758504C // "GXPL"
    private const val COMBINED_VERSION = 1

    /** One file holding [board] and [brushes] bytes: magic, version, two length-prefixed parts, a CRC32 of all. */
    fun pack(board: ByteArray, brushes: ByteArray): ByteArray {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).use { out ->
            out.writeInt(COMBINED_MAGIC)
            out.writeInt(COMBINED_VERSION)
            out.writeInt(board.size)
            out.write(board)
            out.writeInt(brushes.size)
            out.write(brushes)
            val crc = java.util.zip.CRC32().also { it.update(raw.toByteArray()) }
            out.writeLong(crc.value)
        }
        return raw.toByteArray()
    }

    /** The (board, brushes) bytes of a [pack]ed file, or null when it is truncated, damaged or not one. */
    fun unpack(bytes: ByteArray): Pair<ByteArray, ByteArray>? = runCatching {
        require(bytes.size >= MIN_PACKED)
        val body = bytes.copyOfRange(0, bytes.size - Long.SIZE_BYTES)
        val stored = DataInputStream(ByteArrayInputStream(bytes, body.size, Long.SIZE_BYTES)).readLong()
        require(java.util.zip.CRC32().also { it.update(body) }.value == stored) { "checksum" }
        DataInputStream(ByteArrayInputStream(body)).use { input ->
            require(input.readInt() == COMBINED_MAGIC && input.readInt() == COMBINED_VERSION) { "not a palette file" }
            val boardLen = input.readInt()
            require(boardLen in 0..body.size)
            val board = ByteArray(boardLen).also { input.readFully(it) }
            val brushLen = input.readInt()
            require(brushLen in 0..body.size)
            val brushes = ByteArray(brushLen).also { input.readFully(it) }
            require(input.read() == -1) { "trailing bytes" }
            board to brushes
        }
    }.getOrNull()

    private const val MIN_PACKED = 4 + 4 + 4 + 4 + Long.SIZE_BYTES
}
