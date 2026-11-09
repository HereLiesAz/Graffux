package com.hereliesaz.graffitixr.common.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Replacing a file so a crash or a full disk leaves the old file or the new one, never a torn mix.
 *
 * The bytes go to a uniquely named temp file beside the target and are **synced to disk** (`fsync`) before the temp
 * file is renamed over the target with an atomic move; the directory entry is then synced too (best effort). There is
 * no non-atomic fallback: if the rename cannot be done atomically the write fails with an [IOException] and the old
 * file is untouched, rather than a copy that could be interrupted half way and leave the target truncated.
 */
object AtomicFiles {
    /** Replaces [file] with [data]. Throws [IOException] if it could not be done; [file] is then unchanged. */
    fun write(file: File, data: ByteArray) = write(file) { it.write(data) }

    /** Replaces [file] with what [produce] writes. Throws [IOException] on failure; [file] is then unchanged. */
    fun write(file: File, produce: (OutputStream) -> Unit) {
        val dir = file.absoluteFile.parentFile ?: throw IOException("no parent directory for ${file.name}")
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                produce(out)
                out.flush()
                out.fd.sync()
            }
            replace(tmp, file)
            syncDirectory(dir)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun replace(tmp: File, target: File) {
        try {
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            throw IOException("could not replace ${target.name} atomically; the old file is unchanged", e)
        }
    }

    /** Makes the rename itself durable. Not every filesystem allows opening a directory: then it is skipped. */
    private fun syncDirectory(dir: File) {
        runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }
}
