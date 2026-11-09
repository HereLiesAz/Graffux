package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.PaintTransaction
import com.hereliesaz.graffitixr.common.azphalt.PaletteBoard
import com.hereliesaz.graffitixr.common.azphalt.PigmentConcentration
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Saving and restoring the palette board in the project (spec/painterly.md 6.2, 6.5): the board's
 * recipes go through the material sidecar v4 (`FLAG_CONCENTRATION`, with the slot table naming the tubes),
 * and the two brushes' loads in a tiny companion file. Restore **fails closed**: a board whose size or tube
 * slots differ from the current set is not restored (the paint is never reinterpreted as different tubes).
 */
internal object PaletteBoardPersistence {
    // v2: amounts are stored divided by [AMOUNT_SCALE] (the sidecar codec clamps a channel to 0..1, and a texel
    // can hold more than 1 of paint), so the file name changed with the meaning; a v1 board is simply not read.
    const val BOARD_FILE = "palette-board-v2.gxmt"

    /** The most paint a texel is saved with; amounts above it are capped (documented, tested). */
    const val MAX_AMOUNT = 8f
    private const val AMOUNT_SCALE = MAX_AMOUNT
    private const val SUM_TOLERANCE = 1e-3f
    private const val AMOUNT_TOLERANCE = 1e-4f
    const val BRUSH_FILE = "palette-brushes.bin"

    private const val BRUSH_MAGIC = 0x50425253 // "PBRS"
    private const val BRUSH_VERSION = 1
    private const val CH = PaintTransaction.CH

    private const val BYTE_MASK = 0xFF

