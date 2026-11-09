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
        if (format != FORMAT) return "unknown format $format"
        if (canvasWidth !in 1..MAX_CANVAS || canvasHeight !in 1..MAX_CANVAS) return "canvas size out of range"
        if (strokes.size > MAX_STROKES) return "more than $MAX_STROKES strokes"
        var samples = 0L
        for ((i, s) in strokes.withIndex()) {
            samples += s.samples.size
            if (samples > MAX_SAMPLES) return "more than $MAX_SAMPLES samples"
            if (s.samples.isEmpty()) return "stroke $i has no samples"
            if (!(s.diameterPx > 0f) || !s.diameterPx.isFinite()) return "stroke $i has a bad brush size"
            if (s.samples.any { !it.x.isFinite() || !it.y.isFinite() }) return "stroke $i has a non-finite sample"
            if (s.samples.zipWithNext().any { (a, b) -> b.uptimeMillis < a.uptimeMillis }) return "stroke $i goes back in time"
        }
        return null
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
