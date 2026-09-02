package com.hereliesaz.graffux.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushColorSource
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.azphalt.BrushSampleBuilder
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
import java.awt.image.BufferedImage
import kotlin.random.Random

/**
 * A real (not a mockup) stamp-brush drawing surface for the desktop app: pointer/pen input ->
 * [BrushSample]s -> [BrushStamps.dynamicDabs] (the SAME shared entry point the Android app's
 * stroke pipeline uses -- arc-length placement, taper, first-touch blot, sensor-bound dynamics,
 * jitter, everything the brush preset declares, not a hand-rolled desktop-only pressure curve) ->
 * [compositeTileParallel] (the shared max-combine falloff compositor, spread across CPU cores) ->
 * blitted onto a [BufferedImage] canvas.
 *
 * Every pointer callback ([detectStampGestures]'s `onStart`/`onMove`/`onEnd`) is a suspend function
 * invoked directly from the single gesture-handling coroutine, and each one's own render is awaited
 * before the next pointer event is even read. This is deliberate, not incidental: an earlier version
 * fired a separate `scope.launch` per move with no join before baking the stroke on release, which
 * silently dropped the tail of every stroke (the bake ran before the last frame's render had even
 * started). Keeping it sequential trades a small amount of input-to-paint latency on a very dense
 * drag for actually painting what was drawn -- correctness over responsiveness for this first pass.
 *
 * What this still does NOT attempt (see DESKTOP.md): shaped/masked brush tips (a null tip renders
 * a generated round mask, same as [com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes]' own
 * presets do without an installed extension), grain, layers, or project persistence -- a first,
 * honestly-scoped vertical slice proving the shared engine and a pen-aware, multi-core-parallel
 * desktop renderer actually work end to end against the SAME dab-generation code Android runs, not
 * a full port of feature:editor's surrounding UI/state. [CanvasState] does give it real Undo,
 * though -- a whole-canvas snapshot stack, not tile-diffed the way Android's history eventually
 * might be, but genuinely functional.
 */
@Composable
fun DesktopStampCanvas(
    state: CanvasState,
    brush: AzphaltBrush,
    brushRadiusPx: Float,
    colorArgb: Int,
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var displayBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // Keeps the displayed bitmap in sync with `state.committed` when it changes from OUTSIDE a
    // live stroke -- Undo, most notably. A stroke in progress updates `displayBitmap` itself, more
    // often than `state.committed` changes (only once, on release), so this never fights it.
    LaunchedEffect(state.committed) {
        displayBitmap = state.committed?.toComposeImageBitmap()
    }

    Box(
        modifier = modifier
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0 && size != canvasSize) {
                    canvasSize = size
                    val fresh = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
                    val old = state.committed
                    if (old != null) {
                        fresh.createGraphics().apply {
                            drawImage(old, 0, 0, null)
                            dispose()
                        }
                    }
                    // `displayBitmap` follows via the LaunchedEffect above.
                    state.replaceWithoutHistory(fresh)
                }
            }
            .pointerInput(brush, brushRadiusPx, colorArgb) {
                val sampleBuilder = BrushSampleBuilder()
                val samples = ArrayList<BrushSample>()
                var strokeBase: BufferedImage? = null
                var strokeSeed = 0L
                var lastRenderedFrame: BufferedImage? = null

                suspend fun renderStroke(base: BufferedImage) {
                    val dabs = BrushStamps.dynamicDabs(samples, brushRadiusPx * 2f, brush, strokeSeed)
                    val tiles = compositeTileParallel(
                        dabs = dabs,
                        canvasWidth = base.width,
                        canvasHeight = base.height,
                        colorArgb = colorArgb,
                        secondaryColorArgb = colorArgb,
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
                        val base = state.committed
                        if (base != null) {
                            strokeBase = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_ARGB).apply {
                                createGraphics().apply { drawImage(base, 0, 0, null); dispose() }
                            }
                            samples.clear()
                            strokeSeed = Random.nextLong()
                            samples.add(
                                sampleBuilder.add(
                                    x = position.x,
                                    y = position.y,
                                    uptimeMillis = System.nanoTime() / 1_000_000L,
                                    pressure = pressure,
                                ),
                            )
                            renderStroke(strokeBase!!)
                        }
                    },
                    onMove = { position, pressure ->
                        val base = strokeBase
                        if (base != null) {
                            samples.add(
                                sampleBuilder.add(
                                    x = position.x,
                                    y = position.y,
                                    uptimeMillis = System.nanoTime() / 1_000_000L,
                                    pressure = pressure,
                                ),
                            )
                            renderStroke(base)
                        }
                    },
                    onEnd = {
                        // The last `onMove`'s renderStroke call has already been awaited by the time
                        // this runs (see the class doc comment), so `lastRenderedFrame` is exactly
                        // what's on screen -- bake it in as the next stroke's starting point.
                        lastRenderedFrame?.let { state.commitStroke(it) }
                        strokeBase = null
                        lastRenderedFrame = null
                        samples.clear()
                    },
                )
            },
    ) {
        displayBitmap?.let { bmp ->
            Image(bitmap = bmp, contentDescription = "Canvas", modifier = Modifier.fillMaxSize())
        }
    }
}
