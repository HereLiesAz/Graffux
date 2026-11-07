package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.AssetContribution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads, verifies and caches the weights of a not-bundled model asset (`path == ""` +
 * `remoteUrl`, spec/extension-manifest.md § assets). The spec makes the check mandatory: the bytes
 * MUST match the manifest's `checksum` (`sha256-<hex>`) before use. So the rule here is absolute:
 * **a file is only returned from [verifiedFile] / [ensure] after its SHA-256 matched**, and an asset
 * with no usable `checksum` is never downloaded at all.
 *
 * Because the checksum (not the host) is the integrity gate, redirects may cross hosts as long as
 * they stay on https -- GitHub release downloads redirect to a CDN host.
 *
 * Storage is app-private (`<root>/<extension>/<sha256-hex>.bin`), named by digest so a republished
 * listing with a new checksum can never be satisfied by stale bytes. A download goes to
 * `<digest>.part` and is resumed with `Range` after an interruption; the verified file appears by
 * atomic rename, so a crash leaves either nothing or a complete verified file, never a half-written
 * one that looks usable.
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth", "TooManyFunctions", "LongParameterList")
class ModelAssetCache(
    private val root: File,
    private val transport: ModelTransport = HttpModelTransport(),
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    /** Where an asset stands. Progress and terminal outcomes are both states so a UI needs one flow. */
    sealed interface State {
        /** Nothing fetched yet. */
        data object NotDownloaded : State

        /** [downloaded] of [total] bytes ([total] null when the server did not say). */
        data class Downloading(val downloaded: Long, val total: Long?) : State

        /** Verified weights are at [file]. */
        data class Ready(val file: File) : State

        /**
         * Cannot be fetched as listed: the problem is in the listing (no or invalid checksum, no or
         * insecure URL, file gone), so retrying will not help. [reason] says which; [message] is
         * user-facing.
         */
        data class Unavailable(val reason: Reason, val message: String) : State

        /** A transient or integrity failure; a later [ensure] retries (and resumes where it can). */
        data class Failed(val reason: Reason, val message: String) : State
    }

    enum class Reason {
        NO_URL, INSECURE_URL, NO_CHECKSUM, BAD_CHECKSUM, NOT_FOUND, HTTP_ERROR, NETWORK, SIZE_MISMATCH,
        CHECKSUM_MISMATCH, TOO_LARGE, STORAGE, BAD_NAME,
    }

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())

    /** Latest [State] per [keyOf] key. Observe to show progress; absent = never asked. */
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    private val locks = ConcurrentHashMap<String, Mutex>()

    // path -> (length, mtime) of a file whose digest this process already confirmed, so reading the
    // weights for each estimate does not re-hash 60+ MB. A changed length/mtime forces a re-hash.
    private val confirmed = ConcurrentHashMap<String, Pair<Long, Long>>()

    /** Stable key for one asset of one extension. */
    fun keyOf(extensionId: String, asset: AssetContribution): String =
        "$extensionId|${asset.type.wire}|${asset.role.orEmpty()}|" +
            if (isBundle(asset)) "bundle:${bundleId(asset.files.orEmpty())}" else asset.remoteUrl.orEmpty()

    fun stateOf(extensionId: String, asset: AssetContribution): State =
        _states.value[keyOf(extensionId, asset)] ?: State.NotDownloaded

    /**
     * The verified weights for [asset] if they are on disk and still match the checksum; null
     * otherwise (never downloaded, deleted, or corrupted since). Re-checks the digest unless this
     * process already confirmed the same unchanged file.
     */
    fun verifiedFile(extensionId: String, asset: AssetContribution): File? {
        if (isBundle(asset)) return verifiedBundle(extensionId, asset)
        val digest = parseChecksum(asset.checksum) ?: return null
        val file = finalFile(extensionId, digest)
        if (!file.isFile) return null
        val stamp = file.length() to file.lastModified()
        if (confirmed[file.path] == stamp) return file
        if (asset.byteSize != null && file.length() != asset.byteSize) return null
        if (sha256Hex(file) != digest) {
            file.delete()
            return null
        }
        confirmed[file.path] = stamp
        return file
    }

    /**
     * Makes sure verified weights for [asset] are cached, downloading (or resuming) if needed, and
     * returns the final [State]: [State.Ready], [State.Unavailable] or [State.Failed]. Cancellable --
     * cancelling keeps the partial file for a later resume. Concurrent calls for one asset serialize,
     * so the second simply finds the first's result.
     */
    suspend fun ensure(extensionId: String, asset: AssetContribution): State {
        val key = keyOf(extensionId, asset)
        val lock = locks.getOrPut(key) { Mutex() }
        return lock.withLock {
            val outcome = try {
                withContext(Dispatchers.IO) { fetch(extensionId, asset, key) }
            } catch (e: CancellationException) {
                publish(key, State.NotDownloaded)
                throw e
            }
            publish(key, outcome)
            outcome
        }
    }

    /** Removes everything cached for [extensionId] (called on uninstall). */
    fun delete(extensionId: String) {
        val dir = dirFor(extensionId)
        dir.deleteRecursively()
        val prefix = "$extensionId|"
        _states.update { it.filterKeys { k -> !k.startsWith(prefix) } }
        confirmed.keys.removeAll { it.startsWith(dir.path) }
    }

    private fun publish(key: String, state: State) = _states.update { it + (key to state) }

    private suspend fun fetch(extensionId: String, asset: AssetContribution, key: String): State {
        if (isBundle(asset)) return fetchBundle(extensionId, asset, key)
        if (asset.checksum.isNullOrBlank()) {
            return unavailable(
                Reason.NO_CHECKSUM,
                "This listing publishes no checksum for its model, so it cannot be verified; not downloading.",
            )
        }
        val digest = parseChecksum(asset.checksum) ?: return unavailable(
            Reason.BAD_CHECKSUM,
            "This listing's model checksum is not a valid sha256-<hex> digest; not downloading.",
        )
        val rawUrl = asset.remoteUrl?.takeIf { it.isNotBlank() }
            ?: return unavailable(Reason.NO_URL, "This listing's model has no download address.")
        val url = parseUrl(rawUrl, transport.requireHttps) ?: return unavailable(
            Reason.INSECURE_URL,
            "This listing's model address is not a plain https URL; not downloading.",
        )
        val declared = asset.byteSize
        if (declared != null && (declared <= 0 || declared > maxBytes)) return tooLarge()

        verifiedFile(extensionId, asset)?.let { return State.Ready(it) }

        val dir = dirFor(extensionId)
        if (!dir.isDirectory && !dir.mkdirs()) return State.Failed(Reason.STORAGE, "Could not create the model folder.")
        val part = File(dir, "$digest.part")
        val finalFile = finalFile(extensionId, digest)
        return try {
            download(url, part, declared, key)?.let { return it }
            verifyDownload(part, declared, digest)?.let { return it }
            Files.move(
                part.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            confirmed[finalFile.path] = finalFile.length() to finalFile.lastModified()
            State.Ready(finalFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            State.Failed(Reason.NETWORK, "Download interrupted (${e.message}). It will resume next time.")
        }
    }

    /** Streams into [part], resuming if a partial file exists. Returns a terminal state on failure, null on success. */
    private suspend fun download(
        url: URL, part: File, declared: Long?, key: String, progress: Progress = Progress.NONE,
            allowResume: Boolean = true,
    ): State? {
        if (!allowResume) part.delete()
        var resumeFrom = if (part.isFile) part.length() else 0L
        if (declared != null && resumeFrom > declared) {
            part.delete()
            resumeFrom = 0
        }
        if (declared != null && resumeFrom == declared) return null // a complete .part from an interrupted verify
        var retriedFromScratch = false
        while (true) {
            transport.open(url, resumeFrom).use { r ->
                when {
                    r.code == HTTP_RANGE_NOT_SATISFIABLE && resumeFrom > 0 && !retriedFromScratch -> {
                        part.delete()
                        resumeFrom = 0
                        retriedFromScratch = true
                    }
                    r.code == HTTP_NOT_FOUND || r.code == HTTP_GONE -> return unavailable(
                        Reason.NOT_FOUND,
                        "The model file is no longer at the address the listing gives (HTTP ${r.code}).",
                    )
                    r.code != HTTP_OK && r.code != HTTP_PARTIAL ->
                        return State.Failed(Reason.HTTP_ERROR, "The model server answered HTTP ${r.code}.")
                    else -> {
                        val resumed = r.code == HTTP_PARTIAL && r.rangeStart == resumeFrom && resumeFrom > 0
                        if (!resumed) resumeFrom = 0 // a 200, or a range we did not ask for: start over
                        val remaining = r.contentLength.takeIf { it >= 0 }
                        val total = remaining?.let { it + resumeFrom } ?: declared
                        if (total != null && total > maxBytes) return tooLarge()
                        val body = r.body ?: return State.Failed(Reason.NETWORK, "The server sent no data.")
                        return copyTo(body, part, resumed, resumeFrom, total, key, progress)
                    }
                }
            }
        }
    }

    private suspend fun copyTo(
        input: InputStream, part: File, append: Boolean, startAt: Long, total: Long?, key: String, progress: Progress,
    ): State? {
        val ctx = currentCoroutineContext()
        var written = startAt
        var lastPublished = 0L
        FileOutputStream(part, append).use { out ->
            val buf = ByteArray(BUFFER)
            while (true) {
                ctx.ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                written += n
                if (written > maxBytes) {
                    out.close()
                    part.delete()
                    return tooLarge()
                }
                out.write(buf, 0, n)
                if (written - lastPublished >= PROGRESS_STEP) {
                    lastPublished = written
                    publish(key, State.Downloading(progress.done + written,
                        progress.total ?: total?.let { progress.done + it }))
                }
            }
            out.fd.sync()
        }
        return null
    }

    /** Size then digest of the finished download; discards it and returns a failure if either is off. */
    private fun verifyDownload(part: File, declared: Long?, digest: String, label: String = "model"): State? {
        if (declared != null && part.length() != declared) {
            val got = part.length()
            // Shorter = truncated (resumable); longer = the wrong file. The listing's size is the contract.
            if (got > declared) part.delete()
            return State.Failed(Reason.SIZE_MISMATCH,
                "The downloaded $label is $got bytes, the listing says $declared.")
        }
        if (sha256Hex(part) != digest) {
            part.delete()
            return State.Failed(
                Reason.CHECKSUM_MISMATCH,
                "The downloaded $label does not match the listing's checksum; discarded.",
            )
        }
        return null
    }


    // ---- multi-file bundles (`files[]`) ----

    /** Bytes already finished ahead of the file being streamed, and the whole set's size, for aggregate progress. */
    private class Progress(val done: Long, val total: Long?) {
        companion object { val NONE = Progress(0, null) }
    }

    private class Member(val name: String, val url: URL, val digest: String, val size: Long?, val range: Boolean)

    /**
     * Downloads every member of a bundle into one directory under its exact name, all-or-nothing.
     * Members land in a staging directory (`<bundleId>.part`), each verified (size, then SHA-256) and
     * renamed into place as it completes; only when the whole set is verified is the staging directory
     * renamed to `<bundleId>`. So the visible bundle directory is always complete. A failed member
     * leaves the verified ones in staging (and the failed one's partial bytes, for Range resume): a
     * retry re-fetches only what is missing or corrupt. Every member is validated *before* any byte is
     * fetched, so a listing with one bad entry downloads nothing.
     */
    private suspend fun fetchBundle(extensionId: String, asset: AssetContribution, key: String): State {
        val members = when (val plan = planBundle(asset)) {
            is BundlePlan.Rejected -> return plan.state
            is BundlePlan.Members -> plan.members
        }
        val sizes = members.map { it.size }
        val total = if (sizes.all { it != null }) sizes.sumOf { it!! } else null
        if (total != null && total > maxBytes) return tooLarge()
        verifiedBundle(extensionId, asset)?.let { return State.Ready(it) }

        val id = bundleId(asset.files.orEmpty())
        val root = dirFor(extensionId)
        val staging = File(root, "$id.part")
        if (!staging.isDirectory && !staging.mkdirs()) return State.Failed(Reason.STORAGE,
            "Could not create the model folder.")
        return try {
            var done = 0L
            for ((index, m) in members.withIndex()) {
                val target = File(staging, m.name)
                if (target.isFile) {
                    if (memberMatches(target, m)) { done += target.length(); continue }
                    target.delete()
                }
                val part = File(staging, "$PART_PREFIX$index")
                download(m.url, part, m.size, key, Progress(done, total), m.range)?.let { return it }
                verifyDownload(part, m.size, m.digest, m.name)?.let { return it }
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
                done += target.length()
            }
            val names = members.map { it.name }.toSet()
            staging.listFiles()?.filter { it.name !in names }?.forEach { it.deleteRecursively() }
            val finalDir = File(root, id)
            if (finalDir.exists()) finalDir.deleteRecursively()
            Files.move(staging.toPath(), finalDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
            members.forEach { m -> File(finalDir,
                m.name).let { confirmed[it.path] = it.length() to it.lastModified() } }
            State.Ready(File(finalDir, primaryMember(asset.files.orEmpty()).name))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            State.Failed(Reason.NETWORK, "Download interrupted (${e.message}). It will resume next time.")
        }
    }

    private sealed interface BundlePlan {
        class Rejected(val state: State) : BundlePlan
        class Members(val members: List<Member>) : BundlePlan
    }

    private fun planBundle(asset: AssetContribution): BundlePlan {
        val files = asset.files.orEmpty()
        val names = HashSet<String>()
        val members = ArrayList<Member>(files.size)
        for (f in files) {
            if (!isSafeMemberName(f.name) || !names.add(f.name)) {
                return BundlePlan.Rejected(unavailable(Reason.BAD_NAME,
                    "This listing names a model file '${f.name}' that cannot be stored safely; not downloading."))
            }
            if (f.checksum.isNullOrBlank()) {
                return BundlePlan.Rejected(unavailable(Reason.NO_CHECKSUM,
                    "This listing publishes no checksum for '${f.name}', so it cannot be verified; not downloading."))
            }
            val digest = parseChecksum(f.checksum) ?: return BundlePlan.Rejected(
                unavailable(Reason.BAD_CHECKSUM,
                    "The checksum for '${f.name}' is not a valid sha256-<hex> digest; not downloading."),
            )
            val raw = f.remoteUrl?.takeIf { it.isNotBlank() } ?: return BundlePlan.Rejected(
                unavailable(Reason.NO_URL, "This listing gives no download address for '${f.name}'."),
            )
            val url = parseUrl(raw, transport.requireHttps) ?: return BundlePlan.Rejected(
                unavailable(Reason.INSECURE_URL,
                    "The address for '${f.name}' is not a plain https URL; not downloading."),
            )
            val declared = f.byteSize
            if (declared != null && (declared <= 0 || declared > maxBytes)) return BundlePlan.Rejected(tooLarge())
            members += Member(f.name, url, digest, f.byteSize, f.supportsRange != false)
        }
        if (members.isEmpty()) return BundlePlan.Rejected(unavailable(Reason.NO_URL,
            "This listing's model bundle has no files."))
        return BundlePlan.Members(members)
    }

    private fun memberMatches(file: File, m: Member): Boolean =
        file.isFile && (m.size == null || file.length() == m.size) && sha256Hex(file) == m.digest

    /**
     * The graph file of a complete, intact bundle, or null. Re-hashes members unless this process
     * already confirmed them unchanged. A member that fails is deleted and the rest of the bundle is
     * moved back to staging, so the visible directory is never partial and only that member re-downloads.
     */
    private fun verifiedBundle(extensionId: String, asset: AssetContribution): File? {
        val files = asset.files.orEmpty()
        val plan = planBundle(asset) as? BundlePlan.Members ?: return null
        val dir = File(dirFor(extensionId), bundleId(files))
        if (!dir.isDirectory) return null
        var intact = true
        for (m in plan.members) {
            val f = File(dir, m.name)
            val stamp = if (f.isFile) f.length() to f.lastModified() else null
            val ok = stamp != null && (confirmed[f.path] == stamp ||
                ((m.size == null || f.length() == m.size) && sha256Hex(f) == m.digest))
            if (ok) confirmed[f.path] = stamp!! else { f.delete(); confirmed.remove(f.path); intact = false }
        }
        if (intact) return File(dir, primaryMember(files).name)
        val staging = File(dir.parentFile, "${dir.name}.part")
        if (staging.exists()) dir.deleteRecursively() else Files.move(dir.toPath(), staging.toPath(),
            StandardCopyOption.ATOMIC_MOVE)
        return null
    }

    private fun tooLarge() =
        State.Failed(Reason.TOO_LARGE, "The model is larger than the ${maxBytes / MB} MB this app will download.")

    private fun unavailable(reason: Reason, message: String) = State.Unavailable(reason, message)

    private fun dirFor(extensionId: String): File = File(root, safeName(extensionId))

    private fun finalFile(extensionId: String, digest: String) = File(dirFor(extensionId), "$digest.bin")

    companion object {
        const val DEFAULT_MAX_BYTES = 512L * 1024 * 1024
        private const val MB = 1024 * 1024
        private const val BUFFER = 64 * 1024
        private const val PROGRESS_STEP = 256L * 1024
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL = 206
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_GONE = 410
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val SHA256_HEX_LENGTH = 64
        private const val PREFIX = "sha256-"

        private const val PART_PREFIX = ".part-"

        /** True when [asset] is a multi-file bundle (`files[]` non-empty); scalar url/checksum are then ignored. */
        fun isBundle(asset: AssetContribution): Boolean = !asset.files.isNullOrEmpty()

        /**
         * The member a runtime opens: the first `*.onnx` (so `model.onnx`, not `model.onnx_data`), else
         * the first listed. The others sit beside it under their exact names.
         */
        fun primaryMember(files: List<com.hereliesaz.graffitixr.common.azphalt.ModelFile>) =
            files.firstOrNull { it.name.endsWith(".onnx", ignoreCase = true) } ?: files.first()

        /** One path segment, no separators, not hidden (so never `.`/`..` or our `.part-` files). */
        internal fun isSafeMemberName(name: String): Boolean =
            name.isNotEmpty() && name.length <= MAX_NAME && !name.startsWith(".") &&
                name.none { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() }

        /** Stable directory name for a member set; a changed name or digest changes it, so stale bytes never match. */
        internal fun bundleId(files: List<com.hereliesaz.graffitixr.common.azphalt.ModelFile>): String {
            val md = MessageDigest.getInstance("SHA-256")
            files.sortedBy { it.name }.forEach {
                md.update("${it.name}\t${parseChecksum(it.checksum) ?: it.checksum}\n".toByteArray())
            }
            return "b_" + md.digest().joinToString("") { "%02x".format(it) }.take(BUNDLE_ID_HEX)
        }
        private const val BUNDLE_ID_HEX = 24
        private const val MAX_NAME = 128

        /** `sha256-<64 hex>` (case-insensitive) -> lowercase hex; null for anything else. */
        fun parseChecksum(raw: String?): String? {
            val s = raw?.trim() ?: return null
            if (!s.startsWith(PREFIX, ignoreCase = true)) return null
            val hex = s.substring(PREFIX.length).lowercase()
            return hex.takeIf { it.length == SHA256_HEX_LENGTH && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        }

        fun sha256Hex(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** An extension id as one safe path segment (ids are reverse-DNS; never trust one as a path). */
        internal fun safeName(id: String): String = "x_" + id.replace(Regex("[^A-Za-z0-9._-]"), "_")

        private fun parseUrl(raw: String, requireHttps: Boolean): URL? = try {
            val uri = URI(raw.trim())
            val schemeOk = uri.scheme.equals("https", true) || (!requireHttps && uri.scheme.equals("http", true))
            if (schemeOk && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) uri.toURL() else null
        } catch (_: URISyntaxException) {
            null
        }
    }
}

/** One HTTP response for [ModelTransport]; [rangeStart] is a 206 body's first byte, from `Content-Range`. */
class ModelResponse(
    val code: Int,
    val contentLength: Long,
    val rangeStart: Long?,
    val body: InputStream?,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    override fun close() {
        runCatching { body?.close() }
        onClose()
    }
}

/** The network seam; production uses [HttpModelTransport], tests a fake. */
interface ModelTransport {
    /** True when only https may be used (always, outside tests). */
    val requireHttps: Boolean get() = true

    /** GET [url], asking for bytes from [rangeStart] on when it is above zero. */
    @Throws(IOException::class)
    fun open(url: URL, rangeStart: Long): ModelResponse
}

/**
 * [HttpURLConnection] transport. Follows up to [MAX_REDIRECTS] redirects, https to https only, to any
 * host (the checksum is the integrity gate -- see [ModelAssetCache]). Sends no cookies or auth headers.
 */
class HttpModelTransport(override val requireHttps: Boolean = true) : ModelTransport {
    override fun open(url: URL, rangeStart: Long): ModelResponse {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val c = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // Range offsets and Content-Length must describe the raw bytes, not a compressed stream.
                setRequestProperty("Accept-Encoding", "identity")
                if (rangeStart > 0) setRequestProperty("Range", "bytes=$rangeStart-")
            }
            val code = c.responseCode
            if (code in REDIRECTS) {
                val next = c.getHeaderField("Location")?.let { URL(current, it) }
                c.disconnect()
                if (next == null) throw IOException("The download redirected nowhere")
                val ok = next.protocol.equals("https", true) || (!requireHttps && next.protocol.equals("http", true))
                if (!ok) throw IOException("The download redirected to a non-https address")
                current = next
            } else {
                val stream = if (code in HTTP_SUCCESS) c.inputStream else null
                val start = c.getHeaderField("Content-Range")
                    ?.let { CONTENT_RANGE.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                return ModelResponse(code, c.contentLengthLong, start, stream) { c.disconnect() }
            }
        }
        throw IOException("The download redirected too many times")
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        val HTTP_SUCCESS = 200..299
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        val CONTENT_RANGE = Regex("""bytes\s+(\d+)-""")
    }
}
