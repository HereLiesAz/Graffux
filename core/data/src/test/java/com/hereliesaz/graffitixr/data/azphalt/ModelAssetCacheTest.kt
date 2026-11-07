package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.AssetContribution
import com.hereliesaz.graffitixr.common.azphalt.AssetType
import com.hereliesaz.graffitixr.data.azphalt.ModelAssetCache.Reason
import com.hereliesaz.graffitixr.data.azphalt.ModelAssetCache.State
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

class ModelAssetCacheTest {
    private lateinit var root: File
    private val payload = ByteArray(1_000_000) { (it * 31 % 251).toByte() }
    private val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    @Before fun setUp() {
        root = Files.createTempDirectory("models").toFile()
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun asset(
        checksum: String? = "sha256-$digest",
        byteSize: Long? = payload.size.toLong(),
        url: String? = "https://example.test/midas.onnx",
    ) = AssetContribution(
        type = AssetType.ONNX, path = "", role = "depth", remoteUrl = url, checksum = checksum, byteSize = byteSize,
    )

    /** Serves [bytes], honouring Range when [ranges] is true; records requests. */
    private class FakeTransport(
        var bytes: ByteArray,
        val ranges: Boolean = true,
        var status: Int = 200,
        var failAfter: Int? = null,
    ) : ModelTransport {
        val requests = mutableListOf<Long>()

        override fun open(url: URL, rangeStart: Long): ModelResponse {
            requests += rangeStart
            if (status != 200) return ModelResponse(status, 0, null, null)
            val start = if (ranges && rangeStart > 0) rangeStart.toInt() else 0
            val slice = bytes.copyOfRange(start, bytes.size)
            val body: InputStream = failAfter?.let { limit -> Truncating(slice, limit) } ?: ByteArrayInputStream(slice)
            return if (start > 0) ModelResponse(206, slice.size.toLong(), start.toLong(), body)
            else ModelResponse(200, slice.size.toLong(), null, body)
        }
    }

    private class Truncating(data: ByteArray, private val limit: Int) : InputStream() {
        private val inner = ByteArrayInputStream(data)
        private var read = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (read >= limit) throw IOException("connection reset")
            val n = inner.read(b, off, minOf(len, limit - read))
            if (n > 0) read += n
            return n
        }
    }

    private fun cache(t: ModelTransport) = ModelAssetCache(root, t)

    @Test fun `downloads, verifies and returns the file`() = runBlocking {
        val c = cache(FakeTransport(payload))
        val s = c.ensure("com.x.midas", asset())
        assertTrue(s is State.Ready)
        val f = (s as State.Ready).file
        assertEquals(payload.size.toLong(), f.length())
        assertEquals(f, c.verifiedFile("com.x.midas", asset()))
        assertTrue(f.path.startsWith(root.path))
        assertFalse(File(f.parentFile, "$digest.part").exists())
    }

    @Test fun `refuses an asset with no checksum and never touches the network`() = runBlocking {
        val t = FakeTransport(payload)
        val s = cache(t).ensure("com.x.midas", asset(checksum = null))
        assertEquals(Reason.NO_CHECKSUM, (s as State.Unavailable).reason)
        assertTrue(t.requests.isEmpty())
    }

    @Test fun `refuses a malformed checksum`() = runBlocking {
        val t = FakeTransport(payload)
        for (bad in listOf("sha256-abc", "md5-$digest", "sha256-" + "z".repeat(64), digest)) {
            val s = cache(t).ensure("com.x.midas", asset(checksum = bad))
            assertEquals(bad, Reason.BAD_CHECKSUM, (s as State.Unavailable).reason)
        }
        assertTrue(t.requests.isEmpty())
    }

    @Test fun `refuses a non https or missing url`() = runBlocking {
        val t = FakeTransport(payload)
        val http = cache(t).ensure("a", asset(url = "http://example.test/m.onnx"))
        assertEquals(Reason.INSECURE_URL, (http as State.Unavailable).reason)
        val creds = cache(t).ensure("a", asset(url = "https://trusted.test@evil.test/m.onnx"))
        assertEquals(Reason.INSECURE_URL, (creds as State.Unavailable).reason)
        val none = cache(t).ensure("a", asset(url = null))
        assertEquals(Reason.NO_URL, (none as State.Unavailable).reason)
        assertTrue(t.requests.isEmpty())
    }

    @Test fun `a 404 is unavailable not retryable`() = runBlocking {
        val s = cache(FakeTransport(payload, status = 404)).ensure("a", asset())
        assertEquals(Reason.NOT_FOUND, (s as State.Unavailable).reason)
    }

