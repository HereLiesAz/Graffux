// SPDX-License-Identifier: Apache-2.0
// Binary plist/keyed-archive layout and brush field interpretation adapted from ProCraft,
// Copyright (c) 2026 ArtCraft Team and the ProCraft contributors. See docs/ProCraft adoption.md.
package com.hereliesaz.graffitixr.data.brush

import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushCurvePoint
import com.hereliesaz.graffitixr.common.azphalt.BrushParameter
import com.hereliesaz.graffitixr.common.azphalt.BrushResponseCurve
import com.hereliesaz.graffitixr.common.azphalt.BrushSensor
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorBinding
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Procreate single-brush import. Reads supported scalar settings and embedded shape/grain
 * images. Pressure curves, render modes and secondary brushes remain approximations.
 *
 * No entry is extracted to disk. Both compressed and inflated input are capped. Object references
 * are followed on demand, never recursively expanded into an attacker-controlled object graph.
 */
object ProcreateBrushReader {
    const val MAX_FILE_BYTES = 16 * 1024 * 1024
    private const val MAX_INFLATED_BYTES = 32 * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 2 * 1024 * 1024
    private const val MAX_ENTRIES = 128
    const val MAX_SET_FILE_BYTES = 32 * 1024 * 1024
    internal const val MAX_SET_INFLATED_BYTES = 64 * 1024 * 1024
    private const val MAX_SET_ENTRIES = 2048
    internal const val MAX_SET_BRUSHES = 256
    internal const val PRESSURE_CURVE_STEPS = 32
    private const val MAX_CURVE_POINTS = 256

    /** Returns null for unsupported, malformed or oversized single-brush files. */
    fun read(bytes: ByteArray): AzphaltBrush? = readWithAssets(bytes)?.brush

    /** Embedded texture bytes and the archive's polarity flag. */
    data class Texture(val bytes: ByteArray, val inverted: Boolean)
    /** Decoded settings and user-supplied images; missing stock images are reported separately. */
    data class Imported(val brush: AzphaltBrush, val shape: Texture?, val grain: Texture?, val missingTexture: Boolean)

    fun readWithAssets(bytes: ByteArray): Imported? = readSingle(bytes) { }

