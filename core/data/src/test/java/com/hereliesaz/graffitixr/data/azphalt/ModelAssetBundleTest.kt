package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.AssetContribution
import com.hereliesaz.graffitixr.common.azphalt.AssetType
import com.hereliesaz.graffitixr.common.azphalt.ModelFile
import com.hereliesaz.graffitixr.data.azphalt.ModelAssetCache.Reason
import com.hereliesaz.graffitixr.data.azphalt.ModelAssetCache.State
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest

/** Multi-file bundles (`files[]`): layout, all-or-nothing, per-member verification, Range resume. */
class ModelAssetBundleTest {
    private lateinit var root: File
    private val graph = ByteArray(5_000) { (it * 7 % 251).toByte() }
    private val data = ByteArray(700_000) { (it * 13 % 241).toByte() }
    private fun sha(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Before fun setUp() { root = Files.createTempDirectory("bundle").toFile() }
    @After fun tearDown() { root.deleteRecursively() }

    private fun bundle(
        graphSum: String = "sha256-${sha(graph)}",
        dataSum: String? = "sha256-${sha(data)}",
        dataSize: Long? = data.size.toLong(),
        names: Pair<String, String> = "model.onnx" to "model.onnx_data",
        range: Boolean? = true,
    ) = AssetContribution(
        type = AssetType.ONNX, path = "", role = "depth",
        files = listOf(
            ModelFile(names.first, remoteUrl = "https://example.test/a/model.onnx", checksum = graphSum,
                byteSize = graph.size.toLong(), supportsRange = range),
            ModelFile(names.second, remoteUrl = "https://example.test/a/model.onnx_data", checksum = dataSum,
                byteSize = dataSize, supportsRange = range),
        ),
    )

    private class Fake(val served: MutableMap<String, ByteArray>, val ranges: Boolean = true) : ModelTransport {
        val requests = mutableListOf<Pair<String, Long>>()
        var failDataAfter: Int? = null

        override fun open(url: URL, rangeStart: Long): ModelResponse {
            requests += url.path to rangeStart
            val bytes = served[url.path] ?: return ModelResponse(404, 0, null, null)
            val start = if (ranges && rangeStart > 0) rangeStart.toInt() else 0
            val slice = bytes.copyOfRange(start, bytes.size)
            val limit = failDataAfter.takeIf { url.path.endsWith("_data") }
            val body: InputStream = if (limit != null) Cut(slice, limit) else ByteArrayInputStream(slice)
            return if (start > 0) ModelResponse(206, slice.size.toLong(), start.toLong(), body)
            else ModelResponse(200, slice.size.toLong(), null, body)
        }
    }

    private class Cut(d: ByteArray, private val limit: Int) : InputStream() {
        private val inner = ByteArrayInputStream(d)
        private var read = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (read >= limit) throw IOException("reset")
            val n = inner.read(b, off, minOf(len, limit - read))
            if (n > 0) read += n
            return n
        }
    }

    private fun served() = mutableMapOf("/a/model.onnx" to graph, "/a/model.onnx_data" to data)

    @Test fun `downloads every member into one directory under its exact name and returns the graph`() = runBlocking {
        val c = ModelAssetCache(root, Fake(served()))
        val ready = c.ensure("com.x.depth", bundle()) as State.Ready
        assertEquals("model.onnx", ready.file.name)
        val dir = ready.file.parentFile
        assertEquals(setOf("model.onnx", "model.onnx_data"), dir.list()!!.toSet())
        assertEquals(sha(data), ModelAssetCache.sha256Hex(File(dir, "model.onnx_data")))
        assertEquals(ready.file, c.verifiedFile("com.x.depth", bundle()))
        assertEquals(1, File(root, ModelAssetCache.safeName("com.x.depth")).list()!!.size) // no staging left over
    }

    @Test fun `a second ensure downloads nothing`() = runBlocking {
        val t = Fake(served())
        val c = ModelAssetCache(root, t)
        c.ensure("x", bundle())
        val n = t.requests.size
        assertTrue(c.ensure("x", bundle()) is State.Ready)
        assertEquals(n, t.requests.size)
    }

    @Test fun `a member with a wrong checksum fails the set and nothing is exposed`() = runBlocking {
        val s = served().apply { this["/a/model.onnx_data"] = data.copyOf().also { it[10] = 99 } }
        val c = ModelAssetCache(root, Fake(s))
        val r = c.ensure("x", bundle())
        assertTrue(r is State.Failed && r.reason == Reason.CHECKSUM_MISMATCH)
        assertNull(c.verifiedFile("x", bundle()))
        val dir = File(root, ModelAssetCache.safeName("x"))
        assertTrue(dir.list()!!.none { !it.endsWith(".part") }) // only staging exists
        assertFalse(File(dir, ModelAssetCache.bundleId(bundle().files!!)).exists())
    }

