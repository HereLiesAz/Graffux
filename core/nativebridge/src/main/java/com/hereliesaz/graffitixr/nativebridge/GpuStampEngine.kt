// FILE: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/GpuStampEngine.kt
package com.hereliesaz.graffitixr.nativebridge

import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.HardwareBuffer
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kotlin bridge to the persistent GPU dab compositor. Three interchangeable native backends produce
 * the same pixels -- Vulkan compute (VulkanStampEngine.cpp), OpenGL ES 3.1 compute
 * (GlesStampEngine.cpp) and wgpu (core/wgpu-engine, the Rust engine shared with the desktop app,
 * behind WgpuStampEngine.cpp) -- chosen per instance by [backend], by default [Backend.preferred]
 * (a Settings choice, so they can be compared on a real device).
 */
class GpuStampEngine(val backend: Backend = Backend.preferred) {
    init { NativeLibLoader.loadAll() }

    /**
     * [hardwareBufferOutput]: whether the backend can render into an AHardwareBuffer, which direct
     * display (LiveStrokeOverlay, raw Vulkan interop) needs. wgpu cannot yet, so with wgpu selected
     * direct display is simply ineligible and strokes display through readback.
     */
    enum class Backend(val nativeId: Int, val label: String, val hardwareBufferOutput: Boolean) {
        VULKAN(0, "vulkan", true),
        GLES(1, "gles", true),

        /** The long-term single engine (ARCHITECTURE.md); needs libgraffux_wgpu.so in the APK. */
        WGPU(2, "wgpu", false);

        companion object {
            /** Backend new engines use. Set from Settings at startup and whenever it changes. */
            @Volatile
            var preferred: Backend = VULKAN

            fun fromLabel(label: String?): Backend = entries.firstOrNull { it.label == label } ?: VULKAN

            /** SharedPreferences file/key holding the chosen backend's [label]. */
            const val PREFS = "gpu_engine"
            const val KEY = "backend"
        }
    }

    private data class PoolKey(
        val width: Int,
        val height: Int,
        val hardwareBufferBacked: Boolean,
        val backend: Backend,
    )
    private data class CachedHandle(val key: PoolKey, val handle: Long)

    companion object {
        private const val MAX_POOLED_HANDLES = 2
        private val poolLock = Any()
        private val pooledHandles = ArrayDeque<CachedHandle>()
        private val nativeCreationCount = AtomicInteger(0)

        private const val RECT_INTS = 4

        /**
         * Every live wgpu native handle (checked out by an engine or pooled). Touched only on
         * [GpuRenderThread], so resident-layer refreshes and invalidations can reach whichever
         * handle holds a layer without racing that handle's own work.
         */
        private val liveWgpuHandles = LinkedHashSet<Long>()

        /** Instance used only to reach the instance-bound JNI entry points from the companion. */
        private var jniHelper: GpuStampEngine? = null

        private fun helper(): GpuStampEngine =
            jniHelper ?: GpuStampEngine(Backend.WGPU).also { jniHelper = it }

        @JvmStatic
        fun trimPool() {
            val cached = synchronized(poolLock) {
                if (pooledHandles.isEmpty()) return
                buildList { while (pooledHandles.isNotEmpty()) add(pooledHandles.removeFirst()) }
            }
            val destroyer = GpuStampEngine()
            cached.filter { it.key.backend != Backend.WGPU }.forEach { destroyer.nativeDestroy(it.handle) }
            val wgpu = cached.filter { it.key.backend == Backend.WGPU }.map { it.handle }
            if (wgpu.isNotEmpty()) {
                GpuRenderThread.post {
                    wgpu.forEach {
                        liveWgpuHandles.remove(it)
                        destroyer.nativeDestroy(it)
                    }
                }
            }
        }

        /**
         * The stroke [stroke] started is committed, and [committed] (the CPU's authoritative
         * result) is now layer [ResidentStroke.layerKey] at [generation]. Queued behind the
         * stroke's own GPU work; re-uploads only the rows the stroke painted plus [changed]
         * ({x, y, w, h}: where [committed] differs from the layer the stroke started from), then
         * retags the resident copy so the next stroke on this layer binds it without an upload. A
         * no-op when the handle is gone or the copy was rebound or invalidated meanwhile -- the next
         * stroke then misses and uploads, which is always correct. [committed] must not be mutated
         * afterwards without invalidating the layer.
         */
        @JvmStatic
        fun refreshResident(stroke: ResidentStroke, generation: Long, committed: Bitmap, changed: IntArray) {
            require(changed.size >= RECT_INTS) { "changed must be {x, y, w, h}" }
            GpuRenderThread.post {
                if (stroke.handle !in liveWgpuHandles) return@post
                helper().nativeRefreshLayer(
                    stroke.handle, longArrayOf(stroke.layerKey, stroke.session, generation), committed, changed,
                )
            }
        }

        /** The CPU layer [layerKey] changed outside a GPU stroke: drop it from every engine (queued). */
        @JvmStatic
        fun invalidateResident(layerKey: Long) {
            GpuRenderThread.post {
                if (liveWgpuHandles.isEmpty()) return@post
                val jni = helper()
                liveWgpuHandles.forEach { jni.nativeInvalidateLayer(it, layerKey) }
            }
        }

        /** Every layer changed (document switch, memory pressure): drop all resident layers (queued). */
        @JvmStatic
        fun invalidateAllResident() {
            GpuRenderThread.post {
                if (liveWgpuHandles.isEmpty()) return@post
                val jni = helper()
                liveWgpuHandles.forEach { jni.nativeInvalidateAllLayers(it) }
            }
        }

        internal fun nativeCreationCountForTesting(): Int = nativeCreationCount.get()

        // ---- Per-device tuning and telemetry (feature/editor gpu/ package) -------------------

        /**
         * Receives every timed pass: [PassKind] ordinal, nanoseconds, and whether the number came
         * from GPU timestamps (true) or CPU wall time around the native call (false). Null = off.
         */
        @Volatile var passTimingSink: PassTimingSink? = null

        /** `key=value` lines from the most recently initialized engine (see StampEngine.h gpuInfo). */
        @Volatile var lastGpuInfo: String? = null
            private set

        /** Resident-layer budget new and live wgpu engines use; <= 0 keeps the engine default. */
        @Volatile var residentBudgetBytes: Long = 0L
            private set

        /**
         * Process-wide tuning for engines created from now on: stamp workgroup edge (8 or 16;
         * Vulkan and wgpu honour it, GLES has fixed local sizes) and GPU timestamp queries.
         */
        @JvmStatic
        fun applyTuning(stampTile: Int, timestamps: Boolean) {
            runCatching { helper().nativeSetStampTuning(stampTile, timestamps) }
        }

        /** Changes the wgpu resident budget on every live engine (queued) and for new ones. */
        @JvmStatic
        fun setResidentBudget(bytes: Long) {
            residentBudgetBytes = bytes
            if (bytes <= 0L) return
            GpuRenderThread.post {
                if (liveWgpuHandles.isEmpty()) return@post
                val jni = helper()
                liveWgpuHandles.forEach { jni.nativeSetResidentBudget(it, bytes) }
            }
        }

        private fun takePooled(key: PoolKey): Long = synchronized(poolLock) {
            // wgpu: most recently returned first -- it holds the layers painted most recently.
            val iterator = if (key.backend == Backend.WGPU) {
                pooledHandles.descendingIterator()
            } else {
                pooledHandles.iterator()
            }
            while (iterator.hasNext()) {
                val cached = iterator.next()
                if (cached.key == key) {
                    iterator.remove()
                    return@synchronized cached.handle
                }
            }
            0L
        }

        private fun putPooled(cached: CachedHandle): Long = synchronized(poolLock) {
            val evicted = if (pooledHandles.size >= MAX_POOLED_HANDLES) pooledHandles.removeFirst().handle else 0L
            pooledHandles.addLast(cached)
            evicted
        }
    }

