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

/**
 * Walking or re-encoding a deeper document recurses once per level. The deepest the model itself nests
 * (arrays, objects and maps stacked inside one another, read off the serial descriptors by
 * `LocalizeArchiveUrisTest.the depth cap is far above anything the model nests`) is 10, so 64 leaves a
 * newer format room to nest several times deeper, and the test fails if the model ever grows past a quarter
 * of it.
 */
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
            // Whether THIS character is escaped is decided by the state before it: after a backslash a quote
            // is part of the string, and a backslash that was itself escaped escapes nothing.
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
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
    is JsonObject -> {
        val out = LinkedHashMap<String, JsonElement>(node.size)
        for ((k, v) in node) {
            // A class's named field, a map's value; anything else, or a name the class lacks, is unknown.
            val field = when (d?.kind) {
                StructureKind.CLASS -> d.getElementIndex(k).takeIf { it >= 0 }?.let(d::getElementDescriptor)
                StructureKind.MAP -> d.getElementDescriptor(1)
                else -> null
            }
            // A key at an unknown position is text a newer build may read as a name or a path, so it is
            // neutralized like a value (two keys that both become "" keep the later). The model has no
            // map keyed by a Uri (a test checks), so a known position's keys need nothing.
            val key = if (field == null) localizeUnknown(k, localRoot) else k
            out[key] = localizeNode(v, field, localRoot)
        }
        JsonObject(out)
    }
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
 * A value that can reach a file, content or network opener: `file:`, `content:`, `jar:`, `android.resource:`
 * and the other local or archive schemes (any case, with or without `//`), or any scheme written with an
 * authority (`ftp://h/x`, `ssh://h/p`). Plain text with a colon (`Mural:v2`), `mailto:`, `tel:` and inline
 * `data:` values reach none of those and are not matched.
 */
private val LOCAL_URI = Regex(
    """^(?:(?:file|content|jar|zip|android\.resource|asset|res|resource|ftp|ftps):|[a-zA-Z][a-zA-Z0-9+.-]*://)""",
    RegexOption.IGNORE_CASE,
)

private val WEB_LINK = Regex("^https?://", RegexOption.IGNORE_CASE)

/** A Windows drive path (`C:\x`, `c:/x`). */
private val DRIVE_PATH = Regex("^[a-zA-Z]:[/\\\\]")

/**
 * A string at a position the model does not know. Leading whitespace and control characters are ignored
 * for the checks (`java.net.URL` trims them, so ` file:///x` still opens). A web link is not a local file
 * and stays. Otherwise the text and every percent-decoded form of it are checked: a URI that can reach an
 * opener ([LOCAL_URI]) is localized (when written plainly) or blanked (when it only appears once decoded,
 * as in `file%3A%2F%2F%2Fx`), and a value that reads as a filesystem path (absolute, rooted with `\`, a
 * drive or UNC path, a `..` segment, a NUL) is blanked. Everything else stays, `data:`, `mailto:` and
 * `Mural:v2` included. The tradeoff, on purpose: unknown text that starts with `/` (`"/shrug"`) is
 * blanked too, since it cannot be told from an absolute path; it is lost only from the installed copy of
 * a newer-format file.
 */
private fun localizeUnknown(value: String, localRoot: String): String {
    val text = value.trimStart { it <= ' ' }
    val forms = decodedForms(text)
    return when {
        WEB_LINK.containsMatchIn(text) -> value
        forms == null -> ""
        LOCAL_URI.containsMatchIn(text) -> localizeUri(text, localRoot)
        forms.any { LOCAL_URI.containsMatchIn(it.trimStart { c -> c <= ' ' }) || looksLikeLocalPath(it) } -> ""
        else -> value
    }
}

private fun looksLikeLocalPath(form: String): Boolean =
    '\u0000' in form || form.startsWith("/") || form.startsWith("\\") || DRIVE_PATH.containsMatchIn(form) ||
        form.split('/', '\\').any { it == ".." }

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
