package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.ExtensionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the azphalt registry client (spec/repository-api.md): request URLs, response parsing, and the
 * mapping to catalog cards. Transport is faked, so this is a pure unit test.
 */
class RepositoryApiTest {

    /** Records the URLs requested and returns canned bodies keyed by a path fragment. */
    private class FakeHttp(private val bodies: Map<String, String>) {
        val requested = mutableListOf<String>()
        fun get(url: String, @Suppress("UNUSED_PARAMETER") headers: Map<String, String>): String {
            requested += url
            return bodies.entries.firstOrNull { url.contains(it.key) }?.value
                ?: error("no fake body for $url")
        }
    }

    @Test
    fun `search parses the paginated response and hits the right URL`() {
        val fake = FakeHttp(mapOf(
            "/packages" to """
                {
                  "packages": [
                    { "id": "com.a.lut", "name": "A LUT", "author": "A", "version": "1.2.0",
                      "types": ["lut"], "tags": ["warm"], "priceStatus": "free" },
                    { "id": "com.b.filter", "name": "B Filter", "author": "B", "version": "2.0.0",
                      "types": ["code"], "priceStatus": "paid" }
                  ],
                  "total": 2, "page": 1, "pages": 1
                }
            """.trimIndent(),
        ))
        val client = RepositoryClient("https://reg.example/", fake::get)

        val resp = client.search(q = "grade", types = listOf("lut"), page = 1)

        assertEquals(2, resp.total)
        assertEquals(1, resp.pages)
        assertEquals("com.a.lut", resp.packages[0].id)
        assertTrue(resp.packages[1].isPaid)
        assertFalse(resp.packages[0].isPaid)
        // URL is built off the trimmed base with the query params.
        val url = fake.requested.single()
        assertTrue(url.startsWith("https://reg.example/packages?"))
        assertTrue(url.contains("q=grade"))
        assertTrue(url.contains("types=lut"))
        assertTrue(url.contains("page=1"))
    }

    @Test
    fun `official store base url targets the www api host`() {
        assertEquals("https://www.azphalt.store/api", AzphaltStore.REGISTRY_BASE_URL)
    }

    @Test
    fun `listPackages parses the official store's flat array and maps live fields`() {
        val fake = FakeHttp(mapOf(
            "/packages" to """
                [
                  { "id":"com.azphalt.model.vosk","name":"Vosk Transcription","author":"Azphalt Models",
                    "version":"0.22.0","kind":"asset","capabilities":["assets"],"downloads":6200,
                    "ratingCount":0,"updatedAt":"2026-07-20T01:00:00Z","targetApps":[],
                    "mediaDomains":["model"],"types":["model"],"price":null },
                  { "id":"com.paid.pack","name":"Paid Pack","version":"1.0.0","kind":"asset",
                    "types":["lut"],"downloads":10,"price":499 }
                ]
            """.trimIndent(),
        ))
        val client = RepositoryClient("https://www.azphalt.store/api", fake::get)

        val pkgs = client.listPackages()
        assertEquals(2, pkgs.size)
        val vosk = pkgs[0]
        assertEquals(ExtensionKind.ASSET, vosk.kind)   // declared kind is used directly
        assertEquals(6200, vosk.downloads)
        assertFalse(vosk.isPaid)                        // price: null → free
        assertTrue(pkgs[1].isPaid)                      // numeric price → paid

        // Live kind + downloads flow onto the catalog card; an asset package is installable.
        val card = vosk.toMarketplaceEntry(
            "https://www.azphalt.store/api/packages/com.azphalt.model.vosk/versions/0.22.0/download",
        )
        assertEquals(ExtensionKind.ASSET, card.kind)
        assertEquals(6200, card.downloads)
        assertTrue(card.installable)
        assertTrue(fake.requested.single().endsWith("/packages"))
    }

