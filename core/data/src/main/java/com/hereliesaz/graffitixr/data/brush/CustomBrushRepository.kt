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
data class CustomBrush(val id: String, val brush: AzphaltBrush, val importedSet: ImportedBrushSetMembership? = null)

/** Stable identity and source order of a member of an imported brush set. */
@Serializable
data class ImportedBrushSetMembership(val id: String, val name: String, val order: Int)

/**
 * Stores Brush Studio's brushes as one JSON file each under `filesDir/brushes/`, with optional
 * immutable PNG sidecars owned by imported brushes, mirroring how
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
                ?.sortedWith(compareBy<CustomBrush>(
                    { it.importedSet != null },
                    { it.importedSet?.name?.lowercase().orEmpty() },
                    { it.importedSet?.id.orEmpty() },
                    { it.importedSet?.order ?: 0 },
                    { it.brush.name.lowercase() },
                    { it.id },
                ))
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

    /** Resolves only fixed, brush-owned asset names, never extension or arbitrary paths. */
    fun assetFile(id: String, path: String?): File? = synchronized(lock) {
        if (!isSafeId(id) || path !in setOf("shape.png", "grain.png")) return@synchronized null
        File(dir, "$id.$path").takeIf { it.isFile }
    }

    /** New imports publish their metadata only after all PNG sidecars exist. A crash can leave
     * invisible orphan files, but cannot overwrite a previously saved brush's assets. */
    fun saveImported(
        id: String,
        brush: AzphaltBrush,
        shape: ByteArray?,
        grain: ByteArray?,
        importedSet: ImportedBrushSetMembership? = null,
    ): Boolean = synchronized(lock) {
        if (importedSet != null && (!isSafeId(importedSet.id) ||
                importedSet.name.isBlank() || importedSet.name.length > 128 || importedSet.order < 0)
        ) return@synchronized false
        if (!isSafeId(id) || File(dir, "$id.json").exists()) return@synchronized false
        val written = mutableListOf<File>()
        val ok = runCatching {
            for ((name, bytes) in listOf("shape.png" to shape, "grain.png" to grain)) {
                if (bytes == null) continue
                require(bytes.isNotEmpty() && bytes.size <= 8 * 1024 * 1024)
                val file = File(dir, "$id.$name")
                require(!file.exists())
                written.add(file)
                file.writeBytes(bytes)
            }
            writeBrush(id, brush.copy(shapePath = shape?.let { "shape.png" }, grainPath = grain?.let { "grain.png" }, tipFormat = null), importedSet)
        }.getOrDefault(false)
        if (!ok) written.forEach { it.delete() }
        ok
    }

    /** Creates or overwrites the brush with [id]. Returns false if [id] is unsafe or the write failed. */
    fun save(id: String, brush: AzphaltBrush): Boolean = synchronized(lock) {
        writeBrush(id, brush, _brushes.value.firstOrNull { it.id == id }?.importedSet)
    }

    /** Publishes brush settings and set membership together. Call only while holding [lock]. */
    private fun writeBrush(id: String, brush: AzphaltBrush, importedSet: ImportedBrushSetMembership?): Boolean = synchronized(lock) {
        if (!isSafeId(id)) return@synchronized false
        if (brush.shapePath != null && (brush.shapePath != "shape.png" || assetFile(id, brush.shapePath) == null)) return@synchronized false
        if (brush.grainPath != null && (brush.grainPath != "grain.png" || assetFile(id, brush.grainPath) == null)) return@synchronized false
        val target = File(dir, "$id.json")
        val tmp = File(dir, "$id.json.tmp")
        val ok = runCatching {
            // Write-then-rename, not a truncating writeText in place: a process death mid-write
            // used to leave a truncated "$id.json" that refresh()'s runCatching-decode then
            // silently skips forever, permanently dropping the brush with no error surfaced
            // anywhere. The temp file is never left half-written where refresh() would find it.
            tmp.writeText(json.encodeToString(CustomBrush(id, brush.sanitized(), importedSet)))
            check(tmp.renameTo(target)) { "Could not publish brush metadata" }
        }.isSuccess
        tmp.delete()
        if (ok) refresh()
        ok
    }

    fun delete(id: String) {
        if (!isSafeId(id)) return
        synchronized(lock) {
            val metadata = File(dir, "$id.json")
            metadata.delete()
            if (!metadata.exists()) {
                assetFile(id, "shape.png")?.delete()
                assetFile(id, "grain.png")?.delete()
            }
            refresh()
        }
    }

    fun load(id: String): AzphaltBrush? = _brushes.value.firstOrNull { it.id == id }?.brush
}
