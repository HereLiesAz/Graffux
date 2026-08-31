package com.hereliesaz.graffitixr.data.azphalt

import android.content.Context
import com.hereliesaz.graffitixr.common.azphalt.AssetType
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.AzpSignatures
import com.hereliesaz.graffitixr.common.azphalt.CubeLut
import com.hereliesaz.graffitixr.common.azphalt.ExtensionKind
import com.hereliesaz.graffitixr.common.azphalt.ExtensionState
import com.hereliesaz.graffitixr.common.azphalt.LutInputTransfer
import com.hereliesaz.graffitixr.common.azphalt.TrustedKey
import com.hereliesaz.graffitixr.common.azphalt.TrustStore
import com.hereliesaz.graffitixr.common.azphalt.parseCubeLut
import com.hereliesaz.graffitixr.common.azphalt.parseManifest
import com.hereliesaz.graffitixr.common.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The GraffitiXR side of the azphalt marketplace: install `.azp` packages (fetched by the host itself,
 * handed off from a store app, or picked from a file — see [installFromStream]), track what's
 * installed, and hand installed asset extensions (LUTs, brushes) to the editor. Browsing/acquiring is
 * delegated to a separate store app ([AzphaltStoreHandoff], spec/store-app.md) rather than built here —
 * this class's job starts once bytes exist, regardless of where they came from.
 *
 * The filesystem under `filesDir/extensions/<id>/` IS the installed-state — [installed] is rebuilt by
 * scanning it, so an install/uninstall survives process death with no separate index to fall out of
 * step with it. [ExtensionStateStore] sits beside that rather than duplicating it: it records what
 * this host *did* (spec/state-reporting.md), including the outcomes — removed, failed, downloaded —
 * that by definition have no directory left to scan.
 */