    // Every public call that touches the native engine, and destroy(), is @Synchronized on this
    // instance. A live stroke's background batch runs stamp/readback calls outside the editor's own
    // stampLiveLock, and stroke teardown (a fast lift, or the next stroke starting) destroys or
    // pools the engine from another thread. Unsynchronized, destroy() freed the mapped staging
    // memory mid-readback: SIGSEGV in GpuStampEngine::readback's memcpy at a page boundary
    // (issues #434, #438). Now destroy() waits for the in-flight call, and any call after it sees a
    // zero handle and returns false, which the batch already treats as "fall back to the CPU".
    @Volatile private var nativeHandle: Long = 0L
    private var poolKey: PoolKey? = null
    private var healthy = true
    private var hardwareBufferExported = false
    private var substrateHeightUploaded = false
    private var paintHeightUploaded = false

    val isInitialized: Boolean get() = nativeHandle != 0L

    @Synchronized
    fun init(width: Int, height: Int): Boolean = initialize(width, height, false)
    @Synchronized
    fun initHardwareBufferBacked(width: Int, height: Int): Boolean = initialize(width, height, true)

    private fun initialize(width: Int, height: Int, hardwareBufferBacked: Boolean): Boolean {
        if (width <= 0 || height <= 0) { destroy(); return false }
        if (hardwareBufferBacked && !backend.hardwareBufferOutput) { destroy(); return false }
        destroy()
        substrateHeightUploaded = false
        paintHeightUploaded = false
        val key = PoolKey(width, height, hardwareBufferBacked, backend)
        val cached = takePooled(key)
        if (cached != 0L) {
            nativeHandle = cached
            poolKey = key
            healthy = true
            hardwareBufferExported = false
            if (onGpu { nativeClear(cached) }) return true
            destroyHandle(cached, backend)
            nativeHandle = 0L
            poolKey = null
            healthy = false
        }
        nativeCreationCount.incrementAndGet()
        val created = onGpu {
            val handle = if (hardwareBufferBacked) {
                nativeInitHardwareBuffer(width, height, backend.nativeId)
            } else {
                nativeInit(width, height, backend.nativeId)
            }
            if (handle != 0L && backend == Backend.WGPU) liveWgpuHandles.add(handle)
            handle
        }
        if (created != 0L) onCreated(created)
        nativeHandle = created
        poolKey = if (created != 0L) key else null
        healthy = created != 0L
        hardwareBufferExported = false
        return created != 0L
    }

    @Synchronized
    fun getHardwareBuffer(): HardwareBuffer? {
        if (!isInitialized) return null
        val buffer = onGpu { nativeGetHardwareBuffer(nativeHandle) }
        if (buffer != null) hardwareBufferExported = true
        return buffer
    }

