// SPDX-License-Identifier: Apache-2.0
// Binary plist/keyed-archive layout and brush field interpretation adapted from ProCraft,
// Copyright (c) 2026 ArtCraft Team and the ProCraft contributors. See docs/ProCraft adoption.md.
package com.hereliesaz.graffitixr.data.brush

import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * First-stage Procreate single-brush import. Reads only the keyed archive's scalar settings;
 * images, pressure curves, render modes and secondary brushes are intentionally not converted.
 * The caller must identify the result as a parameter-only approximation.
 *
 * No entry is extracted to disk. Both compressed and inflated input are capped. Object references
 * are followed on demand, never recursively expanded into an attacker-controlled object graph.
 */
object ProcreateBrushReader {
    const val MAX_FILE_BYTES = 16 * 1024 * 1024
    private const val MAX_INFLATED_BYTES = 32 * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 2 * 1024 * 1024
    private const val MAX_ENTRIES = 128

    /** Returns null for unsupported, malformed or oversized single-brush files. */
    fun read(bytes: ByteArray): AzphaltBrush? {
        if (bytes.size > MAX_FILE_BYTES) return null
        return try {
            var archive: ByteArray? = null
            var total = 0
            var entries = 0
            ZipInputStream(bytes.inputStream()).use { zip ->
                val buffer = ByteArray(8192)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= MAX_ENTRIES)
                    val isArchive = entry.name == "Brush.archive" && !entry.isDirectory
                    require(!isArchive || archive == null)
                    val out = if (isArchive) ByteArrayOutputStream() else null
                    while (true) {
                        val n = zip.read(buffer)
                        if (n == -1) break
                        total += n
                        require(total <= MAX_INFLATED_BYTES)
                        if (out != null) {
                            require(out.size() + n <= MAX_ARCHIVE_BYTES)
                            out.write(buffer, 0, n)
                        }
                    }
                    if (out != null) archive = out.toByteArray()
                    zip.closeEntry()
                }
            }
            decode(archive ?: return null)
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(bytes: ByteArray): AzphaltBrush {
        val plist = BinaryPlist(bytes)
        val top = plist.dictionary(plist.top)
        require(plist.string(top.getValue("\$archiver")) == "NSKeyedArchiver")
        val objects = plist.array(top.getValue("\$objects"))
        fun resolve(ref: Int): Int {
            val uid = plist.uid(ref) ?: return ref
            return objects[uid]
        }
        val root = plist.dictionary(resolve(plist.dictionary(top.getValue("\$top")).getValue("root")))
        fun scalar(key: String): Int? = root[key]?.let(::resolve)
        fun number(key: String): Double? = scalar(key)?.let(plist::number)?.takeIf { it.isFinite() }
        val nameRef = scalar("name")
        val name = nameRef?.let { ref ->
            plist.string(ref) ?: plist.dictionaryOrNull(ref)?.get("NS.string")?.let(::resolve)?.let(plist::string)
        }?.takeUnless { it == "\$null" }?.trim()?.take(128)?.takeIf { it.isNotBlank() } ?: "Imported Brush"
        require(root.keys.any { it in setOf("plotSpacing", "shapeCount", "shapeRoundness", "dynamicsJitterSize", "dynamicsJitterOpacity") })
        // ProCraft converts plotSpacing to its control with a 2.2 power and then back to
        // physical spacing with the same power. The resulting diameter fraction is
        // 0.0083 + sqrt(2) * raw, using its studio.rs floor and span.
        return AzphaltBrush(
            name = name,
            spacing = number("plotSpacing")?.coerceIn(0.0, 2.0)?.let { (0.0083 + sqrt(2.0) * it).toFloat() } ?: 0.1f,
            isotropicSpacing = false,
            tipRatio = number("shapeRoundness")?.toFloat() ?: 1f,
            sizeJitter = number("dynamicsJitterSize")?.toFloat() ?: 0f,
            opacityJitter = number("dynamicsJitterOpacity")?.toFloat() ?: 0f,
            count = number("shapeCount")?.coerceIn(0.0, 1.0)?.let { floor(it * 15.0).toInt() + 1 } ?: 1,
            countJitter = number("shapeCountJitter")?.toFloat() ?: 0f,
        ).sanitized()
    }

