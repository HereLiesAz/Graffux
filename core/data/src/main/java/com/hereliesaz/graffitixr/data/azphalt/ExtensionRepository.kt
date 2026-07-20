package com.hereliesaz.graffitixr.data.azphalt

import android.content.Context
import com.hereliesaz.graffitixr.common.azphalt.AssetType
import com.hereliesaz.graffitixr.common.azphalt.AzpSignatures
import com.hereliesaz.graffitixr.common.azphalt.CubeLut
import com.hereliesaz.graffitixr.common.azphalt.TrustStore
import com.hereliesaz.graffitixr.common.azphalt.parseCubeLut
import com.hereliesaz.graffitixr.common.azphalt.parseManifest
import com.hereliesaz.graffitixr.common.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
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
     * is its resolved `.azp` download URL, so the existing [install] path works unchanged once the store
     * serves downloads. Blocking IO — call from a background dispatcher.
     */
    fun browseStore(query: String? = null): List<MarketplaceEntry> =
        storeRegistry.listPackages(query).map { pkg ->
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
            return synchronized(lock) {
                val installed = tempFile.inputStream().use { installer.install(it, nowMs) }
                _installed.value = scanInstalled()
                installed
            }
        } finally {
            tempFile.delete()
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

    fun uninstall(id: String) = synchronized(lock) {
        val ext = _installed.value.find { it.id == id } ?: return
        File(ext.dir).deleteRecursively()
        _installed.value = scanInstalled()
    }

    /** Installed LUT asset extensions, paired with their loadable [CubeLut] (parsed lazily on use). */
    fun installedLuts(): List<InstalledExtension> =
        _installed.value.filter { ext -> ext.manifest.assets.any { it.type == AssetType.LUT } }

    /** Load the first LUT of an installed extension, or null if it has none / fails to parse. */
    fun loadLut(id: String): CubeLut? {
        val ext = _installed.value.find { it.id == id } ?: return null
        val lutAsset = ext.manifest.assets.firstOrNull { it.type == AssetType.LUT } ?: return null
        val file = File(ext.filePath(lutAsset.path))
        if (!file.exists()) return null
        return runCatching { parseCubeLut(file.readText()) }.getOrNull()
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