    @Synchronized
    fun upload(bitmap: Bitmap): Boolean {
        if (!isInitialized) return false
        require(bitmap.config == Bitmap.Config.ARGB_8888) { "GpuStampEngine.upload requires ARGB_8888, got ${bitmap.config}" }
        return onGpu { nativeUpload(nativeHandle, bitmap) }.also { if (!it) healthy = false }
    }

    /** Upload a static R8 canvas-height tile. Call once before substrate-enabled stamping. */
    @Synchronized
    fun uploadSubstrateHeight(heightR8: ByteArray, width: Int, height: Int): Boolean {
        if (!isInitialized || width <= 0 || height <= 0) return false
        require(heightR8.size >= width * height) {
            "heightR8 too small: need ${width * height}, got ${heightR8.size}"
        }
        val ok = onGpu { nativeUploadSubstrateHeight(nativeHandle, heightR8, width, height) }
        substrateHeightUploaded = ok
        if (!ok) healthy = false
        return ok
    }

    /**
     * Uploads the existing normalized per-pixel paint height used by [ImpastoEngine].
     * This is a GPU mirror of the caller-owned FloatArray, not a second height model. The native
     * engine requires full canvas dimensions so shader texel coordinates remain identical to the
     * CPU heightMap[y * width + x] contract.
     */
    @Synchronized
    fun uploadPaintHeight(heightMap: FloatArray, width: Int, height: Int): Boolean {
        if (!isInitialized || width <= 0 || height <= 0) return false
        require(heightMap.size >= width * height) {
            "heightMap too small: need ${width * height}, got ${heightMap.size}"
        }
        val ok = onGpu { nativeUploadPaintHeight(nativeHandle, heightMap, width, height) }
        paintHeightUploaded = ok
        if (!ok) healthy = false
        return ok
    }

    /** Historical stroke-level paint entry point. */
    @Synchronized
    fun stampDabs(dabs: List<BrushDab>, colorArgb: Int, hardness: Float): Boolean {
        if (!isInitialized || dabs.isEmpty()) return false
        val flat = FloatArray(dabs.size * 5)
        for (i in dabs.indices) {
            val d = dabs[i]
            val base = i * 5
            flat[base] = d.x; flat[base + 1] = d.y; flat[base + 2] = d.radius
            flat[base + 3] = d.alpha; flat[base + 4] = d.angleDeg
        }
        return timed(PassKind.STAMP) { onGpu { nativeStampDabs(nativeHandle, flat, colorArgb, hardness) } }
            .also { if (!it) healthy = false }
    }

    /**
     * Widened Krita-style entry point: every dab owns its resolved colour, flow and hardness.
     *
     * [buildUp] (default false) mirrors [com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush.
     * buildUp] / StampBrushRenderer.paintDabs' own compositing choice: false takes each pixel's
     * single strongest dab in this call rather than compounding every overlap (matching
     * paintRoundDabsMaxCombined, so a dragged soft round brush doesn't read as hardened on the GPU
     * live-paint path either); true composites sequentially in submission order instead.
     *
     * [strokeMax] (ignored when [buildUp]) extends that max-combine across every call since the
     * last [upload]/[clear] instead of just this one, so a live stroke fed in per-frame batches
     * renders exactly like the single max-combined commit call. Without it each frame boundary
     * compounds and a soft round hardens into dots. Costs width*height*8 bytes of GPU memory,
     * allocated on first use; returns false (caller falls back to CPU) if that can't be had.
     */
    @Synchronized
    fun stampResolvedDabs(
        dabs: List<ResolvedBrushDab>,
        buildUp: Boolean = false,
        substrate: VulkanSubstrateParams? = null,
        strokeMax: Boolean = false,
    ): Boolean {
        if (!isInitialized || dabs.isEmpty()) return false
        if (substrate != null && !substrateHeightUploaded) return false
        val flat = FloatArray(dabs.size * 15)
        for (i in dabs.indices) {
            val d = dabs[i]
            val base = i * 15
            flat[base] = d.x
            flat[base + 1] = d.y
            flat[base + 2] = d.radius
            flat[base + 3] = d.alpha
            flat[base + 4] = d.angleDeg
            flat[base + 5] = Color.red(d.colorArgb) / 255f
            flat[base + 6] = Color.green(d.colorArgb) / 255f
            flat[base + 7] = Color.blue(d.colorArgb) / 255f
            flat[base + 8] = Color.alpha(d.colorArgb) / 255f
            flat[base + 9] = d.flow
            flat[base + 10] = d.hardness.coerceIn(0f, 1f)
            flat[base + 11] = d.contactDepth.coerceIn(0f, 1f)
            flat[base + 12] = d.reservoirLoad.coerceIn(0f, 1f)
            flat[base + 13] = d.depositionRate.coerceIn(0f, 1f)
            flat[base + 14] = d.substrateResponse.coerceIn(0f, 1f)
        }
        val cfg = substrate?.sanitized()
        return timed(PassKind.STAMP) { onGpu {
            nativeStampResolvedDabs(
                nativeHandle, flat, buildUp, cfg != null, cfg != null && paintHeightUploaded,
                cfg?.baseHeight ?: 0f, cfg?.heightScale ?: 0f, cfg?.textureScale ?: 1f,
                cfg?.textureOffsetX ?: 0f, cfg?.textureOffsetY ?: 0f, strokeMax,
            )
        } }.also { if (!it) healthy = false }
    }

