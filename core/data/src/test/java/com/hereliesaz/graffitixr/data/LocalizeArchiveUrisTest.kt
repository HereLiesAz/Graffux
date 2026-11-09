package com.hereliesaz.graffitixr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizeArchiveUrisTest {
    private val root = "file:///data/user/0/app/files/projects/new/"

    @Test fun `a file inside the archived project is re-rooted here`() = assertEquals(
        """{"uri":"${root}layers/a.png"}""",
        localizeArchiveUris("""{"uri":"file:///data/user/0/other/files/projects/old/layers/a.png"}""", root),
    )

    @Test fun `a content uri is dropped`() =
        assertEquals("""{"uri":""}""", localizeArchiveUris("""{"uri":"content://media/external/images/1"}""", root))

    @Test fun `a file outside any project is dropped`() =
        assertEquals("""{"uri":""}""", localizeArchiveUris("""{"uri":"file:///sdcard/secret.png"}""", root))

    @Test fun `parent segments are refused`() = assertEquals(
        """{"uri":""}""",
        localizeArchiveUris("""{"uri":"file:///x/projects/old/../victim/a.png"}""", root),
    )

    @Test fun `other strings are untouched`() =
        assertEquals("""{"name":"Wall piece"}""", localizeArchiveUris("""{"name":"Wall piece"}""", root))

    /** Uri.getPath() decodes percent-escapes, so these all name a parent directory once opened. */
    @Test fun `percent-encoded and other disguised traversal is refused`() {
        for (rest in listOf(
            "%2e%2e/victim/a.png", "%2E%2E/victim/a.png", ".%2e/victim/a.png", "..%2fvictim/a.png",
            "a%2f..%2f..%2fvictim.png", "a%5c..%5cvictim.png", "%252e%252e/victim/a.png",
            "a/..%00/b.png", "a/..?x/b.png", "a/..#x", "a/%2e/b.png", "a%00.png",
        )) {
            assertEquals(
                rest,
                """{"uri":""}""",
                localizeArchiveUris("""{"uri":"file:///x/projects/old/$rest"}""", root),
            )
        }
    }

    // The localizer must see the JSON string the model will see, not its escaped spelling.
    @Test fun `json-escaped uris are localized like plain ones`() {
        val inside = """{"uri":"file:\/\/\/x\/projects\/old\/layers\/a.png"}"""
        assertEquals("""{"uri":"${root}layers/a.png"}""", localizeArchiveUris(inside, root))
        for (raw in listOf(
            """file:\/\/\/etc\/hosts""",
            """file:///etc/hosts""",
            """file:\/\/\/x\/projects\/old\/..\/victim\/a.png""",
            """file:///x/projects/old/../victim/a.png""",
            """file:///x/projects/old/%2e%2e/victim/a.png""",
            """file:///etc/hosts""",
        )) {
            assertEquals(raw, """{"uri":""}""", localizeArchiveUris("""{"uri":"$raw"}""", root))
        }
    }

    @Test fun `uri fields without a scheme or with a single slash are dropped`() {
        assertEquals("""{"uri":""}""", localizeArchiveUris("""{"uri":"/etc/hosts"}""", root))
        assertEquals(
            """{"overlayImageUri":""}""",
            localizeArchiveUris("""{"overlayImageUri":"/data/data/p/files/x.png"}""", root),
        )
        assertEquals("""{"uri":""}""", localizeArchiveUris("""{"uri":"file:/etc/hosts"}""", root))
        assertEquals(
            """{"targetImageUris":["","${root}ok.png"]}""",
            localizeArchiveUris("""{"targetImageUris":["/etc/a","file:///x/projects/old/ok.png"]}""", root),
        )
    }

    @Test fun `text that merely looks odd is untouched`() {
        val json = """{"name":"Mural: v2","note":"a/b","layers":[{"id":"1","opacity":1.0E-4,"uri":""}]}"""
        assertEquals(json, localizeArchiveUris(json, root))
    }

    @Test fun `escaped question marks and percent signs in names survive`() {
        // Uri.fromFile writes "what?.png" as what%3F.png and "50%.png" as 50%25.png; neither is traversal.
        for (name in listOf("what%3F.png", "50%25.png", "a%23b.png")) {
            assertEquals(
                name,
                """{"uri":"${root}layers/$name"}""",
                localizeArchiveUris("""{"uri":"file:///x/projects/old/layers/$name"}""", root),
            )
        }
    }

    // User text is not a URI just because it looks like one: only Uri-typed positions are localized.
    @Test fun `text that looks like a uri is kept at known text positions`() {
        val json = """{"name":"file: notes","layers":[{"name":"file:///etc/hosts","opacity":1.0,""" +
            """"textParams":{"text":"https://azphalt.store","fontName":"content:x"}}]}"""
        assertEquals(json, localizeArchiveUris(json, root))
    }

    // A newer-format project is installed from this JSON as written, so a field this build does not know
    // is neutralized if it could name a file; fields the model knows keep their text.
    @Test fun `unknown fields of a newer format cannot carry a file reference`() {
        val json = """{"formatVersion":99,"name":"Mural","layers":[{"name":"file: notes",""" +
            """"sourceImage":"file:///data/data/p/files/other.png","modelFile":"../../x",""" +
            """"stencil":"/etc/hosts","other":"a\\..\\b","frames":["content://m/1","ok"],"label":"Mural"}]}"""
        val expected = """{"formatVersion":99,"name":"Mural","layers":[{"name":"file: notes",""" +
            """"sourceImage":"","modelFile":"","stencil":"","other":"","frames":["","ok"],"label":"Mural"}]}"""
        assertEquals(expected, localizeArchiveUris(json, root))
    }

    @Test fun `a name the model uses elsewhere gains nothing under an unknown parent`() {
        assertEquals(
            """{"formatVersion":99,"extra":{"0":"","name":""}}""",
            localizeArchiveUris(
                """{"formatVersion":99,"extra":{"0":"file:///data/data/p/files/other.png","name":"../../x"}}""",
                root,
            ),
        )
        assertEquals(
            """{"layerFiles":{"name":""}}""",
            localizeArchiveUris("""{"layerFiles":{"name":"file:///etc/hosts"}}""", root),
        )
        assertEquals(
            """{"unknownList":{"0":"","1":""},"also":[{"uri":"","text":""}]}""",
            localizeArchiveUris(
                """{"unknownList":{"0":"/etc/a","1":"../b"},"also":[{"uri":"/x","text":"../y"}]}""",
                root,
            ),
        )
    }

    @Test fun `file references in unknown fields are caught in any spelling`() {
        for (raw in listOf(
            "%2e%2e%2f%2e%2e%2fother", "..%2fx", "a%2f..%2fb", "%2fetc%2fhosts", "%252e%252e%252fx",
            "C:\\\\x", "c:/x", "\\\\\\\\host\\\\share", "a%00b", "a\\u0000b", "x/../y", "..\\\\y",
        )) {
            assertEquals(raw, """{"sourceImage":""}""", localizeArchiveUris("""{"sourceImage":"$raw"}""", root))
        }
    }

    @Test fun `web links and plain text in unknown fields are kept, a leading slash is the one tradeoff`() {
        val kept = """{"helpUrl":"https://azphalt.store","docs":"HTTP://x.example/a/../b","note":"see you",""" +
            """"ratio":"3/4","dots":"a..b","pct":"50%","partial":"%zz"}"""
        assertEquals(kept, localizeArchiveUris(kept, root))
        // Unknown text that starts with "/" cannot be told from an absolute path, so it is blanked from the
        // installed copy: pinned here so the loss is a decision and not a surprise.
        assertEquals("""{"note":""}""", localizeArchiveUris("""{"note":"/shrug"}""", root))
    }

    @Test fun `an unknown field naming a file inside the project is re-rooted`() = assertEquals(
        """{"sourceImage":"${root}layers/a.png"}""",
        localizeArchiveUris("""{"sourceImage":"file:///x/projects/old/layers/a.png"}""", root),
    )

    @Test fun `unicode digits are not hex in an escape`() {
        // Android's Uri.decode reads ASCII hex only: %\uFF12f is a literal, harmless file name.
        val name = "a%\uFF12fb.png"
        assertEquals(
            """{"uri":"${root}layers/$name"}""",
            localizeArchiveUris("""{"uri":"file:///x/projects/old/layers/$name"}""", root),
        )
    }

    /** Every path through the real model's descriptors that ends at a Uri ("[]" is a list item). */
    private fun uriPaths(): List<List<String>> {
        val found = mutableListOf<List<String>>()
        val kinds = mutableSetOf<Any>()
        fun walk(d: kotlinx.serialization.descriptors.SerialDescriptor, path: List<String>, stack: Set<String>) {
            kinds += d.kind
            if (d.serialName.removeSuffix("?") == "Uri") {
                found += path
                return
            }
            val k = d.kind
            val name = d.serialName.removeSuffix("?")
            if (k == kotlinx.serialization.descriptors.StructureKind.CLASS) {
                if (name in stack) return
                for (i in 0 until d.elementsCount) {
                    walk(d.getElementDescriptor(i), path + d.getElementName(i), stack + name)
                }
            } else if (k == kotlinx.serialization.descriptors.StructureKind.LIST) {
                walk(d.getElementDescriptor(0), path + "[]", stack)
            } else if (k == kotlinx.serialization.descriptors.StructureKind.MAP) {
                walk(d.getElementDescriptor(1), path + "key", stack)
            }
        }
        walk(com.hereliesaz.graffitixr.common.model.GraffitiProject.serializer().descriptor, emptyList(), emptySet())
        // The lockstep walk understands classes, lists, maps and primitives; a polymorphic or contextual
        // type in the model would be treated as unknown, so adding one must be a conscious change here.
        assertTrue(kinds.toString(), kinds.none {
            it is kotlinx.serialization.descriptors.PolymorphicKind ||
                it == kotlinx.serialization.descriptors.SerialKind.CONTEXTUAL
        })
        return found
    }

    private fun wrap(path: List<String>, leaf: String): String = when {
        path.isEmpty() -> leaf
        path[0] == "[]" -> "[" + wrap(path.drop(1), leaf) + "]"
        else -> """{"${path[0]}":""" + wrap(path.drop(1), leaf) + "}"
    }

    @Test fun `every uri position in the model is localized there`() {
        val paths = uriPaths()
        val names = paths.map { it.last { p -> p != "[]" } }
        assertTrue(names.toString(), names.containsAll(listOf("uri", "thumbnailUri", "targetImageUris")))
        for (path in paths) {
            // A web link is kept at an unknown position but is not a project file at a Uri one.
            val blank = wrap(path, "\"\"")
            assertEquals(path.toString(), blank, localizeArchiveUris(wrap(path, "\"https://x.example/a.png\""), root))
            assertEquals(path.toString(), blank, localizeArchiveUris(wrap(path, "\"file:///etc/hosts\""), root))
            assertEquals(
                path.toString(),
                wrap(path, "\"${root}a.png\""),
                localizeArchiveUris(wrap(path, "\"file:///x/projects/old/a.png\""), root),
            )
        }
    }

    // Android's Uri.decode is lenient: a bad escape elsewhere in the segment must not hide a good one.
    @Test fun `a malformed escape does not hide traversal in the same segment`() {
        for (rest in listOf("..%2f..%2fetc%2fhosts%zz", "%zz..%2fx", "a%2f..%2f%", "%2e%2e%zz%2fx")) {
            assertEquals(
                rest,
                """{"uri":""}""",
                localizeArchiveUris("""{"uri":"file:///x/projects/old/$rest"}""", root),
            )
        }
        for (name in listOf("100%.png", "50%zz.png", "a%.b%25c.png")) {
            assertEquals(
                name,
                """{"uri":"${root}$name"}""",
                localizeArchiveUris("""{"uri":"file:///x/projects/old/$name"}""", root),
            )
        }
    }

    @Test fun `an escaped space in a file name is kept`() = assertEquals(
        """{"uri":"${root}layers/my%20layer.png"}""",
        localizeArchiveUris("""{"uri":"file:///x/projects/old/layers/my%20layer.png"}""", root),
    )

    // Depth is bounded before any recursive work: a few KB of brackets must not take the process down.

    @Test fun `json nested far deeper than any project is refused cleanly`() {
        val depth = 100_000
        val arrays = """{"id":"x","extra":""" + "[".repeat(depth) + "]".repeat(depth) + "}"
        val objects = """{"id":"x","extra":""" + """{"a":""".repeat(depth) + "1" + "}".repeat(depth) + "}"
        for (deep in listOf(arrays, objects)) {
            val failure = runCatching { localizeArchiveUris(deep, root) }.exceptionOrNull()
            assertTrue(failure.toString(), failure is IllegalArgumentException)
        }
    }

    @Test fun `ordinary depth is untouched`() {
        val nested = """{"id":"x","extra":""" + "[".repeat(20) + "\"ok\"" + "]".repeat(20) + "}"
        assertEquals(nested, localizeArchiveUris(nested, root))
    }

    @Test fun `brackets inside strings do not count as depth`() {
        val json = """{"name":"""" + "[".repeat(500) + """","x":"\\"{{{{"}"""
        assertEquals(json, localizeArchiveUris(json, root))
    }

    // Unknown fields: any URI scheme, in any case, with or without "//".
    @Test fun `any scheme in an unknown field is neutralized`() {
        for (raw in listOf("FILE:/etc/hosts", "File:///x", "jar:file:///x!/y", "content:x", "android.resource:x/1")) {
            assertEquals(raw, """{"x":""}""", localizeArchiveUris("""{"x":"$raw"}""", root))
        }
        val kept = """{"x":"Mural: v2","y":"https://azphalt.store","z":"HTTPS://azphalt.store","w":"12:30"}"""
        assertEquals(kept, localizeArchiveUris(kept, root))
    }

    // The depth scan must know which quotes end a string: \" does not, and \\ followed by " does.

    @Test fun `an escaped quote followed by many brackets inside a string is not depth`() {
        val json = """{"name":"a\"""" + "[".repeat(100) + """"}"""
        assertEquals(json, localizeArchiveUris(json, root))
        assertFalse(exceedsJsonDepth(json))
    }

    @Test fun `real depth after a string with an escaped quote is still refused`() {
        val deep = """{"name":"a\"b","extra":""" + "[".repeat(100_000) + "]".repeat(100_000) + "}"
        assertTrue(exceedsJsonDepth(deep))
        val failure = runCatching { localizeArchiveUris(deep, root) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is IllegalArgumentException)
    }

    @Test fun `an escaped backslash before the closing quote ends the string`() {
        val deep = """{"name":"a\\","extra":""" + "[".repeat(100) + "]".repeat(100) + "}"
        assertTrue(exceedsJsonDepth(deep))
        val shallow = """{"name":"a\\","extra":[["x"]]}"""
        assertFalse(exceedsJsonDepth(shallow))
        assertEquals(shallow, localizeArchiveUris(shallow, root))
    }
}
