package com.hereliesaz.graffitixr.data.strokedata

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class StrokeRelayClientTest {

    private lateinit var server: HttpServer
    private var status = 201
    private var responseBody = """{"status":"stored"}"""
    private var location: String? = null
    private var method: String? = null
    private var path: String? = null
    private var contentType: String? = null
    private var received: ByteArray? = null

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            method = exchange.requestMethod
            path = exchange.requestURI.path
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            received = exchange.requestBody.readBytes()
            location?.let { exchange.responseHeaders.add("Location", it) }
            val bytes = responseBody.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun client() = StrokeRelayClient("http://127.0.0.1:${server.address.port}", Dispatchers.Unconfined)

    private val file = byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3)

    @Test
    fun `a stored file is sent as a PUT to the device folder`() = runBlocking {
        val result = client().upload("Pixel-5", "session-1790680692015.jsonl.gz", file)
        assertTrue(result.isSuccess)
        assertEquals("PUT", method)
        assertEquals("/v1/stroke-data/Pixel-5/session-1790680692015.jsonl.gz", path)
        assertEquals("application/gzip", contentType)
        assertArrayEquals(file, received)
    }

    @Test
    fun `a file the relay already had counts as uploaded`() = runBlocking {
        status = 200
        responseBody = """{"status":"exists"}"""
        assertTrue(client().upload("Pixel-5", "session-1790680692015.jsonl.gz", file).isSuccess)
    }

    @Test
    fun `a 200 that is not the relay, like a login page, is not an upload`() = runBlocking {
        status = 200
        responseBody = "<html><body>Sign in to continue</body></html>"
        val result = client().upload("Pixel-5", "session-1790680692015.jsonl.gz", file)
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("did not come from the stroke relay"))
    }

    @Test
    fun `a redirect, like a login wall, is a failure and is not followed`() = runBlocking {
        status = 302
        responseBody = ""
        location = "http://127.0.0.1:${server.address.port}/login"
        val result = client().upload("Pixel-5", "session-1790680692015.jsonl.gz", file)
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("HTTP 302"))
        assertEquals("/v1/stroke-data/Pixel-5/session-1790680692015.jsonl.gz", path)
    }

    @Test
    fun `rate limiting, a missing branch and bad files all leave the file on the device`() = runBlocking {
        for (code in listOf(400, 413, 415, 429, 502, 503)) {
            status = code
            val result = client().upload("Pixel-5", "session-1790680692015.jsonl.gz", file)
            assertFalse("HTTP $code must fail", result.isSuccess)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("HTTP $code"))
        }
    }

    @Test
    fun `no network is a failure, not a crash`() = runBlocking {
        val closed = client()
        server.stop(0)
        assertFalse(closed.upload("Pixel-5", "session-1790680692015.jsonl.gz", file).isSuccess)
    }
}