    /**
     * shaders/stamp_masked.comp counterpart to [stampResolvedDabs]: each dab samples [maskAlpha8]
     * (an R8 alpha-only tip texture, [maskWidth]x[maskHeight], white=full coverage) in its own
     * rotated/scaled local space instead of using the round coverage falloff -- the GPU-side
     * counterpart to StampBrushRenderer's masked-tip CPU path (docs/Krita Brush Engine Adoption.md
     * item 15). The mask texture is only re-uploaded natively when its dimensions or content differ
     * from the previous call, so repeated calls with the same tip within one stroke are cheap.
     *
     * [grainAlpha8] (item 15's texture/grain follow-up) is an optional second single-channel tile
     * -- pre-baked exactly like [com.hereliesaz.graffitixr.feature.editor.BrushTipMaskCache]'s own
     * grain-tile cache, so this only ever multiplies coverage down, matching
     * `StampBrushRenderer.applyGrain`'s CPU math. `null` (the default) disables grain for this
     * call. [grainCanvasLocked]/[grainScale]/[grainPhaseX]/[grainPhaseY] mirror
     * `GrainBehavior.CANVAS_LOCKED` vs `MOVING`, `AzphaltBrush.grainScale`, and the caller's
     * already-resolved per-stroke phase (`grainOffsetX`/`Y` plus any `grainRandomOffsetPerStroke`
     * draw), same as the CPU path resolves them once per stroke.
     *
     * [secondaryDabs] (item 15's masked/dual-brush follow-up) is an optional second tip composited
     * onto each primary dab -- the GPU counterpart to `StampBrushRenderer.paintMaskedDabs`'
     * DST_IN/DST_OUT secondary-tip compositing. When non-empty it must be exactly `dabs.size()`
     * long (same index, parallel arrays -- a per-STROKE feature, matching how
     * `AzphaltBrush.maskedBrush` attaches a `MaskDab` to every dab or none). [secondaryMaskAlpha8]/
     * [secondaryMaskWidth]/[secondaryMaskHeight] are the secondary tip's own R8 mask texture, same
     * convention as [maskAlpha8]. An empty [secondaryDabs] (the default) disables dual-brush
     * compositing entirely.
     */
    @Synchronized
    fun stampMaskedDabs(
        dabs: List<MaskedBrushDab>,
        hardness: Float,
        maskAlpha8: ByteArray,
        maskWidth: Int,
        maskHeight: Int,
        grainAlpha8: ByteArray? = null,
        grainWidth: Int = 0,
        grainHeight: Int = 0,
        grainCanvasLocked: Boolean = false,
        grainScale: Float = 1f,
        grainPhaseX: Float = 0f,
        grainPhaseY: Float = 0f,
        secondaryDabs: List<SecondaryBrushDab> = emptyList(),
        secondaryMaskAlpha8: ByteArray? = null,
        secondaryMaskWidth: Int = 0,
        secondaryMaskHeight: Int = 0,
        substrate: VulkanSubstrateParams? = null,
    ): Boolean {
        if (!isInitialized || dabs.isEmpty()) return false
        if (substrate != null && !substrateHeightUploaded) return false
        require(maskWidth > 0 && maskHeight > 0) { "maskWidth/maskHeight must be positive" }
        require(maskAlpha8.size >= maskWidth * maskHeight) {
            "maskAlpha8 too small: need ${maskWidth * maskHeight}, got ${maskAlpha8.size}"
        }
        if (grainAlpha8 != null) {
            require(grainWidth > 0 && grainHeight > 0) { "grainWidth/grainHeight must be positive when grainAlpha8 is supplied" }
            require(grainAlpha8.size >= grainWidth * grainHeight) {
                "grainAlpha8 too small: need ${grainWidth * grainHeight}, got ${grainAlpha8.size}"
            }
        }
        if (secondaryDabs.isNotEmpty()) {
            require(secondaryDabs.size == dabs.size) {
                "secondaryDabs must be exactly dabs.size() long: got ${secondaryDabs.size}, expected ${dabs.size}"
            }
            require(secondaryMaskAlpha8 != null && secondaryMaskWidth > 0 && secondaryMaskHeight > 0) {
                "secondaryMaskAlpha8/secondaryMaskWidth/secondaryMaskHeight must be supplied when secondaryDabs is non-empty"
            }
            require(secondaryMaskAlpha8.size >= secondaryMaskWidth * secondaryMaskHeight) {
                "secondaryMaskAlpha8 too small: need ${secondaryMaskWidth * secondaryMaskHeight}, got ${secondaryMaskAlpha8.size}"
            }
        }
        val flat = FloatArray(dabs.size * 15)
        for (i in dabs.indices) {
            val d = dabs[i]
            val base = i * 15
            flat[base] = d.x
            flat[base + 1] = d.y
            flat[base + 2] = d.radius
            flat[base + 3] = d.alpha
            flat[base + 4] = d.angleDeg
            flat[base + 5] = Color.red(d.colorArgb) / 255f
            flat[base + 6] = Color.green(d.colorArgb) / 255f
            flat[base + 7] = Color.blue(d.colorArgb) / 255f
            flat[base + 8] = Color.alpha(d.colorArgb) / 255f
            flat[base + 9] = d.flow
            flat[base + 10] = d.tipRatio
            flat[base + 11] = d.contactDepth.coerceIn(0f, 1f)
            flat[base + 12] = d.reservoirLoad.coerceIn(0f, 1f)
            flat[base + 13] = d.depositionRate.coerceIn(0f, 1f)
            flat[base + 14] = d.substrateResponse.coerceIn(0f, 1f)
        }
        val secondaryFlat = if (secondaryDabs.isNotEmpty()) {
            FloatArray(secondaryDabs.size * 8).also { out ->
                for (i in secondaryDabs.indices) {
                    val sd = secondaryDabs[i]
                    val base = i * 8
                    out[base] = sd.x
                    out[base + 1] = sd.y
                    out[base + 2] = sd.radius
                    out[base + 3] = sd.tipRatio
                    out[base + 4] = sd.alpha
                    out[base + 5] = sd.angleDeg
                    out[base + 6] = sd.flowMultiplier
                    out[base + 7] = if (sd.keepInside) 1f else 0f
                }
            }
        } else null
        val cfg = substrate?.sanitized()
        return timed(PassKind.STAMP) { onGpu {
            nativeStampMaskedDabs(
            nativeHandle, flat, hardness, maskAlpha8, maskWidth, maskHeight,
            grainAlpha8, grainWidth, grainHeight, grainCanvasLocked, grainScale, grainPhaseX, grainPhaseY,
            secondaryFlat, secondaryMaskAlpha8, secondaryMaskWidth, secondaryMaskHeight,
            cfg != null, cfg != null && paintHeightUploaded,
            cfg?.baseHeight ?: 0f, cfg?.heightScale ?: 0f, cfg?.textureScale ?: 1f,
            cfg?.textureOffsetX ?: 0f, cfg?.textureOffsetY ?: 0f,
            )
        } }.also { if (!it) healthy = false }
    }

