package com.hereliesaz.graffitixr.data.brush

import android.content.Context
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A brush the user built in Brush Studio, identified independently of its (renameable) name. */
@Serializable
data class CustomBrush(val id: String, val brush: AzphaltBrush)

/**
 * Stores Brush Studio's brushes as one JSON file each under `filesDir/brushes/`, mirroring how
 * ProjectManager persists projects. Deliberately NOT routed through [AzpInstaller]: that path
 * requires a signed `.azp` zip with a sha256 manifest, which is the right shape for third-party
 * packages and the wrong one for a brush the user just dialled in themselves.
 *
 * As with the extension store, the directory IS the state — [brushes] is refreshed by rescanning it
 * after every mutation, so there's no cache that can disagree with disk.
 */
@Singleton
class CustomBrushRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _brushes = MutableStateFlow<List<CustomBrush>>(emptyList())
    val brushes: StateFlow<List<CustomBrush>> = _brushes.asStateFlow()

    private val dir: File get() = File(context.filesDir, "brushes").apply { mkdirs() }

    /**
     * Serializes refresh/save/delete against each other. Without this, two mutations dispatched
     * from different coroutines (both routed through Dispatchers.IO, a genuinely parallel pool)
     * could each call refresh() and race: an earlier save's directory scan can still be in flight
     * when a later, unrelated delete's own refresh() finishes, and the earlier scan then overwrites
     * `_brushes.value` with a snapshot that still includes the just-deleted brush -- a last-writer-
     * wins loss identical in shape to the async-publisher races fixed elsewhere in this app (#244,
     * #249), just for this repository's own directory-is-the-state cache.
     */
    private val lock = Any()

    init {
        refresh()
    }

    /** Rescans the brush directory. A file that fails to parse is skipped, not fatal. */
    fun refresh() {
        synchronized(lock) {
            val loaded = dir.listFiles { f -> f.isFile && f.extension == "json" }
                ?.mapNotNull { file -> runCatching { json.decodeFromString<CustomBrush>(file.readText()) }.getOrNull() }
                ?.sortedBy { it.brush.name.lowercase() }
                ?: emptyList()
            _brushes.value = loaded
        }
    }

    /**
     * `id` becomes a filename component (`"$id.json"`) with no other containment check, unlike
     * the `.azp` extension pipeline this class's own doc comment says it deliberately bypasses --
     * that pipeline canonical-path-checks every entry it unpacks. Every current caller only ever
     * passes a value that already round-tripped through this same repository's own `id` field
     * (originally `UUID.randomUUID().toString()`), so this is defensive hardening against a
     * future caller, not a fix for a reachable bug today.
     */
    private fun isSafeId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 128 && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    /** Creates or overwrites the brush with [id]. Returns false if [id] is unsafe or the write failed. */
    fun save(id: String, brush: AzphaltBrush): Boolean = synchronized(lock) {
        if (!isSafeId(id)) return@synchronized false
        val target = File(dir, "$id.json")
        val tmp = File(dir, "$id.json.tmp")
        val ok = runCatching {
            // Write-then-rename, not a truncating writeText in place: a process death mid-write
            // used to leave a truncated "$id.json" that refresh()'s runCatching-decode then
            // silently skips forever, permanently dropping the brush with no error surfaced
            // anywhere. The temp file is never left half-written where refresh() would find it.
            tmp.writeText(json.encodeToString(CustomBrush(id, brush.sanitized())))
            tmp.renameTo(target) || run { tmp.copyTo(target, overwrite = true); tmp.delete(); true }
        }.isSuccess
        tmp.delete()
        if (ok) refresh()
        ok
    }

    fun delete(id: String) {
        if (!isSafeId(id)) return
        synchronized(lock) {
            File(dir, "$id.json").delete()
            refresh()
        }
    }

    fun load(id: String): AzphaltBrush? = _brushes.value.firstOrNull { it.id == id }?.brush
}
