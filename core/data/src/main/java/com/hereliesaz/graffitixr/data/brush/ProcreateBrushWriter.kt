// SPDX-License-Identifier: Apache-2.0
// Keyed archive layout and brush field semantics follow ProCraft (ArtCraft Team and contributors).
package com.hereliesaz.graffitixr.data.brush

import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushParameter
import com.hereliesaz.graffitixr.common.azphalt.BrushSensor
import com.hereliesaz.graffitixr.common.azphalt.GrainBehavior
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.sqrt

/** Writes the supported Procreate brush subset, not a lossless Azphalt serialization. */
object ProcreateBrushWriter {
    // Procreate's portable archive units and field bounds.
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_NAME_LENGTH = 128
    private const val BRUSH_ARCHIVE_VERSION = 4
    private const val SPACING_OFFSET = 0.0083
    private const val MAX_SHAPE_COUNT = 16.0
    private const val DEGREES_PER_HALF_TURN = 180.0
    private const val MAX_GRAIN_SCALE = 16.0
    private const val REFERENCE_GRAIN_WIDTH = 256
    private const val PERCENT_SCALE = 100.0
    private const val KEYED_ARCHIVE_VERSION = 100000
    private const val MAX_FOLDER_NAME_LENGTH = 100

    // Binary property-list marker bytes and trailer layout.
    private const val UNSIGNED_BYTE_MASK = 0xff
    private const val SHORT_LENGTH_LIMIT = 15
    private const val INTEGER_MARKER = 0x12
    private const val UTF16_MARKER = 0x60
    private const val UID_MARKER = 0x80
    private const val REAL_MARKER = 0x23
    private const val TRUE_MARKER = 0x09
    private const val FALSE_MARKER = 0x08
    private const val ARRAY_MARKER = 0xa0
    private const val DICTIONARY_MARKER = 0xd0
    private const val HEADER_BYTES = 8
    private const val TRAILER_RESERVED_BYTES = 6
    private const val OFFSET_BYTES = 4
    private const val OBJECT_REF_BYTES = 4

    /** PNGs use opaque grayscale coverage (white paints); the caller prepares the pixels. */
    data class ExportBrush(
        val brush: AzphaltBrush, val shape: ByteArray, val grain: ByteArray?,
        val grainWidth: Int? = null,
    )

    /** Ordered brush IDs for a named library set. The loader resolves one brush at a time. */
    data class ExportSet(val name: String, val ids: List<String>)

    fun write(brush: AzphaltBrush, shape: ByteArray, grain: ByteArray?, grainWidth: Int? = null): ByteArray =
        zip(ProcreateBrushReader.MAX_FILE_BYTES) { addBrush("", entries(ExportBrush(brush, shape, grain, grainWidth))) }

    private fun entries(input: ExportBrush): Map<String, ByteArray> {
        val (brush, shape, grain) = input
        val clean = brush.sanitized()
        require(shape.size in 1..MAX_IMAGE_BYTES)
        require(grain == null || grain.size in 1..MAX_IMAGE_BYTES)
        val fields = linkedMapOf<String, Any>(
                "\$class" to Uid(3),
                "name" to Uid(2),
                "version" to BRUSH_ARCHIVE_VERSION,
                "plotSpacing" to ((clean.spacing.toDouble() *
                    (if (clean.isotropicSpacing) 1.0 / clean.tipRatio else 1.0)) - SPACING_OFFSET)
                    .div(sqrt(2.0)).coerceIn(0.0, 2.0),
                "shapeRoundness" to clean.tipRatio.toDouble(),
                "dynamicsJitterSize" to clean.sizeJitter.toDouble(),
                "dynamicsJitterOpacity" to clean.opacityJitter.toDouble(),
                "shapeCount" to clean.count / MAX_SHAPE_COUNT,
                "shapeCountJitter" to clean.countJitter.toDouble(),
                "shapeAngle" to clean.angle.toDouble() * Math.PI / DEGREES_PER_HALF_TURN,
                "shapeRotation" to if (clean.followStroke) 1 else 0,
                "shapeOrientation" to 1,
                "grainOrientation" to 1,
                "textureScale" to if (clean.grainBehavior == GrainBehavior.CANVAS_LOCKED)
                    (clean.grainScale * sqrt(2.0)).coerceIn(0.0, MAX_GRAIN_SCALE)
                else ((input.grainWidth ?: REFERENCE_GRAIN_WIDTH) * clean.grainScale /
                    (REFERENCE_GRAIN_WIDTH.toDouble() * sqrt(2.0))).coerceIn(0.0, MAX_GRAIN_SCALE),
                "textureZoom" to 1.0,
                "textureOffsetJitter" to clean.grainRandomOffsetPerStroke,
                "grainDepth" to clean.grainStrength.toDouble(),
                "textureApplication" to if (clean.grainBehavior == GrainBehavior.CANVAS_LOCKED) 1 else 0,
                "shapeInverted" to false,
                "textureInverted" to false,
                "bundledShapePath" to Uid(0),
                "bundledGrainPath" to Uid(0),
        )
        val objects = mutableListOf<Any>(
            "\$null",
            fields,
            clean.name.take(MAX_NAME_LENGTH),
            linkedMapOf(
                "\$classes" to listOf("SilicaBrush", "ValkyrieBrush", "NSObject"),
                "\$classname" to "SilicaBrush",
            ),
        )
        clean.inputSmoothing?.let { smoothing ->
            fields["plotSmoothing"] = smoothing.streamline / PERCENT_SCALE
            fields["plotMovingAverageStabilization"] = smoothing.stabilization / PERCENT_SCALE
            fields["plotFFTSmoothingAmount"] = smoothing.motionFiltering / PERCENT_SCALE
            fields["dynamicsPressureSmoothing"] = smoothing.pressure / PERCENT_SCALE
        }
        addPressureRoutes(clean, fields, objects)
        val archive = Plist().write(linkedMapOf(
            "\$version" to KEYED_ARCHIVE_VERSION,
            "\$archiver" to "NSKeyedArchiver",
            "\$top" to mapOf("root" to Uid(1)),
            "\$objects" to objects,
        ))
        return linkedMapOf("Brush.archive" to archive, "Shape.png" to shape).apply {
            if (grain != null) put("Grain.png", grain)
        }
    }

