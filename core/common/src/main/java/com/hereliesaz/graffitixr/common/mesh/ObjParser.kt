package com.hereliesaz.graffitixr.common.mesh

import kotlin.math.sqrt

/**
 * Wavefront OBJ reader.
 *
 * OBJ is the format chosen to start from because it's text, ubiquitous as an export target, and
 * needs no dependency — glTF would mean pulling in a JSON/GLB stack before anything can be drawn on
 * screen at all. It also carries exactly what surface painting needs later: per-vertex UVs.
 *
 * Parsing is lenient by design. Real-world OBJ files come out of a dozen exporters and routinely
 * omit normals or UVs, use any of the four `f` corner spellings, index backwards from the end of the
 * file, and carry material/group/smoothing directives this doesn't care about. Anything unrecognised
 * is skipped rather than treated as an error, because failing a whole model over one stray line
 * helps nobody.
 */
object ObjParser {

    class ObjParseException(message: String) : Exception(message)

    /**
     * What a model may hold. A model file is untrusted (a picked file, a `.fux`, a co-op peer's upload),
     * so the parse is bounded in time and memory by these, not by what the file claims:
     *
     * - [MAX_TRIANGLES]: triangles after fan-triangulation. A flat-shaded or UV-seamed mesh emits up to
     *   three vertices per triangle, so the emitted-vertex cap, which also caps the `v`, `vt` and `vn`
     *   lists, is three times that ([MAX_VERTICES]): a 400,000-triangle flat-shaded model still opens.
     * - [MAX_LINE_CHARS] and [MAX_FACE_CORNERS]: one line, and the corners of one face. Lines are found
     *   by index and checked before a single character is copied, so a 60 MB line (or a file with no
     *   newline at all) is refused without ever being split into tokens.
     *
     * Memory at the caps, beyond the file's own text: the position, uv and normal lists are primitive
     * arrays (32 bytes per index, 38 MB at 1.2 million), the emitted vertices 32 bytes each (38 MB, plus the
     * copy `build` makes), the dedupe map 12 bytes a slot at under 60% load (25 MB) and the indices 12
     * bytes a triangle (5 MB): about 150 MB peak, not the several hundred a boxed `ArrayList<Float>` would
     * take. A typical model is a small fraction of that.
     */
    const val MAX_TRIANGLES = 400_000
    const val MAX_VERTICES = 3 * MAX_TRIANGLES
    const val MAX_LINE_CHARS = 64 * 1024
    const val MAX_FACE_CORNERS = 16_384

    /** The limits [parse] enforces; tests pass small ones. */
    data class Limits(val maxVertices: Int = MAX_VERTICES, val maxTriangles: Int = MAX_TRIANGLES) {
        init {
            // A corner key packs three indices into 21 bits each; this keeps every one of them in range.
            require(maxVertices in 1 until INDEX_LIMIT) { "maxVertices must be below $INDEX_LIMIT" }
        }
    }

    /**
     * Parses [text] into an indexed [Mesh], refusing a model past [limits].
     *
     * OBJ indexes position/uv/normal independently, but GL wants one index per unique combination —
     * so corners are de-duplicated by their `v/vt/vn` triple, which is what keeps the vertex buffer
     * from exploding on a model where most corners are shared.
     *
     * Missing normals are generated from face winding, so a model exported without them still
     * shades instead of rendering flat black. Missing UVs default to zero; nothing can be painted on
     * those, but the model still displays.
     */
    fun parse(text: String, limits: Limits = Limits()): Mesh {
        val builder = Builder(limits)
        var start = 0
        while (start <= text.length) {
            val newline = text.indexOf('\n', start)
            val end = if (newline < 0) text.length else newline
            parseLine(text, start, end, builder)
            start = end + 1
        }
        return builder.build()
    }

