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
     * Strips URIs, paths and file names out of a failure [reason] before it's persisted. Each
     * whitespace-delimited token of the first [MAX_SCAN_LENGTH] characters is replaced by `[redacted]` when
     * it holds a URI (`scheme://...`), reads as a path (starts with `/`, `./`, `../`, `~/` or a drive letter,
     * has two or more separators, or has one separator and ends in a file extension), or is a bare file name
     * with a known data-file extension ([FILE_EXTENSIONS]); the result is cut to [MAX_REASON_LENGTH]. It is a
     * linear scan over bounded input: this runs inside the lock the provider's reads share, so it must not
     * be possible to make it slow. Best effort: free text can still name something in words.
     * [ExtensionStateProvider] exposes this store's entries through an exported
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
        return scrub(raw.take(MAX_SCAN_LENGTH)).take(MAX_REASON_LENGTH)
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

        private const val REDACTED = "[redacted]"

        /** How much of a reason is read at all: a diagnostic is a sentence, and the rest is dropped unread. */
        private const val MAX_SCAN_LENGTH = 400

        /** Extensions of the files this app keeps and reads: a bare name ending in one of these is a file name. */
        private val FILE_EXTENSIONS = setOf(
            "json", "png", "jpg", "jpeg", "webp", "obj", "mtl", "gltf", "glb", "azp", "cube", "bak", "tmp",
            "corrupt", "xml", "fux", "gxr", "prefs", "pdf", "zip", "apk", "wasm",
        )

        /** Two or more separators (three or more parts) read as a path whatever the parts are. */
        private const val PATH_SEGMENTS = 3

        private const val MAX_EXTENSION_LENGTH = 5

        private const val EDGE_PUNCTUATION = "()[]{}<>\"',;:"

        private val DRIVE_PATH = Regex("^[A-Za-z]:[/\\\\]")

        /** [text] with each token that names a URI, path or file replaced by [REDACTED], spacing kept. */
        private fun scrub(text: String): String {
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                var end = i
                val space = text[i].isWhitespace()
                while (end < text.length && text[end].isWhitespace() == space) end++
                val piece = text.substring(i, end)
                out.append(if (!space && namesAFile(piece)) REDACTED else piece)
                i = end
            }
            return out.toString()
        }

        private fun namesAFile(token: String): Boolean {
            val core = token.trim { it in EDGE_PUNCTUATION }
            val scheme = token.indexOf("://")
            return core.isNotEmpty() &&
                ((scheme > 0 && token[scheme - 1].isLetter()) || looksLikePath(core) || isDataFileName(core))
        }

        private fun looksLikePath(core: String): Boolean {
            val rooted = (core.startsWith("/") && core.length > 1) || core.startsWith("./") ||
                core.startsWith("../") || core.startsWith("~/") || DRIVE_PATH.containsMatchIn(core)
            val parts = core.split('/', '\\')
            return rooted || parts.size >= PATH_SEGMENTS || (parts.size == 2 && hasExtension(parts.last()))
        }

        /** `name.ext`, the extension short, alphanumeric and holding a letter (so not `2.0` or `1.2.3`). */
        private fun hasExtension(name: String): Boolean {
            val dot = name.lastIndexOf('.')
            val ext = if (dot in 1 until name.length - 1) name.substring(dot + 1) else ""
            val shortAndPlain = ext.length in 1..MAX_EXTENSION_LENGTH && ext.all { it.isLetterOrDigit() }
            return shortAndPlain && ext.any { it.isLetter() }
        }

        private fun isDataFileName(core: String): Boolean {
            val dot = core.lastIndexOf('.')
            return dot > 0 && core.substring(dot + 1).lowercase() in FILE_EXTENSIONS
        }

        /** A failure reason is a short diagnostic string, not a place for an exception's full
         *  stack-trace-adjacent detail to accumulate. */
        private const val MAX_REASON_LENGTH = 200

        private val locks = HashMap<String, Any>()

        private fun lockFor(file: File): Any = synchronized(locks) {
            locks.getOrPut(runCatching { file.canonicalPath }.getOrDefault(file.path)) { Any() }
        }
    }
}