    /**
     * Export representable size/opacity/flow pressure routes with the engine response folded into a
     * target-specific curve. Non-pressure or compound routes are intentionally omitted; the
     * exporter already warns that unsupported dynamics are lossy.
     */
    private fun addPressureRoutes(
        brush: AzphaltBrush,
        fields: MutableMap<String, Any>,
        objects: MutableList<Any>,
    ) {
        fun add(value: Any): Uid {
            objects.add(value)
            return Uid(objects.lastIndex)
        }
        for ((parameter, key, curveKey) in listOf(
            Triple(BrushParameter.SIZE, "dynamicsPressureSize", "dynamicsPressureSizeCurve"),
            Triple(BrushParameter.OPACITY, "dynamicsPressureOpacity", "dynamicsPressureOpacityCurve"),
            Triple(BrushParameter.FLOW, "dynamicsPressureOpacityTransfer", "dynamicsPressureTransferModulationCurve"),
        )) {
            val route = brush.dynamics.filter { it.parameter == parameter }.singleOrNull() ?: continue
            if (route.sensor != BrushSensor.PRESSURE) continue
            val lo = route.outputMin
            val hi = route.outputMax
            val amount = when {
                hi == 1f && lo in 0f..1f -> 1f - lo
                lo == 1f && hi in 0f..1f -> hi - 1f
                else -> continue
            }
            if (amount == 0f || (parameter == BrushParameter.FLOW && amount < 0f)) continue
            val refs = (0..ProcreateBrushReader.PRESSURE_CURVE_STEPS).map { step ->
                val x = step.toFloat() / ProcreateBrushReader.PRESSURE_CURVE_STEPS
                val y = ((route.map(x) - lo) / (hi - lo)).coerceIn(0f, 1f)
                add("{$x, $y}")
            }
            val arrayClass = add(mapOf("\$classname" to "NSMutableArray",
                "\$classes" to listOf("NSMutableArray", "NSArray", "NSObject")))
            val array = add(mapOf("\$class" to arrayClass, "NS.objects" to refs))
            val curveClass = add(mapOf("\$classname" to "ValkyrieMagnitudinalCurve",
                "\$classes" to listOf("ValkyrieMagnitudinalCurve", "NSObject")))
            fields[key] = amount.toDouble()
            fields[curveKey] = add(mapOf("\$class" to curveClass, "points" to array))
        }
    }

    /** A set manifest lists UUID folders in source order; each holds the same files as a single brush. */
    fun writeSet(set: ExportSet, load: (String) -> ExportBrush): ByteArray {
        require(set.ids.size in 1..ProcreateBrushReader.MAX_SET_BRUSHES)
        val folders = set.ids.map { UUID.randomUUID().toString().uppercase(Locale.ROOT) }
        return zip(ProcreateBrushReader.MAX_SET_FILE_BYTES) {
            add("brushset.plist", Plist().write(mapOf("name" to set.name.take(MAX_NAME_LENGTH), "brushes" to folders)))
            set.ids.forEachIndexed { index, id -> addBrush("${folders[index]}/", entries(load(id))) }
        }
    }

