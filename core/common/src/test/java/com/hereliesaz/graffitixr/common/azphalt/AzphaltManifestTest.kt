package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AzphaltManifestTest {

    @Test
    fun `parses the invert example manifest`() {
        val m = parseManifest(
            """
            {
              "azphalt": "0.1",
              "id": "com.hereliesaz.invert",
              "name": "Invert",
              "version": "1.0.0",
              "kind": "code",
              "license": "MIT",
              "author": "Az",
              "description": "Invert layer colors, by adjustable strength.",
              "compat": ">=0.1",
              "entry": "code/main.js",
              "runtime": "js",
              "capabilities": ["bitmap", "params", "canvas"],
              "contributes": { "filters": [{ "id": "invert", "name": "Invert", "entry": "invert", "ui": "ui/panel.json" }] },
              "files": {}
            }
            """.trimIndent(),
        )
        assertEquals("com.hereliesaz.invert", m.id)
        assertEquals(ExtensionKind.CODE, m.kind)
        assertEquals(Runtime.JS, m.runtime)
        assertEquals(listOf(Capability.BITMAP, Capability.PARAMS, Capability.CANVAS), m.capabilities)
        assertEquals("invert", m.contributes?.filters?.single()?.id)
    }

    @Test
    fun `parses an asset manifest with lut assets`() {
        val m = parseManifest(
            """
            {
              "azphalt": "0.1", "id": "com.filmluts.teal", "name": "Teal LUT",
              "version": "1.0.0", "kind": "asset", "license": "CC-BY-4.0", "compat": ">=0.1",
              "assets": [{ "type": "lut", "path": "assets/teal.cube" }], "files": {}
            }
            """.trimIndent(),
        )
        assertEquals(ExtensionKind.ASSET, m.kind)
        assertEquals(AssetType.LUT, m.assets.single().type)
        assertEquals("assets/teal.cube", m.assets.single().path)
        assertNull(m.runtime)
        assertTrue(m.capabilities.isEmpty())
    }

    @Test
    fun `tolerates unknown future fields`() {
        val m = parseManifest(
            """
            { "azphalt":"0.2","id":"x.y","name":"N","version":"1.0.0","kind":"mixed",
              "license":"MIT","compat":">=0.2","files":{},"somethingNew":{"a":1},"pricing":42 }
            """.trimIndent(),
        )
        assertEquals(ExtensionKind.MIXED, m.kind)
    }

    @Test
    fun `parses shader and transition assets with params and ui`() {
        val m = parseManifest(
            """
            {
              "azphalt": "0.1", "id": "com.fx.pack", "name": "FX", "version": "1.0.0",
              "kind": "asset", "license": "MIT", "compat": ">=0.1",
              "assets": [
                { "type": "shader", "path": "assets/glow.fs", "params": { "format": "isf" }, "ui": "ui/glow.json" },
                { "type": "transition", "path": "assets/wipe.glsl", "params": { "format": "gl-transition" } }
              ],
              "files": {}
            }
            """.trimIndent(),
        )
        assertEquals(AssetType.SHADER, m.assets[0].type)
        assertEquals("ui/glow.json", m.assets[0].ui)
        assertEquals("isf", m.assets[0].params?.get("format")?.let { it.toString().trim('"') })
        assertEquals(AssetType.TRANSITION, m.assets[1].type)
        assertNull(m.assets[1].ui)
    }

    @Test
    fun `parses AI model assets with role and byteSize`() {
        val m = parseManifest(
            """
            {
              "azphalt": "0.1", "id": "com.ai.seg", "name": "Segmenter", "version": "1.0.0",
              "kind": "asset", "license": "MIT", "compat": ">=0.1",
              "targetApps": ["com.hereliesaz.graffitixr"],
              "assets": [
                { "type": "tflite", "path": "models/seg.tflite", "role": "segmentation", "byteSize": 4194304 },
                { "type": "lut", "path": "assets/grade.cube" }
              ],
              "files": {}
            }
            """.trimIndent(),
        )
        assertEquals(AssetType.TFLITE, m.assets[0].type)
        assertTrue(m.assets[0].type.isModel)
        assertEquals("segmentation", m.assets[0].role)
        assertEquals(4194304L, m.assets[0].byteSize)
        assertEquals(listOf("com.hereliesaz.graffitixr"), m.targetApps)
        assertFalse(m.assets[1].type.isModel)
    }

    @Test
    fun `unrecognized asset type parses as UNKNOWN instead of throwing`() {
        // A type newer than this build knows about must not break the parse.
        val m = parseManifest(
            """
            {
              "azphalt": "0.1", "id": "com.x.y", "name": "N", "version": "1.0.0",
              "kind": "asset", "license": "MIT", "compat": ">=0.1",
              "assets": [{ "type": "quantum-brush", "path": "assets/q.bin" }],
              "files": {}
            }
            """.trimIndent(),
        )
        assertEquals(AssetType.UNKNOWN, m.assets[0].type)
    }

    @Test
    fun `parses the app and mcp kinds and tolerates an unknown one`() {
        val app = parseManifest(
            """
            { "azphalt":"0.1","id":"com.x.app","name":"App","version":"1.0.0","kind":"app",
              "license":"MIT","compat":">=0.1","app":{"platforms":{"android":{"packageId":"com.x"}}},"files":{} }
            """.trimIndent(),
        )
        assertEquals(ExtensionKind.APP, app.kind)
        val mcp = parseManifest(
            """
            { "azphalt":"0.1","id":"com.x.mcp","name":"MCP","version":"1.0.0","kind":"mcp",
              "license":"MIT","compat":">=0.1","mcp":{"remote":"https://x.example/sse"},"files":{} }
            """.trimIndent(),
        )
        assertEquals(ExtensionKind.MCP, mcp.kind)
        // A kind newer than this build maps to UNKNOWN instead of throwing (parses, then the installer
        // refuses it) — matching AssetType/Capability's forward-compat behaviour.
        val future = parseManifest(
            """
            { "azphalt":"0.1","id":"com.x.future","name":"Future","version":"1.0.0","kind":"theme",
              "license":"MIT","compat":">=0.1","files":{} }
            """.trimIndent(),
        )
        assertEquals(ExtensionKind.UNKNOWN, future.kind)
    }

    @Test
    fun `parses the time and audio capabilities and tolerates an unknown one`() {
        val m = parseManifest(
            """
            { "azphalt":"0.1","id":"com.x.code","name":"N","version":"1.0.0","kind":"code","license":"MIT",
              "compat":">=0.1","entry":"code/main.js","runtime":"js",
              "capabilities":["audio","time","telepathy"],"files":{} }
            """.trimIndent(),
        )
        // Named 0.1 capabilities resolve; a capability newer than this build maps to UNKNOWN, not a throw.
        assertEquals(listOf(Capability.AUDIO, Capability.TIME, Capability.UNKNOWN), m.capabilities)
    }

    @Test
    fun `parses template and overlay asset types and a preview`() {
        val m = parseManifest(
            """
            {
              "azphalt":"0.1","id":"com.x.tpl","name":"Templates","version":"1.0.0",
              "kind":"asset","license":"MIT","compat":">=0.1",
              "preview": { "image": "preview/card.png", "clip": "https://cdn.example/x.mp4" },
              "assets":[
                { "type":"template", "path":"assets/story.json" },
                { "type":"overlay",  "path":"assets/frame.png" }
              ],
              "files":{}
            }
            """.trimIndent(),
        )
        assertEquals(AssetType.TEMPLATE, m.assets[0].type)
        assertEquals(AssetType.OVERLAY, m.assets[1].type)
        assertEquals("preview/card.png", m.preview?.image)
        assertEquals("https://cdn.example/x.mp4", m.preview?.clip)
    }

    @Test
    fun `parses remote assets, standalone, tags and transition contributions`() {
        val m = parseManifest(
            """
            {
              "azphalt":"0.1","id":"com.x.mix","name":"Mix","version":"1.0.0","kind":"mixed",
              "license":"MIT","compat":">=0.1","entry":"code/main.js","runtime":"js",
              "contributes":{ "transitions":[{ "id":"wipe","name":"Wipe","entry":"wipe" }] },
              "assets":[
                { "type":"lut", "path":"assets/base.cube", "tags":["warm"] },
                { "type":"shader", "path":"assets/fx.glsl", "standalone":false },
                { "type":"tflite", "path":"", "remoteUrl":"https://cdn.example/m.tflite",
                  "checksum":"sha256-abc", "byteSize":16777216, "role":"depth" }
              ],
              "files":{}
            }
            """.trimIndent(),
        )
        // standalone defaults true; an explicit false is honoured (asset-only hosts skip it).
        assertTrue(m.assets[0].standalone)
        assertEquals(listOf("warm"), m.assets[0].tags)
        assertFalse(m.assets[1].standalone)
        // Remote (not-bundled) asset: empty path + remoteUrl + checksum.
        assertEquals("", m.assets[2].path)
        assertEquals("https://cdn.example/m.tflite", m.assets[2].remoteUrl)
        assertEquals("sha256-abc", m.assets[2].checksum)
        assertEquals("wipe", m.contributes?.transitions?.single()?.id)
    }

    @Test
    fun `parses lut strength and inputTransfer params`() {
        val m = parseManifest(
            """
            {
              "azphalt":"0.1","id":"com.x.lut","name":"Graded","version":"1.0.0","kind":"asset",
              "license":"MIT","compat":">=0.1",
              "assets":[{ "type":"lut","path":"assets/g.cube","params":{ "strength":0.5,"inputTransfer":"log-c" } }],
              "files":{}
            }
            """.trimIndent(),
        )
        val params = m.assets.single().params
        assertEquals("log-c", params?.get("inputTransfer")?.let { it.toString().trim('"') })
        assertTrue(m.assets.single().standalone)
    }

    @Test
    fun `compat is validated per the single-comparator 0_1 grammar`() {
        // Host implements 0.1; comparator defaults to >=.
        assertTrue(isCompatibleSpec(">=0.1"))
        assertTrue(isCompatibleSpec("0.1"))    // bare == ">="
        assertTrue(isCompatibleSpec("0.0"))    // >=0.0
        assertTrue(isCompatibleSpec("=0.1"))
        assertTrue(isCompatibleSpec("<=0.1"))
        assertTrue(isCompatibleSpec("<0.2"))   // host 0.1 is < 0.2
        assertFalse(isCompatibleSpec(">=0.2"))
        assertFalse(isCompatibleSpec(">=1.0"))
        assertFalse(isCompatibleSpec(">0.1"))  // host is not strictly greater than 0.1
        assertFalse(isCompatibleSpec("=0.2"))
        assertFalse(isCompatibleSpec("<0.1"))  // host is not < 0.1
        // Outside the 0.1 grammar → unparseable → fails closed (matches @azphalt/azp's compatSatisfies).
        assertFalse(isCompatibleSpec("^0.1"))
        assertFalse(isCompatibleSpec("~0.1"))
        assertFalse(isCompatibleSpec(">=0.1 || <0.3"))
        assertFalse(isCompatibleSpec("0.1.0-beta"))
        assertFalse(isCompatibleSpec("latest"))
        assertEquals("0.1", AZPHALT_SPEC_VERSION)
    }

    @Test
    fun `compatSatisfies mirrors the reference across host versions`() {
        assertTrue(compatSatisfies("1.2.3", ">=1.0"))
        assertTrue(compatSatisfies("1.0.0", ">=1"))       // omitted parts are 0
        assertFalse(compatSatisfies("0.9.9", ">=1.0"))
        assertTrue(compatSatisfies("2.0.0", ">1.9"))
        assertTrue(compatSatisfies("1.4.0", "<=1.4"))
        assertTrue(compatSatisfies("1.4.0", "=1.4"))
        assertFalse(compatSatisfies("1.4.1", "=1.4"))     // 1.4 ≡ 1.4.0 ≠ 1.4.1
        assertFalse(compatSatisfies("bogus", ">=0.1"))    // unparseable host → false
        assertFalse(compatSatisfies("1.0.0", "not-a-compat"))
    }

    @Test
    fun `parseCompat rejects grammar outside 0_1 and defaults the comparator`() {
        assertNull(parseCompat("^1.0"))
        assertNull(parseCompat("~1.0"))
        assertNull(parseCompat("1.x"))
        assertNull(parseCompat(">=1.0 <2.0"))
        assertEquals(CompatOp.GE, parseCompat("1.2.3")?.op)   // default comparator
        assertEquals(CompatOp.LT, parseCompat("<0.2")?.op)
        val c = parseCompat(">=0.1")
        assertEquals(0, c?.major); assertEquals(1, c?.minor); assertEquals(0, c?.patch)
    }

    @Test
    fun `carries file digests for integrity`() {
        val m = parseManifest(
            """
            { "azphalt":"0.1","id":"x.y","name":"N","version":"1.0.0","kind":"code","license":"MIT",
              "compat":">=0.1","entry":"code/main.js","runtime":"wasm",
              "files":{"code/main.js":"sha256-abc","ui/panel.json":"sha256-def"} }
            """.trimIndent(),
        )
        assertEquals("sha256-abc", m.files["code/main.js"])
        assertEquals(Runtime.WASM, m.runtime)
    }
}
