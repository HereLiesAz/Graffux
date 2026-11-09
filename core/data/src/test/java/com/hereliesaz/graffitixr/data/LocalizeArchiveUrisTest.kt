package com.hereliesaz.graffitixr.data

import org.junit.Assert.assertEquals
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

    @Test fun `an escaped space in a file name is kept`() = assertEquals(
        """{"uri":"${root}layers/my%20layer.png"}""",
        localizeArchiveUris("""{"uri":"file:///x/projects/old/layers/my%20layer.png"}""", root),
    )
}
