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
}
