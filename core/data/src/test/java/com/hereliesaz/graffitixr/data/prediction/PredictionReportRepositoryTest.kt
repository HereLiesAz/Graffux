package com.hereliesaz.graffitixr.data.prediction

import android.content.Context
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.privacy.ReportConsent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Crash and ranking reports are public GitHub issues filed with the user's own token: off by default,
 * and with no token of the user's own they do nothing. There is no other credential to fall back on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PredictionReportRepositoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val unconfined = Dispatchers.Unconfined
    }

    @Test fun `crash reports are off by default`() {
        assertFalse(ReportConsent.crashReportsEnabled(context))
    }

    @Test fun `no token means not connected, and filing does nothing even with consent on`() = runBlocking {
        ReportConsent.setCrashReportsEnabled(context, true)
        val repo = PredictionReportRepository(context, dispatchers)
        assertFalse(repo.isConnected.value)
        // Success here is the no-op path: it returns before any request is built.
        assertTrue(repo.fileIssue("title", "body").isSuccess)
        ReportConsent.setCrashReportsEnabled(context, false)
    }

    @Test fun `no developer or fallback credential exists in the shipped sources`() {
        val roots = listOf("src/main", "../../app/src/main", "../../feature/editor/src/main", "../common/src/main")
            .map(::File).filter { it.isDirectory }
        val kt = roots.flatMap { r -> r.walkTopDown().filter { it.extension == "kt" }.toList() }
        assertTrue("sources not found from ${File(".").absolutePath}", kt.isNotEmpty())
        val tokenShapes = Regex("""(ghp_|github_pat_|gho_|ghs_)[A-Za-z0-9_]{10,}""")
        val issueWriters = kt.filter { it.readText().contains("/issues\"") }.map { it.name }
        assertEquals(listOf("PredictionReportRepository.kt"), issueWriters)
        val buildConfigTokens = Regex("""BuildConfig\.\w*(TOKEN|SECRET|PAT)\w*""")
        for (f in kt) {
            val text = f.readText()
            assertFalse("${f.name} embeds a GitHub token", tokenShapes.containsMatchIn(text))
            assertFalse("${f.name} reads a build-time credential", buildConfigTokens.containsMatchIn(text))
        }
    }
}
