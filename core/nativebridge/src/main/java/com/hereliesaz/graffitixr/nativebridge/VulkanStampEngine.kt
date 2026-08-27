// FILE: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/VulkanStampEngine.kt
package com.hereliesaz.graffitixr.nativebridge

import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kotlin bridge to the native Vulkan compute dab compositor (Phase 3 of
 * `docs/Native Rendering Engine Design.md` §9) — VulkanStampEngine.cpp/.h and stamp.comp.
 *
 * A wrapper instance owns one checked-out native engine at a time. [destroy] returns a healthy
 * engine to a tiny process-local pool instead of tearing down the Vulkan instance/device/pipeline
 * after every brush stroke. The next wrapper requesting the same dimensions/backing mode checks
 * that engine out, clears its layer to transparent, and reuses it. The pool is deliberately bounded
 * to two entries: enough for the editor's round/stamp preview paths without turning old canvas sizes
 * into immortal GPU allocations. Eviction performs the real native destruction.
 *
 * The class remains not thread-safe per instance. Pool checkout/check-in is synchronized; callers
 * still serialize operations on each checked-out wrapper.
 */
class VulkanStampEngine {

    init {
        NativeLibLoader.loadAll()
    }

    private data class PoolKey(
        val width: Int,
        val height: Int,
        val hardwareBufferBacked: Boolean,
    )

    private data class CachedHandle(
        val key: PoolKey,
        val handle: Long,
    )

    companion object {
        private const val MAX_POOLED_HANDLES = 2
        private val poolLock = Any()
        private val pooledHandles = ArrayDeque<CachedHandle>()
        private val nativeCreationCount = AtomicInteger(0)

        /**
         * Immediately releases every idle pooled native engine. Active checked-out wrappers are not
         * touched. Useful for explicit memory-pressure handling and instrumentation-test cleanup.
         */
        @JvmStatic
        fun trimPool() {
            val handles = synchronized(poolLock) {
                if (pooledHandles.isEmpty()) return
                buildList {
                    while (pooledHandles.isNotEmpty()) add(pooledHandles.removeFirst().handle)
                }
            }
            // nativeDestroy is an instance JNI method only because the original bridge was; it does
            // not use jobject. A handle-less wrapper is therefore sufficient to invoke it here.
            val destroyer = VulkanStampEngine()
            handles.forEach(destroyer::nativeDestroy)
        }

        internal fun nativeCreationCountForTesting(): Int = nativeCreationCount.get()

        private fun takePooled(key: PoolKey): Long {
            synchronized(poolLock) {
                val iterator = pooledHandles.iterator()
                while (iterator.hasNext()) {
                    val cached = iterator.next()
                    if (cached.key == key) {
                        iterator.remove()
                        return cached.handle
                    }
                }
            }
            return 0L
        }

        private fun putPooled(cached: CachedHandle): Long {
            synchronized(poolLock) {
                var evicted = 0L
                if (pooledHandles.size >= MAX_POOLED_HANDLES) {
                    evicted = pooledHandles.removeFirst().handle
                }
                pooledHandles.addLast(cached)
                return evicted
            }
        }
    }

    // Opaque graffux::VulkanStampEngine* while checked out, 0 when this wrapper owns none.
    private var nativeHandle: Long = 0L
    private var poolKey: PoolKey? = null
    private var healthy: Boolean = true
    // An exported Java HardwareBuffer keeps an independent ref to the AHB. Do not recycle that
    // native engine into another drawing session after export: callers are entitled to keep reading
    // the old buffer contents until they close their Java object.
    private var hardwareBufferExported: Boolean = false

    val isInitialized: Boolean get() = nativeHandle != 0L

    /** Creates or reuses a plain device-memory layer, cleared to transparent black. */
    fun init(width: Int, height: Int): Boolean = initialize(width, height, hardwareBufferBacked = false)

    /** Creates or reuses an AHardwareBuffer-backed layer, cleared to transparent black. */
    fun initHardwareBufferBacked(width: Int, height: Int): Boolean =
        initialize(width, height, hardwareBufferBacked = true)