    /** Charges every inflated byte to the caller's budget as well as the single-brush limit. */
    private fun readSingle(bytes: ByteArray, charge: (Int) -> Unit): Imported? {
        if (bytes.size > MAX_FILE_BYTES) return null
        return try {
            var archive: ByteArray? = null
            val images = mutableMapOf<String, ByteArray>()
            var total = 0
            var entries = 0
            ZipInputStream(bytes.inputStream()).use { zip ->
                val buffer = ByteArray(8192)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= MAX_ENTRIES)
                    val isArchive = entry.name == "Brush.archive" && !entry.isDirectory
                    require(!isArchive || archive == null)
                    val isImage = !entry.isDirectory && entry.name.length <= 512 &&
                        entry.name.substringAfterLast('.').lowercase(java.util.Locale.ROOT) in setOf("png", "jpg", "jpeg")
                    require(!isImage || entry.name !in images)
                    val out = if (isArchive || isImage) ByteArrayOutputStream() else null
                    while (true) {
                        val n = zip.read(buffer)
                        if (n == -1) break
                        charge(n)
                        total += n
                        require(total <= MAX_INFLATED_BYTES)
                        if (out != null) {
                            require(out.size() + n <= (if (isArchive) MAX_ARCHIVE_BYTES else 8 * 1024 * 1024))
                            out.write(buffer, 0, n)
                        }
                    }
                    if (out != null) {
                        if (isArchive) archive = out.toByteArray() else images[entry.name] = out.toByteArray()
                    }
                    zip.closeEntry()
                }
            }
            decode(archive ?: return null, images)
        } catch (_: Exception) {
            null
        }
    }

    /** A set's manifest order, successfully decoded brushes and rejected member count. */
    data class ImportedSet(val name: String, val brushes: List<Imported>, val skipped: Int)

    /**
     * Reads the XML or binary `brushset.plist` and its listed brush folders. All archive entries,
     * including ignored ones, count towards the aggregate inflation limit. No files are extracted.
     * Invalid members are counted; a malformed container or manifest rejects the whole set.
     */
    fun readSet(bytes: ByteArray): ImportedSet? {
        if (bytes.size > MAX_SET_FILE_BYTES) return null
        return try {
            val files = readSetEntries(bytes)
            val (name, folders) = setManifest(files["brushset.plist"] ?: return null)
            val brushes = folders.mapNotNull { folder ->
                val prefix = "$folder/"
                val archive = files["${prefix}Brush.archive"] ?: return@mapNotNull null
                val images = files.asSequence().filter { (key, _) -> key.startsWith(prefix) &&
                    key != "${prefix}Brush.archive" }.associate { (key, value) -> key.removePrefix(prefix) to value }
                runCatching { decode(archive, images) }.getOrNull()
            }
            ImportedSet(name, brushes, folders.size - brushes.size)
        } catch (_: Exception) {
            null
        }
    }

    /** Keeps only bounded manifest, archive and texture entries, rejecting duplicate names. */
    private fun readSetEntries(bytes: ByteArray, library: Boolean = false): Map<String, ByteArray> {
        val files = mutableMapOf<String, ByteArray>()
        var total = 0
        var count = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            val buffer = ByteArray(8192)
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= MAX_SET_ENTRIES)
                val name = entry.name
                val limit = when {
                    entry.isDirectory || name.length > 512 -> 0
                    library -> when {
                        libraryMetaName(name.substringAfterLast('/')) && name.count { it == '/' } <= 1 -> MAX_ARCHIVE_BYTES
                        name.count { it == '/' } == 1 && name.endsWith(".brush") -> MAX_FILE_BYTES
                        name.count { it == '/' } == 1 && name.endsWith("/.empty") -> 1
                        else -> 0
                    }
                    name == "brushset.plist" || name.endsWith("/Brush.archive") -> MAX_ARCHIVE_BYTES
                    name.substringAfterLast('.').lowercase(java.util.Locale.ROOT) in setOf("png", "jpg", "jpeg") -> 8 * 1024 * 1024
                    else -> 0
                }
                val out = if (limit > 0) ByteArrayOutputStream() else null
                while (true) {
                    val n = zip.read(buffer)
                    if (n == -1) break
                    total += n
                    require(total <= MAX_SET_INFLATED_BYTES)
                    if (out != null) {
                        require(out.size() + n <= limit)
                        out.write(buffer, 0, n)
                    }
                }
                if (out != null) require(files.put(name, out.toByteArray()) == null)
                zip.closeEntry()
            }
        }
        return files
    }

    /** Ordered sets; empty sets are retained here so the caller can report that it cannot save them. */
    data class ImportedLibrary(val sets: List<ImportedSet>)

    /** Reads nested brush ZIPs using independent outer and aggregate inner inflation budgets.
     * Library metadata follows ProCraft's .procreate_meta layout: line index plus signed offset
     * gives source position. Tool defaults and icons are not imported. No files are extracted.
     */
    fun readLibrary(bytes: ByteArray): ImportedLibrary? {
        if (bytes.size > MAX_SET_FILE_BYTES) return null
        return try {
            val files = readSetEntries(bytes, library = true)
            val top = files.keys.singleOrNull { '/' !in it && libraryMetaName(it) } ?: return null
            val folders = linkedMapOf<String, MutableList<String>>()
            for (path in files.keys) {
                if (path.count { it == '/' } != 1) continue
                val folder = path.substringBefore('/')
                val file = path.substringAfter('/')
                require(safeLibraryComponent(folder) && safeLibraryComponent(file))
                if (file.endsWith(".brush") || file == ".empty") {
                    val members = folders.getOrPut(folder) { mutableListOf() }
                    if (file.endsWith(".brush")) members.add(file)
                }
            }
            require(folders.size <= MAX_SET_BRUSHES && folders.values.sumOf { it.size } in 1..MAX_SET_BRUSHES)
            var inflated = 0L
            val sets = libraryOrder(folders.keys.toList(), files.getValue(top)).map { folder ->
                val prefix = "$folder/"
                val meta = files.keys.filter { it.startsWith(prefix) && libraryMetaName(it.removePrefix(prefix)) }
                require(meta.size <= 1)
                val names = libraryOrder(folders.getValue(folder), meta.singleOrNull()?.let(files::get))
                val brushes = names.mapNotNull { file ->
                    val decoded = readSingle(files.getValue(prefix + file)) { n ->
                        inflated += n
                        require(inflated <= MAX_SET_INFLATED_BYTES)
                    }
                    // readSingle handles malformed members locally, but a shared budget failure
                    // rejects the entire library, even if the last member exhausted it.
                    require(inflated <= MAX_SET_INFLATED_BYTES)
                    decoded
                }
                ImportedSet(folder.trim().take(128), brushes, names.size - brushes.size)
            }
            ImportedLibrary(sets)
        } catch (_: Exception) {
            null
        }
    }

    private fun libraryMetaName(name: String): Boolean =
        name.matches(Regex("""\.procreate_meta\.[0-9a-fA-F]{32}"""))

    private fun safeLibraryComponent(name: String): Boolean =
        name.isNotBlank() && name.length <= 256 && name !in setOf(".", "..") &&
            '/' !in name && '\\' !in name && '\u0000' !in name

    /** Unlisted members follow listed members in archive order; opaque properties are ignored. */
    private fun libraryOrder(names: List<String>, meta: ByteArray?): List<String> {
        if (meta == null) return names
        val end = meta.indexOf(0.toByte()).let { if (it < 0) meta.size else it }
        val lines = meta.copyOfRange(0, end).decodeToString(throwOnInvalidSequence = true).lineSequence()
        val positions = mutableMapOf<String, Long>()
        for ((index, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            val fields = line.split('/')
            if (fields.size != 3) continue
            val offset = fields[1].trim().toIntOrNull() ?: continue
            positions.putIfAbsent(fields[0], index.toLong() + offset)
        }
        return names.sortedBy { positions[it] ?: Long.MAX_VALUE }
    }

    /** Reads manifest fields without following external XML entities or keyed-archive references. */
    private fun setManifest(bytes: ByteArray): Pair<String, List<String>> {
        val (name, folders) = if (bytes.take(8).toByteArray().decodeToString() == "bplist00") {
            val plist = BinaryPlist(bytes)
            val fields = plist.dictionary(plist.top)
            val title = fields["name"]?.let(plist::string)
            val ids = plist.array(fields.getValue("brushes"))
            require(ids.size <= MAX_SET_BRUSHES)
            title to ids.map { requireNotNull(plist.string(it)) }
        } else {
            xmlSetManifest(bytes)
        }
        require(folders.size in 1..MAX_SET_BRUSHES && folders.distinct().size == folders.size)
        require(folders.all { it.isNotBlank() && it.length <= 256 && it !in setOf(".", "..") &&
            '/' !in it && '\\' !in it && '\u0000' !in it })
        return (name?.trim()?.take(128)?.takeIf { it.isNotBlank() } ?: "Imported") to folders
    }

    /** Accepts the standard Apple plist declaration but never passes a DTD to the XML parser. */
    private fun xmlSetManifest(bytes: ByteArray): Pair<String?, List<String>> {
        val appleDtd = Regex("""<!DOCTYPE\s+plist\s+PUBLIC\s+["']-//Apple(?: Computer)?//DTD PLIST 1\.0//EN["']\s+["']https?://www\.apple\.com/DTDs/PropertyList-1\.0\.dtd["']\s*>""")
        val xml = bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF").replace(appleDtd, "")
        // Reject all other markup declarations, including internal subsets and entities.
        require("<!" !in xml)
        val builder = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
        builder.setEntityResolver { _, _ -> error("External XML entities are forbidden") }
        val root = builder.parse(org.xml.sax.InputSource(java.io.StringReader(xml))).documentElement
        fun children(element: org.w3c.dom.Element): List<org.w3c.dom.Element> =
            (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? org.w3c.dom.Element }
        require(root.tagName == "plist")
        val dict = children(root).single()
        require(dict.tagName == "dict")
        val nodes = children(dict)
        require(nodes.size % 2 == 0)
        val fields = mutableMapOf<String, org.w3c.dom.Element>()
        for ((key, value) in nodes.chunked(2)) {
            require(key.tagName == "key" && children(key).isEmpty())
            require(fields.put(key.textContent, value) == null)
        }
        val array = fields.getValue("brushes")
        require(array.tagName == "array")
        val ids = children(array)
        require(ids.size <= MAX_SET_BRUSHES && ids.all { it.tagName == "string" && children(it).isEmpty() })
        val name = fields["name"]?.also { require(it.tagName == "string" && children(it).isEmpty()) }?.textContent
        return name to ids.map { it.textContent }
    }

    private fun decode(bytes: ByteArray, images: Map<String, ByteArray>): Imported {
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
        fun string(key: String): String? = scalar(key)?.let { ref ->
            plist.string(ref) ?: plist.dictionaryOrNull(ref)?.get("NS.string")?.let(::resolve)?.let(plist::string)
        }?.takeUnless { it == "\$null" }?.takeIf { it.isNotBlank() }
        fun curve(key: String): BrushResponseCurve = runCatching {
            val curve = plist.dictionary(scalar(key) ?: return@runCatching BrushResponseCurve.LINEAR)
            val points = plist.dictionary(resolve(curve.getValue("points")))
            val refs = plist.array(resolve(points.getValue("NS.objects")))
            require(refs.size in 2..MAX_CURVE_POINTS)
            val parsed = refs.mapNotNull { ref ->
                val value = plist.string(resolve(ref)) ?: return@mapNotNull null
                val coords = value.removePrefix("{").removeSuffix("}").split(',')
                if (coords.size != 2) return@mapNotNull null
                val x = coords[0].trim().toFloatOrNull() ?: return@mapNotNull null
                val y = coords[1].trim().toFloatOrNull() ?: return@mapNotNull null
                if (!x.isFinite() || !y.isFinite()) return@mapNotNull null
                BrushCurvePoint(x, y)
            }.distinctBy { it.x.coerceIn(0f, 1f) }
            require(parsed.size >= 2)
            BrushResponseCurve(parsed).sanitized()
        }.getOrDefault(BrushResponseCurve.LINEAR)
        val overall = curve("dynamicsPressureOverall")
        val dynamics = listOfNotNull(
            pressureBinding(number("dynamicsPressureSize"), BrushParameter.SIZE, overall,
                curve("dynamicsPressureSizeCurve")),
            pressureBinding(number("dynamicsPressureOpacity"), BrushParameter.OPACITY, overall,
                curve("dynamicsPressureOpacityCurve")),
        )
        val name = string("name")?.trim()?.take(128)?.takeIf { it.isNotBlank() } ?: "Imported Brush"
        var missingTexture = false
        fun texture(key: String, defaultName: String, inverted: Boolean): Texture? {
            val name = string(key)
            val data = if (name == null) images[defaultName] else images[name] ?: images[name.substringAfterLast('/')]
            if (name != null && data == null) missingTexture = true
            return data?.let { Texture(it, inverted) }
        }
        val shape = texture("bundledShapePath", "Shape.png", (number("shapeInverted") ?: 0.0) >= 0.5)
        val oldGrain = number("grainOrientation") == null && number("textureOrientation") != null && number("textureApplication") == 1.0
        val grain = texture("bundledGrainPath", "Grain.png", !oldGrain && (number("textureInverted") ?: 0.0) >= 0.5)
        require(root.keys.any { it in setOf("plotSpacing", "shapeCount", "shapeRoundness", "dynamicsJitterSize", "dynamicsJitterOpacity") })
        // ProCraft converts plotSpacing to its control with a 2.2 power and then back to
        // physical spacing with the same power. The resulting diameter fraction is
        // 0.0083 + sqrt(2) * raw, using its studio.rs floor and span.
        val brush = AzphaltBrush(
            name = name,
            spacing = number("plotSpacing")?.coerceIn(0.0, 2.0)?.let { (0.0083 + sqrt(2.0) * it).toFloat() } ?: 0.1f,
            isotropicSpacing = false,
            tipRatio = number("shapeRoundness")?.toFloat() ?: 1f,
            sizeJitter = number("dynamicsJitterSize")?.toFloat() ?: 0f,
            opacityJitter = number("dynamicsJitterOpacity")?.toFloat() ?: 0f,
            dynamics = dynamics,
            count = number("shapeCount")?.coerceIn(0.0, 1.0)?.let { floor(it * 15.0).toInt() + 1 } ?: 1,
            countJitter = number("shapeCountJitter")?.toFloat() ?: 0f,
            grainStrength = number("grainDepth")?.toFloat() ?: 1f,
            grainBehavior = if (number("textureApplication") == 1.0)
                com.hereliesaz.graffitixr.common.azphalt.GrainBehavior.CANVAS_LOCKED
                else com.hereliesaz.graffitixr.common.azphalt.GrainBehavior.MOVING,
        ).sanitized()
        return Imported(brush, shape, grain, missingTexture)
    }

    /**
     * ProCraft applies the overall pressure response before the target-specific response. Signed
     * amount controls attenuation: positive reduces at low pressure, negative at high pressure.
     * Sample the composed piecewise-linear approximation into the existing deterministic engine
     * curve; no separate Procreate renderer or device-telemetry path is introduced.
     */
    private fun pressureBinding(
        rawAmount: Double?,
        parameter: BrushParameter,
        overall: BrushResponseCurve,
        target: BrushResponseCurve,
    ): BrushSensorBinding? {
        val amount = rawAmount?.coerceIn(-1.0, 1.0)?.toFloat() ?: return null
        if (amount == 0f) return null
        val points = (0..PRESSURE_CURVE_STEPS).map { step ->
            val x = step.toFloat() / PRESSURE_CURVE_STEPS
            BrushCurvePoint(x, target.evaluate(overall.evaluate(x)))
        }
        return BrushSensorBinding(
            sensor = BrushSensor.PRESSURE,
            parameter = parameter,
            outputMin = if (amount > 0f) 1f - amount else 1f,
            outputMax = if (amount > 0f) 1f else 1f + amount,
            curve = BrushResponseCurve(points),
        )
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
