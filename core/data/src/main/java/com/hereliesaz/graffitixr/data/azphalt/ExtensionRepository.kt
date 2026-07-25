package com.hereliesaz.graffitixr.data.azphalt

import android.content.Context
import com.hereliesaz.graffitixr.common.azphalt.AssetType
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.AzpSignatures
import com.hereliesaz.graffitixr.common.azphalt.CubeLut
import com.hereliesaz.graffitixr.common.azphalt.LutInputTransfer
import com.hereliesaz.graffitixr.common.azphalt.TrustStore
import com.hereliesaz.graffitixr.common.azphalt.parseCubeLut
import com.hereliesaz.graffitixr.common.azphalt.parseManifest
import com.hereliesaz.graffitixr.common.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
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

/** Upper bound on a registry catalog JSON response — a guard against a hostile/oversized reply. */
private const val MAX_REGISTRY_RESPONSE_BYTES: Long = 16L * 1024 * 1024

/**
 * The GraffitiXR side of the azphalt marketplace: browse the catalog, install `.azp` packages, track
 * what's installed, and hand installed asset extensions (LUTs) to the editor. The filesystem under
 * `filesDir/extensions/<id>/` IS the installed-state — [installed] is rebuilt by scanning it, so an
 * install/uninstall survives process death with no separate index.
 */
