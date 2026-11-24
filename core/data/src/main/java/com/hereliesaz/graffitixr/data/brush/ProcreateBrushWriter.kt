// SPDX-License-Identifier: Apache-2.0
// Keyed archive layout and brush field semantics follow ProCraft (ArtCraft Team and contributors).
package com.hereliesaz.graffitixr.data.brush

import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.GrainBehavior
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.sqrt

/** Writes the supported Procreate brush subset, not a lossless Azphalt serialization. */
object ProcreateBrushWriter {
    /** PNGs must use opaque grayscale coverage (white paints); the caller prepares the pixels. */
    fun write(brush: AzphaltBrush, shape: ByteArray, grain: ByteArray?): ByteArray {
        val clean = brush.sanitized()
        require(shape.size in 1..8 * 1024 * 1024)
        require(grain == null || grain.size in 1..8 * 1024 * 1024)
        val objects = listOf(
            "\$null",
            linkedMapOf(
                "\$class" to Uid(3),
                "name" to Uid(2),
                "version" to 4,
                "plotSpacing" to ((clean.spacing.toDouble() * (if (clean.isotropicSpacing) 1.0 / clean.tipRatio else 1.0)) - 0.0083)
                    .div(sqrt(2.0)).coerceIn(0.0, 2.0),
                "shapeRoundness" to clean.tipRatio.toDouble(),
                "dynamicsJitterSize" to clean.sizeJitter.toDouble(),
                "dynamicsJitterOpacity" to clean.opacityJitter.toDouble(),
                "shapeCount" to clean.count / 16.0,
                "shapeCountJitter" to clean.countJitter.toDouble(),
                "grainDepth" to clean.grainStrength.toDouble(),
                "textureApplication" to if (clean.grainBehavior == GrainBehavior.CANVAS_LOCKED) 1 else 0,
                "shapeInverted" to false,
                "textureInverted" to false,
                "bundledShapePath" to Uid(0),
                "bundledGrainPath" to Uid(0),
            ),
            clean.name.take(128),
            linkedMapOf(
                "\$classes" to listOf("SilicaBrush", "ValkyrieBrush", "NSObject"),
                "\$classname" to "SilicaBrush",
            ),
        )
        val archive = Plist().write(linkedMapOf(
            "\$version" to 100000,
            "\$archiver" to "NSKeyedArchiver",
            "\$top" to mapOf("root" to Uid(1)),
            "\$objects" to objects,
        ))
        val result = ByteArrayOutputStream()
        ZipOutputStream(result).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("Brush.archive", archive)
            entry("Shape.png", shape)
            if (grain != null) entry("Grain.png", grain)
        }
        return result.toByteArray().also { require(it.size <= ProcreateBrushReader.MAX_FILE_BYTES) }
    }

    private data class Uid(val index: Int)

    /** Minimal binary property-list encoder. Object table indices differ from archive UIDs. */
    private class Plist {
        private val nodes = mutableListOf<ByteArray>()

        private fun bytes(block: DataOutputStream.() -> Unit): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use(block)
            return out.toByteArray()
        }

        private fun DataOutputStream.header(kind: Int, length: Int) {
            if (length < 15) writeByte(kind or length)
            else {
                writeByte(kind or 15)
                writeByte(0x12)
                writeInt(length)
            }
        }

        private fun add(value: Any): Int {
            val encoded = when (value) {
                is String -> bytes { header(0x60, value.length); write(value.toByteArray(Charsets.UTF_16BE)) }
                is Uid -> bytes { require(value.index in 0..255); writeByte(0x80); writeByte(value.index) }
                is Int -> bytes { writeByte(0x12); writeInt(value) }
                is Double -> bytes { writeByte(0x23); writeDouble(value) }
                is Boolean -> bytes { writeByte(if (value) 0x09 else 0x08) }
                is List<*> -> {
                    val refs = value.map { add(requireNotNull(it)) }
                    bytes { header(0xa0, refs.size); refs.forEach { writeInt(it) } }
                }
                is Map<*, *> -> {
                    val keys = value.keys.map { add(requireNotNull(it)) }
                    val refs = value.values.map { add(requireNotNull(it)) }
                    bytes { header(0xd0, refs.size); keys.forEach { writeInt(it) }; refs.forEach { writeInt(it) } }
                }
                else -> error("Unsupported plist value")
            }
            nodes.add(encoded)
            return nodes.lastIndex
        }

        fun write(root: Any): ByteArray {
            val top = add(root)
            return bytes {
                write("bplist00".toByteArray(Charsets.US_ASCII))
                var offset = 8
                val offsets = nodes.map { node ->
                    val start = offset
                    write(node)
                    offset += node.size
                    start
                }
                offsets.forEach { writeInt(it) }
                write(ByteArray(6))
                writeByte(4)
                writeByte(4)
                writeLong(nodes.size.toLong())
                writeLong(top.toLong())
                writeLong(offset.toLong())
            }
        }
    }
}
