// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/threed/ModelView.kt
package com.hereliesaz.graffitixr.feature.editor.threed

import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.graffitixr.common.mesh.Mesh
import com.hereliesaz.graffitixr.common.mesh.OrbitCamera

/**
 * A 3D model viewport: a [GLSurfaceView] driven by an [OrbitCamera], with the gestures the rest of
 * the app already uses for the 2D canvas.
 *
 * - one finger  → orbit
 * - two fingers → pinch to dolly, drag to pan
 *
 * Matching the 2D canvas's gesture vocabulary matters more than matching other 3D apps: someone who
 * has learned to move around a drawing here shouldn't have to learn a second set of rules to move
 * around a model.
 *
 * The camera lives in Compose state and is pushed to the renderer, so all the actual maths stays in
 * the pure [OrbitCamera] and none of it needs a device to test.
 */
@Composable
fun ModelView(
    mesh: Mesh?,
    modifier: Modifier = Modifier,
) {
    // Framed on first sight of a mesh, so a model of any authored scale opens filling the view.
    var camera by remember(mesh) { mutableStateOf(mesh?.let { OrbitCamera.framing(it) } ?: OrbitCamera()) }
    val renderer = remember { ModelRenderer() }

    renderer.camera = camera
    remember(mesh) { mesh?.let { renderer.setMesh(it) } }

    Box(modifier = modifier) {
        AndroidView(
            factory = { context ->
                GLSurfaceView(context).apply {
                    setEGLContextClientVersion(2)
                    // Depth is required — without it faces draw in submission order and a solid
                    // model renders inside-out from half the angles.
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break

                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            val centroid = event.calculateCentroid()

                            camera = if (pressed >= 2) {
                                // Two fingers: pinch dollies, drag slides the target.
                                var next = camera
                                if (zoom != 1f && centroid != Offset.Unspecified) next = next.dolly(zoom)
                                if (pan != Offset.Zero) next = next.pan(pan.x, pan.y)
                                next
                            } else {
                                // One finger orbits. Negated so the model follows the finger rather
                                // than running away from it.
                                camera.orbit(-pan.x * ORBIT_SENSITIVITY, pan.y * ORBIT_SENSITIVITY)
                            }
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
        )
    }
}

/** Radians of rotation per pixel dragged — a full turn in roughly a screen width. */
private const val ORBIT_SENSITIVITY = 0.008f
