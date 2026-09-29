package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.data.azphalt.ExtensionRepository
import com.hereliesaz.graffitixr.data.brush.CustomBrushRepository
import com.hereliesaz.graffitixr.data.figma.FigmaRepository
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The first save of a brand-new project, through the mandatory project dialog (Graffux's path). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectGateFirstSaveTest {
    private val dispatcher = StandardTestDispatcher()
    private val current = MutableStateFlow<GraffitiProject?>(null)
    private val saved = mutableListOf<GraffitiProject>()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        EditorViewModel.projectGateEnabled = true
    }

    @After fun tearDown() {
        EditorViewModel.projectGateEnabled = false
        Dispatchers.resetMain()
    }

    private fun build(): EditorViewModel {
        val settings = mockk<SettingsRepository>(relaxed = true) {
            every { backgroundColor } returns MutableStateFlow(0)
            every { inputSampleRateHz } returns MutableStateFlow(120)
            every { canvasRenderScale } returns MutableStateFlow(1f)
            every { isRightHanded } returns MutableStateFlow(true)
            every { isImperialUnits } returns MutableStateFlow(false)
            every { gestureMapping } returns MutableStateFlow(emptyMap())
            every { savedPalette } returns MutableStateFlow(emptyList())
        }
        val projects = mockk<ProjectRepository>(relaxed = true) {
            every { currentProject } returns current
            every { this@mockk.projects } returns MutableStateFlow(emptyList())
            coEvery { getProjects() } answers { saved.toList() }
            coEvery { createProject(any<GraffitiProject>()) } answers {
                val p = firstArg<GraffitiProject>(); saved += p; current.value = p
            }
            coEvery { updateProject(any<(GraffitiProject) -> GraffitiProject>()) } answers {
                current.value?.let { current.value = firstArg<(GraffitiProject) -> GraffitiProject>()(it) }
            }
            coEvery { saveArtifact(any(), any(), any()) } returns "/tmp/layer.png"
        }
        val extensions = mockk<ExtensionRepository>(relaxed = true) {
            every { installed } returns MutableStateFlow(emptyList())
        }
        val brushes = mockk<CustomBrushRepository>(relaxed = true) {
            every { this@mockk.brushes } returns MutableStateFlow(emptyList())
        }
        val figma = mockk<FigmaRepository>(relaxed = true) {
            every { isAuthenticated } returns MutableStateFlow(false)
        }
        return EditorViewModel(
            projectRepository = projects, settingsRepository = settings,
            projectManager = mockk(relaxed = true), exportManager = mockk(relaxed = true),
            context = RuntimeEnvironment.getApplication(), slamManager = mockk(relaxed = true),
            dispatchers = EditorViewModelFixture.dispatchers(dispatcher), opEmitter = mockk(relaxed = true),
            extensionRepository = extensions, repositoryApiClient = mockk(relaxed = true),
            customBrushRepository = brushes, figmaRepository = figma, projectFileScanner = mockk(relaxed = true),
        )
    }

    @Test
    fun `first save from the project dialog creates the project with its background layer`() {
        val vm = build()
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.projectGate.value)

        vm.onProjectGateSave("Wall piece")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.projectGate.value)
        val state = vm.uiState.value
        assertEquals(current.value?.id, state.projectId)
        assertEquals("Wall piece", current.value?.name)
        // The Background layer survives the project's own LoadedProject, and is what got saved.
        assertEquals(listOf("Background"), state.layers.map { it.name })
        assertEquals(listOf("Background"), current.value?.layers?.map { it.name })
    }
}
