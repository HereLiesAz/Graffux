package com.hereliesaz.graffux.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.azphalt.ArgbColor
import com.hereliesaz.graffitixr.common.azphalt.BrushColorSource
import com.hereliesaz.graffitixr.common.azphalt.Dab
import java.awt.image.BufferedImage
import kotlin.math.hypot

/**
 * A real (not a mockup) stamp-brush drawing surface for the desktop app: pointer/pen input ->
 * [Dab] list -> [compositeTileParallel] (the shared azphalt engine's max-combine falloff, spread
 * across CPU cores) -> blitted onto a [BufferedImage] canvas.
 *
 * Every pointer callback ([detectStampGestures]'s `onStart`/`onMove`/`onEnd`) is a suspend function
 * invoked directly from the single gesture-handling coroutine, and each one's own render is awaited
 * before the next pointer event is even read. This is deliberate, not incidental: an earlier version
 * fired a separate `scope.launch` per move with no join before baking the stroke on release, which
 * silently dropped the tail of every stroke (the bake ran before the last frame's render had even
 * started). Keeping it sequential trades a small amount of input-to-paint latency on a very dense
 * drag for actually painting what was drawn -- correctness over responsiveness for this first pass.
 *
 * What this deliberately does NOT attempt (see DESKTOP.md): the full sensor-binding pipeline
 * ([com.hereliesaz.graffitixr.common.azphalt.BrushSensorEngine]) feature:editor's Android UI
 * drives, [com.hereliesaz.graffitixr.common.azphalt.BrushStamps.place]'s arc-length resampling
 * with per-dab pressure interpolation, shaped/masked tips, or layers/undo — this is a first,
 * honestly-scoped vertical slice proving the shared engine and a pen-aware, multi-core-parallel
 * desktop renderer actually work end to end, not a port of the full Android editor.
 */
@Composable
fun DesktopStampCanvas(
    brushRadiusPx: Float,
    brushHardness: Float,
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var committed by remember { mutableStateOf<BufferedImage?>(null) }
    var displayBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    Box(
        modifier = modifier
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0 && size != canvasSize) {
                    canvasSize = size
                    val fresh = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
                    val old = committed
                    if (old != null) {
                        fresh.createGraphics().apply {
                            drawImage(old, 0, 0, null)
                            dispose()
                        }
                    }
                    committed = fresh
                    displayBitmap = fresh.toComposeImageBitmap()
                }
            }
            .pointerInput(Unit) {
                val strokeDabs = ArrayList<Dab>()
                var lastDabPos: Offset? = null
                var strokeBase: BufferedImage? = null
                var lastRenderedFrame: BufferedImage? = null

                fun addDabIfFarEnough(position: Offset, pressure: Float) {
                    val minSpacing = (brushRadiusPx * 2f * 0.12f).coerceAtLeast(1.5f)
                    val last = lastDabPos
                    if (last != null && hypot((position.x - last.x).toDouble(), (position.y - last.y).toDouble()) < minSpacing) {
                        return
                    }
                    lastDabPos = position
                    // Pressure curve: a light touch still shows something (0.35x floor) rather than
                    // vanishing, a firm touch reaches full size — tuned for a Surface Pen's usable
                    // pressure range rather than 0..1 linearly, which reads as mostly-thin in practice.
                    val pressureFactor = (0.35f + 0.65f * pressure.coerceIn(0f, 1f))
                    strokeDabs.add(
                        Dab(
                            x = position.x,
                            y = position.y,
                            radius = (brushRadiusPx * pressureFactor).coerceAtLeast(0.5f),
                            alpha = pressureFactor.coerceIn(0.15f, 1f),
                            angleDeg = 0f,
                            hardness = brushHardness,
                        ),
                    )
                }

                suspend fun renderStroke(base: BufferedImage) {
                    val tiles = compositeTileParallel(
                        dabs = strokeDabs,
                        canvasWidth = base.width,
                        canvasHeight = base.height,
                        colorArgb = ArgbColor.argb(255, 20, 20, 20),
                        secondaryColorArgb = ArgbColor.argb(255, 20, 20, 20),
                        colorSource = BrushColorSource.PLAIN,
                        flow = 1f,
                    )
                    val pixels = IntArray(base.width * base.height)
                    base.getRGB(0, 0, base.width, base.height, pixels, 0, base.width)
                    for (tile in tiles) blitSrcOver(pixels, base.width, base.height, tile)
                    val frame = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_ARGB)
                    frame.setRGB(0, 0, base.width, base.height, pixels, 0, base.width)
                    lastRenderedFrame = frame
                    displayBitmap = frame.toComposeImageBitmap()
                }

                detectStampGestures(
                    onStart = { position, pressure ->
                        val base = committed
                        if (base != null) {
                            strokeBase = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_ARGB).apply {
                                createGraphics().apply { drawImage(base, 0, 0, null); dispose() }
                            }
                            strokeDabs.clear()
                            lastDabPos = null
                            addDabIfFarEnough(position, pressure)
                            renderStroke(strokeBase!!)
                        }
                    },
                    onMove = { position, pressure ->
                        val base = strokeBase
                        if (base != null) {
                            addDabIfFarEnough(position, pressure)
                            renderStroke(base)
                        }
                    },
                    onEnd = {
                        // The last `onMove`'s renderStroke call has already been awaited by the time
                        // this runs (see the class doc comment), so `lastRenderedFrame` is exactly
                        // what's on screen -- bake it in as the next stroke's starting point.
                        lastRenderedFrame?.let { committed = it }
                        strokeBase = null
                        lastRenderedFrame = null
                        strokeDabs.clear()
                        lastDabPos = null
                    },
                )
            },
    ) {
        displayBitmap?.let { bmp ->
            Image(bitmap = bmp, contentDescription = "Canvas", modifier = Modifier.fillMaxSize())
        }
    }
}