    private fun byteAt(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and BYTE_MASK

    /**
     * Writes texel [i]'s recipe into [conc] as 255ths summing to 255 (a rounding drift is repaired on its largest
     * channel); false when it has no recipe at all (paint but every channel zero), which is saved as empty. A NaN or
     * infinite channel counts as none (it would make roundToInt() throw and abort every save).
     */
    private fun quantiseTexel(board: PaletteBoard, i: Int, conc: ByteArray): Boolean {
        var sum = 0
        for (c in 0 until CH) {
            val raw = board.surface.conc[i * CH + c].let { if (it.isFinite()) it else 0f }
            val q = (raw * PigmentConcentration.SUM).roundToInt().coerceIn(0, PigmentConcentration.SUM)
            conc[i * CH + c] = q.toByte()
            sum += q
        }
        if (sum == 0) return false
        if (sum != PigmentConcentration.SUM) {
            var big = 0
            for (c in 1 until CH) if (byteAt(conc, i * CH + c) > byteAt(conc, i * CH + big)) big = c
            val fixed = byteAt(conc, i * CH + big) + (PigmentConcentration.SUM - sum)
            conc[i * CH + big] = fixed.coerceIn(0, PigmentConcentration.SUM).toByte()
        }
        return true
    }

    fun snapshot(board: PaletteBoard): MaterialStateCodec.Snapshot {
        val n = board.size
        val amount = FloatArray(n * n) {
            val a = board.surface.amount[it]
            (if (a.isFinite()) a.coerceIn(0f, MAX_AMOUNT) else 0f) / AMOUNT_SCALE
        }
        val conc = ByteArray(n * n * CH)
        for (i in 0 until n * n) {
            if (board.surface.amount[i] > 0f && !quantiseTexel(board, i, conc)) amount[i] = 0f
        }
        return MaterialStateCodec.Snapshot(
            width = n,
            height = n,
            heightMap = amount,
            concentration = conc,
            slotTable = board.tubes.map { MaterialStateCodec.SlotEntry(it.id, it.dataVersion) },
        )
    }

    /** Whether [snap] is this board's data: same size and tubes, and every amount a real, bounded number. */
    fun accepts(board: PaletteBoard, snap: MaterialStateCodec.Snapshot): Boolean {
        val n = board.size
        val slots = board.tubes.map { MaterialStateCodec.SlotEntry(it.id, it.dataVersion) }
        val sameShape = snap.width == n && snap.height == n && snap.slotTable == slots
        val amountsOk = snap.heightMap?.all { it.isFinite() && it >= 0f && it * AMOUNT_SCALE <= MAX_AMOUNT } != false
        val concOk = snap.concentration?.let { it.size == n * n * CH } != false
        return sameShape && amountsOk && concOk
    }

    /**
     * The decoded board when saved bytes ([boardBytes], [brushBytes]) can be used by [board] (same size and tubes,
     * sane amounts, valid brushes), else null. Changes nothing: the brushes are checked on a scratch board.
     */
    fun usable(board: PaletteBoard, boardBytes: ByteArray?, brushBytes: ByteArray?): MaterialStateCodec.Snapshot? {
        val snap = boardBytes?.let { runCatching { MaterialStateCodec.decode(it) }.getOrNull() } ?: return null
        val scratch = PaletteBoard(board.size, board.tubes)
        val brushesOk = brushBytes == null || decodeBrushes(scratch, brushBytes)
        return snap.takeIf { accepts(board, it) && brushesOk }
    }

    /** Applies a [usable] snapshot and the brushes to [board]. */
    fun applySaved(board: PaletteBoard, snap: MaterialStateCodec.Snapshot, brushBytes: ByteArray?) {
        restore(board, snap)
        brushBytes?.let { decodeBrushes(board, it) }
    }

    /** Restores [board] from [snap]; false (board untouched) when it is not this board's data. */
    fun restore(board: PaletteBoard, snap: MaterialStateCodec.Snapshot): Boolean {
        if (!accepts(board, snap)) return false
        val n = board.size
        val conc = snap.concentration
        val amount = snap.heightMap
        // Written straight into the surface, so the board cannot see what changed: a displayed copy redraws it all.
        board.markAllDirty()
        board.surface.conc.fill(0f)
        board.surface.amount.fill(0f)
        if (conc != null && amount != null) {
            for (i in 0 until n * n) {
                board.surface.amount[i] = amount[i] * AMOUNT_SCALE
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
                out.writeFloat(b.amount[0].coerceIn(0f, PaletteBoard.BRUSH_CAPACITY))
                for (c in 0 until CH) out.writeFloat(b.conc[c])
            }
        }
        return raw.toByteArray()
    }

    fun decodeBrushes(board: PaletteBoard, bytes: ByteArray): Boolean = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == BRUSH_MAGIC && input.readInt() == BRUSH_VERSION) { "not a brush file" }
            val hand = input.readInt()
            require(hand in board.brushes.indices) { "hand out of range" }
            val amounts = FloatArray(board.brushes.size)
            val conc = Array(board.brushes.size) { FloatArray(CH) }
            for (i in board.brushes.indices) {
                // a full brush can exceed capacity by a rounding step (the transaction sums rounded floats); accept
                // that, clamp it, and still refuse a genuinely excessive, negative, NaN or infinite amount
                amounts[i] = input.readFloat().also {
                    require(it.isFinite() && it >= 0f && it <= PaletteBoard.BRUSH_CAPACITY + AMOUNT_TOLERANCE) {
                        "amount out of range"
                    }
                }.coerceAtMost(PaletteBoard.BRUSH_CAPACITY)
                var sum = 0f
                for (c in 0 until CH) {
                    conc[i][c] = input.readFloat().also { require(it.isFinite() && it in 0f..1f) { "conc range" } }
                    sum += conc[i][c]
                }
                // a recipe sums to 1; a brush holding nothing may hold nothing
                require(sum <= 1f + SUM_TOLERANCE && (amounts[i] <= 0f || abs(sum - 1f) <= SUM_TOLERANCE)) {
                    "recipe does not sum to 1"
                }
            }
            for (i in board.brushes.indices) {
                board.brushes[i].amount[0] = amounts[i]
                conc[i].copyInto(board.brushes[i].conc)
            }
            board.hand = hand
        }
        true
    }.getOrDefault(false)
}
