package com.hereliesaz.graffitixr.feature.editor

import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine

/**
 * Hosts the live-stroke overlay (Settings → Direct display): a transparent SurfaceView above the
 * window. The wgpu engine presents the stroke in progress into the SurfaceView's own surface
 * through a wgpu swapchain ([GpuStampEngine.DirectSurface]). (The Vulkan/GLES engines' separate
 * SurfaceControl child layer, LiveStrokeOverlay, was retired with those engines.) Shows nothing
 * unless a stroke is being drawn through it, so it's harmless to keep composed. Place it under the
 * drawing surface in composition order so touches still reach the canvas.
 */
@Suppress("FunctionNaming") // Composable naming.
@Composable
fun LiveStrokeOverlayHost(vm: EditorViewModel, geometry: OverlayGeometry, modifier: Modifier = Modifier) {
    DisposableEffect(geometry) {
        vm.setOverlayGeometry(geometry)
        onDispose { vm.setOverlayGeometry(null) }
    }
    AndroidView(
        modifier = modifier.onGloballyPositioned { geometry.overlayOrigin = it.positionInWindow() },
        factory = { context ->
            SurfaceView(context).apply {
                setZOrderOnTop(true)
                holder.setFormat(PixelFormat.TRANSLUCENT)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        GpuStampEngine.DirectSurface.set(holder.surface, width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        // Blocks until the wgpu swapchain (if any) has let go of the surface.
                        GpuStampEngine.DirectSurface.set(null, 0, 0)
                    }
                })
            }
        },
    )
}
