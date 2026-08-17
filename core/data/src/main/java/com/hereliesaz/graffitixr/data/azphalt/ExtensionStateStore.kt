package com.hereliesaz.graffitixr.data.azphalt

import android.util.Log
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

    /**
     * Strips anything that looks like a URI or absolute file path out of a failure [reason] before
     * it's persisted. [ExtensionStateProvider] exposes this store's entries through an exported
     * `ContentProvider` gated only by a `normal`-level permission — any app can declare it and read
     * with no user prompt — and its own KDoc promises "nothing in a row identifies a device, a
     * user, or an install". [reason] comes from a caught exception's raw `.message` at every call
     * site, though, and a revoked read grant on a user-picked document surfaces as a
     * FileNotFoundException/SecurityException naming its `content://` URI — exactly the kind of
     * thing that promise says never happens. Scrub it here, once, at the one place every reason
     * actually gets written, rather than trusting every future caller to remember to.
     */
    private fun sanitizeReason(raw: String?): String? {
        if (raw == null) return null
        return URI_OR_PATH.replace(raw, "[redacted]").take(MAX_REASON_LENGTH)
    }

    /**
     * The lock is per *file*, not per instance.
     *
     * Two instances exist over the same path in one process: the repository builds one, and
     * [ExtensionStateProvider] — which Android may construct before the application object, so it
     * cannot share the repository's — builds another. A per-instance lock leaves them uncoordinated,
     * so a store app querying the provider on a Binder thread can read the file midway through an
     * install's write.
     */
    private val lock: Any = lockFor(file)

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
    ): Boolean {
        return synchronized(lock) {
            val entry = ExtensionStateEntry(
                id = id,
                version = version,
                state = state.wire,
                at = stamp(atMs),
                reason = if (state == ExtensionState.FAILED) sanitizeReason(reason) else null,
            )
            write(listOf(entry) + read().filterNot { it.id == id })
        }
    }

    /** Forget a package entirely — for a state that should read as "never had it", not "removed". */
    fun forget(id: String): Boolean =
        synchronized(lock) { write(read().filterNot { it.id == id }) }

    private fun read(): List<ExtensionStateEntry> {
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(ExtensionStateDocument.serializer(), file.readText()).entries
        }.getOrDefault(emptyList())
    }

    /**
     * Returns whether the write landed.
     *
     * A failure is reported rather than swallowed. It is deliberately **not** thrown: the caller's
     * install genuinely happened, and failing it because a bookkeeping file could not be written
     * would turn a cosmetic problem into a functional one. But a silent failure means the store
     * shows *Get* on everything with nothing anywhere to say why, so it is logged and returned.
     */
    private fun write(entries: List<ExtensionStateEntry>): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            file.parentFile?.mkdirs()
            // Write-then-rename, so a process death mid-write leaves the previous file intact. There
            // is no in-place fallback: this used to fall back to a truncating `writeText`, which is
            // exactly the half-written file the rename exists to prevent.
            tmp.writeText(json.encodeToString(ExtensionStateDocument.serializer(), ExtensionStateDocument(entries)))
            if (!tmp.renameTo(file)) {
                try {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                    true
                } catch (t: Throwable) {
                    tmp.delete()
                    Log.w(TAG, "Could not replace ${file.name}: rename failed; extension state is stale", t)
                    false
                }
            } else {
                true
            }
        } catch (t: Throwable) {
            tmp.delete()
            Log.w(TAG, "Could not write ${file.name}; extension state is stale", t)
            false
        }
    }

    companion object {
        /** Name of the state file inside the app's files dir. */
        const val FILE_NAME: String = "azphalt-state.json"

        private const val TAG = "AzphaltState"

        /** Matches a URI (`scheme://...`) or an absolute Unix path — see [sanitizeReason]. */
        private val URI_OR_PATH = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://\S+|/[\w.\-/]{3,}""")

        /** A failure reason is a short diagnostic string, not a place for an exception's full
         *  stack-trace-adjacent detail to accumulate. */
        private const val MAX_REASON_LENGTH = 200

        private val locks = HashMap<String, Any>()

        private fun lockFor(file: File): Any = synchronized(locks) {
            locks.getOrPut(runCatching { file.canonicalPath }.getOrDefault(file.path)) { Any() }
        }
    }
}