    @Test fun `a retry after a bad member refetches only that member`() = runBlocking {
        val s = served().apply { this["/a/model.onnx_data"] = data.copyOf().also { it[10] = 99 } }
        val t = Fake(s)
        val c = ModelAssetCache(root, t)
        c.ensure("x", bundle())
        s["/a/model.onnx_data"] = data
        t.requests.clear()
        assertTrue(c.ensure("x", bundle()) is State.Ready)
        assertEquals(listOf("/a/model.onnx_data"), t.requests.map { it.first }.distinct())
    }

    @Test fun `size mismatch on a member is reported`() = runBlocking {
        val c = ModelAssetCache(root, Fake(served()))
        val r = c.ensure("x", bundle(dataSize = data.size - 1L))
        assertTrue(r is State.Failed)
        assertNull(c.verifiedFile("x", bundle(dataSize = data.size - 1L)))
    }

    @Test fun `interrupted member resumes with Range and the set completes`() = runBlocking {
        val t = Fake(served()).apply { failDataAfter = 300_000 }
        val c = ModelAssetCache(root, t)
        assertTrue(c.ensure("x", bundle()) is State.Failed)
        t.failDataAfter = null
        t.requests.clear()
        assertTrue(c.ensure("x", bundle()) is State.Ready)
        val dataReq = t.requests.single { it.first.endsWith("_data") }
        assertEquals(300_000L, dataReq.second)
        assertFalse(t.requests.any { it.first == "/a/model.onnx" }) // the finished member is not fetched again
    }

    @Test fun `supportsRange false restarts instead of resuming`() = runBlocking {
        val t = Fake(served()).apply { failDataAfter = 100_000 }
        val c = ModelAssetCache(root, t)
        c.ensure("x", bundle(range = false))
        t.failDataAfter = null
        t.requests.clear()
        assertTrue(c.ensure("x", bundle(range = false)) is State.Ready)
        assertTrue(t.requests.all { it.second == 0L })
    }

    @Test fun `a member with no checksum refuses the whole bundle before any download`() = runBlocking {
        val t = Fake(served())
        val c = ModelAssetCache(root, t)
        val r = c.ensure("x", bundle(dataSum = null))
        assertTrue(r is State.Unavailable && r.reason == Reason.NO_CHECKSUM)
        assertTrue(t.requests.isEmpty())
    }

    @Test fun `unsafe or duplicate member names are refused`() = runBlocking {
        for (names in listOf("../evil" to "model.onnx_data", "model.onnx" to "a/b", "model.onnx" to "model.onnx",
            ".x" to "y", "model.onnx" to "")) {
            val t = Fake(served())
            val r = ModelAssetCache(root, t).ensure("x", bundle(names = names))
            assertTrue("$names -> $r", r is State.Unavailable && r.reason == Reason.BAD_NAME)
            assertTrue(t.requests.isEmpty())
        }
    }

    @Test fun `a member corrupted on disk later drops the set and only it is refetched`() = runBlocking {
        val t = Fake(served())
        val c = ModelAssetCache(root, t)
        val ready = c.ensure("x", bundle()) as State.Ready
        File(ready.file.parentFile, "model.onnx_data").appendBytes(byteArrayOf(1))
        val fresh = ModelAssetCache(root, t) // new process: nothing confirmed
        assertNull(fresh.verifiedFile("x", bundle()))
        assertFalse(ready.file.parentFile.exists()) // never a half-visible directory
        t.requests.clear()
        assertTrue(fresh.ensure("x", bundle()) is State.Ready)
        assertEquals(listOf("/a/model.onnx_data"), t.requests.map { it.first }.distinct())
    }

    @Test fun `changing a member checksum is a new bundle that stale bytes cannot satisfy`() = runBlocking {
        val c = ModelAssetCache(root, Fake(served()))
        c.ensure("x", bundle())
        assertNull(c.verifiedFile("x", bundle(graphSum = "sha256-" + "0".repeat(64))))
    }

    @Test fun `progress aggregates across members`() = runBlocking {
        val c = ModelAssetCache(root, Fake(served()))
        val seen = mutableListOf<Long>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            c.states.collect { m -> (m.values.firstOrNull() as? State.Downloading)?.let { seen += it.downloaded } }
        }
        c.ensure("x", bundle())
        job.cancel()
        assertTrue(seen.all { it <= graph.size + data.size })
        assertNotNull(c.stateOf("x", bundle()) as? State.Ready)
    }

    @Test fun `delete removes the bundle`() = runBlocking {
        val c = ModelAssetCache(root, Fake(served()))
        c.ensure("x", bundle())
        c.delete("x")
        assertNull(c.verifiedFile("x", bundle()))
    }
}
