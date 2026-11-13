package com.hereliesaz.graffux

import android.content.Context
import com.hereliesaz.graffitixr.data.strokedata.StrokeDataStore
import com.hereliesaz.graffitixr.data.strokedata.StrokeRelayClient
import com.hereliesaz.graffitixr.feature.editor.strokedata.STROKE_DATA_PREFS
import com.hereliesaz.graffitixr.feature.editor.strokedata.StrokeContributionConsent
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

/** What [StrokeDataUploader] does with a file for each answer the relay can give. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StrokeDataUploaderFlowTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var server: HttpServer
    private var status = 201
    private var requests = 0

    @Before fun start() {
        StrokeDataStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        val prefs = context.getSharedPreferences(STROKE_DATA_PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        StrokeDataStore.get(context).purgeAll()
        StrokeContributionConsent.declareAge16AndEnable(prefs, now = 0L)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests++
            exchange.requestBody.readBytes()
            val body = if (status in 200..299) """{"status":"stored"}""" else "{}"
            exchange.sendResponseHeaders(status, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
            exchange.close()
        }
        server.start()
        val store = StrokeDataStore.get(context)
        store.append(JSONObject().put("type", "stroke")) { JSONObject().put("schema", 4) }
        store.closeSession()
    }

    @After fun stop() = server.stop(0)

    private fun uploader() = StrokeDataUploader(
        context,
        StrokeRelayClient("http://127.0.0.1:${server.address.port}", Dispatchers.Unconfined),
    )

    private fun pending() = StrokeDataStore.get(context).pending().size

    @Test fun `a 503 keeps the file for the next attempt`() = runBlocking {
        status = 503
        assertEquals(0, uploader().uploadPending())
        assertEquals(1, pending())
    }

    @Test fun `a permanent 400 deletes the file so it cannot block the rest`() = runBlocking {
        status = 400
        assertEquals(0, uploader().uploadPending())
        assertEquals(0, pending())
    }

    @Test fun `a 201 uploads and deletes`() = runBlocking {
        status = 201
        assertEquals(1, uploader().uploadPending())
        assertEquals(0, pending())
    }

    @Test fun `a file recorded before the declaration is deleted without any request`() = runBlocking {
        val prefs = context.getSharedPreferences(STROKE_DATA_PREFS, Context.MODE_PRIVATE)
        StrokeContributionConsent.declareAge16AndEnable(prefs, now = System.currentTimeMillis() + 60_000)
        assertEquals(0, uploader().uploadPending())
        assertEquals(0, pending())
        assertTrue("no request was made", requests == 0)
    }
}