    /**
     * Persistent Color Smudge pass. The image must already be seeded with [upload].
     *
     * Reservoir pickup remains part of this same Color Smudge operation. [baseColorRate],
     * [chargeDecayRate], and [pickupRate] let native code evolve the same finite brush load and
     * carried colour as the CPU reference while [ColorSmudgeDab.colorRateMultiplier] preserves
     * sensor authority per resolved dab.
     *
     * [sampleSource] (item 11's Sample Merged follow-up) is an optional composite of the other
     * visible layers, ARGB ints in the same layout [android.graphics.Bitmap.getPixels] produces --
     * the GPU counterpart to `ColorSmudgeEngine.apply`'s `sampleSource` parameter. When supplied
     * (with matching positive [sampleSourceWidth]/[sampleSourceHeight]), every dab in this call
     * reads pickup from it instead of the active layer, matching the CPU reference's `readSource`
     * vs `pixels` split exactly. It is converted to RGBA byte order here -- R,G,B,A per pixel, the
     * same native layout [upload]/`nativeReadback` already use for the layer image itself (see
     * `GraffitiJNI.cpp`'s `nativeReadback` doc comment) -- so the GPU texture this seeds is byte-
     * identical to what [upload] would produce for the same pixels. `null` (the default) disables
     * it for this call.
     */
    @Synchronized
    fun colorSmudge(
        dabs: List<ColorSmudgeDab>,
        mode: Int,
        radiusPx: Float,
        feathering: Float,
        smearAlpha: Boolean,
        paintColorArgb: Int,
        dilution: Float = 0f,
        baseColorRate: Float = 0f,
        chargeDecayRate: Float = 0f,
        pickupRate: Float = 0f,
        sampleSource: IntArray? = null,
        sampleSourceWidth: Int = 0,
        sampleSourceHeight: Int = 0,
    ): Boolean {
        if (!isInitialized || dabs.size < 2) return false
        val flat = FloatArray(dabs.size * 8)
        for (i in dabs.indices) {
            val d = dabs[i]
            val base = i * 8
            flat[base] = d.x
            flat[base + 1] = d.y
            flat[base + 2] = d.smudgeRate
            flat[base + 3] = d.colorRate
            flat[base + 4] = d.opacity
            flat[base + 5] = d.smudgeRadius
            flat[base + 6] = d.colorRateMultiplier
            flat[base + 7] = d.distanceDeltaPx
        }
        val sampleSourceRgba8 = if (
            sampleSource != null && sampleSourceWidth > 0 && sampleSourceHeight > 0 &&
            sampleSource.size >= sampleSourceWidth * sampleSourceHeight
        ) {
            val pixelCount = sampleSourceWidth * sampleSourceHeight
            val bytes = ByteArray(pixelCount * 4)
            for (i in 0 until pixelCount) {
                val argb = sampleSource[i]
                val base = i * 4
                bytes[base] = ((argb shr 16) and 0xFF).toByte()
                bytes[base + 1] = ((argb shr 8) and 0xFF).toByte()
                bytes[base + 2] = (argb and 0xFF).toByte()
                bytes[base + 3] = ((argb shr 24) and 0xFF).toByte()
            }
            bytes
        } else null
        val ok = timed(PassKind.SMUDGE) { onGpu {
            nativeColorSmudge(
            nativeHandle, flat, mode, radiusPx, feathering, smearAlpha, paintColorArgb, dilution,
            baseColorRate, chargeDecayRate, pickupRate,
            sampleSourceRgba8, sampleSourceWidth, sampleSourceHeight,
            )
        } }
        if (!ok) healthy = false
        return ok
    }

