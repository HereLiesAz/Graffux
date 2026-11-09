package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.PaintTransaction
import com.hereliesaz.graffitixr.common.azphalt.PaletteBoard
import com.hereliesaz.graffitixr.common.azphalt.PigmentConcentration
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.roundToInt

/**
 * Saving and restoring the palette board in the project (spec/painterly.md 6.2, 6.5): the board's
 * recipes go through the material sidecar v4 (`FLAG_CONCENTRATION`, with the slot table naming the tubes),
 * and the two brushes' loads in a tiny companion file. Restore **fails closed**: a board whose size or tube
 * slots differ from the current set is not restored (the paint is never reinterpreted as different tubes).
 */
internal object PaletteBoardPersistence {
    const val BOARD_FILE = "palette-board.gxmt"
    const val BRUSH_FILE = "palette-brushes.bin"
    private const val BRUSH_MAGIC = 0x50425253 // "PBRS"
    private const val BRUSH_VERSION = 1
    private const val CH = PaintTransaction.CH

    private const val BYTE_MASK = 0xFF

    private fun byteAt(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and BYTE_MASK

    fun snapshot(board: PaletteBoard): MaterialStateCodec.Snapshot {
        val n = board.size
        val amount = FloatArray(n * n) { board.surface.amount[it].coerceIn(0f, 1f) }
        val conc = ByteArray(n * n * CH)
        for (i in 0 until n * n) {
            if (board.surface.amount[i] <= 0f) continue
            var sum = 0
            for (c in 0 until CH) {
                val q = (board.surface.conc[i * CH + c] * PigmentConcentration.SUM).roundToInt().coerceIn(0,
                    PigmentConcentration.SUM)
                conc[i * CH + c] = q.toByte()
                sum += q
            }
            // a texel must be a recipe (sum 255) or empty; repair a rounding drift on its largest channel
            if (sum != 0 && sum != PigmentConcentration.SUM) {
                var big = 0
                for (c in 1 until CH) if (byteAt(conc, i * CH + c) > byteAt(conc, i * CH + big)) big = c
                val fixed = byteAt(conc, i * CH + big) + (PigmentConcentration.SUM - sum)
                conc[i * CH + big] = fixed.coerceIn(0, PigmentConcentration.SUM).toByte()
            }
        }
        return MaterialStateCodec.Snapshot(
            width = n,
            height = n,
            heightMap = amount,
            concentration = conc,
            slotTable = board.tubes.map { MaterialStateCodec.SlotEntry(it.id, it.dataVersion) },
        )
    }

    /** Restores [board] from [snap]; false (board untouched) when it is not this board's data. */
    fun restore(board: PaletteBoard, snap: MaterialStateCodec.Snapshot): Boolean {
        val slots = board.tubes.map { MaterialStateCodec.SlotEntry(it.id, it.dataVersion) }
        if (snap.width != board.size || snap.height != board.size || snap.slotTable != slots) return false
        val n = board.size
        board.surface.conc.fill(0f)
        board.surface.amount.fill(0f)
        val conc = snap.concentration
        val amount = snap.heightMap
        if (conc != null && amount != null) {
            for (i in 0 until n * n) {
                board.surface.amount[i] = amount[i]
                for (c in 0 until CH) {
                    board.surface.conc[i * CH + c] = byteAt(conc, i * CH + c) / PigmentConcentration.SUM.toFloat()
                }
            }
        }
        return true
    }

    fun encodeBrushes(board: PaletteBoard): ByteArray {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).use { out ->
            out.writeInt(BRUSH_MAGIC)
            out.writeInt(BRUSH_VERSION)
            out.writeInt(board.hand)
            for (b in board.brushes) {
                out.writeFloat(b.amount[0])
                for (c in 0 until CH) out.writeFloat(b.conc[c])
            }
        }
        return raw.toByteArray()
    }

    fun decodeBrushes(board: PaletteBoard, bytes: ByteArray): Boolean = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == BRUSH_MAGIC && input.readInt() == BRUSH_VERSION) { "not a brush file" }
            val hand = input.readInt()
            val amounts = FloatArray(board.brushes.size)
            val conc = Array(board.brushes.size) { FloatArray(CH) }
            for (i in board.brushes.indices) {
                amounts[i] = input.readFloat().also { require(it.isFinite() && it >= 0f) }
                for (c in 0 until CH) conc[i][c] = input.readFloat().also { require(it.isFinite() && it >= 0f) }
            }
            for (i in board.brushes.indices) {
                board.brushes[i].amount[0] = amounts[i].coerceAtMost(PaletteBoard.BRUSH_CAPACITY)
                conc[i].copyInto(board.brushes[i].conc)
            }
            board.hand = hand
        }
        true
    }.getOrDefault(false)
}
