// FILE: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/WgpuDirectDisplay.kt
package com.hereliesaz.graffitixr.nativebridge

/**
 * Who shows the live stroke on the overlay window with wgpu direct display (core/wgpu-engine
 * direct.rs; docs/Native Rendering Engine Design.md §3).
 *
 * A window has one producer, but the engine pool keeps two native wgpu handles and a stroke can
 * land on either. So exactly one handle ([owner]) is attached to the window at a time: starting a
 * stroke on another handle detaches the previous owner first. Everything here runs on
 * [GpuRenderThread] (the natives are wgpu calls), so it needs no locking.
 *
 * Any failure turns direct display off for the rest of the stroke and reports false: the caller
 * then shows the stroke through readback, exactly as without direct display.
 */
internal class WgpuDirectDisplay<S : Any>(private val natives: Natives<S>) {

    /** The wgpu calls this drives (GpuStampEngine's JNI in the app, fakes in tests). */
    interface Natives<S> {
        fun attach(handle: Long, surface: S, width: Int, height: Int): Boolean
        fun detach(handle: Long)
        fun begin(handle: Long): Boolean
        fun present(handle: Long, matrix: FloatArray?, newBatch: Boolean): Boolean
        fun end(handle: Long): Boolean
    }

    private var surface: S? = null
    private var width = 0
    private var height = 0

    /** The handle attached to [surface]; 0 = none. */
    var owner: Long = 0L
        private set

    /** Whether [owner] is showing a stroke now. */
    var showing: Boolean = false
        private set

    val hasSurface: Boolean get() = surface != null

    /**
     * The overlay window appeared, changed size ([surface] non-null) or is going away (null). A
     * window going away detaches its owner now: the caller must not return from
     * `surfaceDestroyed` while a producer is still connected.
     */
    fun setSurface(surface: S?, width: Int, height: Int) {
        if (surface !== this.surface || surface == null) {
            if (owner != 0L) natives.detach(owner)
            owner = 0L
            showing = false
        }
        this.surface = surface
        this.width = width
        this.height = height
    }

    /**
     * Stroke start on [handle], after its layer is seeded: attach (moving the window from the
     * previous owner), snapshot the base, clear. False = keep the readback display this stroke.
     */
    fun begin(handle: Long): Boolean {
        val target = surface?.takeIf { handle != 0L && width > 0 && height > 0 } ?: return false
        if (owner != 0L && owner != handle) {
            natives.detach(owner)
            owner = 0L
        }
        showing = false
        if (natives.attach(handle, target, width, height)) {
            owner = handle
            showing = natives.begin(handle)
        } else {
            natives.detach(handle)
            owner = 0L
        }
        return showing
    }

    /** A batch was stamped on [handle]: present it through [matrix] (surface px -> layer px). */
    fun present(handle: Long, matrix: FloatArray): Boolean {
        if (!showing || handle != owner) return false
        val ok = natives.present(handle, matrix, true)
        if (!ok) stop(handle)
        return ok
    }

    /** Refinement ran on [handle] while it shows a stroke: re-present the ease with the last matrix. */
    fun represent(handle: Long): Boolean {
        if (!showing || handle != owner) return false
        val ok = natives.present(handle, null, false)
        if (!ok) stop(handle)
        return ok
    }

    /** Stroke over (the committed layer is on screen): clear the surface. Stays attached. */
    fun end() {
        if (owner != 0L && showing) stop(owner)
    }

    /** [handle] is being destroyed; its surface goes with it. */
    fun handleDestroyed(handle: Long) {
        if (handle != owner) return
        owner = 0L
        showing = false
    }

    private fun stop(handle: Long) {
        showing = false
        natives.end(handle)
    }
}
