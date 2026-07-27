package com.hereliesaz.graffitixr.data.figma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [FigmaClient] against a canned transport — the reason it takes `httpGet` as a parameter rather
 * than opening its own connections.
 */
class FigmaClientTest {

    private val requests = mutableListOf<Pair<String, Map<String, String>>>()

    private fun client(response: (String) -> String) = FigmaClient(
        token = "tok-123",
        httpGet = { url, headers ->
            requests += url to headers
            response(url)
        },
    )

    @Test
    fun `authenticates with Figma's own token header`() {
        // Figma uses X-Figma-Token for a personal access token, NOT Authorization: Bearer.
        client { """{"id":"1","email":"a@b.c","handle":"az"}""" }.me()
        assertEquals(mapOf("X-Figma-Token" to "tok-123"), requests.single().second)
    }

    @Test
    fun `me parses the account`() {
        val user = client { """{"id":"1","email":"a@b.c","handle":"az"}""" }.me()
        assertEquals("az", user.handle)
    }

    @Test
    fun `unknown response fields do not break parsing`() {
        // Figma adds fields over time; a richer response must keep working.
        val user = client { """{"id":"1","handle":"az","somethingNew":{"a":1},"email":"a@b.c"}""" }.me()
        assertEquals("az", user.handle)
    }

    @Test
    fun `file bounds the traversal depth`() {
        val file = client { """{"name":"Design","document":{"id":"0:0","type":"DOCUMENT"}}""" }
            .file("KEY123")
        assertEquals("Design", file.name)
        val url = requests.single().first
        assertTrue(url, url.startsWith("https://api.figma.com/v1/files/KEY123"))
        assertTrue("should bound depth: $url", url.contains("depth=2"))
    }

    @Test
    fun `images requests png at the given scale and returns the url map`() {
        val urls = client { """{"images":{"1:2":"https://cdn/a.png"},"status":200}""" }
            .images("KEY", listOf("1:2"), scale = 3f)
        assertEquals(mapOf("1:2" to "https://cdn/a.png"), urls)

        val url = requests.single().first
        assertTrue(url, url.contains("format=png"))
        assertTrue(url, url.contains("scale=3"))
        // Node ids contain a colon; it's percent-encoded so the query is unambiguous.
        assertTrue("colon should be encoded: $url", url.contains("ids=1%3A2"))
    }

    @Test
    fun `images clamps the scale to what Figma accepts`() {
        client { """{"images":{},"status":200}""" }.images("KEY", listOf("1:2"), scale = 99f)
        assertTrue(requests.single().first.contains("scale=4"))
    }

    @Test
    fun `images drops nodes Figma declined to render`() {
        // A null URL means "couldn't render this one" — it must not reach the importer as a null.
        val urls = client { """{"images":{"1:2":"https://cdn/a.png","3:4":null},"status":200}""" }
            .images("KEY", listOf("1:2", "3:4"))
        assertEquals(setOf("1:2"), urls.keys)
    }

    @Test
    fun `images surfaces an error payload as an exception`() {
        try {
            client { """{"err":"Not found","status":404}""" }.images("KEY", listOf("1:2"))
            fail("expected FigmaException")
        } catch (e: FigmaException) {
            assertEquals("Not found", e.message)
        }
    }

    @Test
    fun `images makes no request for an empty selection`() {
        assertEquals(emptyMap<String, String>(), client { fail("should not fetch"); "" }.images("KEY", emptyList()))
        assertTrue(requests.isEmpty())
    }
}
