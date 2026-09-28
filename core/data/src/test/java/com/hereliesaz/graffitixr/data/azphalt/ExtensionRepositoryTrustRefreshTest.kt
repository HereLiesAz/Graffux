package com.hereliesaz.graffitixr.data.azphalt

import android.content.Context
import com.hereliesaz.graffitixr.common.DispatcherProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
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
        return ExtensionRepository(context, dispatchers)
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
}
