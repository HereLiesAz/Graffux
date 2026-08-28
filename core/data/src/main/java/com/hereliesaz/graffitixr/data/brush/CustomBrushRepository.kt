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

    init {
        refresh()
    }

    /** Rescans the brush directory. A file that fails to parse is skipped, not fatal. */
    fun refresh() {
        val loaded = dir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.mapNotNull { file -> runCatching { json.decodeFromString<CustomBrush>(file.readText()) }.getOrNull() }
            ?.sortedBy { it.brush.name.lowercase() }
            ?: emptyList()
        _brushes.value = loaded
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
    fun save(id: String, brush: AzphaltBrush): Boolean {
        if (!isSafeId(id)) return false
        val ok = runCatching {
            File(dir, "$id.json").writeText(json.encodeToString(CustomBrush(id, brush.sanitized())))
        }.isSuccess
        if (ok) refresh()
        return ok
    }

    fun delete(id: String) {
        if (!isSafeId(id)) return
        File(dir, "$id.json").delete()
        refresh()
    }

    fun load(id: String): AzphaltBrush? = _brushes.value.firstOrNull { it.id == id }?.brush
}
