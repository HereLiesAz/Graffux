package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.AssetContribution
import com.hereliesaz.graffitixr.common.azphalt.AssetType
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest

/** The real transport against a loopback server (http allowed only via the test-only flag). */
class HttpModelTransportTest {
    private lateinit var server: HttpServer
    private lateinit var root: File
    private val payload = ByteArray(300_000) { (it % 200).toByte() }
    private val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before fun up() {
        root = Files.createTempDirectory("m").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/real") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val body = payload.copyOfRange(start, payload.size)
            if (start > 0) ex.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
            ex.sendResponseHeaders(if (start > 0) 206 else 200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/hop") { ex ->
            ex.responseHeaders.add("Location", "$base/real")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/downgrade") { ex ->
            ex.responseHeaders.add("Location", "ftp://example.test/x")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.start()
    }

    @After fun down() {
        server.stop(0)
        root.deleteRecursively()
    }

    private fun asset(path: String) = AssetContribution(
        type = AssetType.ONNX, path = "", role = "depth", remoteUrl = base + path,
        checksum = "sha256-$digest", byteSize = payload.size.toLong(),
    )

    @Test fun `follows a redirect and verifies the bytes`() = runBlocking {
        val cache = ModelAssetCache(root, HttpModelTransport(requireHttps = false))
        assertTrue(cache.ensure("a", asset("/hop")) is ModelAssetCache.State.Ready)
    }

    @Test fun `sends a range header and gets a partial body`() {
        val r = HttpModelTransport(requireHttps = false).open(URL("$base/real"), 1000)
        r.use {
            assertEquals(206, it.code)
            assertEquals(1000L, it.rangeStart)
            assertEquals(payload.size - 1000, it.body!!.readBytes().size)
        }
    }

    @Test fun `refuses a redirect to a non-http scheme`() {
        val transport = HttpModelTransport(requireHttps = false)
        assertThrows(IOException::class.java) { transport.open(URL("$base/downgrade"), 0) }
    }

    @Test fun `production transport requires https`() {
        assertTrue(HttpModelTransport().requireHttps)
    }
}
