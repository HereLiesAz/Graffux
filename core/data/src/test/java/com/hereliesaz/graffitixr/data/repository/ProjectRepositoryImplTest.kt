package com.hereliesaz.graffitixr.data.repository

import android.content.Context
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.data.ProjectManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProjectRepositoryImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

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
        every { mockManager.isSafeProjectId(any()) } returns true
        coEvery { mockManager.loadProjectMetadata(context, "p1") } returns
            GraffitiProject(id = "p1", name = "Stale", documentWidth = 111)

        val live = GraffitiProject(id = "p1", name = "Live", documentWidth = 999)
        repo.createProject(live) // seeds _currentProject with the in-memory state

        repo.updateTargetFingerprint("p1", "/map/path")

        val result = repo.currentProject.value
        assertEquals("/map/path", result?.targetFingerprintPath)
        assertEquals(999, result?.documentWidth) // the live value, not the stale disk read's 111
        // The transform never consulted the disk, and publishing the edit patched one list entry
        // instead of re-parsing every project.
        coVerify(exactly = 0) { mockManager.getProjectList(any()) }
    }

    @Test
    fun `updateTargetFingerprint is a no-op when the id doesn't match the live project`() = runTest(testDispatcher) {
        val mockManager = mockk<ProjectManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val repo = ProjectRepositoryImpl(context, mockManager)
        every { mockManager.isSafeProjectId(any()) } returns true
        val live = GraffitiProject(id = "p1", name = "Live")
        repo.createProject(live)

        repo.updateTargetFingerprint("some-other-project", "/map/path")

        assertEquals(null, repo.currentProject.value?.targetFingerprintPath)
    }

    @Test
    fun `an in-flight transform saves its own project when another is opened meanwhile`() = runTest(testDispatcher) {
        val mockManager = mockk<ProjectManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val repo = ProjectRepositoryImpl(context, mockManager)
        every { mockManager.isSafeProjectId(any()) } returns true
        val b = GraffitiProject(id = "B", name = "B")
        coEvery { mockManager.loadProjectMetadata(context, "B") } returns b

        repo.createProject(GraffitiProject(id = "A", name = "A"))

        val saved = mutableListOf<GraffitiProject>()
        val gate = CompletableDeferred<Unit>()
        coEvery { mockManager.saveProject(any(), capture(saved), any(), any()) } coAnswers { gate.await() }

        launch { repo.updateProject { it.copy(name = "A-edited") } }
        runCurrent() // the transform ran and its save is parked on the gate
        launch { repo.loadProject("B") }
        runCurrent() // the switch must wait for the save, not slip in front of it
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("A-edited"), saved.map { it.name })
        assertEquals("A", saved.single().id)
        assertEquals("B", repo.currentProject.value?.id)
    }

    @Test
    fun `saveArtifact does not resurrect a deleted project directory`() = runTest(testDispatcher) {
        val files = tmp.newFolder("files")
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns files
        val mockManager = mockk<ProjectManager>(relaxed = true)
        every { mockManager.isSafeProjectId(any()) } returns true
        every { mockManager.resolveInside(any(), any()) } answers { File(firstArg<File>(), secondArg<String>()) }
        val repo = ProjectRepositoryImpl(context, mockManager)

        repo.deleteProject("gone")
        repo.saveArtifact("gone", "layer.png", byteArrayOf(1))
        assertFalse(File(files, "projects/gone").exists())

        // Coming back (e.g. re-created) re-enables writes, and leaves no temp files behind.
        repo.createProject(GraffitiProject(id = "gone", name = "back"))
        val path = repo.saveArtifact("gone", "layer.png", byteArrayOf(2))
        assertTrue(File(path).exists())
        assertEquals(listOf("layer.png"), File(files, "projects/gone").list()!!.toList())
    }
}
