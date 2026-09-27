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
import com.hereliesaz.graffitixr.nativebridge.LiveStrokeOverlay

/**
 * Hosts the live-stroke overlay (LiveStrokeOverlay, Settings → Direct display): a transparent
 * SurfaceView above the window whose SurfaceControl child the Vulkan overlay renders into. Shows
 * nothing unless a stroke is being drawn through it, so it's harmless to keep composed. Place it
 * under the drawing surface in composition order so touches still reach the canvas.
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
    if (overlay == null) return
    AndroidView(
        modifier = modifier.onGloballyPositioned { geometry.overlayOrigin = it.positionInWindow() },
        factory = { context ->
            SurfaceView(context).apply {
                setZOrderOnTop(true)
                holder.setFormat(PixelFormat.TRANSLUCENT)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        overlay.attach(holder.surface, width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        overlay.detach()
                    }
                })
            }
        },
    )
}
