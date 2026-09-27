package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.feature.editor.prediction.PredictionTournament
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The per-stroke prediction report must rank the samples of the stroke that was just drawn, even
 * after Settings changed the predictor lineup (which rebuilds the tournament). Before the fix the
 * gesture loop kept feeding the discarded tournament, and every report said "no data".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class PredictionRankingFeedTest {

    @get:Rule
    val rule = createComposeRule()

    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences(PredictionTournament.SOLO_PREFS, Context.MODE_PRIVATE)

    private fun stroke() = rule.onRoot().performTouchInput {
        down(Offset(50f, 100f))
        for (i in 1..20) moveTo(Offset(50f + i * 12f, 100f + i * 8f))
        up()
    }

    @Test
    fun `rankings are recorded after the tournament is rebuilt`() {
        prefs.edit().clear().commit()
        val reports = mutableListOf<String>()
        val brushSize = mutableFloatStateOf(20f)
        rule.setContent {
            DrawingCanvas(
                activeTool = Tool.BRUSH,
                brushSize = brushSize.floatValue,
                activeColor = Color.Red,
                layerBitmapKey = null,
                gate = StrokeGate(),
                modifier = Modifier.fillMaxSize(),
                onStrokeStart = { _, _ -> },
                onStrokePoint = {},
                onStrokeEnd = {},
                onStrokeCancel = {},
                onFillTap = { _, _ -> },
                onEyedropStart = {},
                onEyedropSample = {},
                onEyedropEnd = {},
                onPredictionRanked = { report, _ -> reports += report },
            )
        }

        stroke()
        rule.waitForIdle()
        assertTrue("no report after first stroke", reports.isNotEmpty())
        assertFalse("first stroke unranked:\n${reports.last()}", reports.last().contains("f1: no data"))

        // Settings pins one model: the canvas recomposes and builds a new tournament.
        prefs.edit().putString(PredictionTournament.SOLO_KEY, "linear").commit()
        brushSize.floatValue = 21f
        rule.waitForIdle()

        stroke()
        rule.waitForIdle()
        val last = reports.last()
        assertTrue("tournament was not rebuilt:\n$last", last.startsWith("models: linear,"))
        assertFalse("stroke after rebuild unranked:\n$last", last.contains("no data"))
        prefs.edit().clear().commit()
    }
}
