package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The view-model's recent colours reach the settings store in order and complete, however the saves and the
 * first load interleave. The dispatcher is a [StandardTestDispatcher] stepped by hand, and the fake store's
 * first write can be held open ([gate]) so a later one finishes before it: with one coroutine per change, the
 * older snapshot then lands last.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelRecentColoursTest {
    private val dispatcher = StandardTestDispatcher()
    private val a = Color(0xFFAA0000)
    private val b = Color(0xFF00BB00)
    private val c = Color(0xFF0000CC)
    private val d = Color(0xFFDDDD00)

    /** What the "disk" holds: set when a write completes, so a slow older write overwrites a newer one. */
    private var disk: String? = null
    private val writeLog = ArrayList<String>()

    /** Held open to make the first write slow; a later one is never held. */
    private var gate: CompletableDeferred<Unit>? = null
    private var firstWrite = true

    /** What the settings flow emits for the stored colours; nothing until the test emits. */
    private val stored = MutableSharedFlow<String?>(replay = 1)

    private lateinit var viewModel: EditorViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(NativeLibLoader)
        every { NativeLibLoader.loadAll() } returns Unit
        val settings: SettingsRepository = mockk(relaxed = true)
        every { settings.recentColors } returns stored
        every { settings.backgroundColor } returns kotlinx.coroutines.flow.flowOf(0xFF000000.toInt())
        coEvery { settings.setRecentColors(any()) } coAnswers {
            val encoded = firstArg<String>()
            if (firstWrite) {
                firstWrite = false
                gate?.await()
            }
            writeLog += encoded
            disk = encoded
        }
        val projects: ProjectRepository = mockk(relaxed = true)
        every { projects.currentProject } returns MutableStateFlow<GraffitiProject?>(null)
        val figma: com.hereliesaz.graffitixr.data.figma.FigmaRepository = mockk(relaxed = true)
        every { figma.isAuthenticated } returns MutableStateFlow(false)
        val provider = object : DispatcherProvider {
            override val main: CoroutineDispatcher = dispatcher
            override val io: CoroutineDispatcher = dispatcher
            override val default: CoroutineDispatcher = dispatcher
            override val unconfined: CoroutineDispatcher = dispatcher
        }
        viewModel = EditorViewModel(
            projects, settings, mockk(relaxed = true), mockk(relaxed = true), mockk<Context>(relaxed = true),
            mockk(relaxed = true), provider, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), figma, mockk(relaxed = true),
        )
        dispatcher.scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(NativeLibLoader)
    }

    private fun idle() = dispatcher.scheduler.advanceUntilIdle()

    private fun load(colours: List<Color>) {
        stored.tryEmit(encodeRecentColors(colours))
        idle()
    }

    @Test
    fun `rapid delete then delete leaves the last state on disk even when the first write is slow`() {
        load(listOf(a, b, c))
        gate = CompletableDeferred()
        firstWrite = true
        viewModel.onRecentColorDeleted(a)
        dispatcher.scheduler.runCurrent() // write 1 starts and waits on the gate
        viewModel.onRecentColorDeleted(b)
        dispatcher.scheduler.runCurrent()
        gate!!.complete(Unit)
        idle()
        assertEquals(listOf(c), viewModel.recentColors.value)
        assertEquals(encodeRecentColors(listOf(c)), disk)
    }

    @Test
    fun `delete then commit leaves both on disk even when the first write is slow`() {
        load(listOf(a, b))
        gate = CompletableDeferred()
        firstWrite = true
        viewModel.onRecentColorDeleted(a)
        dispatcher.scheduler.runCurrent()
        viewModel.onColorCommitted(d)
        dispatcher.scheduler.runCurrent()
        gate!!.complete(Unit)
        idle()
        assertEquals(listOf(d, b), viewModel.recentColors.value)
        assertEquals(encodeRecentColors(listOf(d, b)), disk)
    }

    @Test
    fun `a commit before the stored colours load does not erase them`() {
        viewModel.onColorCommitted(d)
        idle()
        assertEquals("nothing is written before the load", emptyList<String>(), writeLog)
        load(listOf(a, b))
        assertEquals(listOf(d, a, b), viewModel.recentColors.value)
        assertEquals(encodeRecentColors(listOf(d, a, b)), disk)
        assertTrue(writeLog.none { it == encodeRecentColors(listOf(d)) })
    }

    @Test
    fun `a delete before the stored colours load is not undone by the load`() {
        viewModel.onRecentColorDeleted(a)
        idle()
        load(listOf(a, b))
        assertEquals(listOf(b), viewModel.recentColors.value)
        assertEquals(encodeRecentColors(listOf(b)), disk)
    }
}
