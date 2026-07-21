// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/importer/DocumentImporter.kt
package com.hereliesaz.graffitixr.common.importer

/**
 * The kinds of design document the app can be handed to open. Detection is by file magic first
 * (reliable across renamed files and content-provider URIs with no name), falling back to the
 * extension. The Android layer maps each kind to a concrete decode strategy.
 */
enum class DocumentFormat {
    /** Adobe Photoshop — decoded into discrete layers by [PsdReader]. */
    PSD,

    /** PDF, and modern Adobe Illustrator `.ai` (which embeds a PDF-compatible stream). Flattened
     *  by the Android `PdfRenderer` — one imported layer per page. */
    PDF,

    /** Procreate archive (a ZIP). Its embedded composite is imported as a single flattened layer. */
    PROCREATE,

    /** Canva has no distributable local file format; documents live in the cloud and are exported to
     *  PNG/PDF/JPG. Surfaced so the app can explain this rather than silently fail. */
    CANVA,

    /** An ordinary raster image (PNG/JPEG/WebP/GIF) — handled by the normal add-image path. */
    IMAGE,

    UNKNOWN,
}

/**
 * Sniffs a handed-in file and, for the formats decodable without Android (PSD), parses it into an
 * [ImportedDocument]. Everything here is JVM-testable; the Android boundary supplies the bytes and
 * turns the result into `Bitmap`-backed editor layers, and owns the `PdfRenderer`/ZIP strategies for
 * the other [DocumentFormat]s.
 */
object DocumentImporter {

    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    /** Classifies a file from its name and leading [header] bytes (magic wins over extension). */
    fun detect(fileName: String?, header: ByteArray): DocumentFormat {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when {
            starts(header, '8'.code, 'B'.code, 'P'.code, 'S'.code) || ext == "psd" || ext == "psb" -> DocumentFormat.PSD
            starts(header, '%'.code, 'P'.code, 'D'.code, 'F'.code) -> DocumentFormat.PDF
            ext == "ai" || ext == "pdf" -> DocumentFormat.PDF
            isZip(header) && ext == "procreate" -> DocumentFormat.PROCREATE
            ext == "procreate" -> DocumentFormat.PROCREATE
            ext == "canva" -> DocumentFormat.CANVA
            isRasterImage(header) || ext in IMAGE_EXTS -> DocumentFormat.IMAGE
            else -> DocumentFormat.UNKNOWN
        }
    }

    /**
     * Parses [bytes] into discrete layers for the formats this pure module can decode (currently
     * PSD). Returns null for formats that need an Android decoder (PDF/Procreate) or that carry no
     * layers — the caller then falls back accordingly.
     */
    fun readLayered(fileName: String?, bytes: ByteArray): ImportedDocument? =
        if (detect(fileName, bytes) == DocumentFormat.PSD && PsdReader.isPsd(bytes)) {
            PsdReader.read(bytes)
        } else {
            null
        }

    private fun isZip(h: ByteArray): Boolean =
        starts(h, 'P'.code, 'K'.code, 0x03, 0x04) ||
            starts(h, 'P'.code, 'K'.code, 0x05, 0x06) || // empty archive
            starts(h, 'P'.code, 'K'.code, 0x07, 0x08)    // spanned

    private fun isRasterImage(h: ByteArray): Boolean =
        starts(h, 0x89, 'P'.code, 'N'.code, 'G'.code) ||  // PNG
            starts(h, 0xFF, 0xD8, 0xFF) ||                 // JPEG
            starts(h, 'G'.code, 'I'.code, 'F'.code, '8'.code) || // GIF
            starts(h, 'R'.code, 'I'.code, 'F'.code, 'F'.code)    // RIFF/WebP container

    private fun starts(h: ByteArray, vararg magic: Int): Boolean {
        if (h.size < magic.size) return false
        for (i in magic.indices) if ((h[i].toInt() and 0xFF) != (magic[i] and 0xFF)) return false
        return true
    }
}