@Singleton
class ExtensionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    dispatcherProvider: DispatcherProvider,
) {
    private val extensionsRoot = File(context.filesDir, "extensions")

    // The keys this host trusts. Empty for now — signed packages install as SIGNED_UNTRUSTED (valid
    // signature, no established identity) until a trust store is seeded (e.g. a registry's key from
    // .well-known). Wiring that source in is a follow-up; the verification path is already live.
    private val trustStore = TrustStore.EMPTY
    private val installer = AzpInstaller(extensionsRoot, trustStore)

    /** Serializes filesystem-mutating operations so concurrent install/uninstall can't interleave. */
    private val lock = Any()

    // App-lifetime scope for the one-shot initial scan off the injecting thread (see init below).
    private val ioScope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)

    // Start empty and populate on IO — scanInstalled() does disk IO + manifest parse + signature
    // evaluation, which must never run on whatever thread first injects this @Singleton.
    private val _installed = MutableStateFlow<List<InstalledExtension>>(emptyList())
    val installed: StateFlow<List<InstalledExtension>> = _installed.asStateFlow()

    init {
        ioScope.launch { _installed.value = scanInstalled() }
    }

    /** Client for the official azphalt store (https://azphalt.store). GET-only; browse/install use it. */
    private val storeRegistry = RepositoryClient(AzphaltStore.REGISTRY_BASE_URL, ::httpGetString)

    /**
     * Browse the official azphalt store (https://azphalt.store) — the live catalog, not a bundled seed.
     * Fetches the store's package list and maps each to a catalog card whose [MarketplaceEntry.source]
     * is its resolved `.azp` download URL, which [install] then fetches. Blocking IO — call from a
     * background dispatcher.
     *
     * Uses [RepositoryClient.search], not [RepositoryClient.listPackages]: at its canonical root path
     * the store answers with the repository-api.md envelope (`{packages,total,page,pages}`). The bare
     * array is what its storefront-internal `/api/packages` returns, which is a different surface.
     */
    fun browseStore(query: String? = null): List<MarketplaceEntry> =
        storeRegistry.search(q = query).packages.map { pkg ->
            pkg.toMarketplaceEntry(storeRegistry.downloadUrl(pkg.id, pkg.version))
        }

    /**
     * Browse an arbitrary azphalt registry that implements the paginated repository-api.md envelope
     * (`{packages,total,page,pages}`), rather than the official store's flat list. Blocking IO; call
     * from a background dispatcher.
     */
    fun catalogFromRegistry(
        client: RepositoryClient,
        query: String? = null,
        page: Int = 1,
    ): List<MarketplaceEntry> =
        client.search(q = query, page = page).packages.map { pkg ->
            pkg.toMarketplaceEntry(client.downloadUrl(pkg.id, pkg.version))
        }

    fun isInstalled(id: String): Boolean = _installed.value.any { it.id == id }

    /**
     * Fetch, verify, and unpack the entry's `.azp`. Throws on any fetch/integrity/safety failure.
     * Runs blocking IO — call from a background dispatcher.
     */
    fun install(entry: MarketplaceEntry, nowMs: Long): InstalledExtension {
        // Fetch OUTSIDE the lock so a slow/stalled download can't block uninstall or another install.
        // Buffer to a bounded temp file, then serialize only the filesystem-mutating unpack + rescan.
        val tempFile = File.createTempFile("azp_", ".azp", context.cacheDir)
        try {
            openSource(entry.source).use { input ->
                tempFile.outputStream().use { out -> copyBounded(input, out, AzpInstaller.MAX_PACKAGE_BYTES) }
            }
            requireAzpPackage(tempFile, entry)
            return synchronized(lock) {
                val installed = tempFile.inputStream().use { installer.install(it, nowMs) }
                _installed.value = scanInstalled()
                installed
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Verify and unpack an `.azp` from an arbitrary [input] stream — e.g. a user-picked file (a
     * `content://` Uri the caller opened). Buffers to a bounded temp file first (the same zip-bomb
     * guard as [install]), then serializes the unpack + rescan. Throws on any integrity/safety failure.
     * Runs blocking IO — call from a background dispatcher.
     */
    fun installFromStream(input: InputStream, nowMs: Long): InstalledExtension {
        val tempFile = File.createTempFile("azp_", ".azp", context.cacheDir)
        try {
            tempFile.outputStream().use { out -> copyBounded(input, out, AzpInstaller.MAX_PACKAGE_BYTES) }
            return synchronized(lock) {
                val installed = tempFile.inputStream().use { installer.install(it, nowMs) }
                _installed.value = scanInstalled()
                installed
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Reject anything that isn't actually an `.azp` before the installer opens it. A registry that
     * doesn't implement `/download` can still answer 200 — the azphalt store currently serves its
     * single-page-app `index.html` for every unmatched API path — and feeding that to the unpacker
     * surfaced as the baffling "Package has no manifest.json". An `.azp` is a zip, so check the local
     * file header magic and say plainly what arrived instead.
     */
    private fun requireAzpPackage(file: File, entry: MarketplaceEntry) {
        val header = ByteArray(4)
        val read = file.inputStream().use { it.read(header) }
        val isZip = read == 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
            header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
        if (!isZip) {
            throw AzpInstaller.InstallException(
                "${entry.name} isn't downloadable yet — ${entry.source} returned a web page, not a package."
            )
        }
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

    fun uninstall(id: String) {
        synchronized(lock) {
            val ext = _installed.value.find { it.id == id } ?: return@synchronized
            File(ext.dir).deleteRecursively()
            _installed.value = scanInstalled()
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
        val file = File(ext.filePath(lutAsset.path))
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
        val file = File(ext.filePath(relative))
        return if (file.exists()) file.absolutePath else null
    }

    /**
     * Executes a code extension's payload in an isolated sandbox, binding the given host capabilities.
     */
    fun executeCodeExtension(id: String, host: com.hereliesaz.graffitixr.data.azphalt.sandbox.AzphaltSandboxHost) {
        val ext = _installed.value.find { it.id == id } ?: return
        if (ext.manifest.kind != com.hereliesaz.graffitixr.common.azphalt.ExtensionKind.CODE && ext.manifest.kind != com.hereliesaz.graffitixr.common.azphalt.ExtensionKind.MIXED) return
        
        val entryPath = ext.manifest.entry ?: return
        val file = File(ext.filePath(entryPath))
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

    private fun openSource(source: String): InputStream = when {
        source.startsWith("asset:") -> context.assets.open(source.removePrefix("asset:"))
        source.startsWith("https://") -> {
            // https-only with finite timeouts — a cleartext or hung endpoint must not be trusted or
            // block the install indefinitely. HttpURLConnection won't follow a cross-protocol
            // (https→http) redirect, so a downgrade can't be forced.
            val conn = (URL(source).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            conn.inputStream
        }
        source.startsWith("http://") ->
            throw AzpInstaller.InstallException("Refusing cleartext http source (https required): $source")
        else -> throw AzpInstaller.InstallException("Unsupported source: $source")
    }

    /**
     * Blocking https GET returning the response body — the transport behind [storeRegistry]. https-only
     * with finite timeouts (a cleartext or hung registry must not be trusted or stall the UI), and the
     * body is bounded so a hostile/huge catalog response can't OOM the app.
     */
    private fun httpGetString(url: String, headers: Map<String, String>): String {
        if (!url.startsWith("https://")) throw AzpInstaller.InstallException("Registry requires https: $url")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        val out = ByteArrayOutputStream()
        conn.inputStream.use { input -> copyBounded(input, out, MAX_REGISTRY_RESPONSE_BYTES) }
        return out.toByteArray().decodeToString()
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
}