    @Test
    fun `discover parses identity, auth and signing keys`() {
        val fake = FakeHttp(mapOf(
            "/.well-known/azphalt-repository.json" to """
                {
                  "name": "Official SFX Library", "version": "0.1",
                  "description": "High-quality sound effects.",
                  "auth": { "type": "oauth2", "url": "https://sfx.example.com/oauth/authorize" },
                  "supportedTypes": ["audio", "lut"], "profiles": ["video-audio"],
                  "signingKeys": [{ "publicKey": "MCowBQYDK2VwAyEA", "keyId": "reg-2026", "label": "Official SFX Library" }]
                }
            """.trimIndent(),
        ))
        val info = RepositoryClient("https://sfx.example.com", fake::get).discover()

        assertEquals("0.1", info.version)
        assertEquals("oauth2", info.auth?.type)
        assertEquals(listOf("audio", "lut"), info.supportedTypes)
        assertEquals("reg-2026", info.signingKeys.single().keyId)
        assertTrue(fake.requested.single().endsWith("/.well-known/azphalt-repository.json"))
    }

    @Test
    fun `revocations feed parses`() {
        val fake = FakeHttp(mapOf(
            "/revocations" to """
                { "revocations": [
                  { "id": "com.sfx.explosions-pack", "version": "1.1.0", "reason": "malware", "revokedAt": "2026-06-01T09:30:00Z" }
                ] }
            """.trimIndent(),
        ))
        val feed = RepositoryClient("https://reg.example", fake::get).revocations()

        assertEquals("com.sfx.explosions-pack", feed.revocations.single().id)
        assertEquals("malware", feed.revocations.single().reason)
        assertTrue(fake.requested.single().endsWith("/revocations"))
    }

    @Test
    fun `checkUpdates posts installed versions and parses newer releases`() {
        val posted = mutableListOf<Pair<String, String>>()
        val client = RepositoryClient(
            "https://reg.example",
            httpGet = { _, _ -> error("GET not expected") },
            httpPost = { url, body, _ ->
                posted += url to body
                """{ "updates": [ { "id": "com.sfx.explosions-pack", "latest": "1.2.0" } ] }"""
            },
        )

        val resp = client.checkUpdates(listOf(
            UpdateQuery("com.sfx.explosions-pack", "1.1.0"),
            UpdateQuery("com.hereliesaz.halftone", "1.2.0"),
        ))

        assertEquals("1.2.0", resp.updates.single().latest)
        val (url, body) = posted.single()
        assertEquals("https://reg.example/updates", url)
        assertTrue(body.contains("com.sfx.explosions-pack"))
        assertTrue(body.contains("1.1.0"))
    }

    @Test
    fun `download url is well-formed and encodes segments`() {
        val client = RepositoryClient("https://reg.example", { _, _ -> "" })
        assertEquals(
            "https://reg.example/packages/com.a.lut/versions/1.2.0/download",
            client.downloadUrl("com.a.lut", "1.2.0"),
        )
    }

    @Test
    fun `package maps to a catalog card with kind inferred from types`() {
        val asset = RepositoryPackage("com.a.lut", "A", version = "1.0.0", types = listOf("lut"))
        val code = RepositoryPackage("com.b.f", "B", version = "1.0.0", types = listOf("code"))
        val mixed = RepositoryPackage("com.c.m", "C", version = "1.0.0", types = listOf("code", "lut"))

        assertEquals(ExtensionKind.ASSET, asset.toMarketplaceEntry("u").kind)
        assertEquals(ExtensionKind.CODE, code.toMarketplaceEntry("u").kind)
        assertEquals(ExtensionKind.MIXED, mixed.toMarketplaceEntry("u").kind)

        val paidCard = RepositoryPackage("p", "P", version = "1.0.0", types = listOf("lut"), priceStatus = "paid")
            .toMarketplaceEntry("https://reg.example/packages/p/versions/1.0.0/download")
        assertEquals("Paid", paidCard.priceLabel)
        assertEquals("https://reg.example/packages/p/versions/1.0.0/download", paidCard.source)
        // Asset card is installable by this host; a pure-code one is not.
        assertTrue(asset.toMarketplaceEntry("u").installable)
        assertFalse(code.toMarketplaceEntry("u").installable)

        // A registry preview image is carried onto the catalog card for the browse grid.
        val withPreview = RepositoryPackage(
            "com.a.lut", "A", version = "1.0.0", types = listOf("lut"),
            preview = com.hereliesaz.graffitixr.common.azphalt.Preview(image = "https://cdn.example/card.png"),
        ).toMarketplaceEntry("u")
        assertEquals("https://cdn.example/card.png", withPreview.previewImage)
    }

