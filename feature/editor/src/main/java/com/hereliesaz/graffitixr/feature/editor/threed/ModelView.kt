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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.graffitixr.common.mesh.Mesh
import com.hereliesaz.graffitixr.common.mesh.MeshPicker
import com.hereliesaz.graffitixr.common.mesh.OrbitCamera
import com.hereliesaz.graffitixr.common.mesh.TexturePaint

/**
 * A 3D model viewport: a [GLSurfaceView] driven by an [OrbitCamera], with the gestures the rest of
 * the app already uses for the 2D canvas.
 *
 * - one finger  → orbit, or paint when [paintEnabled]
 * - two fingers → pinch to dolly, drag to pan
 *
 * Matching the 2D canvas's gesture vocabulary matters more than matching other 3D apps: someone who
 * has learned to move around a drawing here shouldn't have to learn a second set of rules to move
 * around a model. Two fingers keeps navigating even while painting, for the same reason it does on
 * the flat canvas — you have to be able to get to the part you want to paint without putting the
 * brush down.
 *
 * The camera lives in Compose state and is pushed to the renderer, so all the actual maths stays in
 * the pure [OrbitCamera] / [MeshPicker] / [TexturePaint] and none of it needs a device to test.
 */
@Composable
fun ModelView(
    mesh: Mesh?,
    modifier: Modifier = Modifier,
    texture: PaintableTexture? = null,
    paintEnabled: Boolean = false,
    paintColor: Color = Color.White,
    brushRadiusPx: Float = 24f,
    onPaintChanged: () -> Unit = {},
    captureRequest: Int = 0,
    onCaptured: (android.graphics.Bitmap?) -> Unit = {},
) {
    // Framed on first sight of a mesh, so a model of any authored scale opens filling the view.
    var camera by remember(mesh) { mutableStateOf(mesh?.let { OrbitCamera.framing(it) } ?: OrbitCamera()) }
    val renderer = remember { ModelRenderer() }

    renderer.camera = camera
    renderer.texture = texture
    remember(mesh) { mesh?.let { renderer.setMesh(it) } }

    // Driven by a counter rather than a callback the caller invokes, because only the GL thread can
    // read the framebuffer — the request has to be left for it to pick up on its next frame.
    // Skips 0 so merely composing doesn't fire a capture nobody asked for.
    LaunchedEffect(captureRequest) {
        if (captureRequest > 0) renderer.requestCapture(onCaptured)
    }

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
                // Re-keyed on the paint settings so a gesture in progress always uses the values on
                // screen — otherwise the handler captures the colour and size from the composition
                // that started it and keeps painting with them after the user changes either.
                .pointerInput(mesh, texture, paintEnabled, paintColor, brushRadiusPx) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        // The last UV painted, so the gap between two frames of a drag gets filled
                        // in. Reset per gesture: the previous stroke's end is not this one's start.
                        var lastUv: Pair<Float, Float>? = null
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break

                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            val centroid = event.calculateCentroid()

                            if (pressed >= 2) {
                                // Two fingers: pinch dollies, drag slides the target.
                                var next = camera
                                if (zoom != 1f && centroid != Offset.Unspecified) next = next.dolly(zoom)
                                if (pan != Offset.Zero) next = next.pan(pan.x, pan.y)
                                camera = next
                                // A second finger arriving mid-stroke means the user is navigating,
                                // not drawing a line to wherever the model ends up. Break the stroke
                                // so painting doesn't resume by joining across the move.
                                lastUv = null
                            } else if (paintEnabled && texture != null && mesh != null) {
                                val point = event.changes.firstOrNull { it.pressed }?.position
                                if (point != null) {
                                    val next = paintAt(
                                        mesh, texture, camera, point,
                                        size.width.toFloat(), size.height.toFloat(),
                                        lastUv, paintColor.toArgb(), brushRadiusPx,
                                    )
                                    // Only when paint actually landed, so dragging across empty
                                    // space doesn't schedule a save of an unchanged texture.
                                    if (next != null) onPaintChanged()
                                    lastUv = next
                                }
                            } else {
                                // One finger orbits. Negated so the model follows the finger rather
                                // than running away from it.
                                camera = camera.orbit(-pan.x * ORBIT_SENSITIVITY, pan.y * ORBIT_SENSITIVITY)
                            }
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
        )
    }
}

/**
 * Paints one step of a stroke and returns the UV it landed on, or null if the touch missed the
 * model — a miss ends the stroke, so dragging off the silhouette and back on doesn't draw a line
 * through the space between.
 */
private fun paintAt(
    mesh: Mesh,
    texture: PaintableTexture,
    camera: OrbitCamera,
    point: Offset,
    viewportWidth: Float,
    viewportHeight: Float,
    lastUv: Pair<Float, Float>?,
    colorArgb: Int,
    brushRadiusPx: Float,
): Pair<Float, Float>? {
    if (viewportWidth <= 0f || viewportHeight <= 0f) return lastUv
    val hit = MeshPicker.pick(
        mesh,
        screenX = point.x,
        screenY = point.y,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        view = camera.viewMatrix(),
        projection = camera.projectionMatrix(viewportWidth / viewportHeight),
    ) ?: return null

    val from = lastUv ?: (hit.u to hit.v)
    val stamps = TexturePaint.stamps(
        fromU = from.first,
        fromV = from.second,
        toU = hit.u,
        toV = hit.v,
        width = texture.width,
        height = texture.height,
        // A dab every quarter-radius: dense enough that overlapping circles read as one stroke,
        // sparse enough not to flood a fast drag with redundant draws.
        spacingPx = brushRadiusPx / 4f,
    )
    texture.stamp(stamps, colorArgb, brushRadiusPx)
    return hit.u to hit.v
}

/** Radians of rotation per pixel dragged — a full turn in roughly a screen width. */
private const val ORBIT_SENSITIVITY = 0.008f
