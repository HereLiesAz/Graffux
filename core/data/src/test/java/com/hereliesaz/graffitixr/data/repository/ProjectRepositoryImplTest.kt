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
        coEvery { mockManager.saveProject(any(), capture(saved), any(), any()) } coAnswers { gate.await(); true }

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
        coEvery { mockManager.writeIfWritable<String>(any(), any(), any()) } answers {
            thirdArg<() -> String>().invoke()
        }
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

    @Test
    fun `a refused save throws and leaves the in-memory project as it was`() = runTest(testDispatcher) {
        val mockManager = mockk<ProjectManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { mockManager.isSafeProjectId(any()) } returns true
        val repo = ProjectRepositoryImpl(context, mockManager)
        repo.createProject(GraffitiProject(id = "A", name = "A"))
        coEvery { mockManager.saveProject(any(), any(), any(), any()) } returns false

        val transform = runCatching { repo.updateProject { it.copy(name = "Renamed") } }.exceptionOrNull()
        assertTrue(transform is com.hereliesaz.graffitixr.domain.repository.ProjectNotSavedException)
        assertEquals("A", repo.currentProject.value?.name)

        val plain = runCatching { repo.updateProject(GraffitiProject(id = "A", name = "Plain")) }.exceptionOrNull()
        assertTrue(plain is com.hereliesaz.graffitixr.domain.repository.ProjectNotSavedException)
        assertEquals("A", repo.currentProject.value?.name)
    }

    @Test
    fun `an artifact for a read-only project never lands in the project folder`() = runTest(testDispatcher) {
        val files = tmp.newFolder("files")
        val cache = tmp.newFolder("cache")
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns files
        every { context.cacheDir } returns cache
        val mockManager = mockk<ProjectManager>(relaxed = true)
        every { mockManager.isSafeProjectId(any()) } returns true
        every { mockManager.resolveInside(any(), any()) } answers { File(firstArg<File>(), secondArg<String>()) }
        coEvery { mockManager.writeIfWritable<String>(any(), any(), any()) } returns null
        val repo = ProjectRepositoryImpl(context, mockManager)

        val path = repo.saveArtifact("future", "thumbnail.png", byteArrayOf(7))
        assertFalse(File(files, "projects/future/thumbnail.png").exists())
        assertTrue(File(path).exists())
        assertTrue(File(path).canonicalPath.startsWith(cache.canonicalPath))
    }
}