    /** Benchmark result chosen on this GPU after the first Smudge call (vendor/device ids are 0 on GLES). */
    @Synchronized
    fun colorSmudgeBenchmarkInfo(): ColorSmudgeBenchmarkInfo? {
        if (!isInitialized) return null
        val values = onGpu { nativeColorSmudgeBenchmarkInfo(nativeHandle) } ?: return null
        if (values.size < 5 || values[2] == 0L) return null
        return ColorSmudgeBenchmarkInfo(
            vendorId = values[0].toInt(),
            deviceId = values[1].toInt(),
            selectedTileSize = values[2].toInt(),
            nanos8 = values[3],
            nanos16 = values[4],
        )
    }

    @Synchronized
    fun readback(bitmap: Bitmap): Boolean {
        if (!isInitialized) return false
        require(bitmap.config == Bitmap.Config.ARGB_8888) { "GpuStampEngine.readback requires ARGB_8888, got ${bitmap.config}" }
        val ok = timed(PassKind.READBACK) {
            if (backend == Backend.WGPU) readbackWgpuRect(bitmap) else nativeReadback(nativeHandle, bitmap)
        }
        if (!ok) healthy = false
        return ok
    }

    /**
     * wgpu: only the rectangle dirtied since the last readback crosses from the GPU, and only that
     * rectangle of [bitmap] is written; the engine reports which one.
     */
    private fun readbackWgpuRect(bitmap: Bitmap): Boolean {
        val rect = IntArray(RECT_INTS)
        val ok = onGpu { nativeReadbackRect(nativeHandle, bitmap, rect) }
        if (ok && rect[2] > 0 && rect[3] > 0) {
            lastReadbackRect = rect
            readbackPixels += rect[2].toLong() * rect[3]
        }
        return ok
    }

    /** The rectangle ({x, y, w, h}) the last non-empty wgpu [readback] copied; null otherwise. */
    @Volatile var lastReadbackRect: IntArray? = null
        private set

    /** Pixels read back from the GPU by this engine instance since [init] (wgpu only; diagnostics). */
    @Volatile var readbackPixels: Long = 0L
        private set

    /** Whether this engine keeps layers resident across strokes (wgpu with a current library). */
    @Synchronized
    fun supportsResidentLayers(): Boolean =
        isInitialized && backend == Backend.WGPU && onGpu { nativeSupportsResidentLayers(nativeHandle) }

    /** (resident layer count, bytes they hold) -- 0 to 0 when not wgpu or the library lacks it. */
    @get:Synchronized
    val residentStats: Pair<Long, Long> get() {
        if (!isInitialized || backend != Backend.WGPU) return 0L to 0L
        val stats = onGpu { nativeResidentStats(nativeHandle) }
        return if (stats == null || stats.size < 2) 0L to 0L else stats[0] to stats[1]
    }

    /**
     * Starts a stroke on layer [layerKey] at content [generation] instead of [upload]: binds the
     * copy this engine already holds when it is resident at exactly that generation (no upload, no
     * whole-layer readback), otherwise uploads [seed] as that layer. [seed] must be the layer's
     * pixels at [generation] and is also the caller's readback target, which is why nothing starts
     * out dirty. Null when unsupported (not wgpu, old library) or on failure: use [upload].
     */
    @Synchronized
    fun beginResidentStroke(seed: Bitmap, layerKey: Long, generation: Long): ResidentStroke? {
        if (!isInitialized || backend != Backend.WGPU) return null
        require(seed.config == Bitmap.Config.ARGB_8888) { "beginResidentStroke requires ARGB_8888, got ${seed.config}" }
        val handle = nativeHandle
        return onGpu {
            if (!nativeSupportsResidentLayers(handle)) return@onGpu null
            val bound = nativeBindLayer(handle, layerKey, generation)
            val session = if (bound != 0L) bound else nativeUploadLayer(handle, layerKey, generation, seed)
            if (session == 0L) null else ResidentStroke(handle, layerKey, generation, session, hit = bound != 0L)
        }
    }

    @Synchronized
    fun destroy() {
        val handle = nativeHandle
        substrateHeightUploaded = false
        paintHeightUploaded = false
        if (handle == 0L) return
        drainGpuTimings(handle)
        val key = poolKey
        nativeHandle = 0L
        poolKey = null
        val mayPool = healthy && key != null && !hardwareBufferExported
        healthy = true
        hardwareBufferExported = false
        substrateHeightUploaded = false
        lastReadbackRect = null
        readbackPixels = 0L
        // Pooling is bookkeeping and happens now, so the next stroke's init() finds this handle
        // even while its last batches are still queued: every wgpu call on it runs on
        // GpuRenderThread in order, so the next user's clear() lands after them.
        if (!mayPool) {
            destroyHandle(handle, backend)
        } else {
            val evicted = putPooled(CachedHandle(key!!, handle))
            if (evicted != 0L) destroyHandle(evicted, backend)
        }
    }

