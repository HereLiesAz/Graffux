package com.hereliesaz.graffitixr.data

import org.junit.Assert.assertEquals
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

    // User text is not a URI just because it looks like one: only Uri-typed fields are localized.
    @Test fun `text that looks like a uri is kept outside uri fields`() {
        val json = """{"name":"file: notes","textParams":{"text":"https://azphalt.store","fontName":"content:x"},""" +
            """"layers":[{"name":"file:///etc/hosts","note":"android.resource://x/1"}]}"""
        assertEquals(json, localizeArchiveUris(json, root))
    }

    /** Every Uri-typed field of the real model, so a new one cannot be added without being localized. */
    private fun uriFieldNames(): Set<String> {
        val found = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        fun walk(d: kotlinx.serialization.descriptors.SerialDescriptor, name: String?) {
            if (d.serialName.removeSuffix("?") == "Uri") {
                name?.let(found::add)
                return
            }
            if (!seen.add(d.serialName + name)) return
            val list = d.kind == kotlinx.serialization.descriptors.StructureKind.LIST
            for (i in 0 until d.elementsCount) {
                walk(d.getElementDescriptor(i), if (list) name else d.getElementName(i))
            }
        }
        walk(com.hereliesaz.graffitixr.common.model.GraffitiProject.serializer().descriptor, null)
        return found
    }

    @Test fun `every uri field in the model is localized`() {
        val names = uriFieldNames()
        assertTrue("found only $names", names.containsAll(listOf("uri", "thumbnailUri", "targetImageUris")))
        for (key in names) {
            assertEquals(key, """{"$key":""}""", localizeArchiveUris("""{"$key":"file:///etc/hosts"}""", root))
            assertEquals(
                key,
                """{"$key":"${root}a.png"}""",
                localizeArchiveUris("""{"$key":"file:///x/projects/old/a.png"}""", root),
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
}