    /** One line, text[start, end): checked for length first, and only then copied and split. */
    private fun parseLine(text: String, start: Int, end: Int, builder: Builder) {
        // A comment runs to the end of the line; a long one costs a scan, not a copy.
        var contentEnd = end
        for (i in start until end) {
            if (text[i] == '#') {
                contentEnd = i
                break
            }
        }
        if (contentEnd - start > MAX_LINE_CHARS) {
            throw ObjParseException("This model is too large: a line is longer than $MAX_LINE_CHARS characters")
        }
        val parts = text.substring(start, contentEnd).trim().split(WHITESPACE).filter { it.isNotEmpty() }
        when (parts.firstOrNull()) {
            "v" -> builder.addPosition(parts)
            "vt" -> builder.addUv(parts)
            "vn" -> builder.addNormal(parts)
            "f" -> builder.addFace(parts)
            // mtllib / usemtl / o / g / s and anything else: not needed to draw geometry.
            else -> Unit
        }
    }

    /**
     * One parse's accumulators, so the dispatch loop in [parse] stays flat. The numbers are the format's:
     * three components in a position or normal, two in a uv.
     */
    @Suppress("MagicNumber")
    private class Builder(private val limits: Limits) {
        private val positions = FloatList()
        private val uvs = FloatList()
        private val normals = FloatList()

        // Corner key ("v/vt/vn" resolved to absolute 0-based indices, each plus one) -> emitted vertex.
        private val emitted = LongIntMap()
        private val outVertices = FloatList()
        private val outIndices = IntList()

        // Emitted vertices whose corner carried no usable normal. Tracked per vertex, not per file:
        // a file can have `vn` lines and still have faces that don't reference them.
        private val missingNormals = java.util.BitSet()

        fun addPosition(parts: List<String>) {
            roomFor(positions.size / 3, limits.maxVertices, "vertices")
            // A position may carry a trailing weight; only xyz is used.
            positions.add(parts.float(1)); positions.add(parts.float(2)); positions.add(parts.float(3))
        }

        fun addUv(parts: List<String>) {
            roomFor(uvs.size / 2, limits.maxVertices, "texture coordinates")
            uvs.add(parts.float(1))
            // OBJ's V axis points up, GL texture space points down. Flipping here means the
            // renderer and the later paint-to-texture path both work in GL convention.
            // V is optional in the format (a 1-D texture coordinate); it defaults to 0.
            uvs.add(1f - parts.floatOr(2, 0f))
        }

        fun addNormal(parts: List<String>) {
            roomFor(normals.size / 3, limits.maxVertices, "normals")
            normals.add(parts.float(1)); normals.add(parts.float(2)); normals.add(parts.float(3))
        }

        /** [parts] is the whole `f` line: the keyword, then the corners. */
        fun addFace(parts: List<String>) {
            if (parts.size - 1 > MAX_FACE_CORNERS) {
                throw ObjParseException("This model is too large: a face has more than $MAX_FACE_CORNERS corners")
            }
            if (parts.size < 4) return
            // Fan-triangulate: a quad or n-gon becomes n-2 triangles. Correct for the convex
            // faces exporters emit; a concave n-gon would need ear clipping, which is a
            // problem for a mesh nobody has produced yet.
            for (i in 2 until parts.size - 1) {
                roomFor(outIndices.size / 3, limits.maxTriangles, "triangles")
                outIndices.add(emitCorner(parts[1]))
                outIndices.add(emitCorner(parts[i]))
                outIndices.add(emitCorner(parts[i + 1]))
            }
        }

        fun build(): Mesh {
            if (outIndices.size == 0) throw ObjParseException("No faces found — this doesn't look like an OBJ model")
            val vertices = outVertices.toArray()
            val indices = outIndices.toArray()
            if (!missingNormals.isEmpty) generateNormals(vertices, indices, missingNormals)
            return Mesh(vertices, indices)
        }

