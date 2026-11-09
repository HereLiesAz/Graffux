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
    fun write(file: File, data: ByteArray) = write(file, true) { it.write(data) }

    /**
     * Replaces [file] with what [produce] writes. Throws [IOException] on failure; [file] is then unchanged.
     * With [createParent] false the parent directory MUST already exist: a write can then never recreate a directory
     * that was deleted meanwhile (a deleted project's folder), it just fails.
     */
    fun write(file: File, createParent: Boolean = true, produce: (OutputStream) -> Unit) {
        val dir = file.absoluteFile.parentFile ?: throw IOException("no parent directory for ${file.name}")
        if (createParent) dir.mkdirs()
        if (!dir.isDirectory) throw IOException("directory for ${file.name} does not exist")
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

    /**
     * Makes a rename or delete in [dir] durable. Returns false when the filesystem will not let a directory be
     * opened and synced (the caller then relies on the in-place invalidation of [deleteDurably] instead).
     */
    fun syncDirectory(dir: File): Boolean =
        runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }.isSuccess

    /** Empties [file] in place and syncs it (no directory change needed). False if that could not be done. */
    fun emptyInPlace(file: File): Boolean =
        runCatching { FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.truncate(0); it.force(true) } }
            .isSuccess

    /**
     * Deletes [file] so the deletion survives power loss: its bytes are first emptied IN PLACE and synced (an empty
     * file is invalid wherever the file is read as data, even if the unlink itself is lost), then it is unlinked and
     * the directory synced. True if the file is gone (or never existed); a file that could not be removed is left
     * empty, never half there.
     */
    fun deleteDurably(file: File): Boolean {
        if (!file.exists()) return true
        emptyInPlace(file)
        val gone = file.delete() || !file.exists()
        file.absoluteFile.parentFile?.let { syncDirectory(it) }
        return gone
    }
}
