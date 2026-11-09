package com.hereliesaz.graffitixr.data

import android.util.Log
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// What an imported project.json may name as a file, and how it is made safe. Kept out of ProjectManager,
// which only decides *that* an archive is imported; this decides what its URIs may point at.

/**
 * Rewrites every URI in an imported project.json to [localRoot] (`file://.../projects/<id>/`): a
 * `file://` URI naming a file under some `/projects/<anyId>/` keeps its path inside the project and
 * gains this device's root; every other URI becomes empty.
 *
 * Works on the parsed JSON, not on its text: a string can spell the same URI as `file:\/\/\/etc\/hosts` or
 * `file\u003a///etc/hosts`, and the model only ever sees the decoded value. The JSON is walked together
 * with the model's serial descriptors, position by position (a class field leads to that field's
 * descriptor, a list to its element's, a map to its value's):
 *
 * - where the model says `Uri`, the value is localized;
 * - where it says anything else (a text layer reading `https://azphalt.store`, a name like
 *   `file: notes`), the text is kept as written;
 * - a field the model does not have, and everything below it, is *unknown*: it belongs to a newer
 *   format, which is installed from this JSON as written and read later by a build that would open
 *   it. A file reference there is neutralized ([localizeUnknown]) whatever the keys below it are
 *   called, so a name the model uses elsewhere gains nothing under an unknown parent.
 *
 * Text that is not JSON is returned as it came; decoding it fails the same way.
 */
internal fun localizeArchiveUris(projectJson: String, localRoot: String): String {
    require(!exceedsJsonDepth(projectJson)) { "project.json is nested more than $MAX_JSON_DEPTH levels deep" }
    val root = runCatching { Json.parseToJsonElement(projectJson) }.getOrNull() ?: return projectJson
    val node = localizeNode(root, GraffitiProject.serializer().descriptor, localRoot)
    return Json.encodeToString(JsonElement.serializer(), node)
}

/** No real project nests anywhere near this; walking or re-encoding a deeper one recurses once per level. */
internal const val MAX_JSON_DEPTH = 64

/**
 * Whether [text] nests arrays and objects deeper than [MAX_JSON_DEPTH]. A scan over the characters
 * with a counter (strings and their escapes skipped), so it needs no recursion itself and runs before
 * anything that does: a few KB of `[[[[` must be refused, not overflow the stack.
 */
internal fun exceedsJsonDepth(text: String): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in text) {
        if (inString) {
            escaped = !escaped && c == '\\'
            inString = escaped || c != '"'
        } else if (c == '"') {
            inString = true
        } else if (c == '[' || c == '{') {
            if (++depth > MAX_JSON_DEPTH) return true
        } else if (c == ']' || c == '}') {
            depth--
        }
    }
    return false
}

/** [node] at a position whose model type is [d], or null when the model has nothing there. */
@OptIn(ExperimentalSerializationApi::class)
private fun localizeNode(node: JsonElement, d: SerialDescriptor?, localRoot: String): JsonElement = when (node) {
    is JsonObject -> JsonObject(
        node.mapValues { (k, v) ->
            // A class's named field, a map's value; anything else, or a name the class lacks, is unknown.
            val field = when (d?.kind) {
                StructureKind.CLASS -> d.getElementIndex(k).takeIf { it >= 0 }?.let(d::getElementDescriptor)
                StructureKind.MAP -> d.getElementDescriptor(1)
                else -> null
            }
            localizeNode(v, field, localRoot)
        },
    )
    is JsonArray -> {
        val item = if (d?.kind == StructureKind.LIST) d.getElementDescriptor(0) else null
        JsonArray(node.map { localizeNode(it, item, localRoot) })
    }
    is JsonPrimitive -> when {
        !node.isString -> node
        d == null -> JsonPrimitive(localizeUnknown(node.content, localRoot))
        d.serialName.removeSuffix("?") == URI_SERIAL_NAME -> JsonPrimitive(localizeUri(node.content, localRoot))
        else -> node
    }
}

/** The serial name `UriSerializer` gives every Uri-typed field. */
private const val URI_SERIAL_NAME = "Uri"

/**
 * A string that starts with a URI scheme (`file:`, `FILE:/x`, `jar:file:///x!/y`, `content://`, any case,
 * with or without `//`). A colon followed by a space is a label (`Mural: v2`), not a scheme.
 */
