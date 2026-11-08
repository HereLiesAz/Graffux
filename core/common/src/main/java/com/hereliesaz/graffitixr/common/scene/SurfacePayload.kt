@file:Suppress("MagicNumber", "ReturnCount", "TooManyFunctions", "MaxLineLength", "CyclomaticComplexMethod", "NestedBlockDepth", "TooGenericExceptionCaught", "SwallowedException")

package com.hereliesaz.graffitixr.common.scene

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Why a surface payload was not accepted. */
sealed interface SurfaceParse {
    data class Ok(val payload: SurfacePayload) : SurfaceParse
    data class Rejected(val reason: String) : SurfaceParse
}

/**
 * A ready-made 3D surface from an azphalt "pack of surfaces" (azphalt spec/package-format.md, "Surface
 * format"): the front outline and optionally the side and top outlines, as ordered points in the unit box
 * (x right, y down, z away from the viewer, the shape's front at z = 0). Profiles only; no pixels, no paint,
 * no relief. [front] is `(x, y)`, [side] `(z, y)`, [top] `(x, z)`.
 *
 * Untrusted input: [parse] is the only way in and mirrors `@azphalt/azp` `parseSurface` rule for rule.
 */
class SurfacePayload private constructor(
    val name: String,
    val front: List<Pair<Float, Float>>,
    val side: List<Pair<Float, Float>>?,
    val top: List<Pair<Float, Float>>?,
) {
    /**
     * The surface as a [SurfaceProfile] in a group's box-local space for a canvas of [width] x [height]: the
     * unit box scaled uniformly to [FIT] of the canvas's shorter side and centred, the front outline on the
     * picture plane (z = 0) swept away from the viewer, the side and top outlines through the box's middle.
     * Null when the canvas is empty or the result is not a valid profile.
     */
    fun toProfile(width: Float, height: Float): SurfaceProfile? {
        if (!(width > 0f) || !(height > 0f)) return null
        val s = FIT * min(width, height)
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        fun flat(view: List<Pair<Float, Float>>, at: (Float, Float) -> FloatArray, sx: Float, sy: Float, sz: Float) =
            SurfaceProfile(view.flatMap { (a, b) -> at(a, b).toList() }, sx, sy, sz)
        val front = flat(front, { x, y -> floatArrayOf(ox + x * s, oy + y * s, 0f) }, 0f, 0f, 1f)
        val side = side?.let { flat(it, { z, y -> floatArrayOf(ox + s / 2f, oy + y * s, z * s) }, 1f, 0f, 0f) }
        val top = top?.let { flat(it, { x, z -> floatArrayOf(ox + x * s, oy + s / 2f, z * s) }, 0f, 1f, 0f) }
        val profile = front.copy(side = side, top = top)
        return profile.takeIf { it.isValid && (side == null || side.isValid) && (top == null || top.isValid) }
    }

    /**
     * The surface as triangles (x, y, z in scene pixels, then u, v; 15 floats a triangle) for
     * [SceneGltfExporter.Quad.surface], so a pack's surface exports as glTF through the existing scene exporter
     * like any other surface: the carved solid's faces when it has area, else the swept sheet. Null when it
     * cannot be built.
     */
    fun exportTriangles(width: Float, height: Float): FloatArray? {
        val profile = toProfile(width, height) ?: return null
        val hull = SurfaceHull.of(profile)
        val tris = if (hull != null) {
            hull.meshByView().values.fold(FloatArray(0)) { acc, m -> acc + m }
        } else {
            val extent = SurfaceMath.gridExtent(profile)
            SurfaceMath.mesh(profile, 0f, profile.length, 0f, extent)
        }
        return tris.takeIf { it.size >= 15 && it.size % 15 == 0 }
    }

    companion object {
        const val FORMAT = "azphalt.surface"
        const val VERSION = 1
        const val MAX_BYTES = 65_536
        const val MIN_POINTS = 2
        const val MAX_POINTS = 256
        const val MAX_NAME = 64
        const val MIN_VIEW_LENGTH = 0.01
        const val MAX_DEPTH = 4

        /** The share of the canvas's shorter side an inserted surface's box takes. */
        const val FIT = 0.7f

        private val KEYS = setOf("format", "version", "name", "front", "side", "top")

        /** Validates and parses [bytes] (UTF-8 JSON). Never throws. */
        fun parse(bytes: ByteArray): SurfaceParse {
            if (bytes.size > MAX_BYTES) return SurfaceParse.Rejected("surface is larger than $MAX_BYTES bytes")
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                return SurfaceParse.Rejected("surface is not valid UTF-8")
            }
            return parse(text)
        }

        fun parse(text: String): SurfaceParse {
            fun no(reason: String) = SurfaceParse.Rejected(reason)
            if (text.length > MAX_BYTES) return no("surface is larger than $MAX_BYTES bytes")
            // Structure first, with a scan that cannot recurse: nesting depth and repeated top-level keys.
            scanStructure(text)?.let { return no(it) }
            val root = try {
                Json.parseToJsonElement(text)
            } catch (e: Exception) {
                return no("surface is not valid JSON")
            }
            val o = root as? JsonObject ?: return no("surface must be a JSON object")
            o.keys.firstOrNull { it !in KEYS }?.let { return no("unknown key \"$it\"") }
            if ((o["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content != FORMAT) return no("format must be \"$FORMAT\"")
            val version = (o["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            if (version != VERSION.toDouble()) return no("version must be $VERSION")
            val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (name == null || name.isBlank()) return no("name must be a non-empty string")
            if (name.length > MAX_NAME) return no("name is longer than $MAX_NAME characters")
            if (name.any { it.code <= 0x1f || it.code in 0x7f..0x9f }) return no("name contains a control character")
            if (hasLoneSurrogate(name)) return no("name is not well-formed Unicode")
            if ("front" !in o) return no("front is required")
            val views = HashMap<String, List<Pair<Float, Float>>>()
            for (v in listOf("front", "side", "top")) {
                val el = o[v] ?: continue
                when (val r = view(v, el)) {
                    is ViewResult.Bad -> return no(r.reason)
                    is ViewResult.Good -> views[v] = r.points
                }
            }
            return SurfaceParse.Ok(SurfacePayload(name, views.getValue("front"), views["side"], views["top"]))
        }

        private sealed interface ViewResult {
            class Good(val points: List<Pair<Float, Float>>) : ViewResult
            class Bad(val reason: String) : ViewResult
        }

        private fun view(label: String, el: JsonElement): ViewResult {
            val arr = el as? JsonArray ?: return ViewResult.Bad("$label must be an array of [a, b] points")
            if (arr.size < MIN_POINTS) return ViewResult.Bad("$label needs at least $MIN_POINTS points")
            if (arr.size > MAX_POINTS) return ViewResult.Bad("$label has ${arr.size} points (limit $MAX_POINTS)")
            val out = ArrayList<Pair<Float, Float>>(arr.size)
            var length = 0.0
            var prev: Pair<Double, Double>? = null
            for ((i, p) in arr.withIndex()) {
                val pair = p as? JsonArray
                if (pair == null || pair.size != 2) return ViewResult.Bad("$label[$i] must be a pair [a, b]")
                val nums = pair.map { n ->
                    (n as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
                        ?: return ViewResult.Bad("$label[$i] has a non-finite number")
                }
                if (nums.any { it < 0.0 || it > 1.0 }) return ViewResult.Bad("$label[$i] is outside the unit box [0, 1]")
                prev?.let { length += hypot(nums[0] - it.first, nums[1] - it.second) }
                prev = nums[0] to nums[1]
                out.add(nums[0].toFloat() to nums[1].toFloat())
            }
            if (!(length >= MIN_VIEW_LENGTH)) return ViewResult.Bad("$label is too short to be a curve")
            return ViewResult.Good(out)
        }

        /** Whether [points] encloses area (closed last-to-first): the rule the hull and the azphalt validator share. */
        fun isClosedOutline(points: List<Pair<Float, Float>>): Boolean {
            val pts = points.toMutableList()
            if (pts.size > 1 && hypot((pts.first().first - pts.last().first).toDouble(), (pts.first().second - pts.last().second).toDouble()) < 1e-3) {
                pts.removeAt(pts.lastIndex)
            }
            if (pts.size < 3) return false
            var area = 0.0
            var perimeter = 0.0
            for (i in pts.indices) {
                val p = pts[i]
                val q = pts[(i + 1) % pts.size]
                area += p.first.toDouble() * q.second - q.first.toDouble() * p.second
                perimeter += hypot((q.first - p.first).toDouble(), (q.second - p.second).toDouble())
            }
            return perimeter > 0 && abs(area) / 2 >= 1e-5 * perimeter * perimeter
        }

        private fun hasLoneSurrogate(s: String): Boolean {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c.isHighSurrogate()) {
                    if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return true
                    i += 2
                    continue
                }
                if (c.isLowSurrogate()) return true
                i++
            }
            return false
        }

        /** A reason when [text] nests deeper than [MAX_DEPTH] or repeats a top-level key; null when fine. Iterative. */
        private fun scanStructure(text: String): String? {
            var depth = 0
            var i = 0
            val seen = HashSet<String>()
            while (i < text.length) {
                val c = text[i]
                if (c == '"') {
                    val start = i
                    i++
                    while (i < text.length && text[i] != '"') i += if (text[i] == '\\') 2 else 1
                    if (depth == 1) {
                        var j = i + 1
                        while (j < text.length && text[j].isWhitespace()) j++
                        if (j < text.length && text[j] == ':' && i < text.length) {
                            val key = try {
                                (Json.parseToJsonElement(text.substring(start, i + 1)) as? JsonPrimitive)?.content
                            } catch (_: Exception) {
                                null
                            }
                            if (key != null && !seen.add(key)) return "duplicate key \"$key\""
                        }
                    }
                } else if (c == '[' || c == '{') {
                    if (++depth > MAX_DEPTH) return "surface is nested deeper than $MAX_DEPTH"
                } else if (c == ']' || c == '}') {
                    depth--
                }
                i++
            }
            return null
        }
    }
}