@Singleton
class ExtensionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    dispatcherProvider: DispatcherProvider,
) {
    private val extensionsRoot = File(context.filesDir, "extensions")

    /**
     * What this host has done with each package it acquired (spec/state-reporting.md). Kept apart
     * from the unpacked tree because three of the five reportable states — `removed`, `failed`,
     * `downloaded` — describe packages with no directory to scan.
     */
    private val stateStore = ExtensionStateStore(File(context.filesDir, ExtensionStateStore.FILE_NAME))

    /**
     * The inventory to send with a browse request, so a store's cards say *Open* rather than *Get*.
     *
     * The recorded states are the authority, but they only start accumulating from the first install
     * *after* this feature existed. Anything already unpacked under `extensions/` predates the record
     * and would otherwise be invisible — which would have made this whole channel a no-op for exactly
     * the users it is for: someone upgrading with three extensions installed would open a store and
     * be offered all three as new. So the installed set is folded in, and the recorded state wins
     * wherever both know a package.
     *
     * Folding in rather than back-filling the file on launch is deliberate: an install this host can
     * see on disk right now is a fact it can state without writing anything, and a migration that
     * writes on first launch has a failure mode (a bad write leaves a permanently wrong record) that
     * deriving does not.
     */
    fun stateInventory(): List<com.hereliesaz.graffitixr.common.azphalt.ExtensionStateEntry> {
        val recorded = stateStore.all()
        val known = recorded.map { it.id }.toHashSet()
        val derived = _installed.value
            .filterNot { it.id in known }
            .map {
                com.hereliesaz.graffitixr.common.azphalt.ExtensionStateEntry(
                    id = it.id,
                    version = it.manifest.version,
                    // Present and usable, which is what `active` means here — this host has no
                    // enable/disable toggle, so an installed extension is an available one.
                    state = ExtensionState.ACTIVE.wire,
                    at = null, // unknown: it was installed before anything was recording
                )
            }
        return recorded + derived
    }

    /**
     * The keys this host trusts (spec/package-format.md § Signing). Bootstrapped from the registry's
     * `.well-known/azphalt-repository.json` `signingKeys` on first contact, cached locally so it
     * survives offline launches, and refreshed in the background. A non-empty store transitively
     * trusts every author the registry counter-signs, so installed extensions' [SignatureStatus]
     * upgrades from SIGNED_UNTRUSTED to SIGNED_TRUSTED the moment the registry key arrives.
     */
    @Volatile
    private var trustStore: TrustStore = TrustStore.EMPTY

    @Volatile
    private var installer = AzpInstaller(extensionsRoot, trustStore)

    /** Serializes filesystem-mutating operations so concurrent install/uninstall can't interleave. */
    private val lock = Any()

    // App-lifetime scope for the one-shot initial scan off the injecting thread (see init below).
    private val ioScope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)

    // Start empty and populate on IO — scanInstalled() does disk IO + manifest parse + signature
    // evaluation, which must never run on whatever thread first injects this @Singleton.
    private val _installed = MutableStateFlow<List<InstalledExtension>>(emptyList())
    val installed: StateFlow<List<InstalledExtension>> = _installed.asStateFlow()

    init {
        ioScope.launch {
            synchronized(lock) {
                trustStore = loadCachedTrustStore()
                installer = AzpInstaller(extensionsRoot, trustStore)
                _installed.value = scanInstalled()
            }
            refreshTrustStore()
        }
    }

    fun isInstalled(id: String): Boolean = _installed.value.any { it.id == id }

    /**
     * Verify and unpack an `.azp` from an arbitrary [input] stream — a user-picked file (a
     * `content://` Uri from the file picker) or a package handed off from a store app
     * ([AzphaltStoreHandoff]). Buffers to a bounded temp file first (a zip-bomb guard), then
     * serializes the unpack + rescan. Throws on any integrity/safety failure. Runs blocking IO — call
     * from a background dispatcher.
     */
    fun installFromStream(
        input: InputStream,
        nowMs: Long,
        knownId: String? = null,
        knownVersion: String? = null,
    ): InstalledExtension {
        val tempFile = File.createTempFile("azp_", ".azp", context.cacheDir)
        try {
            tempFile.outputStream().use { out -> copyBounded(input, out, AzpInstaller.MAX_PACKAGE_BYTES) }
            val installed = synchronized(lock) {
                val result = tempFile.inputStream().use {
                    installer.install(
                        it,
                        nowMs,
                        unsupportedKinds = setOf(ExtensionKind.APP, ExtensionKind.MCP, ExtensionKind.PACK),
                    )
                }
                _installed.value = scanInstalled()
                result
            }
            // Graffux has no enable/disable toggle — an installed extension's LUTs and brushes are
            // available the moment it lands — so the honest state is `active`, not `installed`.
            stateStore.record(installed.id, installed.manifest.version, ExtensionState.ACTIVE, nowMs)
            return installed
        } catch (t: Throwable) {
            // A failure is worth reporting precisely because it is a user who wanted something and
            // did not get it. Only recordable when the id is known ahead of the manifest parse —
            // which, under delegated acquisition, the store told us (`azphalt.extra.ID`).
            if (knownId != null && t !is kotlinx.coroutines.CancellationException) {
                stateStore.record(
                    id = knownId,
                    // The version the store said it delivered. `version` is required on an entry and
                    // a store derives *Update* by comparing it to its catalogue, so an empty string
                    // — which this used to write, discarding a value it had been handed — compares
                    // to nothing. Falling back to whatever was last recorded for this id keeps the
                    // entry meaningful when the store told us nothing either.
                    version = knownVersion
                        ?: stateStore.stateOf(knownId)?.version
                        ?: "",
                    state = ExtensionState.FAILED,
                    atMs = nowMs,
                    reason = t.message,
                )
            }
            throw t
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Record that verified bytes are held but not yet installed (spec/state-reporting.md § 1). This
     * state exists only because delegated acquisition splits acquiring from installing: a host that
     * downloads for itself never sees bytes it hasn't committed to.
     */
    fun recordDownloaded(id: String, version: String, nowMs: Long) {
        stateStore.record(id, version, ExtensionState.DOWNLOADED, nowMs)
    }

    /**
     * Record that acquiring or installing [id] did not complete. For failures that never reach
     * [installFromStream] — an unopenable URI, a revoked read grant — which its own failure branch
     * cannot see.
     */
    fun recordFailed(id: String, version: String?, nowMs: Long, reason: String?) {
        stateStore.record(
            id = id,
            version = version ?: stateStore.stateOf(id)?.version ?: "",
            state = ExtensionState.FAILED,
            atMs = nowMs,
            reason = reason,
        )
    }

    /** Copy [input] to [out], aborting if it exceeds [maxBytes] (a compressed-download zip-bomb guard). */
    private fun copyBounded(input: InputStream, out: OutputStream, maxBytes: Long) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw AzpInstaller.InstallException(
                "Package download exceeds the ${maxBytes / (1024 * 1024)} MB limit"
            )
            out.write(buf, 0, n)
        }
    }

    fun uninstall(id: String, nowMs: Long = System.currentTimeMillis()) {
        val removed = synchronized(lock) {
            val ext = _installed.value.find { it.id == id } ?: return@synchronized null
            File(ext.dir).deleteRecursively()
            _installed.value = scanInstalled()
            ext
        }
        // `removed` rather than forgetting it: a store can then offer a reinstall instead of a first
        // purchase, which is the whole reason the state is distinct from never having had it.
        if (removed != null) {
            stateStore.record(id, removed.manifest.version, ExtensionState.REMOVED, nowMs)
        }
    }

    /**
     * Installed LUT asset extensions with a *usable* LUT. Only assets an asset host may use are counted:
     * `standalone != false` (a code-dependent asset in a mixed package is skipped, per spec § Mixed-package
     * asset independence) and locally present (a not-bundled `remoteUrl` LUT has a blank path).
     */
    fun installedLuts(): List<InstalledExtension> =
        _installed.value.filter { ext -> ext.manifest.assets.any(::isUsableLut) }

    /**
     * Load the first usable LUT of an installed extension, honouring the asset's `params.inputTransfer`
     * and `params.strength` (spec § LUT application), or null if it has none / fails to parse.
     */
    fun loadLut(id: String): CubeLut? {
        val ext = _installed.value.find { it.id == id } ?: return null
        val lutAsset = ext.manifest.assets.firstOrNull(::isUsableLut) ?: return null
        val path = ext.filePath(lutAsset.path) ?: return null
        val file = File(path)
        if (!file.exists()) return null
        val lut = runCatching { parseCubeLut(file.readText()) }.getOrNull() ?: return null
        val params = lutAsset.params
        val transfer = LutInputTransfer.fromWire((params?.get("inputTransfer") as? JsonPrimitive)?.contentOrNull)
        val strength = (params?.get("strength") as? JsonPrimitive)?.floatOrNull ?: 1f
        return lut.withInputTransfer(transfer).withStrength(strength)
    }

    /** A LUT asset this host can actually apply: standalone (not code-dependent) and bundled (has a path). */
    private fun isUsableLut(asset: com.hereliesaz.graffitixr.common.azphalt.AssetContribution): Boolean =
        asset.type == AssetType.LUT && asset.standalone && asset.path.isNotBlank()

    /**
     * Does this specific extension carry a LUT this host can apply?
     *
     * Same question as [installedLuts], asked of one record the caller already holds rather than of
     * the current install list. That distinction matters to anything mapping over a snapshot: reading
     * the live list mid-map answers about a *different* moment than the one being mapped.
     */
    fun hasUsableLut(ext: InstalledExtension): Boolean = ext.manifest.assets.any(::isUsableLut)

    /** Does this extension carry a brush this host can paint with? */
    fun hasUsableBrush(ext: InstalledExtension): Boolean = ext.manifest.assets.any(::isUsableBrush)

    /**
     * Installed brush asset extensions the editor can paint with. As with [installedLuts], only assets
     * an asset host may use count: `standalone != false` (a code-dependent brush in a mixed package is
     * skipped, per spec § Mixed-package asset independence). A brush's stamp is optional — a params-only
     * round tip is still usable — so, unlike a LUT, a blank `path` does not disqualify it.
     */
    fun installedBrushes(): List<InstalledExtension> =
        _installed.value.filter { ext -> ext.manifest.assets.any(::isUsableBrush) }

    /**
     * Parse the first usable brush of an installed extension into a normalized [AzphaltBrush] (the
     * declarative stamp + dynamics the editor renders), or null if it has none. The brush's name comes
     * from the manifest; its behaviour from the asset's `params` via [AzphaltBrush.fromParams].
     */
    fun loadBrush(id: String): AzphaltBrush? {
        val ext = _installed.value.find { it.id == id } ?: return null
        val asset = ext.manifest.assets.firstOrNull(::isUsableBrush) ?: return null
        return AzphaltBrush.fromParams(ext.manifest.name, asset.params)
    }

    /** A brush asset this host can paint with: standalone (not code-dependent). Stamp path is optional. */
    private fun isUsableBrush(asset: com.hereliesaz.graffitixr.common.azphalt.AssetContribution): Boolean =
        asset.type == AssetType.BRUSH && asset.standalone

    /**
     * Installed code or mixed extensions that contribute filters.
     */
    fun installedFilters(): List<InstalledExtension> =
        _installed.value.filter { ext -> ext.manifest.contributes?.filters?.isNotEmpty() == true }

    /**
     * Installed code or mixed extensions that contribute tools.
     */
    fun installedTools(): List<InstalledExtension> =
        _installed.value.filter { ext -> ext.manifest.contributes?.tools?.isNotEmpty() == true }

    /**
     * Absolute path to a bundled file [relative] within installed extension [id] (e.g. a brush's stamp
     * or grain image), or null if the extension isn't installed, the path is blank, or the file is
     * absent. The caller decodes it (a Bitmap decode belongs in the Android/editor layer, not here).
     */
    fun assetFilePath(id: String, relative: String): String? {
        if (relative.isBlank()) return null
        val ext = _installed.value.find { it.id == id } ?: return null
        val path = ext.filePath(relative) ?: return null
        val file = File(path)
        return if (file.exists()) file.absolutePath else null
    }

    /**
     * Executes a code extension's payload in an isolated sandbox, binding the given host capabilities.
     *
     * [entryPath] picks WHICH payload runs — a specific [com.hereliesaz.graffitixr.common.azphalt.Contribution.entry]
     * from the manifest's `contributes.filters`/`.tools`/`.commands` (each of those declares its own
     * entry file, distinct from the manifest's top-level one), so a multi-contribution extension can
     * run any one of its declared filters/tools/commands rather than always its single default. Null
     * falls back to the manifest's own top-level `entry` — the whole-module, single-purpose extension
     * this always ran before [entryPath] existed.
     */
    fun executeCodeExtension(
        id: String,
        host: com.hereliesaz.graffitixr.data.azphalt.sandbox.AzphaltSandboxHost,
        entryPath: String? = null,
    ) {
        val ext = _installed.value.find { it.id == id } ?: return
        if (ext.manifest.kind != com.hereliesaz.graffitixr.common.azphalt.ExtensionKind.CODE && ext.manifest.kind != com.hereliesaz.graffitixr.common.azphalt.ExtensionKind.MIXED) return

        val resolvedEntry = entryPath ?: ext.manifest.entry ?: return
        val resolvedPath = ext.filePath(resolvedEntry) ?: return
        val file = File(resolvedPath)
        if (!file.exists()) return
        
        val caps = ext.manifest.capabilities?.map { it.wire }?.toSet() ?: emptySet()
        
        when (ext.manifest.runtime) {
            com.hereliesaz.graffitixr.common.azphalt.Runtime.WASM -> {
                // WasmSandbox's constructor fully consumes the stream while parsing the module
                // (synchronously, inside its init block), so it's safe to close right after
                // construction — .use{} was previously skipped here, leaking a file descriptor per
                // invocation. The sandbox was also being built and immediately discarded with no
                // way to invoke it: .run() actually executes the module's entry point now.
                file.inputStream().use { stream ->
                    val wasmSandbox = com.hereliesaz.graffitixr.data.azphalt.sandbox.WasmSandbox(
                        stream,
                        host,
                        caps
                    )
                    wasmSandbox.run()
                }
            }
            com.hereliesaz.graffitixr.common.azphalt.Runtime.JS -> {
                val jsCode = file.readText()
                // Same leak as above: this asset stream was never closed.
                context.assets.open("wasm/quickjs.wasm").use { qjsWasmStream ->
                    val jsSandbox = com.hereliesaz.graffitixr.data.azphalt.sandbox.JsSandbox(
                        jsCode,
                        qjsWasmStream,
                        host,
                        caps
                    )
                    jsSandbox.eval()
                }
            }
            else -> {
                // Unsupported runtime
            }
        }
    }

    private fun scanInstalled(): List<InstalledExtension> {
        val root = extensionsRoot
        if (!root.isDirectory) return emptyList()
        // Skip dot-prefixed dirs — those are AzpInstaller's in-flight staging dirs, not installs.
        return root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().mapNotNull { dir ->
            val manifestFile = File(dir, "manifest.json")
            if (!manifestFile.exists()) return@mapNotNull null
            runCatching {
                // Re-derive provenance from the unpacked tree so it survives process death, using the
                // verbatim manifest bytes and the detached signature.json (if the package carried one).
                val manifestBytes = manifestFile.readBytes()
                val sigFile = File(dir, "signature.json")
                val signatureJson = if (sigFile.exists()) sigFile.readText() else null
                InstalledExtension(
                    manifest = parseManifest(manifestBytes.decodeToString()),
                    dir = dir.absolutePath,
                    installedAt = manifestFile.lastModified(),
                    signature = AzpSignatures.evaluate(manifestBytes, signatureJson, trustStore),
                )
            }.getOrNull()
        }.sortedBy { it.manifest.name }
    }

    // ── Trust store bootstrap (spec/repository-api.md § Trust bootstrap) ─────────────────────────

    private fun loadCachedTrustStore(): TrustStore {
        val file = File(context.filesDir, TRUST_CACHE_FILE)
        if (!file.exists()) return TrustStore.EMPTY
        val keys = runCatching { parseSigningKeys(file.readText()) }.getOrDefault(emptyList())
        return if (keys.isEmpty()) TrustStore.EMPTY else TrustStore(keys)
    }

    private fun refreshTrustStore() {
        try {
            val connection = URL(WELL_KNOWN_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.connect()
            if (connection.responseCode !in 200..299) return
            // Bounded the same way installFromStream's package download is (copyBounded): this
            // runs unattended on every app launch against a remote endpoint, unlike a user-
            // initiated install, so a compromised/misbehaving server streaming an unbounded or
            // enormous body must not be able to grow this read without limit.
            val out = ByteArrayOutputStream()
            connection.inputStream.use { copyBounded(it, out, MAX_TRUST_STORE_BYTES) }
            val body = out.toByteArray().decodeToString()
            val keys = parseSigningKeys(body)
            if (keys.isEmpty()) return
            val store = TrustStore(keys)
            if (store == trustStore) return
            // Same lock installFromStream/uninstall take before touching installer/_installed. This
            // runs once per launch after a blocking network round trip, unsynchronized -- an install
            // or uninstall landing in that window could have its own, already-correct _installed
            // write overwritten by this rescan afterward, the same last-writer-wins shape fixed
            // elsewhere in this app's async publishers (#244, #249).
            synchronized(lock) {
                File(context.filesDir, TRUST_CACHE_FILE).writeText(body)
                trustStore = store
                installer = AzpInstaller(extensionsRoot, store)
                _installed.value = scanInstalled()
            }
        } catch (_: Exception) {
            // Network unavailable — cached keys (if any) are already in use.
        }
    }

    private fun parseSigningKeys(json: String): List<TrustedKey> = runCatching {
        val root = Json.parseToJsonElement(json).jsonObject
        val arr = root["signingKeys"]?.jsonArray ?: return emptyList()
        arr.mapNotNull { el ->
            val obj = el.jsonObject
            val pk = obj["publicKey"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            TrustedKey(pk, obj["keyId"]?.jsonPrimitive?.contentOrNull, obj["label"]?.jsonPrimitive?.contentOrNull)
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val TRUST_CACHE_FILE = "azphalt-trust-keys.json"
        // The canonical `www.` host, not the bare domain -- see AzphaltStoreHandoff.WEB_STORE_URL:
        // the bare domain 308-redirects here, which this raw HttpURLConnection doesn't follow, so it
        // silently returned nothing (leaving every repository package permanently SIGNED_UNTRUSTED).
        private const val WELL_KNOWN_URL = "https://www.azphalt.store/.well-known/azphalt-repository.json"
        // A signing-key list is a handful of short base64 strings -- generous headroom over any
        // real payload, not a size this endpoint should ever need to approach.
        private const val MAX_TRUST_STORE_BYTES = 1L * 1024 * 1024
    }
}
