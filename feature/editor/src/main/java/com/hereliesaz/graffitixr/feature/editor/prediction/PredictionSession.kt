package com.hereliesaz.graffitixr.feature.editor.prediction

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.math.roundToLong

/**
 * TEMPORARY. One live-stroke surface's prediction tournament, built the same way for both Brush
 * engines — DrawingCanvas (azphalt) and InkBrushCanvas (Jetpack Ink) — so their reports rank the
 * same predictors on the same horizons and only the engine tag differs.
 */
internal class PredictionSession(
    val tournament: PredictionTournament,
    val androidX: AndroidXMotionGesturePredictor,
    /** Display refresh rate, Hz. */
    val refreshRate: Float,
    /** One display frame, ms: the horizon each sample predicts to. */
    val nextFrameMs: Long,
) {
    /** AndroidX is fed raw MotionEvents rather than samples, so callers check before recording. */
    val androidXRunning: Boolean get() = PredictionTournament.ANDROIDX in tournament.activeModels
}

/**
 * The tournament for [view]'s display: Google Ink draws the tail, linear covers a stroke's first
 * samples, AndroidX is ranked alongside. Rebuilt when Settings > Developer changes the solo model
 * or the Ink profile, which is why callers must key their gesture loops on the returned session.
 */
@Composable
internal fun rememberPredictionSession(view: View): PredictionSession {
    val refreshRate = remember(view) {
        runCatching { view.display?.refreshRate }
            .getOrNull()
            ?.takeIf { it.isFinite() && it > 1f }
            ?: DEFAULT_REFRESH_HZ
    }
    val nextFrameMs = (MS_PER_SECOND / refreshRate).roundToLong().coerceIn(MIN_FRAME_MS, MAX_FRAME_MS)
    val prefs = view.context.getSharedPreferences(PredictionTournament.SOLO_PREFS, Context.MODE_PRIVATE)
    val soloModel = prefs.getString(PredictionTournament.SOLO_KEY, null)?.takeIf { it.isNotBlank() }
    val inkProfile = prefs.getString(PredictionTournament.INK_PROFILE_KEY, null)
        .let { saved -> GoogleInkGesturePredictor.Profile.entries.firstOrNull { it.label == saved } }
        ?: GoogleInkGesturePredictor.Profile.STANDARD
    val androidX = remember(view) { AndroidXMotionGesturePredictor(view) }
    return remember(view, soloModel, inkProfile, refreshRate, nextFrameMs) {
        PredictionSession(
            PredictionTournament(
                listOf(LinearGesturePredictor(), androidX),
                soloModel = soloModel,
                inkProfile = inkProfile,
            ),
            androidX,
            refreshRate,
            nextFrameMs,
        )
    }
}

private const val DEFAULT_REFRESH_HZ = 60f
private const val MS_PER_SECOND = 1000f
private const val MIN_FRAME_MS = 4L
private const val MAX_FRAME_MS = 34L