    private fun initialize(width: Int, height: Int, hardwareBufferBacked: Boolean): Boolean {
        if (width <= 0 || height <= 0) {
            destroy()
            return false
        }

        // Re-init is a checkout transition too: return the current healthy engine to the pool first.
        destroy()
        val key = PoolKey(width, height, hardwareBufferBacked)

        val cached = takePooled(key)
        if (cached != 0L) {
            nativeHandle = cached
            poolKey = key
            healthy = true
            hardwareBufferExported = false
            // Pool reuse must preserve init()'s long-standing contract: a newly initialized layer is
            // transparent, never whatever pixels the previous stroke left behind.
            if (nativeClear(cached)) return true

            // A failed clear means the cached engine is suspect. Do not circulate it again.
            nativeDestroy(cached)
            nativeHandle = 0L
            poolKey = null
            healthy = false
        }

        nativeCreationCount.incrementAndGet()
        val created = if (hardwareBufferBacked) {
            nativeInitHardwareBuffer(width, height)
        } else {
            nativeInit(width, height)
        }
        nativeHandle = created
        poolKey = if (created != 0L) key else null
        healthy = created != 0L
        hardwareBufferExported = false
        return created != 0L
    }

    /**
     * Returns the AHardwareBuffer backing an AHB-created layer. Each successful call exports an
     * independent Java reference; that engine will be destroyed, not pooled, when this wrapper is
     * released so the exported buffer cannot silently become another stroke's canvas.
     */
    fun getHardwareBuffer(): HardwareBuffer? {
        if (!isInitialized) return null
        val buffer = nativeGetHardwareBuffer(nativeHandle)
        if (buffer != null) hardwareBufferExported = true
        return buffer
    }

    /** Replaces the layer contents with [bitmap]. */
    fun upload(bitmap: Bitmap): Boolean {
        if (!isInitialized) return false
        require(bitmap.config == Bitmap.Config.ARGB_8888) {
            "VulkanStampEngine.upload requires an ARGB_8888 bitmap, got ${bitmap.config}"
        }
        val ok = nativeUpload(nativeHandle, bitmap)
        if (!ok) healthy = false
        return ok
    }

    /** Stamps [dabs] onto the current layer in submission order. */
    fun stampDabs(dabs: List<BrushDab>, colorArgb: Int, hardness: Float): Boolean {
        if (!isInitialized || dabs.isEmpty()) return false
        val flat = FloatArray(dabs.size * 5)
        for (i in dabs.indices) {
            val d = dabs[i]
            val base = i * 5
            flat[base] = d.x
            flat[base + 1] = d.y
            flat[base + 2] = d.radius
            flat[base + 3] = d.alpha
            flat[base + 4] = d.angleDeg
        }
        val ok = nativeStampDabs(nativeHandle, flat, colorArgb, hardness)
        if (!ok) healthy = false
        return ok
    }

    /** Reads the current layer contents into [bitmap]. */
    fun readback(bitmap: Bitmap): Boolean {
        if (!isInitialized) return false
        require(bitmap.config == Bitmap.Config.ARGB_8888) {
            "VulkanStampEngine.readback requires an ARGB_8888 bitmap, got ${bitmap.config}"
        }
        val ok = nativeReadback(nativeHandle, bitmap)
        if (!ok) healthy = false
        return ok
    }

    /**
     * Releases this wrapper's ownership. Healthy, non-exported engines are cached for reuse;
     * unhealthy engines and engines whose HardwareBuffer escaped to Java are destroyed immediately.
     * Safe to call repeatedly.
     */
    fun destroy() {
        val handle = nativeHandle
        if (handle == 0L) return
        val key = poolKey

        nativeHandle = 0L
        poolKey = null
        val mayPool = healthy && key != null && !hardwareBufferExported
        healthy = true
        hardwareBufferExported = false

        if (!mayPool) {
            nativeDestroy(handle)
            return
        }

        val evicted = putPooled(CachedHandle(key!!, handle))
        if (evicted != 0L) nativeDestroy(evicted)
    }

    private external fun nativeInit(width: Int, height: Int): Long
    private external fun nativeInitHardwareBuffer(width: Int, height: Int): Long
    private external fun nativeClear(handle: Long): Boolean
    private external fun nativeGetHardwareBuffer(handle: Long): HardwareBuffer?
    private external fun nativeUpload(handle: Long, inBitmap: Bitmap): Boolean
    private external fun nativeStampDabs(handle: Long, dabData: FloatArray, colorArgb: Int, hardness: Float): Boolean
    private external fun nativeReadback(handle: Long, outBitmap: Bitmap): Boolean
    private external fun nativeDestroy(handle: Long)
}

/** One dab, mirroring the native shader's input fields. */
data class BrushDab(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float,
    val angleDeg: Float,
)
