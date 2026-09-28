package com.hereliesaz.graffitixr.feature.editor

import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine
import com.hereliesaz.graffitixr.nativebridge.LiveStrokeOverlay

/**
 * Hosts the live-stroke overlay (Settings → Direct display): a transparent SurfaceView above the
 * window. The Vulkan/GLES engines draw into a SurfaceControl child of it (LiveStrokeOverlay, which
 * imports their AHardwareBuffer layer); the wgpu engine presents into the SurfaceView's own surface
 * through a wgpu swapchain ([GpuStampEngine.DirectSurface]). The child layer and the swapchain
 * are separate layers and only one of them shows a given stroke. Shows nothing unless a stroke is
 * being drawn through it, so it's harmless to keep composed. Place it under the drawing surface in
 * composition order so touches still reach the canvas.
 */
@Suppress("FunctionNaming") // Composable naming.
@Composable
fun LiveStrokeOverlayHost(vm: EditorViewModel, geometry: OverlayGeometry, modifier: Modifier = Modifier) {
    val overlay = remember { LiveStrokeOverlay.create() }
    DisposableEffect(overlay) {
        vm.setLiveOverlay(overlay, geometry)
        onDispose {
            vm.setLiveOverlay(null, null)
            overlay?.close()
        }
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
                        overlay?.attach(holder.surface, width, height)
                        GpuStampEngine.DirectSurface.set(holder.surface, width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        overlay?.detach()
                        // Blocks until the wgpu swapchain (if any) has let go of the surface.
                        GpuStampEngine.DirectSurface.set(null, 0, 0)
                    }
                })
            }
        },
    )
}
