package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.AZPHALT_SPEC_VERSION
import com.hereliesaz.graffitixr.common.azphalt.AzphaltManifest
import com.hereliesaz.graffitixr.common.azphalt.AzpSignatures
import com.hereliesaz.graffitixr.common.azphalt.ExtensionKind
import com.hereliesaz.graffitixr.common.azphalt.SignatureStatus
import com.hereliesaz.graffitixr.common.azphalt.TrustStore
import com.hereliesaz.graffitixr.common.azphalt.isCompatibleSpec
import com.hereliesaz.graffitixr.common.azphalt.parseManifest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Reads, verifies, and unpacks `.azp` packages (the ZIP container from azphalt spec/package-format.md)
 * into `<extensionsRoot>/<id>/`. Enforces the host's safety obligations:
 *  - reject unsafe entry paths (absolute, `..` traversal),
 *  - verify every payload file listed in the manifest's `files` map against its SHA-256 digest,
 *  - require a manifest.json.
 *
 * Signature (Ed25519 over the verbatim `manifest.json`) is verified when present: a package carrying
 * an invalid signature is refused (tamper-evidence), while an unsigned or signed-but-untrusted package
 * installs with its provenance recorded on [InstalledExtension.signature]. Trust (identity) is decided
 * against [trustStore] — directly, or via a registry counter-signature (spec/package-format.md § Signing).
 */
