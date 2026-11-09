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
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
        every { projectRepository.currentProject } returns MutableStateFlow(GraffitiProject(id = "open-one"))
        every { settingsRepository.backgroundColor } returns flowOf(0xFF000000.toInt())
        coEvery { projectRepository.getProjects() } answers { projects.toList() }
        coEvery { projectRepository.deleteProject("a") } coAnswers {
            gate.await()
            projects.removeAll { it.id == "a" }
        }
    }

    @After
    fun tearDown() {
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
}