    @Test fun `a server error is a retryable failure`() = runBlocking {
        val s = cache(FakeTransport(payload, status = 503)).ensure("a", asset())
        assertEquals(Reason.HTTP_ERROR, (s as State.Failed).reason)
    }

    @Test fun `wrong bytes are rejected and nothing usable is left`() = runBlocking {
        val tampered = payload.clone().also { it[10] = (it[10] + 1).toByte() }
        val c = cache(FakeTransport(tampered))
        val s = c.ensure("a", asset())
        assertEquals(Reason.CHECKSUM_MISMATCH, (s as State.Failed).reason)
        assertNull(c.verifiedFile("a", asset()))
        assertEquals(0, root.walkTopDown().count { it.isFile })
    }

    @Test fun `a size that disagrees with the listing is rejected`() = runBlocking {
        val s = cache(FakeTransport(payload)).ensure("a", asset(byteSize = payload.size + 5L))
        assertEquals(Reason.SIZE_MISMATCH, (s as State.Failed).reason)
    }

    @Test fun `oversize declared or streamed is refused`() = runBlocking {
        val small = ModelAssetCache(root, FakeTransport(payload), maxBytes = 1000)
        assertEquals(Reason.TOO_LARGE, (small.ensure("a", asset()) as State.Failed).reason)
        val streamed = ModelAssetCache(root, FakeTransport(payload), maxBytes = 1000)
        assertEquals(Reason.TOO_LARGE, (streamed.ensure("b", asset(byteSize = null)) as State.Failed).reason)
        assertEquals(0, root.walkTopDown().count { it.isFile && it.name.endsWith(".part") })
    }

    @Test fun `an interrupted download resumes with a range request`() = runBlocking {
        val t = FakeTransport(payload, failAfter = 400_000)
        val c = cache(t)
        val first = c.ensure("a", asset())
        assertEquals(Reason.NETWORK, (first as State.Failed).reason)
        val part = File(root, "${ModelAssetCache.safeName("a")}/$digest.part")
        assertEquals(400_000L, part.length())

        t.failAfter = null
        val second = c.ensure("a", asset())
        assertTrue(second is State.Ready)
        assertEquals(listOf(0L, 400_000L), t.requests)
    }

    @Test fun `a server that ignores range restarts cleanly`() = runBlocking {
        val t = FakeTransport(payload, ranges = false, failAfter = 300_000)
        val c = cache(t)
        c.ensure("a", asset())
        t.failAfter = null
        assertTrue(c.ensure("a", asset()) is State.Ready)
    }

    @Test fun `ready is idempotent and does not download again`() = runBlocking {
        val t = FakeTransport(payload)
        val c = cache(t)
        c.ensure("a", asset())
        c.ensure("a", asset())
        assertEquals(1, t.requests.size)
    }

    @Test fun `a corrupted cached file is detected and redownloaded`() = runBlocking {
        val t = FakeTransport(payload)
        val c1 = cache(t)
        val f = (c1.ensure("a", asset()) as State.Ready).file
        f.writeBytes(ByteArray(payload.size)) // same length, different content
        f.setLastModified(f.lastModified() + 5_000)
        val c2 = cache(t) // fresh process: no in-memory confirmation
        assertNull(c2.verifiedFile("a", asset()))
        assertTrue(c2.ensure("a", asset()) is State.Ready)
        assertEquals(2, t.requests.size)
    }

    @Test fun `progress is published while downloading`() = runBlocking {
        val c = cache(FakeTransport(payload))
        c.ensure("a", asset())
        assertTrue(c.stateOf("a", asset()) is State.Ready)
        assertNotNull(c.states.value[c.keyOf("a", asset())])
    }

    @Test fun `delete removes files and state`() = runBlocking {
        val c = cache(FakeTransport(payload))
        c.ensure("a", asset())
        c.delete("a")
        assertEquals(0, root.walkTopDown().count { it.isFile })
        assertEquals(State.NotDownloaded, c.stateOf("a", asset()))
    }

    @Test fun `extension ids cannot escape the cache root`() {
        val name = ModelAssetCache.safeName("../../etc")
        assertFalse(name.contains("/"))
        assertTrue(File(root, name).canonicalPath.startsWith(root.canonicalPath))
    }

    @Test fun `checksum parsing`() {
        assertEquals(digest, ModelAssetCache.parseChecksum("SHA256-${digest.uppercase()}"))
        assertNull(ModelAssetCache.parseChecksum(null))
        assertNull(ModelAssetCache.parseChecksum(""))
    }
}
