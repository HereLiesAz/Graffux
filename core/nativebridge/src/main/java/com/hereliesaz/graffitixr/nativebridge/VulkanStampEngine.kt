// FILE: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/VulkanStampEngine.kt
package com.hereliesaz.graffitixr.nativebridge

import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.HardwareBuffer
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/** Kotlin bridge to the persistent Vulkan dab compositor. */
class VulkanStampEngine {
    init { NativeLibLoader.loadAll() }

    private data class PoolKey(val width: Int, val height: Int, val hardwareBufferBacked: Boolean)
    private data class CachedHandle(val key: PoolKey, val handle: Long)

    companion object {
        private const val MAX_POOLED_HANDLES = 2
        private val poolLock = Any()
        private val pooledHandles = ArrayDeque<CachedHandle>()
        private val nativeCreationCount = AtomicInteger(0)

        @JvmStatic
        fun trimPool() {
            val handles = synchronized(poolLock) {
                if (pooledHandles.isEmpty()) return
                buildList { while (pooledHandles.isNotEmpty()) add(pooledHandles.removeFirst().handle) }
            }
            val destroyer = VulkanStampEngine()
            handles.forEach(destroyer::nativeDestroy)
        }

        internal fun nativeCreationCountForTesting(): Int = nativeCreationCount.get()

        private fun takePooled(key: PoolKey): Long = synchronized(poolLock) {
            val iterator = pooledHandles.iterator()
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

    private var nativeHandle: Long = 0L
    private var poolKey: PoolKey? = null
    private var healthy = true
    private var hardwareBufferExported = false

    val isInitialized: Boolean get() = nativeHandle != 0L

    fun init(width: Int, height: Int): Boolean = initialize(width, height, false)
    fun initHardwareBufferBacked(width: Int, height: Int): Boolean = initialize(width, height, true)

    private fun initialize(width: Int, height: Int, hardwareBufferBacked: Boolean): Boolean {
        if (width <= 0 || height <= 0) { destroy(); return false }
        destroy()
        val key = PoolKey(width, height, hardwareBufferBacked)
        val cached = takePooled(key)
        if (cached != 0L) {
            nativeHandle = cached
            poolKey = key
            healthy = true
            hardwareBufferExported = false
            if (nativeClear(cached)) return true
            nativeDestroy(cached)
            nativeHandle = 0L
            poolKey = null
            healthy = false
        }
        nativeCreationCount.incrementAndGet()
        val created = if (hardwareBufferBacked) nativeInitHardwareBuffer(width, height) else nativeInit(width, height)
        nativeHandle = created
        poolKey = if (created != 0L) key else null
        healthy = created != 0L
        hardwareBufferExported = false
        return created != 0L
    }

    fun getHardwareBuffer(): HardwareBuffer? {
        if (!isInitialized) return null
        val buffer = nativeGetHardwareBuffer(nativeHandle)
        if (buffer != null) hardwareBufferExported = true
        return buffer
    }

    fun upload(bitmap: Bitmap): Boolean {
        if (!isInitialized) return false
        require(bitmap.config == Bitmap.Config.ARGB_8888) { "VulkanStampEngine.upload requires ARGB_8888, got ${bitmap.config}" }
        return nativeUpload(nativeHandle, bitmap).also { if (!it) healthy = false }
    }

    /** Historical stroke-level paint entry point. */
    fun stampDabs(dabs: List<BrushDab>, colorArgb: Int, hardness: Float): Boolean {
        if (!isInitialized || dabs.isEmpty()) return false
        val flat = FloatArray(dabs.size * 5)
        for (i in dabs.indices) {
            val d = dabs[i]
            val base = i * 5
            flat[base] = d.x; flat[base + 1] = d.y; flat[base + 2] = d.radius
            flat[base + 3] = d.alpha; flat[base + 4] = d.angleDeg
        }
        return nativeStampDabs(nativeHandle, flat, colorArgb, hardness).also { if (!it) healthy = false }
    }

    /** Widened Krita-style entry point: every dab owns its resolved colour and flow. */
    fun stampResolvedDabs(dabs: List<ResolvedBrushDab>, hardness: Float): Boolean {
        if (!isInitialized || dabs.isEmpty()) return false
        val flat = FloatArray(dabs.size * 10)
        for (i in dabs.indices) {
            val d = dabs[i]
            val base = i * 10
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
        }
        return nativeStampResolvedDabs(nativeHandle, flat, hardness).also { if (!it) healthy = false }
    }

    fun readback(bitmap: Bitmap): Boolean {
        if (!isInitialized) return false
        require(bitmap.config == Bitmap.Config.ARGB_8888) { "VulkanStampEngine.readback requires ARGB_8888, got ${bitmap.config}" }
        return nativeReadback(nativeHandle, bitmap).also { if (!it) healthy = false }
    }

    fun destroy() {
        val handle = nativeHandle
        if (handle == 0L) return
        val key = poolKey
        nativeHandle = 0L
        poolKey = null
        val mayPool = healthy && key != null && !hardwareBufferExported
        healthy = true
        hardwareBufferExported = false
        if (!mayPool) { nativeDestroy(handle); return }
        val evicted = putPooled(CachedHandle(key!!, handle))
        if (evicted != 0L) nativeDestroy(evicted)
    }

    private external fun nativeInit(width: Int, height: Int): Long
    private external fun nativeInitHardwareBuffer(width: Int, height: Int): Long
    private external fun nativeClear(handle: Long): Boolean
    private external fun nativeGetHardwareBuffer(handle: Long): HardwareBuffer?
    private external fun nativeUpload(handle: Long, inBitmap: Bitmap): Boolean
    private external fun nativeStampDabs(handle: Long, dabData: FloatArray, colorArgb: Int, hardness: Float): Boolean
    private external fun nativeStampResolvedDabs(handle: Long, dabData: FloatArray, hardness: Float): Boolean
    private external fun nativeReadback(handle: Long, outBitmap: Bitmap): Boolean
    private external fun nativeDestroy(handle: Long)
}

data class BrushDab(val x: Float, val y: Float, val radius: Float, val alpha: Float, val angleDeg: Float)

data class ResolvedBrushDab(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float,
    val angleDeg: Float,
    val colorArgb: Int,
    val flow: Float,
)
