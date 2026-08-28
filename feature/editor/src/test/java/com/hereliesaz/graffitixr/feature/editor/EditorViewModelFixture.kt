package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.data.azphalt.ExtensionRepository
import com.hereliesaz.graffitixr.data.brush.CustomBrushRepository
import com.hereliesaz.graffitixr.data.figma.FigmaRepository
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.RuntimeEnvironment

/**
 * Builds a real [EditorViewModel] over mocked collaborators.
 *
 * The point is to reach the *commit paths* — the code that decides what pixels a finished stroke
 * leaves on a layer. Those live on the ViewModel and nowhere else, so the tier below it (which
 * compares DrawingEngine's two entry points against each other) can prove the engine is
 * self-consistent but never that a given tool actually goes through it. Three shipped bugs lived in
 * exactly that gap.
 *
 * Every flow the constructor collects has to be stubbed explicitly: a relaxed mock hands back a
 * Flow whose `collect` returns Nothing, and the init block dies on the first one it reaches.
 */
internal object EditorViewModelFixture {

    fun dispatchers(d: CoroutineDispatcher) = object : DispatcherProvider {
        override val main = d
        override val io = d
        override val default = d
        override val unconfined = d
    }

    fun build(d: CoroutineDispatcher, customBrushRepository: CustomBrushRepository? = null): EditorViewModel {
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
            every { currentProject } returns MutableStateFlow(null)
            every { this@mockk.projects } returns MutableStateFlow(emptyList())
        }
        val extensions = mockk<ExtensionRepository>(relaxed = true) {
            every { installed } returns MutableStateFlow(emptyList())
        }
        val brushes = customBrushRepository ?: mockk<CustomBrushRepository>(relaxed = true) {
            every { this@mockk.brushes } returns MutableStateFlow(emptyList())
        }
        val figma = mockk<FigmaRepository>(relaxed = true) {
            every { isAuthenticated } returns MutableStateFlow(false)
        }

        return EditorViewModel(
            projectRepository = projects,
            settingsRepository = settings,
            projectManager = mockk(relaxed = true),
            exportManager = mockk(relaxed = true),
            context = RuntimeEnvironment.getApplication(),
            slamManager = mockk(relaxed = true),
            dispatchers = dispatchers(d),
            opEmitter = mockk(relaxed = true),
            extensionRepository = extensions,
            customBrushRepository = brushes,
            figmaRepository = figma,
            projectFileScanner = mockk(relaxed = true),
        )
    }
}
