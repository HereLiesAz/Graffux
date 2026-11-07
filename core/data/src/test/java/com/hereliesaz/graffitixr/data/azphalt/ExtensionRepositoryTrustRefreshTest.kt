package com.hereliesaz.graffitixr.data.azphalt

import android.content.Context
import com.hereliesaz.graffitixr.common.DispatcherProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A background trust-store refresh must not publish a rescan over an install/uninstall that ran while
 * its network round trip was in flight: that mutation already published its own, correct scan.
 */
class ExtensionRepositoryTrustRefreshTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repository(): ExtensionRepository {
        val context = mockk<Context>()
        every { context.filesDir } returns tmp.newFolder("files")
        every { context.cacheDir } returns tmp.newFolder("cache")
        // Never advanced, so init's launch-time scan and real network refresh never run.
        val idle: CoroutineDispatcher = StandardTestDispatcher()
        val dispatchers = object : DispatcherProvider {
            override val main = idle
            override val io = idle
            override val default = idle
            override val unconfined = idle
        }
        // These bodies carry no root endorsements; this suite is about refresh ordering, not trust.
        return ExtensionRepository(context, dispatchers).also { it.enforceRootEndorsements = false }
    }

    private val keysBody = """{"signingKeys":[{"publicKey":"AAAA","keyId":"k1"}]}"""
    private val otherKeysBody = """{"signingKeys":[{"publicKey":"BBBB","keyId":"k2"}]}"""

    @Test
    fun `refresh skips its rescan when an uninstall ran during the fetch`() {
        val repo = repository()
        val atStart = repo.currentMutationGeneration()

        repo.uninstall("com.example.gone", nowMs = 0L)

        assertTrue(repo.currentMutationGeneration() != atStart)
        assertFalse(repo.applyFetchedTrustStore(keysBody, atStart))
    }

    @Test
    fun `refresh rescans when nothing mutated during the fetch`() {
        val repo = repository()
        val atStart = repo.currentMutationGeneration()

        assertTrue(repo.applyFetchedTrustStore(keysBody, atStart))
        assertEquals(emptyList<InstalledExtension>(), repo.installed.value)
    }

    @Test
    fun `keys are still adopted when the rescan is skipped`() {
        val repo = repository()
        val atStart = repo.currentMutationGeneration()
        repo.uninstall("com.example.gone", nowMs = 0L)

        assertFalse(repo.applyFetchedTrustStore(keysBody, atStart))
        // Same keys again with a current generation: already adopted, so nothing to do.
        assertFalse(repo.applyFetchedTrustStore(keysBody, repo.currentMutationGeneration()))
        // Different keys with a current generation do rescan.
        assertTrue(repo.applyFetchedTrustStore(otherKeysBody, repo.currentMutationGeneration()))
    }

    @Test
    fun `an empty signingKeys list revokes the cached keys but a malformed body does not`() {
        val repo = repository()
        assertTrue(repo.applyFetchedTrustStore(keysBody, repo.currentMutationGeneration()))

        // Not a trust-store document: ignored.
        assertFalse(repo.applyFetchedTrustStore("not json", repo.currentMutationGeneration()))
        assertFalse(repo.applyFetchedTrustStore("""{"other":[]}""", repo.currentMutationGeneration()))
        // Keys are still the original ones: re-applying them is a no-op.
        assertFalse(repo.applyFetchedTrustStore(keysBody, repo.currentMutationGeneration()))

        // A genuine empty list is a revocation and is adopted...
        assertTrue(repo.applyFetchedTrustStore("""{"signingKeys":[]}""", repo.currentMutationGeneration()))
        // ...so the old keys are no longer trusted: adopting them again is a change.
        assertTrue(repo.applyFetchedTrustStore(keysBody, repo.currentMutationGeneration()))
    }

    @Test
    fun `awaitInitialScan returns once the launch-time scan has published`() {
        val context = mockk<Context>()
        every { context.filesDir } returns tmp.newFolder("files2")
        every { context.cacheDir } returns tmp.newFolder("cache2")
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val real = pool.asCoroutineDispatcher()
            val dispatchers = object : DispatcherProvider {
                override val main = real
                override val io = real
                override val default = real
                override val unconfined = real
            }
            val repo = ExtensionRepository(context, dispatchers)
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(5_000) { repo.awaitInitialScan() }
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