    /** A new native engine: record its GPU description and give wgpu the current resident budget. */
    private fun onCreated(handle: Long) = onGpu {
        runCatching { nativeGpuInfo(handle) }.getOrNull()?.let { lastGpuInfo = it }
        if (backend == Backend.WGPU && residentBudgetBytes > 0L) nativeSetResidentBudget(handle, residentBudgetBytes)
    }

    /** GPU-timestamped passes since the last drain, as {kind, nanos} pairs; null without support. */
    @Synchronized
    fun takePassTimings(): LongArray? {
        if (!isInitialized) return null
        return onGpu { nativeTakePassTimings(nativeHandle) }
    }

    /** This engine's `key=value` GPU description (see [lastGpuInfo]); null when not initialized. */
    @Synchronized
    fun gpuInfo(): String? {
        if (!isInitialized) return null
        return onGpu { nativeGpuInfo(nativeHandle) }
    }

    /** Hands GPU timestamps to [passTimingSink] at stroke end; wgpu's drain is queued, not awaited. */
    private fun drainGpuTimings(handle: Long) {
        val sink = passTimingSink ?: return
        val drain = {
            runCatching { nativeTakePassTimings(handle) }.getOrNull()?.let { pairs ->
                var i = 0
                while (i + 1 < pairs.size) {
                    sink.onPass(pairs[i].toInt(), pairs[i + 1], true)
                    i += 2
                }
            }
        }
        if (backend == Backend.WGPU) GpuRenderThread.post { drain() } else drain()
    }

    /** CPU wall time around a native call, reported as not-GPU (telemetry labels it "cpu"). */
    private inline fun <T> timed(kind: PassKind, block: () -> T): T {
        val sink = passTimingSink ?: return block()
        val start = System.nanoTime()
        val result = block()
        sink.onPass(kind.ordinal, System.nanoTime() - start, false)
        return result
    }

    /** Destroys [handle]; wgpu handles later, on the render thread, leaving the live-handle registry. */
    private fun destroyHandle(handle: Long, backend: Backend) {
        if (backend != Backend.WGPU) {
            nativeDestroy(handle)
            return
        }
        // Queued behind the handle's in-flight work instead of waiting for it: destroy() runs on the
        // main thread at stroke teardown and must not stall on a slow GPU.
        GpuRenderThread.post {
            liveWgpuHandles.remove(handle)
            nativeDestroy(handle)
        }
    }

    /** Runs [block] on [GpuRenderThread] for wgpu (every wgpu native call does), inline otherwise. */
    private inline fun <T> onGpu(crossinline block: () -> T): T =
        if (backend == Backend.WGPU) GpuRenderThread.call { block() } else block()

    private external fun nativeInit(width: Int, height: Int, backend: Int): Long
    private external fun nativeInitHardwareBuffer(width: Int, height: Int, backend: Int): Long
    private external fun nativeClear(handle: Long): Boolean
    private external fun nativeGetHardwareBuffer(handle: Long): HardwareBuffer?
    private external fun nativeUpload(handle: Long, inBitmap: Bitmap): Boolean
    private external fun nativeUploadSubstrateHeight(handle: Long, heightR8: ByteArray, width: Int, height: Int): Boolean
    private external fun nativeUploadPaintHeight(handle: Long, heightMap: FloatArray, width: Int, height: Int): Boolean
    private external fun nativeStampDabs(handle: Long, dabData: FloatArray, colorArgb: Int, hardness: Float): Boolean
    private external fun nativeStampResolvedDabs(
        handle: Long, dabData: FloatArray, buildUp: Boolean, hasSubstrate: Boolean, hasPaintHeight: Boolean,
        substrateBaseHeight: Float, substrateHeightScale: Float, substrateTextureScale: Float,
        substrateOffsetX: Float, substrateOffsetY: Float, strokeMax: Boolean,
    ): Boolean
    private external fun nativeStampMaskedDabs(
        handle: Long,
        dabData: FloatArray,
        hardness: Float,
        maskAlpha8: ByteArray,
        maskWidth: Int,
        maskHeight: Int,
        grainAlpha8: ByteArray?,
        grainWidth: Int,
        grainHeight: Int,
        grainCanvasLocked: Boolean,
        grainScale: Float,
        grainPhaseX: Float,
        grainPhaseY: Float,
        secondaryDabData: FloatArray?,
        secondaryMaskAlpha8: ByteArray?,
        secondaryMaskWidth: Int,
        secondaryMaskHeight: Int,
        hasSubstrate: Boolean, hasPaintHeight: Boolean,
        substrateBaseHeight: Float,
        substrateHeightScale: Float,
        substrateTextureScale: Float,
        substrateOffsetX: Float,
        substrateOffsetY: Float,
    ): Boolean
    private external fun nativeColorSmudge(
        handle: Long,
        dabData: FloatArray,
        mode: Int,
        radiusPx: Float,
        feathering: Float,
        smearAlpha: Boolean,
        paintColorArgb: Int,
        dilution: Float,
        baseColorRate: Float,
        chargeDecayRate: Float,
        pickupRate: Float,
        sampleSourceRgba8: ByteArray?,
        sampleSourceWidth: Int,
        sampleSourceHeight: Int,
    ): Boolean
    private external fun nativeColorSmudgeBenchmarkInfo(handle: Long): LongArray?
    private external fun nativeReadback(handle: Long, outBitmap: Bitmap): Boolean
    private external fun nativeReadbackRect(handle: Long, outBitmap: Bitmap, rect: IntArray): Boolean
    private external fun nativeSupportsResidentLayers(handle: Long): Boolean
    private external fun nativeBindLayer(handle: Long, key: Long, generation: Long): Long
    private external fun nativeUploadLayer(handle: Long, key: Long, generation: Long, inBitmap: Bitmap): Long
    /** [ids] = {key, session, generation}; [rect] = {x, y, w, h}. */
    private external fun nativeRefreshLayer(handle: Long, ids: LongArray, bitmap: Bitmap, rect: IntArray): Boolean
    private external fun nativeInvalidateLayer(handle: Long, key: Long): Boolean
    private external fun nativeInvalidateAllLayers(handle: Long)
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetResidentBudget(handle: Long, bytes: Long)
    private external fun nativeResidentStats(handle: Long): LongArray?
    private external fun nativeSetStampTuning(stampTile: Int, timestamps: Boolean)
    private external fun nativeGpuInfo(handle: Long): String?
    private external fun nativeTakePassTimings(handle: Long): LongArray?
}

