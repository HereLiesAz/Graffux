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
}
