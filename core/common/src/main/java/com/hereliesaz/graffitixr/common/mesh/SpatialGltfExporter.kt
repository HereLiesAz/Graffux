package com.hereliesaz.graffitixr.common.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Exports real triangle geometry, not a camera snapshot. Each spatial stroke becomes a
 * pressure-scaled hexagonal tube with per-vertex RGBA paint; one self-contained glTF 2.0 JSON file
 * opens in Blender and other glTF viewers without external .bin files or image dependencies.
 */
object SpatialGltfExporter {
    private const val SIDES = 6
    private const val MAX_VERTICES = 1_000_000

    fun encode(strokes: List<SpatialStroke>): String {
        val positions = ArrayList<Float>()
        val colors = ArrayList<Float>()
        val indices = ArrayList<Int>()
        val boundsMin = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val boundsMax = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)

        fun vertex(p: FloatArray, color: FloatArray) {
            for (axis in 0..2) {
                positions += p[axis]
                boundsMin[axis] = minOf(boundsMin[axis], p[axis])
                boundsMax[axis] = maxOf(boundsMax[axis], p[axis])
            }
            for (channel in color) colors += channel
        }

        for (stroke in strokes) {
            if (stroke.width <= 0f || !stroke.width.isFinite()) continue
            // Only remove consecutive duplicates. A curve that loops back to its starting
            // position must stay closed; global distinctBy would cut its final segment off.
            val unique = stroke.points.filter {
                it.x.isFinite() && it.y.isFinite() && it.z.isFinite() && it.pressure.isFinite()
            }.fold(mutableListOf<SpatialPoint>()) { kept, point ->
                val last = kept.lastOrNull()
                if (last == null || last.x != point.x || last.y != point.y || last.z != point.z) {
                    kept.add(point)
                }
                kept
            }
            if (unique.isEmpty()) continue
            // A tap creates a one-point 3D mark. Export it as a short capsule, not as nothing.
            val points = if (unique.size == 1) {
                val point = unique.single()
                listOf(
                    point.copy(z = point.z - stroke.width * 0.25f),
                    point.copy(z = point.z + stroke.width * 0.25f),
                )
            } else unique
            require(positions.size / 3 + points.size * SIDES <= MAX_VERTICES) {
                "Spatial export exceeds the geometry safety limit"
            }
            val start = positions.size / 3
            val argb = stroke.colorArgb
            val rgba = floatArrayOf(
                ((argb ushr 16) and 255) / 255f,
                ((argb ushr 8) and 255) / 255f,
                (argb and 255) / 255f,
                ((argb ushr 24) and 255) / 255f,
            )
            for (i in points.indices) {
                val p = points[i]
                val before = points[if (i == 0) i else i - 1]
                val after = points[if (i == points.lastIndex) i else i + 1]
                val tangent = normalize(floatArrayOf(
                    after.x - before.x, after.y - before.y, after.z - before.z,
                ))
                val reference = if (kotlin.math.abs(tangent[1]) > 0.9f) {
                    floatArrayOf(1f, 0f, 0f)
                } else {
                    floatArrayOf(0f, 1f, 0f)
                }
                val side = normalize(cross(tangent, reference))
                val binormal = cross(tangent, side)
                val radius = stroke.width * p.pressure.coerceIn(0.05f, 1f) * 0.5f
                for (section in 0 until SIDES) {
                    val angle = 2.0 * PI * section / SIDES
                    val c = cos(angle).toFloat()
                    val s = sin(angle).toFloat()
                    vertex(floatArrayOf(
                        p.x + radius * (side[0] * c + binormal[0] * s),
                        p.y + radius * (side[1] * c + binormal[1] * s),
                        p.z + radius * (side[2] * c + binormal[2] * s),
                    ), rgba)
                }
            }
            for (ring in 0 until points.lastIndex) {
                for (side in 0 until SIDES) {
                    val nextSide = (side + 1) % SIDES
                    val a = start + ring * SIDES + side
                    val b = start + ring * SIDES + nextSide
                    val c = start + (ring + 1) * SIDES + side
                    val d = start + (ring + 1) * SIDES + nextSide
                    indices.add(a); indices.add(c); indices.add(b)
                    indices.add(b); indices.add(c); indices.add(d)
                }
            }
        }

        require(indices.isNotEmpty()) { "Draw at least one two-point curve before exporting" }
        val positionBytes = positions.size * 4
        val colorBytes = colors.size * 4
        val indexBytes = indices.size * 4
        val buffer = ByteBuffer.allocate(positionBytes + colorBytes + indexBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
        positions.forEach { buffer.putFloat(it) }
        colors.forEach { buffer.putFloat(it) }
        indices.forEach { buffer.putInt(it) }
        val encoded = Base64.getEncoder().encodeToString(buffer.array())
        val minimum = boundsMin.joinToString(",")
        val maximum = boundsMax.joinToString(",")
        return """
            {
              "asset":{"version":"2.0","generator":"Graffux Spatial Sketch"},
              "scene":0,
              "scenes":[{"nodes":[0]}],
              "nodes":[{"mesh":0,"name":"Spatial ink"}],
              "meshes":[{"primitives":[{"attributes":{"POSITION":0,"COLOR_0":1},"indices":2,"material":0,"mode":4}]}],
              "extensionsUsed":["KHR_materials_unlit"],
              "materials":[{"name":"Ink","pbrMetallicRoughness":{"metallicFactor":0.0,"roughnessFactor":1.0},
                "doubleSided":true,"alphaMode":"BLEND","extensions":{"KHR_materials_unlit":{}}}],
              "buffers":[{"byteLength":${buffer.capacity()},"uri":"data:application/octet-stream;base64,${encoded}"}],
              "bufferViews":[
                {"buffer":0,"byteOffset":0,"byteLength":${positionBytes},"target":34962},
                {"buffer":0,"byteOffset":${positionBytes},"byteLength":${colorBytes},"target":34962},
                {"buffer":0,"byteOffset":${positionBytes + colorBytes},"byteLength":${indexBytes},"target":34963}
              ],
              "accessors":[
                {"bufferView":0,"componentType":5126,"count":${positions.size / 3},"type":"VEC3","min":[${minimum}],"max":[${maximum}]},
                {"bufferView":1,"componentType":5126,"count":${colors.size / 4},"type":"VEC4"},
                {"bufferView":2,"componentType":5125,"count":${indices.size},"type":"SCALAR"}
              ]
            }
        """.trimIndent()
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun normalize(v: FloatArray): FloatArray {
        val len = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return if (len < 1e-8f) floatArrayOf(0f, 0f, 1f) else FloatArray(3) { v[it] / len }
    }
}
