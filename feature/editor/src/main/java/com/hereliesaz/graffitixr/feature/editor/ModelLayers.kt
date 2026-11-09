@file:Suppress("MagicNumber", "ReturnCount", "SwallowedException")

package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.scene.ModelLayerContent
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** The co-op wire form of a model's OBJ text (spec/3d-space.md, "3D model layers", Co-op). */
internal object ModelCoopCodec {
    /** The largest OBJ a peer may send, inflated: a scanned mesh, not an archive bomb. */
    const val MAX_OBJ_BYTES = 64 * 1024 * 1024

    /** The largest texture side a peer may send. */
    const val MAX_TEXTURE_SIDE = 4096

    fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        return try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream(bytes.size / 4 + 64)
            val buf = ByteArray(64 * 1024)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** The OBJ bytes, or null when corrupt or larger than [MAX_OBJ_BYTES]. */
    fun inflate(deflated: ByteArray): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(deflated)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                out.write(buf, 0, n)
                if (out.size() > MAX_OBJ_BYTES) return null
            }
            out.toByteArray()
        } catch (e: java.util.zip.DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    /**
     * Whether [png] is a whole PNG: the signature, then chunks that each fit in the file, with an IHDR and an
     * IDAT before an IEND that ends it. A partial write keeps an intact header and loses the end, which a
     * bounds-only decode would accept; a paint that then decodes to nothing would restart blank and be
     * saved over the file. Checks structure only, without allocating a bitmap.
     */
    fun isCompletePng(png: ByteArray): Boolean {
        val signature = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (png.size < signature.size + 12 || signature.indices.any { (png[it].toInt() and 0xFF) != signature[it] }) {
            return false
        }
        var pos = signature.size
        var sawHeader = false
        var sawData = false
        var complete = false
        while (!complete && pos + 12 <= png.size) {
            val length = ((png[pos].toInt() and 0xFF) shl 24) or ((png[pos + 1].toInt() and 0xFF) shl 16) or
                ((png[pos + 2].toInt() and 0xFF) shl 8) or (png[pos + 3].toInt() and 0xFF)
            if (length < 0 || pos + 12L + length > png.size) return false
            when (String(png, pos + 4, 4, Charsets.ISO_8859_1)) {
                "IHDR" -> sawHeader = true
                "IDAT" -> sawData = true
                "IEND" -> complete = sawHeader && sawData
            }
            pos += 12 + length
        }
        return complete
    }

    /** Whether [png]'s header names a texture no larger than [MAX_TEXTURE_SIDE] a side. */
    fun textureInBounds(png: ByteArray): Boolean {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size, o)
        return o.outWidth in 1..MAX_TEXTURE_SIDE && o.outHeight in 1..MAX_TEXTURE_SIDE
    }
}

/** The project-folder file names of a model layer: its OBJ and its painted texture. */
internal object ModelFiles {
    fun objFor(layerId: String) = "model_$layerId.obj"

    fun textureFor(layerId: String) = "model_tex_$layerId.png"

    fun safe(name: String?): String? = name?.takeIf { ModelLayerContent.isSafeFileName(it) }

    /** All of [input], or an [java.io.IOException] as soon as it holds more than [max] bytes. */
    fun readBounded(input: java.io.InputStream, max: Int = ModelCoopCodec.MAX_OBJ_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw java.io.IOException("That model file is larger than ${max / (1024 * 1024)} MB")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** [file]'s text, or an [java.io.IOException] when it is longer than [max] bytes. */
    fun readTextBounded(file: java.io.File, max: Int = ModelCoopCodec.MAX_OBJ_BYTES): String {
        if (file.length() > max) throw java.io.IOException("That model file is larger than ${max / (1024 * 1024)} MB")
        return file.inputStream().use { readBounded(it, max) }.decodeToString()
    }
}
