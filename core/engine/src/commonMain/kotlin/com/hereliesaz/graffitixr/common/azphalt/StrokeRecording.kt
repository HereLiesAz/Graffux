package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.Serializable

/**
 * Persistent stamp-stroke capture (spec/painterly.md phase P7b). A stamp stroke used to exist only as the
 * in-memory `StrokeCommand` in the undo history; this is its serialisable form: everything the dab
 * generator needs to reproduce the stroke exactly, so a demo can be replayed after reopening or sharing.
 *
 * The brush is **embedded** (the full [AzphaltBrush] snapshot, as `StrokeCommand` does), so a recording
 * still replays if the pack it came from is later removed or changed; [brushRef] only says where it came from.
 */
@Serializable
data class StampStrokeRecord(
    val brush: AzphaltBrush,
    /** `pack id/brush name` for display and provenance; never used to look the brush up. */
    val brushRef: String = "",
    /** The stroke seed: the same seed and samples give the same dabs. */
    val seed: Long,
    val diameterPx: Float,
    val colorArgb: Int,
    val secondaryArgb: Int = 0xFF000000.toInt(),
    val samples: List<BrushSample>,
    /** The wet film the layer held at touchdown, for brushes whose program reads it; null otherwise. */
    val priorFilm: RecordedFilm? = null,
    /** The canvas and layer transform the samples were mapped through, so a player can place the stroke the same way. */
    val canvasWidth: Int = 0,
    val canvasHeight: Int = 0,
    val layerScale: Float = 1f,
    val layerOffsetX: Float = 0f,
    val layerOffsetY: Float = 0f,
    val layerRotationZ: Float = 0f,
)

/** A [PaintSnapshot] in serialisable form. */
@Serializable
data class RecordedFilm(val keys: List<Long>, val h: List<Float>, val t: List<Long>) {
    fun toSnapshot(): PaintSnapshot = PaintSnapshot(keys.toLongArray(), h.toFloatArray(), t.toLongArray())

    companion object {
        fun of(s: PaintSnapshot): RecordedFilm = RecordedFilm(s.keys.toList(), s.h.toList(), s.t.toList())
    }
}

/** A shareable list of recorded strokes (`azphalt-strokes/1`): play it back through a pack to show the work. */
@Serializable
data class StrokeRecording(
    val format: String = FORMAT,
    val name: String = "",
    val canvasWidth: Int,
    val canvasHeight: Int,
    val substrateId: String = "smooth",
    val strokes: List<StampStrokeRecord>,
) {
    /** Null when valid, else why not. The reader is fail-closed: a recording is never partly accepted. */
    fun validate(): String? {
        headerProblem()?.let { return it }
        if (strokes.size > MAX_STROKES) return "more than $MAX_STROKES strokes"
        var samples = 0L
        for ((i, s) in strokes.withIndex()) {
            samples += s.samples.size
            if (samples > MAX_SAMPLES) return "more than $MAX_SAMPLES samples"
            strokeProblem(i, s)?.let { return it }
        }
        return null
    }

    private fun headerProblem(): String? = when {
        format != FORMAT -> "unknown format $format"
        canvasWidth !in 1..MAX_CANVAS || canvasHeight !in 1..MAX_CANVAS -> "canvas size out of range"
        else -> null
    }

    fun toJson(): String = AzphaltJson.encodeToString(serializer(), this)

    companion object {
        const val FORMAT = "azphalt-strokes/1"
        const val MAX_STROKES = 5_000
        const val MAX_SAMPLES = 400_000L
        const val MAX_CANVAS = 16_384

        /** Parses and validates; null on any problem. */
        fun fromJson(text: String): StrokeRecording? =
            runCatching { AzphaltJson.decodeFromString(serializer(), text) }.getOrNull()?.takeIf { it.validate() == null }

        /** Null when [s] (stroke [i]) is usable, else why not. */
        internal fun strokeProblem(i: Int, s: StampStrokeRecord): String? = when {
            s.samples.isEmpty() -> "stroke $i has no samples"
            !(s.diameterPx > 0f) || !s.diameterPx.isFinite() -> "stroke $i has a bad brush size"
            s.samples.any { !it.x.isFinite() || !it.y.isFinite() } -> "stroke $i has a non-finite sample"
            s.samples.zipWithNext().any { (a, b) -> b.uptimeMillis < a.uptimeMillis } -> "stroke $i goes back in time"
            else -> null
        }

        /** A recording read back with its bad strokes dropped, and how many were. */
        class Repaired(val recording: StrokeRecording, val dropped: Int)

        /**
         * For the project's OWN file, where losing every stroke to one bad one is the wrong trade: the header (format,
         * canvas size) must be sound, or null; then each stroke is decoded and checked alone and only a bad one is
         * dropped, and if the rest exceed the caps the oldest go first. [Repaired.dropped] says how many were lost, so
         * the caller can keep the original file. A shared recording is still read strictly by [fromJson].
         */
        fun fromJsonRepairing(text: String): Repaired? {
            val root = runCatching { AzphaltJson.parseToJsonElement(text) }.getOrNull() as? kotlinx.serialization.json.JsonObject
                ?: return null
            val items = root["strokes"] as? kotlinx.serialization.json.JsonArray ?: return null
            val shell = runCatching {
                AzphaltJson.decodeFromJsonElement(
                    serializer(),
                    kotlinx.serialization.json.JsonObject(root + ("strokes" to kotlinx.serialization.json.JsonArray(emptyList()))),
                )
            }.getOrNull()?.takeIf { it.headerProblem() == null } ?: return null
            val good = ArrayList<StampStrokeRecord>()
            items.forEachIndexed { i, el ->
                val s = runCatching { AzphaltJson.decodeFromJsonElement(StampStrokeRecord.serializer(), el) }.getOrNull()
                if (s != null && strokeProblem(i, s) == null) good += s
            }
            var dropped = items.size - good.size
            var samples = good.sumOf { it.samples.size.toLong() }
            while (good.isNotEmpty() && (good.size > MAX_STROKES || samples > MAX_SAMPLES)) {
                samples -= good.removeAt(0).samples.size
                dropped++
            }
            return Repaired(shell.copy(strokes = good), dropped)
        }
    }
}

/** Replays recorded strokes through the same generator the live stroke and undo replay use. */
object StrokeRecordingPlayer {
    /** The dabs [record] paints: a pure function of the record, identical on every replay. */
    fun dabsOf(record: StampStrokeRecord): List<Dab> =
        LiveStrokeDabs.movementStroke(
            record.samples, record.diameterPx, record.brush, record.seed, record.priorFilm?.toSnapshot(),
        ).dabs

    /** The wet film [record] leaves, to hand to the stroke that follows it. */
    fun filmOf(record: StampStrokeRecord): PaintSnapshot? =
        LiveStrokeDabs.movementStroke(
            record.samples, record.diameterPx, record.brush, record.seed, record.priorFilm?.toSnapshot(),
        ).film

    /** Every stroke's dabs in order, threading each stroke's wet film into the next (as the editor does). */
    fun play(recording: StrokeRecording): List<List<Dab>> {
        var film: PaintSnapshot? = null
        return recording.strokes.map { s ->
            val prior = s.priorFilm?.toSnapshot() ?: film
            val result = LiveStrokeDabs.movementStroke(s.samples, s.diameterPx, s.brush, s.seed, prior)
            film = result.film ?: film
            result.dabs
        }
    }
}