        /**
         * Resolves one `f` corner to an emitted vertex index, adding the vertex the first time that
         * exact position/uv/normal combination is seen.
         */
        private fun emitCorner(corner: String): Int {
            val bits = corner.split('/')
            val vi = resolve(bits.getOrNull(0), positions.size / 3)
            val ti = resolve(bits.getOrNull(1), uvs.size / 2)
            val ni = resolve(bits.getOrNull(2), normals.size / 3)
            if (vi < 0) throw ObjParseException("Face references an undefined vertex: '$corner'")

            // The (vi, ti, ni) triple itself, packed 21 bits to a field, which is exact (never a hash
            // that two different corners could share): the previous packing, (vi+1)*0xFFFFFF +
            // (ti+1)*0xFFFF + (ni+1), was not collision-free (gcd(0xFFFFFF, 0xFFFF) = 255), so two
            // geometrically distinct corners could land on one key and the second would silently snap
            // to the first's position/UV/normal. [Limits] keeps every index plus one below 2^21.
            val key = ((vi + 1).toLong() shl 42) or ((ti + 1).toLong() shl 21) or (ni + 1).toLong()
            val known = emitted[key]
            if (known >= 0) return known

            val out = outVertices
            out.add(positions[vi * 3]); out.add(positions[vi * 3 + 1]); out.add(positions[vi * 3 + 2])
            if (ni >= 0) {
                out.add(normals[ni * 3]); out.add(normals[ni * 3 + 1]); out.add(normals[ni * 3 + 2])
            } else {
                out.add(0f); out.add(0f); out.add(0f) // filled in by generateNormals
            }
            if (ti >= 0) {
                out.add(uvs[ti * 2]); out.add(uvs[ti * 2 + 1])
            } else {
                out.add(0f); out.add(0f)
            }

            val index = out.size / Mesh.FLOATS_PER_VERTEX - 1
            roomFor(index, limits.maxVertices, "vertices")
            if (ni < 0) missingNormals.set(index)
            emitted[key] = index
            return index
        }
    }

    /** Throws unless one more of [what] fits after [count] already held. */
    private fun roomFor(count: Int, limit: Int, what: String) {
        if (count >= limit) throw ObjParseException("This model is too large: more than $limit $what")
    }

    /**
     * OBJ indices are 1-based, and negative values count backwards from the most recent element —
     * so the same file can reference a vertex either way and both must resolve. Returns -1 for an
     * absent field (the empty string in `v//vn`).
     */
    private fun resolve(token: String?, count: Int): Int {
        if (token.isNullOrEmpty()) return -1
        val raw = token.toIntOrNull() ?: return -1
        val zeroBased = if (raw < 0) count + raw else raw - 1
        return if (zeroBased in 0 until count) zeroBased else -1
    }

    /**
     * Area-weighted vertex normals from face winding, for a model exported without them. Accumulate
     * each triangle's cross product onto its three corners, then normalise — shared vertices end up
     * smoothly averaged, which is what makes a curved surface read as curved. Only the vertices in
     * [targets] are written; vertices with a normal from the file keep it.
     */
    private fun generateNormals(vertices: FloatArray, indices: IntArray, targets: java.util.BitSet) {
        val stride = Mesh.FLOATS_PER_VERTEX
        val n = Mesh.NORMAL_OFFSET

        var i = 0
        while (i < indices.size) {
            val a = indices[i] * stride
            val b = indices[i + 1] * stride
            val c = indices[i + 2] * stride

            val ux = vertices[b] - vertices[a]
            val uy = vertices[b + 1] - vertices[a + 1]
            val uz = vertices[b + 2] - vertices[a + 2]
            val vx = vertices[c] - vertices[a]
            val vy = vertices[c + 1] - vertices[a + 1]
            val vz = vertices[c + 2] - vertices[a + 2]

            // Cross product magnitude is twice the triangle area, so larger faces weigh more.
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx

            for (base in intArrayOf(a, b, c)) {
                if (!targets.get(base / stride)) continue
                vertices[base + n] += nx
                vertices[base + n + 1] += ny
                vertices[base + n + 2] += nz
            }
            i += 3
        }

        var v = 0
        while (v < vertices.size) {
            if (!targets.get(v / stride)) {
                v += stride
                continue
            }
            val x = vertices[v + n]
            val y = vertices[v + n + 1]
            val z = vertices[v + n + 2]
            val len = sqrt(x * x + y * y + z * z)
            if (len > 1e-6f) {
                vertices[v + n] = x / len
                vertices[v + n + 1] = y / len
                vertices[v + n + 2] = z / len
            } else {
                // A degenerate or unreferenced vertex has no meaningful normal; point it up so it
                // shades as something rather than as a black hole.
                vertices[v + n] = 0f
                vertices[v + n + 1] = 1f
                vertices[v + n + 2] = 0f
            }
            v += stride
        }
    }