class AzpInstaller(
    private val extensionsRoot: File,
    private val trustStore: TrustStore = TrustStore.EMPTY,
) {

    class InstallException(message: String) : Exception(message)

    companion object {
        /** Cumulative decompressed-size ceiling for a `.azp` — a zip-bomb guard on untrusted input.
         *  Asset packages are small (bundled multi-GB models use `remoteUrl`, not the archive). */
        const val MAX_PACKAGE_BYTES: Long = 64L * 1024 * 1024
    }

    /**
     * Verify and unpack a `.azp` from [input] (a ZIP stream). Returns the [InstalledExtension].
     * Overwrites any prior install of the same id. Throws [InstallException] on any safety/integrity
     * failure, leaving no partial install for that id.
     */
    fun install(input: InputStream, nowMs: Long, allowPublisherChange: Boolean = false): InstalledExtension {
        // Read the whole archive into memory (we must parse the manifest to know the digests before we
        // trust any file). The source can be an attacker-controlled URL, so bound the CUMULATIVE
        // decompressed size while streaming and abort a zip bomb before it can OOM the app — never
        // `readBytes()` an entry unbounded.
        val entries = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L
        val chunk = ByteArray(64 * 1024)
        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val name = e.name
                    if (isUnsafePath(name)) throw InstallException("Unsafe path in package: $name")
                    // ZIP confusion: an archive may carry two entries under one name with different
                    // content, and which one a reader keeps is a matter of implementation. Two
                    // readers then disagree about what the package *is* — a scanner verifies the
                    // first copy while an installer writes the second — and the manifest digest
                    // attests to whichever one happened to win. This used to be caught only by
                    // accident: the map below keeps the last copy, so the conformance fixture
                    // tripped the digest check purely because its bad copy was packed second.
                    // Reverse the two and the same archive installed cleanly.
                    if (entries.containsKey(name)) {
                        throw InstallException("Duplicate entry in package: $name")
                    }
                    val out = ByteArrayOutputStream()
                    while (true) {
                        val n = zip.read(chunk)
                        if (n < 0) break
                        totalBytes += n
                        if (totalBytes > MAX_PACKAGE_BYTES) {
                            throw InstallException("Package exceeds the ${MAX_PACKAGE_BYTES / (1024 * 1024)} MB limit")
                        }
                        out.write(chunk, 0, n)
                    }
                    entries[name] = out.toByteArray()
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }

        val manifestBytes = entries["manifest.json"]
            ?: throw InstallException("Package has no manifest.json")
        val manifest: AzphaltManifest = try {
            parseManifest(manifestBytes.decodeToString())
        } catch (t: Throwable) {
            throw InstallException("Invalid manifest.json: ${t.message}")
        }

        // Host policy (spec/ADOPTION.md). GraffitiXR runs extension code in a WASM sandbox.
        // It installs all known kinds (`asset`, `mixed`, `code`, `app`, `mcp`, `pack`).
        //  - `unknown` is refused.
        if (manifest.kind == ExtensionKind.UNKNOWN) {
            throw InstallException(
                "This host does not install unknown extensions; '${manifest.id}' is kind=${manifest.kind.name.lowercase()}"
            )
        }

        // Conformance: validate the declared spec compatibility against what this host implements.
        if (!isCompatibleSpec(manifest.compat)) {
            throw InstallException(
                "Package '${manifest.id}' needs azphalt ${manifest.compat}; host implements $AZPHALT_SPEC_VERSION"
            )
        }

        // The package format requires a LICENSE file; refuse a package that omits it.
        if (!entries.containsKey("LICENSE")) {
            throw InstallException("Package '${manifest.id}' is missing the required LICENSE file")
        }

        // Integrity: every file the manifest lists must be present and match its digest.
        for ((path, digest) in manifest.files) {
            val bytes = entries[path] ?: throw InstallException("Missing payload file: $path")
            // Exact string compare against the one form the spec defines: the literal prefix
            // `sha256-` followed by lowercase hex (package-format.md § Signing). The reference does
            // the same compare, so accepting more than it does is a silent divergence rather than
            // leniency — this host used to supply a missing prefix and compare case-insensitively,
            // and therefore installed two manifests the reference rejects. Nothing in the fixture
            // suite covers it, so the suite stayed green while the two disagreed.
            if ("sha256-" + sha256Hex(bytes) != digest) {
                throw InstallException("Digest mismatch for $path")
            }
        }

        // …and the converse: every payload the archive carries must be one the manifest listed. The
        // digest loop above only proves the *listed* files are the bytes they claim to be; on its own
        // it says nothing about an entry nobody declared. Skipping such an entry at unpack — which is
        // what this did — is not equivalent to refusing the package: the signature covers the manifest
        // and therefore only the files the manifest names, so an unlisted entry rides inside a
        // correctly-signed archive with nothing attesting to it. The conformance suite calls this out
        // (`unlisted-payload.azp` must fail `verify.ok`), and this host accepted it until the vendored
        // fixtures were run against it.
        //
        // manifest.json is the map itself and signature.json signs it, so neither can appear in it.
        for (path in entries.keys) {
            if (path == "manifest.json" || path == "signature.json") continue
            if (!manifest.files.containsKey(path)) {
                throw InstallException("Unlisted payload (no digest in manifest.files): $path")
            }
        }

        // Provenance: verify the detached Ed25519 signature (if any) over the *verbatim* manifest.json
        // bytes. A present-but-invalid signature is tamper-evidence — refuse it. An unsigned or
        // signed-but-untrusted package installs, with its status recorded for the UI to warn on.
        val signatureJson = entries["signature.json"]?.decodeToString()
        val signatureStatus = AzpSignatures.evaluate(manifestBytes, signatureJson, trustStore)
        if (signatureStatus == SignatureStatus.INVALID) {
            throw InstallException("Package '${manifest.id}' has an invalid signature (tampered or corrupt)")
        }

        // Publisher continuity (spec/package-format.md § Publisher continuity). Overwriting an existing
        // install of this id IS an update, so this host MUST enforce that the update comes from the same
        // signer pinned on first install (trust-on-first-use) — otherwise a third party could replace an
        // installed extension via a same-id package. A different signer key, or a signed→unsigned
        // regression, is refused as a *publisher change* unless the caller passed [allowPublisherChange]
        // (an explicit user-approved key rotation, after which the overwrite re-pins to the new key).
        enforcePublisherContinuity(manifest.id, signatureJson, allowPublisherChange)

        // Unpack into a dot-prefixed staging dir first, then atomically swap it into place — so an
        // IOException mid-unpack (or a path-escape) can never leave a partial <id>/ install, honouring
        // the "no partial install" contract. The staging name starts with '.' so a concurrent rescan
        // skips it. (The rescan filter in ExtensionRepository ignores dot-prefixed dirs.)
        val dir = File(extensionsRoot, safeId(manifest.id))
        val staging = File(extensionsRoot, ".staging-${safeId(manifest.id)}-$nowMs")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        try {
            for ((path, bytes) in entries) {
                // Only unpack manifest.json, the detached signature.json (exempt from the files map, so
                // provenance can be re-derived on rescan), and files the manifest declares (which passed
                // the digest check above). An unlisted entry is an unverified payload — never write it.
                if (path != "manifest.json" && path != "signature.json" && !manifest.files.containsKey(path)) continue
                val target = File(staging, path)
                // Second-line defence: the resolved target must stay inside the staging dir.
                if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                    throw InstallException("Path escapes extension dir: $path")
                }
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
            // Swap in: move any prior install aside first — NOT delete-then-rename — so a renameTo
            // failure (File.renameTo is documented as unreliable; this file's own atomic-write paths
            // elsewhere already assume it can fail) restores the working extension instead of leaving
            // it permanently gone, which would otherwise contradict this function's "no partial
            // install" contract for what should be a pure version update.
            val backup = File(extensionsRoot, ".backup-${safeId(manifest.id)}-$nowMs")
            val hadPriorInstall = dir.exists()
            if (hadPriorInstall && !dir.renameTo(backup)) {
                throw InstallException("Failed to stage prior install of '${manifest.id}' aside for swap")
            }
            if (!staging.renameTo(dir)) {
                if (hadPriorInstall) backup.renameTo(dir) // best-effort restore of the working install
                throw InstallException("Failed to finalize install for '${manifest.id}'")
            }
            if (hadPriorInstall) backup.deleteRecursively()
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }

        return InstalledExtension(
            manifest = manifest,
            dir = dir.absolutePath,
            installedAt = nowMs,
            signature = signatureStatus,
        )
    }

    /**
     * Enforce publisher continuity for a same-id reinstall/update (spec/package-format.md). The signer
     * pinned on the prior install is re-derived from its retained `signature.json`. Rules:
     *  - no prior install, or a prior install that pinned nothing (was unsigned) → nothing to enforce;
     *  - otherwise the new package's signer key MUST equal the pinned key. A missing signature
     *    (signed→unsigned regression) or a different key is a publisher change and is refused, unless
     *    [allow] (an explicit user-approved rotation) is set — the overwrite then re-pins to the new key.
     */
    private fun enforcePublisherContinuity(id: String, newSignatureJson: String?, allow: Boolean) {
        val dir = File(extensionsRoot, safeId(id))
        if (!dir.isDirectory) return // first install of this id — nothing pinned yet
        val priorSig = File(dir, "signature.json")
        val pinnedKey = if (priorSig.exists()) AzpSignatures.parse(priorSig.readText())?.publicKey else null
        if (pinnedKey == null) return // prior install pinned nothing (unsigned) — spec allows the update
        if (allow) return // user approved a publisher change (key rotation); the overwrite re-pins
        val newKey = AzpSignatures.parse(newSignatureJson)?.publicKey
        if (newKey == null) {
            throw InstallException(
                "Publisher change refused for '$id': the installed version is signed, but this update is unsigned"
            )
        }
        if (newKey != pinnedKey) {
            throw InstallException(
                "Publisher change refused for '$id': signed by a different key than the installed version"
            )
        }
    }

    private fun isUnsafePath(name: String): Boolean {
        if (name.startsWith("/") || name.startsWith("\\") || name.contains(":")) return true
        return name.split('/', '\\').any { it == ".." }
    }

    // Reverse-DNS ids are filesystem-safe, but defend anyway: keep only [A-Za-z0-9._-].
    private fun safeId(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
