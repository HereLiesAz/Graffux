package com.hereliesaz.graffitixr.feature.editor.gpu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Orchestration of calibration around the mandatory project dialog, on virtual time. The fake
 * probe takes [stepMs] per step and can be scripted to lose its context.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationCoordinatorTest {
    private val info =
        GpuInfo(engine = "vulkan", renderer = "Adreno (TM) 740", vendorId = GpuInfo.VENDOR_QUALCOMM, driver = "0x1")

    private inner class FakeProbe(var stepMs: Long = 1_000L) : CalibrationProbe {
        val calls = mutableListOf<CalibrationStep>()
        val identityCalls = mutableListOf<Unit>()
        /** Outcomes to return before the real value, per step (e.g. ContextLost once). */
        val script = mutableMapOf<CalibrationStep, ArrayDeque<StepOutcome>>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun identity(): GpuInfo {
            identityCalls += Unit
            return info
        }

        override suspend fun measure(step: CalibrationStep): StepOutcome {
            calls += step
            gate?.await()
            delay(stepMs)
            script[step]?.removeFirstOrNull()?.let { return it }
            return StepOutcome.Measured(
                when (step) {
                    CalibrationStep.STAMP -> 100.0
                    CalibrationStep.READBACK -> 5_000.0
                    CalibrationStep.COMPOSITE -> 2.0
                },
            )
        }
    }

    private class Harness(
        scope: TestScope,
        val probe: CalibrationProbe,
        val kv: MapKeyValueStore = MapKeyValueStore(),
    ) {
        val applied = mutableListOf<GpuTuning>()
        val store = GpuTierStore(kv, appVersion = 1)
        val coordinator = CalibrationCoordinator(
            // Not backgroundScope: advanceUntilIdle does not run background work. A detached
            // SupervisorJob on the test scheduler is foreground work runTest need not wait for.
            scope = CoroutineScope(StandardTestDispatcher(scope.testScheduler) + SupervisorJob()),
            probe = probe,
            store = store,
            onTuning = { t, _ -> applied += t },
        )
    }

    @Test
    fun `starts when the dialog opens and stores the tier`() = runTest {
        val probe = FakeProbe()
        val h = Harness(this, probe)
        assertFalse(h.coordinator.isRunning)
        h.coordinator.onProjectDialogShown()
        runCurrent()
        assertTrue(h.coordinator.isRunning)
        assertEquals(listOf(CalibrationStep.STAMP), probe.calls)
        advanceUntilIdle()
        assertEquals(CalibrationState.DONE, h.coordinator.state.value)
        assertEquals(GpuTierTable.HIGH, h.coordinator.tuning.tier)
        assertTrue(h.coordinator.tuning.calibrated)
        assertEquals(GpuTierTable.HIGH, h.store.load(info.identityKey)?.tier)
        // Default applied first (family knobs), then the calibrated tier.
        assertEquals(listOf(false, true), h.applied.map { it.calibrated })
    }

    @Test
    fun `skipped when a tier is cached for this GPU and driver`() = runTest {
        val probe = FakeProbe()
        val h = Harness(this, probe)
        h.store.save(info.identityKey, GpuTierTable.STANDARD, CalibrationResult(30.0, 2_000.0, 8.0, "vulkan"))
        h.coordinator.onProjectDialogShown()
        advanceUntilIdle()
        assertTrue(probe.calls.isEmpty())
        assertEquals(GpuTierTable.STANDARD, h.coordinator.tuning.tier)
        assertTrue(h.coordinator.tuning.calibrated)
    }

    @Test
    fun `reopening the dialog while running does not restart`() = runTest {
        val probe = FakeProbe()
        val h = Harness(this, probe)
        h.coordinator.onProjectDialogShown()
        advanceTimeBy(500)
        h.coordinator.onProjectDialogShown()
        advanceUntilIdle()
        assertEquals(1, probe.identityCalls.size)
        assertEquals(3, probe.calls.size)
    }

    @Test
    fun `save waits for calibration when it finishes inside the cap`() = runTest {
        val probe = FakeProbe(stepMs = 500) // 1.5 s total
        val h = Harness(this, probe)
        h.coordinator.onProjectDialogShown()
        advanceTimeBy(100)
        val start = currentTime
        val tuning = h.coordinator.awaitForCreate()
        assertTrue(tuning.calibrated)
        assertEquals(GpuTierTable.HIGH, tuning.tier)
        assertTrue(currentTime - start < CalibrationCoordinator.DEFAULT_CAP_MS)
    }

    @Test
    fun `save is capped, opens on the default tier, and the late result is applied`() = runTest {
        val probe = FakeProbe(stepMs = 2_000) // 6 s total
        val h = Harness(this, probe)
        h.coordinator.onProjectDialogShown()
        runCurrent()
        val start = currentTime
        val tuning = h.coordinator.awaitForCreate()
        assertEquals(CalibrationCoordinator.DEFAULT_CAP_MS, currentTime - start)
        assertFalse(tuning.calibrated)
        assertEquals(GpuTierTable.default, tuning.tier)
        assertTrue(h.coordinator.isRunning) // carries on in the background
        advanceUntilIdle()
        assertTrue(h.coordinator.tuning.calibrated)
        assertEquals(GpuTierTable.HIGH, h.applied.last().tier)
    }

    @Test
    fun `save without a run in flight returns at once`() = runTest {
        val h = Harness(this, FakeProbe())
        val start = currentTime
        h.coordinator.awaitForCreate()
        assertEquals(start, currentTime)
    }

    @Test
    fun `load - picker opened, app hidden, then visible again - calibration pauses and resumes`() = runTest {
        val probe = FakeProbe(stepMs = 1_000)
        val h = Harness(this, probe)
        h.coordinator.onProjectDialogShown()
        advanceTimeBy(1_500) // STAMP done, READBACK half way
        // The picker covers the activity: onStop.
        h.coordinator.onAppHidden()
        advanceTimeBy(10_000)
        // The READBACK attempt that overlapped the pause is discarded, and nothing new starts.
        assertEquals(listOf(CalibrationStep.STAMP, CalibrationStep.READBACK), probe.calls)
        assertTrue(h.coordinator.isRunning)
        assertNull(h.store.load(info.identityKey))
        // Back from the picker: onStart.
        h.coordinator.onAppVisible()
        advanceUntilIdle()
        assertEquals(
            listOf(
                CalibrationStep.STAMP,
                CalibrationStep.READBACK,
                CalibrationStep.READBACK,
                CalibrationStep.COMPOSITE,
            ),
            probe.calls,
        )
        assertEquals(CalibrationState.DONE, h.coordinator.state.value)
        assertEquals(GpuTierTable.HIGH, h.store.load(info.identityKey)?.tier)
    }

    @Test
    fun `picker cancelled - back at the dialog with calibration still running`() = runTest {
        val probe = FakeProbe(stepMs = 1_000)
        val h = Harness(this, probe)
        h.coordinator.onProjectDialogShown()
        advanceTimeBy(500)
        // A picker that returns quickly (or runs in a window that keeps us visible) never hides us,
        // and cancelling it touches nothing: the run just continues.
        advanceTimeBy(300)
        assertTrue(h.coordinator.isRunning)
        h.coordinator.onProjectDialogShown() // the dialog is still there; no restart
        advanceUntilIdle()
        assertEquals(1, probe.identityCalls.size)
        assertEquals(CalibrationState.DONE, h.coordinator.state.value)
    }

    @Test
    fun `gpu context lost - the step is restarted, never recorded`() = runTest {
        val probe = FakeProbe(stepMs = 100)
        probe.script[CalibrationStep.READBACK] = ArrayDeque(listOf(StepOutcome.ContextLost, StepOutcome.ContextLost))
        val h = Harness(this, probe)
        h.coordinator.onProjectDialogShown()
        advanceUntilIdle()
        assertEquals(3, probe.calls.count { it == CalibrationStep.READBACK })
        assertEquals(CalibrationState.DONE, h.coordinator.state.value)
        assertEquals(5_000.0, h.store.load(info.identityKey)!!.result.readbackMBps, 0.0)
    }

    @Test
    fun `a step that keeps failing leaves the default and stores nothing`() = runTest {
        val probe = FakeProbe(stepMs = 100)
        val lost = List(CalibrationRunner.DEFAULT_ATTEMPTS) { StepOutcome.ContextLost }
        probe.script[CalibrationStep.COMPOSITE] = ArrayDeque(lost)
        val h = Harness(this, probe)
        h.coordinator.onProjectDialogShown()
        advanceUntilIdle()
        assertEquals(CalibrationState.FAILED, h.coordinator.state.value)
        assertNull(h.store.load(info.identityKey))
        assertFalse(h.coordinator.tuning.calibrated)
        // Next dialog retries, and only the step that failed runs again.
        probe.calls.clear()
        h.coordinator.onProjectDialogShown()
        advanceUntilIdle()
        assertEquals(listOf(CalibrationStep.COMPOSITE), probe.calls)
        assertEquals(CalibrationState.DONE, h.coordinator.state.value)
    }

    @Test
    fun `load finishing before calibration - capped wait, default tier, later apply`() = runTest {
        val probe = FakeProbe(stepMs = 2_000) // 6 s total
        val h = Harness(this, probe)
        h.coordinator.onProjectDialogShown()
        runCurrent()
        val start = currentTime
        val loaded = h.coordinator.alongsideLoad {
            delay(1_000) // reading the project
            "project"
        }
        assertEquals("project", loaded)
        // The load's own time plus at most the cap.
        assertEquals(1_000 + CalibrationCoordinator.DEFAULT_CAP_MS, currentTime - start)
        assertFalse(h.coordinator.tuning.calibrated)
        assertEquals(GpuTierTable.default, h.coordinator.tuning.tier)
        advanceUntilIdle()
        assertTrue(h.coordinator.tuning.calibrated)
        assertEquals(GpuTierTable.HIGH, h.coordinator.tuning.tier)
    }

    @Test
    fun `load slower than calibration adds no wait`() = runTest {
        val probe = FakeProbe(stepMs = 100)
        val h = Harness(this, probe)
        h.coordinator.onProjectDialogShown()
        runCurrent()
        val start = currentTime
        h.coordinator.alongsideLoad { delay(3_000) }
        assertEquals(3_000L, currentTime - start)
        assertTrue(h.coordinator.tuning.calibrated)
    }

    @Test
    fun `process death - nothing stored, the next dialog retries`() = runTest {
        val kv = MapKeyValueStore()
        val first = Harness(this, FakeProbe(stepMs = 1_000), kv)
        first.coordinator.onProjectDialogShown()
        advanceTimeBy(1_500)
        assertTrue(kv.map.isEmpty()) // killed here: nothing persisted
        first.coordinator.onUserDrawing() // stand-in for the dead process's job going away
        val probe = FakeProbe(stepMs = 100)
        val second = Harness(this, probe, kv) // next launch
        second.coordinator.onProjectDialogShown()
        advanceUntilIdle()
        assertEquals(3, probe.calls.size)
        assertEquals(GpuTierTable.HIGH, second.store.load(info.identityKey)?.tier)
    }

    @Test
    fun `drawing cancels a background rerun`() = runTest {
        val probe = FakeProbe(stepMs = 1_000)
        val h = Harness(this, probe)
        h.store.save(info.identityKey, GpuTierTable.STANDARD, CalibrationResult(30.0, 2_000.0, 8.0, "vulkan"))
        h.coordinator.rerun()
        assertNull(h.store.load(info.identityKey))
        advanceTimeBy(500)
        assertTrue(h.coordinator.isRunning)
        h.coordinator.onUserDrawing()
        advanceUntilIdle()
        assertFalse(h.coordinator.isRunning)
        assertEquals(CalibrationState.IDLE, h.coordinator.state.value)
    }

    @Test
    fun `no gpu engine - no calibration, default stays`() = runTest {
        val probe = object : CalibrationProbe {
            override suspend fun identity(): GpuInfo? = null
            override suspend fun measure(step: CalibrationStep) = error("must not run")
        }
        val h = Harness(this, probe)
        h.coordinator.onProjectDialogShown()
        advanceUntilIdle()
        assertEquals(CalibrationState.FAILED, h.coordinator.state.value)
        assertEquals(GpuTierTable.default, h.coordinator.tuning.tier)
        val waited = async { h.coordinator.awaitForCreate() }
        advanceUntilIdle()
        assertFalse(waited.await().calibrated)
    }

    /** A probe whose "engine" lives for the whole step and, like a native call, ignores cancellation. */
    private inner class EngineProbe : CalibrationProbe {
        var alive = 0
        var steps = 0
        override suspend fun identity(): GpuInfo = info
        override suspend fun measure(step: CalibrationStep): StepOutcome =
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                alive += 1
                steps += 1
                try {
                    delay(1_000)
                    StepOutcome.Measured(1.0)
                } finally {
                    alive -= 1
                }
            }
    }

    @Test
    fun `stopForCanvas returns only once the step in flight has destroyed its engine`() = runTest {
        val probe = EngineProbe()
        val h = Harness(this, probe)
        h.coordinator.onProjectDialogShown()
        advanceTimeBy(300)
        assertEquals("a calibration engine is alive mid-step", 1, probe.alive)
        val stopped = async { h.coordinator.stopForCanvas() }
        advanceTimeBy(100)
        assertFalse("waits for the native step, never abandons a live engine", stopped.isCompleted)
        advanceUntilIdle()
        assertTrue(stopped.isCompleted)
        assertEquals("no calibration engine left for the canvas to overlap", 0, probe.alive)
        assertFalse(h.coordinator.isRunning)
        assertEquals("no step started after the stop", 1, probe.steps)
        assertEquals(CalibrationState.IDLE, h.coordinator.state.value)
    }

    @Test
    fun `stopForCanvas is a no-op when nothing runs`() = runTest {
        val h = Harness(this, FakeProbe())
        h.coordinator.stopForCanvas()
        assertEquals(CalibrationState.IDLE, h.coordinator.state.value)
    }
}
