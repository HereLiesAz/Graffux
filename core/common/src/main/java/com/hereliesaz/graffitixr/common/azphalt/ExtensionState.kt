package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What this host has done with an extension it acquired (azphalt spec/state-reporting.md § 1).
 *
 * A store app that cannot tell "you have this" from "you could have this" is a catalogue, not a
 * store — the button on a card should read *Get*, *Open* or *Update*, and only the host knows which,
 * because only the host knows what it did with the bytes it was handed.
 *
 * Two of these exist solely because acquisition and installation are separate events under delegated
 * acquisition: a host that downloads from a repository itself never sees bytes it hasn't committed to.
 * They are also the two worth surfacing, because each is a user who wanted something and didn't get it.
 *
 * **Update availability is deliberately not a state.** Whether a newer version exists is the
 * repository's knowledge, not the host's; a store derives *Update* by comparing the reported [version]
 * against its catalogue. Making it a state would put a claim in the host's mouth it cannot check offline.
 */
enum class ExtensionState(val wire: String) {
    /** Verified bytes are held; the install has not happened yet, or is waiting on something. */
    DOWNLOADED("downloaded"),

    /** Installed and present, but not enabled. */
    INSTALLED("installed"),

    /** Installed and enabled — available to the user right now. */
    ACTIVE("active"),

    /** Acquisition or installation did not complete. Carries an optional reason. */
    FAILED("failed"),

    /** Was installed and no longer is — distinct from never having had it, so a store can offer a
     *  reinstall rather than a first purchase. */
    REMOVED("removed"),
}

/**
 * One reported state (spec § 1 "Entry shape"). The same shape travels on both channels — the browse
 * intent's inventory extra and the state provider's rows.
 *
 * [at] is an **ISO-8601 string, not a numeric timestamp**, because the provider's column type and the
 * wire format are specified to be the same thing; storing millis here and formatting at the boundary
 * would put two representations of one field in the codebase.
 *
 * [reason] is free text and only meaningful for [ExtensionState.FAILED].
 */
@Serializable
data class ExtensionStateEntry(
    val id: String,
    val version: String,
    val state: String,
    val at: String? = null,
    val reason: String? = null,
) {
    /** The parsed [state], or null if it is a value this build does not know. */
    val parsedState: ExtensionState?
        get() = ExtensionState.entries.firstOrNull { it.wire == state }
}

/** The document shape carried by the inventory extra: `{"entries":[…]}`. */
@Serializable
data class ExtensionStateDocument(
    val entries: List<ExtensionStateEntry> = emptyList(),
)

/**
 * Building the inventory document a host sends with a browse request (spec § 3.1).
 *
 * The size rules here are not style. An `Intent` extra crosses a process boundary over Binder, whose
 * transaction buffer is a small fixed budget shared process-wide — an oversized inventory throws
 * `TransactionTooLargeException`, and it throws in the *caller*, so a host that ignores this crashes
 * itself trying to open a store. The spec therefore caps the document at 256 KiB and asks for at most
 * 500 entries.
 */
object ExtensionInventory {

    /** Hard ceiling on the serialized document, per spec § 3.1. */
    const val MAX_BYTES: Int = 256 * 1024

    /** The entry count a host SHOULD stay under; the rest belong on the provider channel (§ 3.2). */
    const val MAX_ENTRIES: Int = 500

    private val json = Json { encodeDefaults = false }

    /**
     * Serialize [entries] into the inventory document, trimmed to fit.
     *
     * Trimming drops from the end rather than refusing to send: a partial inventory makes some of the
     * store's buttons correct, where an omitted one makes all of them wrong. A host with more than
     * fits is expected to expose the remainder through the state provider, which has no size limit.
     */
    fun document(entries: List<ExtensionStateEntry>): String {
        var kept = if (entries.size > MAX_ENTRIES) entries.take(MAX_ENTRIES) else entries
        var text = json.encodeToString(ExtensionStateDocument.serializer(), ExtensionStateDocument(kept))
        // Byte length, not character count — an id or reason outside ASCII costs more than one byte.
        while (kept.isNotEmpty() && text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            kept = kept.dropLast((kept.size / 10).coerceAtLeast(1))
            text = json.encodeToString(ExtensionStateDocument.serializer(), ExtensionStateDocument(kept))
        }
        return text
    }

    /** Parse an inventory document, tolerating unknown fields and states. Empty on anything malformed. */
    fun parse(document: String?): List<ExtensionStateEntry> {
        if (document.isNullOrBlank()) return emptyList()
        return runCatching {
            AzphaltJson.decodeFromString(ExtensionStateDocument.serializer(), document).entries
        }.getOrDefault(emptyList())
    }
}
