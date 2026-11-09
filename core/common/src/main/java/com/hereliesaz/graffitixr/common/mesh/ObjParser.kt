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
     * What a model may hold, for positions, uvs, normals and emitted vertices alike, and for triangles.
     * A model file is untrusted (a picked file, a `.fux`, a co-op peer's upload) and the parse keeps every
     * element in memory, so a few megabytes of `v` lines must not be able to exhaust the heap. A scanned
     * mesh of a million vertices is already far past what the painter can use.
     */
    const val MAX_VERTICES = 1_000_000
    const val MAX_TRIANGLES = 2_000_000

    /** The limits [parse] enforces; tests pass small ones. */
    data class Limits(val maxVertices: Int = MAX_VERTICES, val maxTriangles: Int = MAX_TRIANGLES)

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
        for (rawLine in text.lineSequence()) {
            val parts = rawLine.substringBefore('#').trim().split(WHITESPACE).filter { it.isNotEmpty() }
            when (parts.firstOrNull()) {
                "v" -> builder.addPosition(parts)
                "vt" -> builder.addUv(parts)
                "vn" -> builder.addNormal(parts)
                "f" -> builder.addFace(parts.drop(1))
                // mtllib / usemtl / o / g / s and anything else: not needed to draw geometry.
                else -> Unit
            }
        }
        return builder.build()
    }

    /**
     * One parse's accumulators, so the dispatch loop in [parse] stays flat. The numbers are the format's:
     * three components in a position or normal, two in a uv.
     */
    @Suppress("MagicNumber")
    private class Builder(private val limits: Limits) {
        private val positions = ArrayList<Float>()
        private val uvs = ArrayList<Float>()
        private val normals = ArrayList<Float>()

        // Corner key ("v/vt/vn" resolved to absolute 0-based indices) -> emitted vertex index.
        private val emitted = HashMap<Triple<Int, Int, Int>, Int>()
        private val outVertices = ArrayList<Float>()
        private val outIndices = ArrayList<Int>()

        // Emitted vertices whose corner carried no usable normal. Tracked per vertex, not per file:
        // a file can have `vn` lines and still have faces that don't reference them.
        private val missingNormals = HashSet<Int>()

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

        fun addFace(corners: List<String>) {
            if (corners.size < 3) return
            // Fan-triangulate: a quad or n-gon becomes n-2 triangles. Correct for the convex
            // faces exporters emit; a concave n-gon would need ear clipping, which is a
            // problem for a mesh nobody has produced yet.
            for (i in 1 until corners.size - 1) {
                roomFor(outIndices.size / 3, limits.maxTriangles, "triangles")
                outIndices.add(emitCorner(corners[0]))
                outIndices.add(emitCorner(corners[i]))
                outIndices.add(emitCorner(corners[i + 1]))
            }
        }

        fun build(): Mesh {
            if (outIndices.isEmpty()) throw ObjParseException("No faces found — this doesn't look like an OBJ model")
            val vertices = outVertices.toFloatArray()
            val indices = outIndices.toIntArray()
            if (missingNormals.isNotEmpty()) generateNormals(vertices, indices, missingNormals)
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

            // The (vi, ti, ni) triple itself, not a packed bit-encoding: the previous key —
            // (vi+1)*0xFFFFFF + (ti+1)*0xFFFF + (ni+1) — was not collision-free (gcd(0xFFFFFF, 0xFFFF)
            // = 255), so on a large enough mesh two geometrically distinct corners could hash to the
            // same key and the second would silently snap to the first's position/UV/normal — a
            // triangle corner teleported elsewhere in the mesh with no error. Triple's generated
            // equals/hashCode compares all three fields, which a HashMap key always needs anyway; the
            // hash value colliding is fine; the key being *equal* to a different corner is not.
            val key = Triple(vi, ti, ni)
            emitted[key]?.let { return it }

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
            if (ni < 0) missingNormals += index
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
    private fun generateNormals(vertices: FloatArray, indices: IntArray, targets: Set<Int>) {
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
                if (base / stride !in targets) continue
                vertices[base + n] += nx
                vertices[base + n + 1] += ny
                vertices[base + n + 2] += nz
            }
            i += 3
        }

        var v = 0
        while (v < vertices.size) {
            if (v / stride !in targets) {
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