    /** Minimal lazy binary-plist reader for NSKeyedArchiver dictionaries, arrays and scalars. */
    private class BinaryPlist(private val bytes: ByteArray) {
        private val trailer = bytes.size - 32
        private val offsetWidth: Int
        private val refWidth: Int
        private val count: Int
        private val table: Int
        val top: Int

        init {
            require(bytes.size >= 40 && bytes.copyOfRange(0, 8).decodeToString() == "bplist00")
            offsetWidth = byte(trailer + 6)
            refWidth = byte(trailer + 7)
            require(offsetWidth in 1..8 && refWidth in 1..8)
            count = unsigned(trailer + 8, 8, bytes.size)
            top = unsigned(trailer + 16, 8, bytes.size)
            table = unsigned(trailer + 24, 8, bytes.size)
            require(count in 1..32768 && top in 0 until count && table in 8..trailer)
            require(count.toLong() * offsetWidth <= trailer - table)
        }

        private fun byte(at: Int): Int = bytes[at].toInt() and 255
        private fun unsigned(at: Int, width: Int, limit: Int): Int {
            require(width in 1..8 && at >= 0 && at.toLong() + width <= limit)
            var result = 0L
            repeat(width) {
                result = result * 256 + byte(at + it)
                require(result <= Int.MAX_VALUE)
            }
            return result.toInt()
        }
        private fun offset(ref: Int): Int {
            require(ref in 0 until count)
            return unsigned(table + ref * offsetWidth, offsetWidth, trailer).also { require(it in 8 until table) }
        }
        private fun kind(at: Int) = byte(at) ushr 4
        private fun low(at: Int) = byte(at) and 15
        private fun extent(at: Int): Pair<Int, Int> {
            if (low(at) < 15) return low(at) to at + 1
            require(at + 1 < table && kind(at + 1) == 1 && low(at + 1) <= 3)
            val width = 1 shl low(at + 1)
            return unsigned(at + 2, width, table) to at + 2 + width
        }
        private fun ref(at: Int): Int = unsigned(at, refWidth, table).also { require(it in 0 until count) }
        fun uid(ref: Int): Int? {
            val at = offset(ref)
            return if (kind(at) == 8) unsigned(at + 1, low(at) + 1, table) else null
        }
        fun array(ref: Int): List<Int> {
            val at = offset(ref)
            require(kind(at) == 10)
            val (n, start) = extent(at)
            require(n <= 32768 && start.toLong() + n.toLong() * refWidth <= table)
            return List(n) { ref(start + it * refWidth) }
        }
        fun dictionaryOrNull(ref: Int): Map<String, Int>? =
            if (kind(offset(ref)) == 13) dictionary(ref) else null
        fun dictionary(ref: Int): Map<String, Int> {
            val at = offset(ref)
            require(kind(at) == 13)
            val (n, start) = extent(at)
            require(n <= 4096 && start.toLong() + 2L * n * refWidth <= table)
            val result = HashMap<String, Int>()
            repeat(n) {
                val key = string(ref(start + it * refWidth)) ?: error("Invalid key")
                require(key.length <= 256)
                require(result.put(key, ref(start + (n + it) * refWidth)) == null)
            }
            return result
        }
        fun string(ref: Int): String? {
            val at = offset(ref)
            val width = when (kind(at)) { 5 -> 1; 6 -> 2; else -> return null }
            val (n, start) = extent(at)
            require(n <= 4096 && start.toLong() + n.toLong() * width <= table)
            return String(bytes, start, n * width, if (width == 1) Charsets.US_ASCII else Charsets.UTF_16BE)
        }
        fun number(ref: Int): Double? {
            val at = offset(ref)
            val low = low(at)
            if (kind(at) == 0) return when (low) { 8 -> 0.0; 9 -> 1.0; else -> null }
            if (low > 3) return null
            val width = 1 shl low
            require(at.toLong() + 1 + width <= table)
            val buf = ByteBuffer.wrap(bytes, at + 1, width)
            return when (kind(at)) {
                1 -> if (width == 8) buf.long.toDouble() else unsigned(at + 1, width, table).toDouble()
                2 -> when (width) { 4 -> buf.float.toDouble(); 8 -> buf.double; else -> null }
                else -> null
            }
        }
    }
}