    /** A ProCraft library contains nested brush ZIPs and ordered metadata at each level.
     * Folder names are sanitized and collision-free; the actual brush name lives in its archive.
     * Both the outer ZIP and aggregate inner inflation obey the reader's limits.
     */
    fun writeLibrary(sets: List<ExportSet>, load: (String) -> ExportBrush): ByteArray {
        require(sets.size in 1..ProcreateBrushReader.MAX_SET_BRUSHES)
        require(sets.sumOf { it.ids.size.toLong() } in 1L..ProcreateBrushReader.MAX_SET_BRUSHES.toLong())
        val folders = mutableListOf<String>()
        sets.forEach { folders.add(uniqueName(it.name, folders)) }
        var innerBytes = 0L
        return zip(ProcreateBrushReader.MAX_SET_FILE_BYTES) {
            add(metaName(), metadata(folders))
            sets.forEachIndexed { index, set ->
                val prefix = "${folders[index]}/"
                val names = mutableListOf<String>()
                for (id in set.ids) {
                    val brush = load(id)
                    val files = entries(brush)
                    innerBytes += files.values.sumOf { it.size.toLong() }
                    require(innerBytes <= ProcreateBrushReader.MAX_SET_INFLATED_BYTES)
                    val name = uniqueName(brush.brush.name, names, ".brush")
                    names.add(name)
                    add(prefix + name, zip(ProcreateBrushReader.MAX_FILE_BYTES) { addBrush("", files) })
                }
                if (names.isEmpty()) add(prefix + ".empty", byteArrayOf())
                add(prefix + metaName(), metadata(names))
            }
        }
    }

    /** Metadata lines are sorted by UTF-8 byte order; signed offsets restore original order. */
    private fun metadata(names: List<String>): ByteArray {
        val sorted = names.withIndex().sortedWith { a, b ->
            val left = a.value.encodeToByteArray()
            val right = b.value.encodeToByteArray()
            var cmp = 0
            for (i in 0 until minOf(left.size, right.size)) {
                cmp = (left[i].toInt() and UNSIGNED_BYTE_MASK).compareTo(right[i].toInt() and UNSIGNED_BYTE_MASK)
                if (cmp != 0) break
            }
            if (cmp == 0) left.size.compareTo(right.size) else cmp
        }
        return buildString {
            sorted.forEachIndexed { line, item -> append("${item.value}/${item.index - line}/0\n") }
            append('\u0000')
        }.encodeToByteArray()
    }

    private fun metaName() = ".procreate_meta." + UUID.randomUUID().toString().replace("-", "")

    private fun uniqueName(raw: String, used: List<String>, extension: String = ""): String {
        val base = raw.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ') it else '_' }
            .joinToString("").trim().take(MAX_FOLDER_NAME_LENGTH).ifBlank { "Brushes" }
        var name = base + extension
        var suffix = 2
        while (used.any { it.equals(name, ignoreCase = true) }) {
            name = "$base ($suffix)$extension"
            suffix++
        }
        return name
    }

    /** Retains at most one brush's files and a bounded output archive. */
    private fun zip(limit: Int, block: Archive.() -> Unit): ByteArray {
        val result = ByteArrayOutputStream()
        ZipOutputStream(result).use { zip -> Archive(zip, result, limit).block() }
        return result.toByteArray().also { require(it.size <= limit) }
    }

    private class Archive(
        private val zip: ZipOutputStream,
        private val result: ByteArrayOutputStream,
        private val limit: Int,
    ) {
        private var inflated = 0L

        fun add(name: String, bytes: ByteArray) {
            inflated += bytes.size
            require(inflated <= ProcreateBrushReader.MAX_SET_INFLATED_BYTES)
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
            require(result.size() <= limit)
        }

        fun addBrush(prefix: String, files: Map<String, ByteArray>) {
            files.forEach { (name, bytes) -> add(prefix + name, bytes) }
        }
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
            if (length < SHORT_LENGTH_LIMIT) writeByte(kind or length)
            else {
                writeByte(kind or SHORT_LENGTH_LIMIT)
                writeByte(INTEGER_MARKER)
                writeInt(length)
            }
        }

        private fun add(value: Any): Int {
            val encoded = when (value) {
                is String -> bytes { header(UTF16_MARKER, value.length); write(value.toByteArray(Charsets.UTF_16BE)) }
                is Uid -> bytes {
                    require(value.index in 0..UNSIGNED_BYTE_MASK)
                    writeByte(UID_MARKER)
                    writeByte(value.index)
                }
                is Int -> bytes { writeByte(INTEGER_MARKER); writeInt(value) }
                is Double -> bytes { writeByte(REAL_MARKER); writeDouble(value) }
                is Boolean -> bytes { writeByte(if (value) TRUE_MARKER else FALSE_MARKER) }
                is List<*> -> {
                    val refs = value.map { add(requireNotNull(it)) }
                    bytes { header(ARRAY_MARKER, refs.size); refs.forEach { writeInt(it) } }
                }
                is Map<*, *> -> {
                    val keys = value.keys.map { add(requireNotNull(it)) }
                    val refs = value.values.map { add(requireNotNull(it)) }
                    bytes {
                        header(DICTIONARY_MARKER, refs.size)
                        keys.forEach { writeInt(it) }
                        refs.forEach { writeInt(it) }
                    }
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
                var offset = HEADER_BYTES
                val offsets = nodes.map { node ->
                    val start = offset
                    write(node)
                    offset += node.size
                    start
                }
                offsets.forEach { writeInt(it) }
                write(ByteArray(TRAILER_RESERVED_BYTES))
                writeByte(OFFSET_BYTES)
                writeByte(OBJECT_REF_BYTES)
                writeLong(nodes.size.toLong())
                writeLong(top.toLong())
                writeLong(offset.toLong())
            }
        }
    }
}
