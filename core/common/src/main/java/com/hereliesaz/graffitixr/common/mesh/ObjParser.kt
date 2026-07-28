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
     * Parses [text] into an indexed [Mesh].
     *
     * OBJ indexes position/uv/normal independently, but GL wants one index per unique combination —
     * so corners are de-duplicated by their `v/vt/vn` triple, which is what keeps the vertex buffer
     * from exploding on a model where most corners are shared.
     *
     * Missing normals are generated from face winding, so a model exported without them still
     * shades instead of rendering flat black. Missing UVs default to zero; nothing can be painted on
     * those, but the model still displays.
     */
    fun parse(text: String): Mesh {
        val positions = ArrayList<Float>()
        val uvs = ArrayList<Float>()
        val normals = ArrayList<Float>()

        // Corner key ("v/vt/vn" resolved to absolute 0-based indices) -> emitted vertex index.
        val emitted = HashMap<Long, Int>()
        val outVertices = ArrayList<Float>()
        val outIndices = ArrayList<Int>()
        var sawNormals = false

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(WHITESPACE).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return@forEach

            when (parts[0]) {
                "v" -> {
                    // A position may carry a trailing weight; only xyz is used.
                    positions.add(parts.float(1)); positions.add(parts.float(2)); positions.add(parts.float(3))
                }
                "vt" -> {
                    uvs.add(parts.float(1))
                    // OBJ's V axis points up, GL texture space points down. Flipping here means the
                    // renderer and the later paint-to-texture path both work in GL convention.
                    uvs.add(1f - parts.float(2))
                }
                "vn" -> {
                    normals.add(parts.float(1)); normals.add(parts.float(2)); normals.add(parts.float(3))
                    sawNormals = true
                }
                "f" -> {
                    val corners = parts.drop(1)
                    if (corners.size < 3) return@forEach
                    // Fan-triangulate: a quad or n-gon becomes n-2 triangles. Correct for the convex
                    // faces exporters emit; a concave n-gon would need ear clipping, which is a
                    // problem for a mesh nobody has produced yet.
                    for (i in 1 until corners.size - 1) {
                        listOf(corners[0], corners[i], corners[i + 1]).forEach { corner ->
                            val idx = emitCorner(
                                corner, positions, uvs, normals, emitted, outVertices,
                            )
                            outIndices.add(idx)
                        }
                    }
                }
                // mtllib / usemtl / o / g / s and anything else: not needed to draw geometry.
                else -> Unit
            }
        }

        if (outIndices.isEmpty()) throw ObjParseException("No faces found — this doesn't look like an OBJ model")

        val vertices = outVertices.toFloatArray()
        val indices = outIndices.toIntArray()
        if (!sawNormals) generateNormals(vertices, indices)
        return Mesh(vertices, indices)
    }

    /**
     * Resolves one `f` corner to an emitted vertex index, adding the vertex the first time that
     * exact position/uv/normal combination is seen.
     */
    private fun emitCorner(
        corner: String,
        positions: List<Float>,
        uvs: List<Float>,
        normals: List<Float>,
        emitted: HashMap<Long, Int>,
        out: ArrayList<Float>,
    ): Int {
        val bits = corner.split('/')
        val vi = resolve(bits.getOrNull(0), positions.size / 3)
        val ti = resolve(bits.getOrNull(1), uvs.size / 2)
        val ni = resolve(bits.getOrNull(2), normals.size / 3)
        if (vi < 0) throw ObjParseException("Face references an undefined vertex: '$corner'")

        // Pack the triple into one key; +1 keeps "absent" (-1) distinct from index 0.
        val key = (vi + 1).toLong() * 0xFFFFFFL + (ti + 1).toLong() * 0xFFFFL + (ni + 1).toLong()
        emitted[key]?.let { return it }

        out.add(positions[vi * 3]); out.add(positions[vi * 3 + 1]); out.add(positions[vi * 3 + 2])
        if (ni >= 0) {
            out.add(normals[ni * 3]); out.add(normals[ni * 3 + 1]); out.add(normals[ni * 3 + 2])
        } else {
            out.add(0f); out.add(0f); out.add(0f)  // filled in by generateNormals
        }
        if (ti >= 0) {
            out.add(uvs[ti * 2]); out.add(uvs[ti * 2 + 1])
        } else {
            out.add(0f); out.add(0f)
        }

        val index = out.size / Mesh.FLOATS_PER_VERTEX - 1
        emitted[key] = index
        return index
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
     * smoothly averaged, which is what makes a curved surface read as curved.
     */
    private fun generateNormals(vertices: FloatArray, indices: IntArray) {
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
                vertices[base + n] += nx
                vertices[base + n + 1] += ny
                vertices[base + n + 2] += nz
            }
            i += 3
        }

        var v = 0
        while (v < vertices.size) {
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

    private fun List<String>.float(index: Int): Float =
        getOrNull(index)?.toFloatOrNull()
            ?: throw ObjParseException("Malformed line: expected a number at position $index")

    private val WHITESPACE = Regex("\\s+")
}
