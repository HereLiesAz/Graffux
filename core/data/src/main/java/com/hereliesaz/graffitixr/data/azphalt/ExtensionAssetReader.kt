package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.data.azphalt.sandbox.AzphaltSandboxHost
import java.io.File
import java.io.IOException

/**
 * Path rules shared by install-time unpacking ([AzpInstaller]) and run-time asset reads
 * ([ExtensionAssetReader]) so the two can never drift apart.
 */
object ExtensionPaths {
    /**
     * True for any package-relative path this host refuses: empty, NUL-bearing, absolute (`/`, or a
     * drive/scheme `:`), containing a backslash anywhere, or containing a `..` segment.
     */
    fun isUnsafePath(name: String): Boolean =
        name.isEmpty() || name.contains('\u0000') ||
            name.startsWith("/") || name.contains('\\') || name.contains(":") ||
            name.split('/').any { it == ".." }
}

/**
 * Serves the `assets` capability for ONE installed extension: returns the bytes of a file inside
 * that extension's own install directory, or null when the read is missing or denied.
 *
 * A read is served only if the path:
 *  - passes [ExtensionPaths.isUnsafePath],
 *  - is a key of the manifest's `files` map (the digest-verified payload list; nothing unlisted),
 *  - canonicalises (symlinks resolved) to a regular file strictly inside the extension root, and
 *  - is at most [MAX_ASSET_READ_BYTES] long.
 */
class ExtensionAssetReader(private val extension: InstalledExtension) {

    companion object {
        /** Per-read ceiling. Larger bundled data belongs in a `remoteUrl` asset, not a host read. */
        const val MAX_ASSET_READ_BYTES: Long = 4L * 1024 * 1024
    }

    fun read(path: String): ByteArray? {
        if (ExtensionPaths.isUnsafePath(path) || !extension.manifest.files.containsKey(path)) return null
        return try {
            val root = File(extension.dir).canonicalFile
            val file = File(root, path).canonicalFile
            val inside = file.path.startsWith(root.path + File.separator)
            if (inside && file.isFile && file.length() <= MAX_ASSET_READ_BYTES) readBounded(file) else null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    // length() was checked, but the file could grow between check and read: never read past the cap.
    private fun readBounded(file: File): ByteArray? {
        val buf = ByteArray(MAX_ASSET_READ_BYTES.toInt() + 1)
        var total = 0
        file.inputStream().use { input ->
            while (total < buf.size) {
                val n = input.read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
            }
        }
        return if (total > MAX_ASSET_READ_BYTES) null else buf.copyOf(total)
    }
}

/**
 * Wraps a caller-supplied host so `assetRead` is answered from the invoking extension's own install
 * dir. Every other capability is delegated unchanged; capability gating stays in the sandboxes.
 */
class ExtensionScopedSandboxHost(
    private val delegate: AzphaltSandboxHost,
    private val assets: ExtensionAssetReader,
) : AzphaltSandboxHost by delegate {
    override fun assetRead(path: String): ByteArray? = assets.read(path)
}
