package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.ExtensionState
import com.hereliesaz.graffitixr.common.azphalt.ExtensionStateDocument
import com.hereliesaz.graffitixr.common.azphalt.ExtensionStateEntry
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What this host has done with each extension it acquired (azphalt spec/state-reporting.md).
 *
 * This exists as its own record because the filesystem cannot answer the question. Everywhere else in
 * this package the unpacked tree under `extensions/<id>/` *is* the installed state, which is what
 * makes an install survive process death with no index to corrupt. But three of the five reportable
 * states describe packages that have no directory: `removed` (had it, don't now — and a store needs
 * that to offer a reinstall rather than a first purchase), `failed`, and `downloaded`. A directory
 * scan cannot distinguish any of them from "never heard of it".
 *
 * One entry per package id, last write wins — states are transitions, not a log, so re-reporting the
 * same state is a no-op and history is not kept.
 *
 * Deliberately plain: a file, a lock, no DI. [ExtensionStateProvider] has to read the same data from a
 * `ContentProvider`, which Android may create before the application object exists, so anything that
 * needed an injector would have to be reachable without one anyway.
 */
class ExtensionStateStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Any()

    /** ISO-8601 in UTC, matching the wire format exactly (spec § 1 "Entry shape"). */
    private fun stamp(atMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(atMs))

    /** Every recorded state, newest transition first. */
    fun all(): List<ExtensionStateEntry> = synchronized(lock) { read() }

    /** The recorded state for one package id, or null if this host has no record of it. */
    fun stateOf(id: String): ExtensionStateEntry? = all().firstOrNull { it.id == id }

    /**
     * Record that [id] at [version] entered [state]. [reason] is only meaningful for
     * [ExtensionState.FAILED] and is dropped otherwise, so a stale reason can't outlive the failure
     * that produced it.
     */
    fun record(
        id: String,
        version: String,
        state: ExtensionState,
        atMs: Long,
        reason: String? = null,
    ) {
        synchronized(lock) {
            val entry = ExtensionStateEntry(
                id = id,
                version = version,
                state = state.wire,
                at = stamp(atMs),
                reason = if (state == ExtensionState.FAILED) reason else null,
            )
            write(listOf(entry) + read().filterNot { it.id == id })
        }
    }

    /** Forget a package entirely — for a state that should read as "never had it", not "removed". */
    fun forget(id: String) {
        synchronized(lock) { write(read().filterNot { it.id == id }) }
    }

    private fun read(): List<ExtensionStateEntry> {
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(ExtensionStateDocument.serializer(), file.readText()).entries
        }.getOrDefault(emptyList())
    }

    private fun write(entries: List<ExtensionStateEntry>) {
        runCatching {
            file.parentFile?.mkdirs()
            // Write-then-rename: a half-written state file would otherwise parse as empty on next
            // launch and silently reset every package to "never had it".
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(ExtensionStateDocument.serializer(), ExtensionStateDocument(entries)))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    companion object {
        /** Name of the state file inside the app's files dir. */
        const val FILE_NAME: String = "azphalt-state.json"
    }
}
