package com.hereliesaz.graffitixr.data.repository

import android.content.Context
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.data.ProjectManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectRepositoryImplTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dispatcherProvider = object : DispatcherProvider {
        override val main: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
        override val unconfined: CoroutineDispatcher = testDispatcher
    }

    @Test
    fun `createProject by name adds to state and calls manager`() = runTest(testDispatcher) {
        val mockManager = mockk<ProjectManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val repo = ProjectRepositoryImpl(context, mockManager)

        val project = repo.createProject("Test Project")
        
        assertEquals("Test Project", project.name)
        coVerify { mockManager.saveProject(context, any(), any()) }
    }

    @Test
    fun `getProject calls manager and returns project`() = runTest(testDispatcher) {
        val mockManager = mockk<ProjectManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val repo = ProjectRepositoryImpl(context, mockManager)
        
        val p = GraffitiProject(id = "none", name = "Test")
        coEvery { mockManager.loadProjectMetadata(context, any()) } returns p
        val result = repo.getProject("none")
        assertEquals(p, result)
    }

    @Test
    fun `deleteProject calls manager and updates state`() = runTest(testDispatcher) {
        val mockManager = mockk<ProjectManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val repo = ProjectRepositoryImpl(context, mockManager)

        repo.deleteProject("1")
        coVerify { mockManager.deleteProject(context, "1") }
    }

    @Test
    fun `updateTargetFingerprint merges against the live project, not a stale disk read`() = runTest(testDispatcher) {
        // Regression: this used to be `getProject(id).copy(targetFingerprintPath = path)` then
        // updateProject(that copy) — a disk read independent of _currentProject, discarding any
        // in-memory-only change (e.g. a layer edit whose own disk write just hasn't happened yet)
        // the instant this ran. Stub the disk read to return something visibly stale/different, so
        // a fix that still consults it at all would leak that staleness into the result.
        val mockManager = mockk<ProjectManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val repo = ProjectRepositoryImpl(context, mockManager)
        coEvery { mockManager.loadProjectMetadata(context, "p1") } returns
            GraffitiProject(id = "p1", name = "Stale", documentWidth = 111)

        val live = GraffitiProject(id = "p1", name = "Live", documentWidth = 999)
        repo.createProject(live) // seeds _currentProject with the in-memory state

        repo.updateTargetFingerprint("p1", "/map/path")

        val result = repo.currentProject.value
        assertEquals("/map/path", result?.targetFingerprintPath)
        assertEquals(999, result?.documentWidth) // the live value, not the stale disk read's 111
        coVerify(exactly = 0) { mockManager.loadProjectMetadata(context, "p1") }
    }

    @Test
    fun `updateTargetFingerprint is a no-op when the id doesn't match the live project`() = runTest(testDispatcher) {
        val mockManager = mockk<ProjectManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val repo = ProjectRepositoryImpl(context, mockManager)
        val live = GraffitiProject(id = "p1", name = "Live")
        repo.createProject(live)

        repo.updateTargetFingerprint("some-other-project", "/map/path")

        assertEquals(null, repo.currentProject.value?.targetFingerprintPath)
    }
}
