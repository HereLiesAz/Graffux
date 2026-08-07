package com.hereliesaz.graffitixr.common.model

/**
 * How often, and how recently, each tool has been picked up.
 *
 * Keyed by [Tool.name] rather than by rail item id: a tool is the thing the user chose, and it stays
 * the same tool however they reached it — the rail strip, a nested rail, the quick menu, or the
 * shortcuts sheet itself. Keying off the rail would count the same choice under different names
 * depending on which control happened to be nearest.
 *
 * [Tool.NONE] is never recorded. It is the absence of a tool, and a "most used" list whose top entry
 * is "no tool" would be both true and useless.
 */
data class ToolUsage(
    /** Times each tool has been picked. */
    val counts: Map<String, Int> = emptyMap(),
    /** When each tool was last picked, epoch millis. */
    val lastUsedMs: Map<String, Long> = emptyMap(),
) {
    /** This usage with [tool] recorded once more, at [nowMs]. [Tool.NONE] is ignored. */
    fun recording(tool: Tool, nowMs: Long): ToolUsage {
        if (tool == Tool.NONE) return this
        val key = tool.name
        return copy(
            counts = counts + (key to (counts[key] ?: 0) + 1),
            lastUsedMs = lastUsedMs + (key to nowMs),
        )
    }

    companion object {
        val EMPTY = ToolUsage()
    }
}

/**
 * The three shortcut lists the sheet offers, derived from [ToolUsage].
 *
 * Pure and separate from both the store and the UI, because the ordering rules are the whole feature
 * and are the one part of it worth pinning down in tests: a "recent" list that is not actually in
 * recency order, or a "frequent" list that reshuffles every time two tools tie, is a shortcut strip
 * the user cannot build a habit against.
 */
object ToolRanking {

    /** Tools most recently picked, newest first. */
    fun mostRecent(usage: ToolUsage, limit: Int): List<Tool> =
        usage.lastUsedMs.entries
            .sortedByDescending { it.value }
            .mapNotNull { toolOrNull(it.key) }
            .take(limit.coerceAtLeast(0))

    /**
     * Tools picked most often, commonest first.
     *
     * Ties break on recency, then on the enum's own order — never on map iteration order, which is
     * what would otherwise make two equally-used tools swap places between launches and turn a strip
     * the user is learning the shape of into one they have to read every time.
     */
    fun mostFrequent(usage: ToolUsage, limit: Int): List<Tool> =
        usage.counts.entries
            .mapNotNull { (key, count) -> toolOrNull(key)?.let { Triple(it, count, usage.lastUsedMs[key] ?: 0L) } }
            .sortedWith(
                compareByDescending<Triple<Tool, Int, Long>> { it.second }
                    .thenByDescending { it.third }
                    .thenBy { it.first.ordinal },
            )
            .map { it.first }
            .take(limit.coerceAtLeast(0))

    /**
     * The user's own picks, in the order they chose them, minus anything that no longer names a tool.
     *
     * Their order is kept rather than sorted: a favourites strip is a thing you arrange, and
     * re-sorting it would undo the arranging.
     */
    fun favorites(ids: List<String>, limit: Int): List<Tool> =
        ids.mapNotNull { toolOrNull(it) }.distinct().take(limit.coerceAtLeast(0))

    /**
     * A tool name that survived a rename or a deletion is not a tool. Returning null rather than
     * throwing is what lets a stored list outlive an edit to the enum — `Tool.CROP` was removed
     * during this audit, and any device that had used it would otherwise crash reading its own
     * preferences.
     */
    private fun toolOrNull(name: String): Tool? = Tool.entries.firstOrNull { it.name == name }
}

/**
 * [ToolUsage] as one string, for a preferences store that holds strings.
 *
 * `NAME:count:lastMs`, semicolon-separated. Deliberately not JSON: this is written on every tool
 * selection, it is never read by anything but its own decoder, and a hand-rolled line is one fewer
 * serializer to keep in step with a data class that will change. Any malformed record is skipped
 * rather than failing the whole decode — a corrupt preference should cost the user their shortcut
 * ordering, not their ability to open the app.
 */
object ToolUsageCodec {

    fun encode(usage: ToolUsage): String =
        usage.counts.entries.joinToString(";") { (key, count) ->
            "$key:$count:${usage.lastUsedMs[key] ?: 0L}"
        }

    fun decode(raw: String?): ToolUsage {
        if (raw.isNullOrBlank()) return ToolUsage.EMPTY
        val counts = mutableMapOf<String, Int>()
        val last = mutableMapOf<String, Long>()
        for (record in raw.split(';')) {
            if (record.isBlank()) continue
            val parts = record.split(':')
            if (parts.size != 3) continue
            val name = parts[0]
            val count = parts[1].toIntOrNull() ?: continue
            val ms = parts[2].toLongOrNull() ?: continue
            if (name.isEmpty() || count <= 0) continue
            counts[name] = count
            last[name] = ms
        }
        return ToolUsage(counts, last)
    }
}
