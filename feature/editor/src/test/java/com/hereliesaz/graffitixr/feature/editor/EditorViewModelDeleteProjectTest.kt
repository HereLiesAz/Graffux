package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.coop.OpEmitter
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.data.azphalt.ExtensionRepository
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.nativebridge.LiquifyEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deleting a project from the Open screen: the screen must list what is on disk once the delete has
 * finished. The caller used to call refreshOpenScreen() straight after starting the delete, which
 * raced it on the IO pool and could list the project that was being deleted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelDeleteProjectTest {

    private val testDispatcher = StandardTestDispatcher()
    private val projectRepository: ProjectRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val figmaRepository: com.hereliesaz.graffitixr.data.figma.FigmaRepository = mockk(relaxed = true)
    private val projects = mutableListOf(GraffitiProject(id = "a", name = "A"), GraffitiProject(id = "b", name = "B"))
    private val gate = CompletableDeferred<Unit>()
    private val current = MutableStateFlow<GraffitiProject?>(GraffitiProject(id = "open-one"))

    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
        override val unconfined: CoroutineDispatcher = testDispatcher
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { figmaRepository.isAuthenticated } returns MutableStateFlow(false)
        mockkObject(NativeLibLoader)
        every { NativeLibLoader.loadAll() } returns Unit
        every { projectRepository.currentProject } returns current
        every { settingsRepository.backgroundColor } returns flowOf(0xFF000000.toInt())
        coEvery { projectRepository.getProjects() } answers { projects.toList() }
        coEvery { projectRepository.deleteProject("a") } coAnswers {
            gate.await()
            projects.removeAll { it.id == "a" }
        }
    }

    @After
    fun tearDown() {
        EditorViewModel.projectGateEnabled = false
        Dispatchers.resetMain()
        unmockkObject(NativeLibLoader)
    }

    private fun viewModel() = EditorViewModel(
        projectRepository, settingsRepository, mockk<ProjectManager>(relaxed = true), mockk(relaxed = true),
        mockk<Context>(relaxed = true), mockk<LiquifyEngine>(relaxed = true), dispatchers,
        mockk<OpEmitter>(relaxed = true), mockk<ExtensionRepository>(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), figmaRepository, mockk(relaxed = true),
    )

    @Test
    fun `the Open screen lists what is left once the delete has finished`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.refreshOpenScreen()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), vm.openScreenState.value.inApp.map { it.id })

        vm.deleteProjectById("a")
        runCurrent() // the delete is under way and has not finished
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("b"), vm.openScreenState.value.inApp.map { it.id })
    }

    private val ok = Result.success(Unit)

    /** The repository letting go of the open project as part of deleting it, as the real one does. */
    private fun deletingClearsTheOpenProject() {
        coEvery { projectRepository.deleteProject("open-one") } coAnswers { current.value = null }
    }

    @Test
    fun `deleting the open project opens the most recent other one`() = runTest(testDispatcher) {
        projects.clear()
        projects += listOf(GraffitiProject(id = "b", lastModified = 5), GraffitiProject(id = "c", lastModified = 9))
        coEvery { projectRepository.loadProject(any<String>()) } returns ok
        deletingClearsTheOpenProject()
        val vm = viewModel()
        advanceUntilIdle()
        vm.deleteProjectById("open-one")
        advanceUntilIdle()
        coVerify(exactly = 1) { projectRepository.loadProject("c") }
    }

    @Test
    fun `the fallback still happens when the ui state already saw the open project go`() =
        runTest(testDispatcher) {
        projects.clear()
        projects += GraffitiProject(id = "b", lastModified = 5)
        coEvery { projectRepository.loadProject(any<String>()) } returns ok
        // The repository clears the open project, and the ui state's collector runs before the delete returns.
        coEvery { projectRepository.deleteProject("open-one") } coAnswers {
            current.value = null
            yield()
            yield()
        }
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals("open-one", vm.uiState.value.projectId)
        vm.deleteProjectById("open-one")
        advanceUntilIdle()
        assertEquals("the ui state followed the repository", null, vm.uiState.value.projectId)
        coVerify(exactly = 1) { projectRepository.loadProject("b") }
    }

    @Test
    fun `with no other project the dialog asks for one when the host has the gate`() = runTest(testDispatcher) {
        projects.clear()
        EditorViewModel.projectGateEnabled = true
        deletingClearsTheOpenProject()
        val vm = viewModel()
        advanceUntilIdle()
        vm.deleteProjectById("open-one")
        advanceUntilIdle()
        assertNotNull(vm.projectGate.value)
        coVerify(exactly = 0) { projectRepository.createProject(any<GraffitiProject>()) }
    }

    @Test
    fun `with no other project and no gate a new project is created`() = runTest(testDispatcher) {
        projects.clear()
        deletingClearsTheOpenProject()
        val vm = viewModel()
        advanceUntilIdle()
        vm.deleteProjectById("open-one")
        advanceUntilIdle()
        coVerify(exactly = 1) { projectRepository.createProject(any<GraffitiProject>()) }
    }

    @Test
    fun `a sibling that will not load falls through to a fresh project and says so`() = runTest(testDispatcher) {
        projects.clear()
        projects += GraffitiProject(id = "b", name = "Bee", lastModified = 5)
        coEvery { projectRepository.loadProject("b") } returns Result.failure(Exception("gone"))
        deletingClearsTheOpenProject()
        val vm = viewModel()
        val notices = mutableListOf<EditorNotice>()
        backgroundScope.launch { vm.notices.collect { notices += it } }
        advanceUntilIdle()
        vm.deleteProjectById("open-one")
        advanceUntilIdle()
        coVerify(exactly = 1) { projectRepository.createProject(any<GraffitiProject>()) }
        assertTrue(notices.any { it.text.contains("Couldn't open \"Bee\"") })
    }

    @Test
    fun `a delete that throws is reported, leaves the open project alone and still refreshes the list`() =
        runTest(testDispatcher) {
            coEvery { projectRepository.deleteProject("open-one") } throws java.io.IOException("disk full")
            val vm = viewModel()
            val notices = mutableListOf<EditorNotice>()
            backgroundScope.launch { vm.notices.collect { notices += it } }
            advanceUntilIdle()
            vm.deleteProjectById("open-one")
            advanceUntilIdle()
            assertTrue(notices.any { it.kind == NoticeKind.WARNING && it.text.contains("Couldn't delete") })
            coVerify(exactly = 0) { projectRepository.loadProject(any<String>()) }
            coVerify(exactly = 0) { projectRepository.createProject(any<GraffitiProject>()) }
            assertEquals("open-one", vm.uiState.value.projectId)
            assertEquals(false, vm.openScreenState.value.isScanning)
            assertEquals(listOf("a", "b"), vm.openScreenState.value.inApp.map { it.id })
        }

    @Test
    fun `a delete that throws after the repository let go of the open project still moves on`() =
        runTest(testDispatcher) {
            projects.clear()
            projects += GraffitiProject(id = "b", lastModified = 5)
            coEvery { projectRepository.loadProject(any<String>()) } returns ok
            coEvery { projectRepository.deleteProject("open-one") } coAnswers {
                current.value = null
                throw java.io.IOException("refresh failed")
            }
            val vm = viewModel()
            advanceUntilIdle()
            vm.deleteProjectById("open-one")
            advanceUntilIdle()
            coVerify(exactly = 1) { projectRepository.loadProject("b") }
        }

    @Test
    fun `deleting a project that is not open leaves the editor where it is`() = runTest(testDispatcher) {
        coEvery { projectRepository.deleteProject("b") } returns Unit
        val vm = viewModel()
        advanceUntilIdle()
        vm.deleteProjectById("b")
        advanceUntilIdle()
        coVerify(exactly = 0) { projectRepository.loadProject(any<String>()) }
        coVerify(exactly = 0) { projectRepository.createProject(any<GraffitiProject>()) }
    }
}