    // ---- Extension packs (spec/pack.md § Discovery & installation) ----

    @Test
    fun `getPack reads the pack block off the package detail`() {
        val fake = FakeHttp(mapOf(
            "/packages/com.example.paint-starter" to """
                { "id":"com.example.paint-starter","name":"Paint Starter","kind":"pack","versions":["1.0.0"],
                  "pack":{ "entries":[
                    {"id":"com.foldlab.filmluts","version":"1.0.0","required":true},
                    {"id":"com.brushery.inkbrushes","required":false} ] } }
            """.trimIndent(),
        ))
        val pack = RepositoryClient("https://reg.example", fake::get).getPack("com.example.paint-starter")
        assertEquals(2, pack?.entries?.size)
        assertEquals("com.foldlab.filmluts", pack?.entries?.get(0)?.id)
        assertNull(RepositoryClient("https://reg.example", FakeHttp(mapOf(
            "/packages/plain" to """{ "id":"plain","name":"Plain","versions":["1.0.0"] }""",
        ))::get).getPack("plain"))
    }

    @Test
    fun `resolvePack pins versions, carries paid status, and reports unresolved members`() {
        val fake = FakeHttp(mapOf(
            // Pack detail lists three members: one pinned, one resolve-latest, one that doesn't resolve.
            "/packages/com.example.paint-starter" to """
                { "id":"com.example.paint-starter","name":"Paint Starter","kind":"pack","versions":["1.0.0"],
                  "pack":{ "entries":[
                    {"id":"com.foldlab.filmluts","version":"1.0.0","required":true,"note":"core"},
                    {"id":"com.brushery.inkbrushes","required":true},
                    {"id":"com.hereliesaz.halftone","required":false},
                    {"id":"com.missing.member"} ] } }
            """.trimIndent(),
            "/packages/com.foldlab.filmluts" to
                """{ "id":"com.foldlab.filmluts","name":"Film LUTs","versions":["2.0.0","1.0.0"],"priceStatus":"free" }""",
            "/packages/com.brushery.inkbrushes" to
                """{ "id":"com.brushery.inkbrushes","name":"Ink Brushes","versions":["3.1.0","3.0.0"] }""",
            "/packages/com.hereliesaz.halftone" to
                """{ "id":"com.hereliesaz.halftone","name":"Halftone","versions":["1.0.0"],"priceStatus":"paid" }""",
            // com.missing.member has no body → detail() throws → member is reported unresolved.
        ))
        val resolved = RepositoryClient("https://reg.example", fake::get).resolvePack("com.example.paint-starter")

        assertEquals(3, resolved.entries.size)
        val luts = resolved.entries[0]
        assertEquals("1.0.0", luts.version)             // honours the entry pin
        assertTrue(luts.required)
        assertFalse(luts.paid)
        assertEquals("core", luts.note)
        assertEquals(
            "https://reg.example/packages/com.foldlab.filmluts/versions/1.0.0/download",
            luts.downloadUrl,
        )
        assertEquals("3.1.0", resolved.entries[1].version) // absent pin → member's newest
        assertTrue(resolved.entries[2].paid)               // member's own gate, even in a free pack
        assertFalse(resolved.entries[2].required)          // recommended, not base set
        assertEquals(listOf("com.missing.member"), resolved.unresolved)
    }
}
