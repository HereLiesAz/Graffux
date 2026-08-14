package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.AzphaltManifest
import com.hereliesaz.graffitixr.common.azphalt.SignatureStatus
import java.io.File

/**
 * An azphalt extension that has been verified and unpacked into app storage under
 * `filesDir/extensions/<id>/`. [manifest] is the parsed manifest.json; [dir] is the absolute path to
 * the unpacked tree; [installedAt] is epoch millis; [signature] is the provenance established at
 * install (and re-derived on rescan) — a valid signature is tamper-evidence, trust is separate.
 */
data class InstalledExtension(
    val manifest: AzphaltManifest,
    val dir: String,
    val installedAt: Long,
    val signature: SignatureStatus = SignatureStatus.UNSIGNED,
) {
    val id: String get() = manifest.id

    /**
     * Absolute path to a payload file within the unpacked extension tree — an asset's LUT, a brush's
     * stamp/grain image, or [AzphaltManifest.entry] itself.
     *
     * [relative] is attacker-controlled: AzpInstaller's zip-entry-name checks (path traversal,
     * duplicate entries, digest verification) only ever ran against the archive's own entry names,
     * never against these manifest-declared strings, which are read back out and resolved here long
     * after install. Naive concatenation let a package with a manifest entry of `../../../../data/
     * data/<pkg>/...` — signed or not, since none of the install-time checks touch this field —
     * execute or read arbitrary files on the device via [ExtensionRepository]'s callers. Resolve
     * against the real filesystem and refuse anything that lands outside [dir], returning null rather
     * than a path a caller might use anyway.
     */
    fun filePath(relative: String): String? {
        val root = File(dir).canonicalFile
        val candidate = File(root, relative).canonicalFile
        val rootWithSeparator = root.path + File.separator
        return if (candidate.path == root.path || candidate.path.startsWith(rootWithSeparator)) {
            candidate.path
        } else {
            null
        }
    }
}
