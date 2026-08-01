package com.hereliesaz.graffitixr.data.azphalt

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading what a store app returned (azphalt spec/store-app.md § The result).
 *
 * The case worth pinning is the multi-package one. A browse request may ask for several packages and
 * the spec answers with **one `ClipData` item each**; a host that reads only `intent.data` installs
 * the first and discards the rest silently, which looks to the user like the store lost their
 * selection.
 */
class AzphaltStoreHandoffTest {

    private fun uri(s: String): Uri = mockk<Uri>().also { every { it.toString() } returns s }

    private fun intentWith(data: Uri?, clip: ClipData?): Intent = mockk<Intent>().also {
        every { it.data } returns data
        every { it.clipData } returns clip
    }

    private fun clipOf(vararg uris: Uri): ClipData = mockk<ClipData>().also { clip ->
        every { clip.itemCount } returns uris.size
        uris.forEachIndexed { i, u ->
            every { clip.getItemAt(i) } returns mockk<ClipData.Item>().also { item ->
                every { item.uri } returns u
            }
        }
    }

    @Test
    fun `a single selection comes back as one package`() {
        val only = uri("content://store/a.azp")
        assertEquals(listOf(only), AzphaltStoreHandoff.packageUris(intentWith(only, null)))
    }

    @Test
    fun `every ClipData item is returned, in order`() {
        val a = uri("content://store/a.azp")
        val b = uri("content://store/b.azp")
        val c = uri("content://store/c.azp")
        val result = AzphaltStoreHandoff.packageUris(intentWith(a, clipOf(a, b, c)))
        assertEquals("a multi-package result must not collapse to its first item", listOf(a, b, c), result)
    }

    @Test
    fun `ClipData wins over data when both are set`() {
        // A store that sets both is describing the same selection twice; the ClipData is the one that
        // can express more than one package, so it is the authority.
        val a = uri("content://store/a.azp")
        val b = uri("content://store/b.azp")
        assertEquals(listOf(a, b), AzphaltStoreHandoff.packageUris(intentWith(a, clipOf(a, b))))
    }

    @Test
    fun `nothing returned is no packages, not a crash`() {
        assertTrue(AzphaltStoreHandoff.packageUris(null).isEmpty())
        assertTrue(AzphaltStoreHandoff.packageUris(intentWith(null, null)).isEmpty())
        // An empty ClipData falls through to `data`, which is also absent here.
        val empty = mockk<ClipData>().also { every { it.itemCount } returns 0 }
        assertTrue(AzphaltStoreHandoff.packageUris(intentWith(null, empty)).isEmpty())
    }

    @Test
    fun `the result metadata is read from the documented extras`() {
        val intent = mockk<Intent>().also {
            every { it.getStringExtra(AzphaltStoreHandoff.EXTRA_ID) } returns "com.hereliesaz.azphalt.halftone"
            every { it.getStringExtra(AzphaltStoreHandoff.EXTRA_VERSION) } returns "1.2.0"
            every { it.getStringExtra(AzphaltStoreHandoff.EXTRA_INTEGRITY) } returns "sha256-abc"
            every { it.getStringExtra(AzphaltStoreHandoff.EXTRA_SIGNER_KEY) } returns "MCowBQ…"
            every { it.getStringExtra(AzphaltStoreHandoff.EXTRA_ENTITLEMENT) } returns null
            every { it.getStringExtra(AzphaltStoreHandoff.EXTRA_REPORT_TOKEN) } returns "tok-1"
            every { it.hasExtra(AzphaltStoreHandoff.EXTRA_SIGNED) } returns true
            every { it.getBooleanExtra(AzphaltStoreHandoff.EXTRA_SIGNED, false) } returns true
        }

        val result = AzphaltStoreHandoff.resultOf(intent)
        assertEquals("com.hereliesaz.azphalt.halftone", result.id)
        assertEquals("1.2.0", result.version)
        assertEquals("sha256-abc", result.integrity)
        assertEquals(true, result.signed)
        assertEquals("tok-1", result.reportToken)
        assertNull(result.entitlement)
    }

    @Test
    fun `an absent signed extra is unknown, not false`() {
        // "The store did not say" and "the store said no" are different claims, and only the first
        // is true of a store that omits the extra.
        val intent = mockk<Intent>(relaxed = true).also {
            every { it.hasExtra(AzphaltStoreHandoff.EXTRA_SIGNED) } returns false
            every { it.getStringExtra(any()) } returns null
        }
        assertNull(AzphaltStoreHandoff.resultOf(intent).signed)
    }

    @Test
    fun `no intent yields empty metadata rather than throwing`() {
        val result = AzphaltStoreHandoff.resultOf(null)
        assertNull(result.id)
        assertNull(result.reportToken)
    }
}