    /** [float] for an optional trailing field: [default] when the line stops before [index]. */
    private fun List<String>.floatOr(index: Int, default: Float): Float =
        if (index < size) float(index) else default

    private fun List<String>.float(index: Int): Float =
        getOrNull(index)?.toFloatOrNull()?.takeIf { it.isFinite() }
            ?: throw ObjParseException("Malformed line: expected a finite number at position $index")

    private val WHITESPACE = Regex("\\s+")
}

/** Indices are packed 21 bits to a field in a corner key. */
private const val INDEX_LIMIT = (1 shl 21) - 1

/** A growable `FloatArray`: the parse keeps millions of floats and boxing each one would cost 5x the memory. */
private class FloatList {
    private var data = FloatArray(INITIAL_CAPACITY)
    var size = 0
        private set

    fun add(value: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }

    operator fun get(index: Int): Float = data[index]

    fun toArray(): FloatArray = data.copyOf(size)
}

/** A growable `IntArray`. */
private class IntList {
    private var data = IntArray(INITIAL_CAPACITY)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }

    fun toArray(): IntArray = data.copyOf(size)
}

/**
 * Non-negative `Long` keys to `Int` values, open addressing with linear probing and no boxing; keeps the
 * emitted-vertex lookup at 12 bytes a slot instead of a `HashMap` entry plus two boxed values.
 */
@Suppress("MagicNumber")
private class LongIntMap {
    private var keys = LongArray(INITIAL_SLOTS) { EMPTY }
    private var values = IntArray(INITIAL_SLOTS)
    private var count = 0

    /** The value for [key], or -1. */
    operator fun get(key: Long): Int {
        val mask = keys.size - 1
        var slot = slotOf(key, mask)
        while (keys[slot] != EMPTY) {
            if (keys[slot] == key) return values[slot]
            slot = (slot + 1) and mask
        }
        return -1
    }

    operator fun set(key: Long, value: Int) {
        // Under 60% full, so a probe stays short.
        if ((count + 1) * 10 > keys.size * 6) grow()
        put(key, value)
    }

    private fun put(key: Long, value: Int) {
        val mask = keys.size - 1
        var slot = slotOf(key, mask)
        while (keys[slot] != EMPTY && keys[slot] != key) slot = (slot + 1) and mask
        if (keys[slot] == EMPTY) count++
        keys[slot] = key
        values[slot] = value
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(oldKeys.size * 2) { EMPTY }
        values = IntArray(oldKeys.size * 2)
        count = 0
        for (i in oldKeys.indices) if (oldKeys[i] != EMPTY) put(oldKeys[i], oldValues[i])
    }

    private fun slotOf(key: Long, mask: Int): Int = ((key * GOLDEN) ushr 32).toInt() and mask

    private companion object {
        const val EMPTY = -1L
        const val INITIAL_SLOTS = 1024

        /** 2^64 / the golden ratio, as a signed Long: spreads packed keys over the table. */
        const val GOLDEN = -7046029254386353131L
    }
}

private const val INITIAL_CAPACITY = 1024
