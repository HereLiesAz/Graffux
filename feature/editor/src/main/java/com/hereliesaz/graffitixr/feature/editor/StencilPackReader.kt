package com.hereliesaz.graffitixr.feature.editor

import android.graphics.BitmapFactory
import com.hereliesaz.graffitixr.common.importer.ImportedDocument
import com.hereliesaz.graffitixr.common.importer.ImportedLayer
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * StencilPack v1 receiver. Each PNG remains a separate editor layer in source paint order.
 * See https://github.com/HereLiesAz/stencil-artist/blob/main/docs/STENCILPACK_V1.md.
 *
 * All limits are enforced on inflated contents, not ZIP headers, to reject zip bombs.
 */
internal object StencilPackReader {
    private const val FORMAT = "com.hereliesaz.stencilpack"
    private const val MAX_BYTES = 48L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
    private const val MAX_LAYERS = 128
    private const val MAX_TOTAL_PIXELS = 24_000_000L

    fun read(bytes: ByteArray): ImportedDocument? = try {
        if (bytes.size.toLong() > MAX_BYTES || bytes.size < 4) return null
        val files = LinkedHashMap<String, ByteArray>()
        var used = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || name.isBlank() || name.startsWith("/") ||
                    name.contains("..") || name.contains('\\') || name.contains('/') ||
                    files.containsKey(name) || files.size >= MAX_LAYERS + 2
                ) return null
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val n = archive.read(chunk)
                    if (n < 0) break
                    used += n
                    if (buffer.size() + n > MAX_ENTRY_BYTES || used > MAX_BYTES) return null
                    buffer.write(chunk, 0, n)
                }
                files[name] = buffer.toByteArray()
            }
        }
        val manifestBytes = files["manifest.json"] ?: return null
        if (manifestBytes.size > 256 * 1024) return null
        val json = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        if (json.optString("format") != FORMAT || json.optInt("version") != 1 ||
            json.optString("order") != "bottom-to-top" ||
            json.optString("coverage") != "overpaint"
        ) return null
        val width = json.optInt("width")
        val height = json.optInt("height")
        if (width !in 1..8192 || height !in 1..8192) return null
        val spec = json.optJSONArray("layers") ?: return null
        if (spec.length() !in 1..MAX_LAYERS ||
            width.toLong() * height * spec.length() > MAX_TOTAL_PIXELS
        ) return null
        val filenames = HashSet<String>()
        val decoded = ArrayList<ImportedLayer>(spec.length())
        var decodedWidth = 0
        var decodedHeight = 0
        for (i in 0 until spec.length()) {
            val layer = spec.getJSONObject(i)
            val name = layer.optString("name").ifBlank { "Stencil sheet ${i + 1}" }.take(128)
            val path = layer.optString("file")
            if (!path.matches(Regex("stencil-sheet-[0-9]+[.]png")) || !filenames.add(path)) return null
            val png = files[path] ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
            if (bounds.outWidth != width || bounds.outHeight != height) return null
            val sample = maxOf(1, (maxOf(width, height) + 2047) / 2048)
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size, options) ?: return null
            try {
                if (i == 0) {
                    decodedWidth = bmp.width
                    decodedHeight = bmp.height
                }
                if (bmp.width != decodedWidth || bmp.height != decodedHeight) return null
                val argb = IntArray(bmp.width * bmp.height)
                bmp.getPixels(argb, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                decoded += ImportedLayer(
                    name = name,
                    argb = argb,
                    width = bmp.width,
                    height = bmp.height,
                    isVisible = layer.optBoolean("visible", true),
                )
            } finally {
                bmp.recycle()
            }
        }
        ImportedDocument(decodedWidth, decodedHeight, decoded)
    } catch (_: Exception) {
        null
    }
}
