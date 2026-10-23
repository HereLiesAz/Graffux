package com.hereliesaz.graffux

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A thumbnail render cancelled by its own caller (a slider moved the key on) must not hand its
 * waiters a null, which [EffectLayerPreview] shows as the failure fallback: a waiter renders the
 * key itself instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EffectPreviewEngineCancelTest {

    private val entry = CarouselEntry(
        key = "lut",
        label = "LUT",
        action = CarouselAction.ExtensionLut("lut"),
        selected = false,
    )

    @Test
    fun `a waiter renders the key itself when the first renderer is cancelled`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var calls = 0
        val firstMayFinish = CompletableDeferred<Unit>()
        val engine = EffectPreviewEngine(
            renderer = { _, source, _ ->
                calls++
                if (calls == 1) firstMayFinish.await()
                source.copy(Bitmap.Config.ARGB_8888, false)
            },
            dispatcher = dispatcher,
        )
        engine.updateSource(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))

        val first = launch(dispatcher) { engine.thumbnail(entry, null) }
        advanceUntilIdle()
        val waiter = async(dispatcher) { engine.thumbnail(entry, null) }
        advanceUntilIdle()
        first.cancel()
        advanceUntilIdle()

        assertNotNull("the waiter must not see the cancelled render as a failure", waiter.await())
        assertEquals("the waiter rendered the key itself", 2, calls)
    }
}