private val URI_LIKE = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:(?!\s)""")

private val WEB_LINK = Regex("^https?://", RegexOption.IGNORE_CASE)

/** A Windows drive path (`C:\x`, `c:/x`). */
private val DRIVE_PATH = Regex("^[a-zA-Z]:[/\\\\]")

/**
 * A string at a position the model does not know. A web link is not a local file and stays. Any other
 * URI (anything that starts with a scheme, in any case, so `FILE:/x` and `jar:file:///x!/y` too) is
 * localized; a value that reads as a filesystem path (absolute, rooted with `\`, a drive or UNC path,
 * a `..` segment, a NUL, in any percent-encoding) is blanked. Plain text stays. The tradeoff, on
 * purpose: unknown text that starts with `/` (`"/shrug"`) is blanked too, since it cannot be told from
 * an absolute path; it is lost only from the installed copy of a newer-format file.
 */
private fun localizeUnknown(value: String, localRoot: String): String = when {
    WEB_LINK.containsMatchIn(value) -> value
    URI_LIKE.containsMatchIn(value) -> localizeUri(value, localRoot)
    looksLikeLocalPath(value) -> ""
    else -> value
}

private fun looksLikeLocalPath(value: String): Boolean {
    val forms = decodedForms(value) ?: return true
    return forms.any { form ->
        '\u0000' in form || form.startsWith("/") || form.startsWith("\\") || DRIVE_PATH.containsMatchIn(form) ||
            form.split('/', '\\').any { it == ".." }
    }
}

/** `file://<anything>/projects/<id>/<rest>`: <rest> is what stays. */
private val IN_PROJECT = Regex("^file://.*?/projects/[^/]+/(.+)$")

private fun localizeUri(uri: String, localRoot: String): String {
    val inProject = IN_PROJECT.find(uri)?.groupValues?.get(1)
    val safe = inProject?.takeIf { rest -> rest.split('/').all { isSafeUriSegment(it) } }
    return if (safe != null) localRoot + safe else ""
}

/**
 * Whether one path segment of an archive URI stays inside its folder once opened. A raw `?` or `#`
 * ends the path when the URI is parsed (`..?x` is `..`), so those are refused as written. After that
 * Uri.getPath() percent-decodes, so `%2e%2e` or `..%2f` are parent references: every decoded form
 * ([decodedForms]) is refused if it is empty, `.`, `..`, or holds a separator or NUL. A decoded `?`,
 * `#` or `%` is only a filename character (`what%3F.png`, `50%25.png`).
 */
private fun isSafeUriSegment(segment: String): Boolean {
    val forms = decodedForms(segment).takeIf { '?' !in segment && '#' !in segment } ?: return false
    return forms.none { it in URI_DOT_SEGMENTS || URI_UNSAFE_CHARS.containsMatchIn(it) }
}

/**
 * [text] and what it becomes under repeated lenient percent-decoding, until it stops changing (a
 * double-encoded `%252e` too); null if it never settles within [MAX_URI_DECODES] rounds.
 */
private fun decodedForms(text: String): List<String>? {
    val forms = mutableListOf(text)
    repeat(MAX_URI_DECODES) {
        val next = lenientPercentDecode(forms.last())
        if (next == forms.last()) return forms
        forms += next
    }
    return null
}

/**
 * [text] with each valid `%XX` decoded as UTF-8 and every other `%` kept as a literal, the way Android's
 * Uri.decode reads a path. A malformed escape (`%zz`, a lone `%`) must not stop the rest being decoded:
 * `..%2f..%2fetc%zz` still holds a separator.
 */
private fun lenientPercentDecode(text: String): String {
    if ('%' !in text) return text
    // ASCII hex only, like Android's Uri.decode; Unicode digits (fullwidth `\uFF12`) are not hex.
    fun asciiHex(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + HEX_LETTER_OFFSET
        in 'A'..'F' -> c - 'A' + HEX_LETTER_OFFSET
        else -> null
    }
    val out = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < text.length) {
        val high = if (text[i] == '%') text.getOrNull(i + 1)?.let(::asciiHex) else null
        val low = if (high != null) text.getOrNull(i + 2)?.let(::asciiHex) else null
        if (high != null && low != null) {
            out.write(high * HEX_RADIX + low)
            i += ESCAPE_LENGTH
        } else {
            val codePoint = text.codePointAt(i)
            out.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
            i += Character.charCount(codePoint)
        }
    }
    return out.toString(Charsets.UTF_8.name())
}

private const val HEX_LETTER_OFFSET = 10
private const val HEX_RADIX = 16
private const val ESCAPE_LENGTH = 3

private val URI_DOT_SEGMENTS = setOf("", ".", "..")
private val URI_UNSAFE_CHARS = Regex("""[/\\\u0000]""")
private const val MAX_URI_DECODES = 4

/**
 * [localizeArchiveUris] for an archive, or null (logged) when the project.json cannot be localized, so an
 * import fails as an import error instead of crashing.
 */
internal fun localizeImportedProject(projectJson: String, localRoot: String): String? = try {
    localizeArchiveUris(projectJson, localRoot)
} catch (e: IllegalArgumentException) {
    Log.e("ProjectManager", "Archive rejected: project.json cannot be localized", e)
    null
} catch (e: StackOverflowError) {
    // Unreachable behind exceedsJsonDepth; an import error, never a dead process, if that changes.
    Log.e("ProjectManager", "Archive rejected: project.json is nested too deeply", e)
    null
}