/** Pass kinds; ordinals match StampEngine.h `PassKind` and wgpu's timing.rs. */
enum class PassKind(val label: String) {
    STAMP("stamp"),
    READBACK("readback"),
    COMPOSITE("composite"),
    SMUDGE("smudge"),

    /** Reserved for the multipass draft/clarity scheduler. */
    MULTIPASS("multipass"),
}

/** See [GpuStampEngine.passTimingSink]. [kind] = [PassKind] ordinal. */
fun interface PassTimingSink {
    fun onPass(kind: Int, nanos: Long, gpu: Boolean)
}

/**
 * A stroke started on a resident layer ([GpuStampEngine.beginResidentStroke]): which native engine
 * holds the layer, and the bind session its commit refresh must match. [hit] = no upload was needed.
 */
class ResidentStroke internal constructor(
    internal val handle: Long,
    val layerKey: Long,
    val generation: Long,
    val session: Long,
    val hit: Boolean,
)

data class BrushDab(val x: Float, val y: Float, val radius: Float, val alpha: Float, val angleDeg: Float)

data class ColorSmudgeDab(
    val x: Float,
    val y: Float,
    val smudgeRate: Float,
    val colorRate: Float,
    val opacity: Float,
    val smudgeRadius: Float,
    val colorRateMultiplier: Float = 1f,
    val distanceDeltaPx: Float = 0f,
)

data class ColorSmudgeBenchmarkInfo(
    val vendorId: Int,
    val deviceId: Int,
    val selectedTileSize: Int,
    val nanos8: Long,
    val nanos16: Long,
)

data class VulkanSubstrateParams(
    val baseHeight: Float = 0f,
    val heightScale: Float = 1f,
    val textureScale: Float = 1f,
    val textureOffsetX: Float = 0f,
    val textureOffsetY: Float = 0f,
) {
    fun sanitized(): VulkanSubstrateParams = copy(
        baseHeight = baseHeight.coerceIn(0f, 1f),
        heightScale = heightScale.coerceIn(0f, 1f),
        textureScale = textureScale.coerceAtLeast(0.05f),
    )
}

data class ResolvedBrushDab(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float,
    val angleDeg: Float,
    val colorArgb: Int,
    val flow: Float,
    /** This dab's own resolved edge falloff (0 = soft, 1 = hard) -- see AzphaltBrush.hardness and
     *  a HARDNESS BrushSensorBinding. Always sent per-dab (unlike the historical [stampDabs]
     *  entry point) so a pressure/tilt-driven hardness dynamic renders identically on the GPU
     *  path as it already does on StampBrushRenderer's CPU path. */
    val hardness: Float = 1f,
    val contactDepth: Float = 1f,
    val reservoirLoad: Float = 1f,
    val depositionRate: Float = 1f,
    val substrateResponse: Float = 0f,
)

/** [ResolvedBrushDab] plus [tipRatio] (height/width of the tip -- see AzphaltBrush.tipRatio), for [GpuStampEngine.stampMaskedDabs]. */
data class MaskedBrushDab(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float,
    val angleDeg: Float,
    val colorArgb: Int,
    val flow: Float,
    val tipRatio: Float,
    val contactDepth: Float = 1f,
    val reservoirLoad: Float = 1f,
    val depositionRate: Float = 1f,
    val substrateResponse: Float = 0f,
)

/**
 * Item 15's masked/dual-brush follow-up: the secondary tip [GpuStampEngine.stampMaskedDabs]
 * composites onto a primary [MaskedBrushDab] at the same list index -- mirrors
 * `com.hereliesaz.graffitixr.common.azphalt.MaskDab`, the CPU-side equivalent, except
 * [keepInside] is pre-resolved from `MaskedBrushBlendMode` + `invert` into a single flag rather
 * than shipping the enum across the JNI boundary (`true` = `DST_IN`/keep-inside, `false` =
 * `DST_OUT`/cut -- see `StampBrushRenderer.paintMaskedDabs`' `keepInside` local for the exact
 * resolution rule this must match).
 */
data class SecondaryBrushDab(
    val x: Float,
    val y: Float,
    val radius: Float,
    val tipRatio: Float,
    val alpha: Float,
    val angleDeg: Float,
    val flowMultiplier: Float,
    val keepInside: Boolean,
)