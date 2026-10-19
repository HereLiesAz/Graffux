package com.hereliesaz.graffitixr.data.azphalt.sandbox

import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.types.ValType
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.MemoryLimits
import com.dylibso.chicory.wasm.Parser
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** WASM pages are 64 KiB each (fixed by the spec) — this caps a guest module's own linear memory
 *  at 256 MiB regardless of what the module itself declares as its maximum. */
private const val MAX_GUEST_MEMORY_PAGES = 4096

/** Size of the i32 a guest is handed through a pointer. */
private const val I32_BYTES = 4

/**
 * Executes a WASM module within an isolated Chicory sandbox.
 * No ambient authority — the guest gets nothing beyond the capability-gated host functions bound
 * in [bindCapabilities], never filesystem/network/process access. [run] also bounds how long a
 * single invocation may run (see [runSandboxBounded]) and memory is capped at
 * [MAX_GUEST_MEMORY_PAGES] regardless of what the module itself declares as its own maximum.
 */
class WasmSandbox(
    wasmBytes: InputStream,
    host: AzphaltSandboxHost,
    grantedCapabilities: Set<String> = emptySet()
) {
    private val instance: Instance

    // Extracted host functions mapped to capabilities
    private val hostFunctions = mutableListOf<HostFunction>()

    // Every capability call goes through this proxy so a timed-out, abandoned worker loses host
    // access (see runSandboxBounded's onTimeout).
    private val host = RevocableSandboxHost(host)

    init {
        bindCapabilities(grantedCapabilities)

        val imports = ImportValues.builder()
            .withFunctions(hostFunctions.map { it })
            .build()
        val wasmModule = Parser.parse(wasmBytes)
        val builder = Instance.builder(wasmModule)
            .withImportValues(imports)
        // Instance.Builder.withMemoryLimits() overrides the module's own declared memory-0
        // limits entirely (both initial AND maximum) when set — so the guest's own declared
        // *initial* size has to be read and preserved here, or a module that starts with more
        // than one page would fail to instantiate at all. Only the maximum is actually being
        // capped; a module with no memory section at all just has nothing to cap.
        wasmModule.memorySection().ifPresent { section ->
            val declaredInitialPages = section.getMemory(0).limits().initialPages()
            builder.withMemoryLimits(MemoryLimits(declaredInitialPages, MAX_GUEST_MEMORY_PAGES))
        }
        instance = builder.build()
    }

    /**
     * Invokes the module's per-invocation entry point, if it declares one under the conventional
     * "run" export (mirrors JsSandbox.eval() for the JS runtime). A module that relies solely on
     * WASM start-section side effects during instantiation — already run by
     * Instance.builder().build() above — exports nothing here; the azphalt spec doesn't mandate a
     * "run" export, so a missing one is a no-op rather than a hard failure.
     */
    fun run() {
        val entry = try {
            instance.export("run")
        } catch (_: Exception) {
            null
        } ?: return
        // Bounded: see SandboxExecution.kt. Without this, a guest `run` export that never
        // returns (an infinite loop is one WASM instruction) pins a thread forever with no way
        // to cancel it — this class had no execution-time bound of any kind before.
        runSandboxBounded(onTimeout = this.host::revoke) { entry.apply() }
    }

    private fun bindParamFunctions() {
        hostFunctions.add(
            HostFunction(
                "env", "paramNumber",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.F64)),
                { _: Instance, args: LongArray ->
                    val key = instance.memory().readString(args[0].toInt(), args[1].toInt())
                    val value = host.paramNumber(key) ?: 0.0
                    longArrayOf(java.lang.Double.doubleToRawLongBits(value))
                }
            )
        )
        hostFunctions.add(
            HostFunction(
                "env", "paramBool",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
                { _: Instance, args: LongArray ->
                    val key = instance.memory().readString(args[0].toInt(), args[1].toInt())
                    val value = host.paramBool(key) ?: false
                    longArrayOf(if (value) 1L else 0L)
                }
            )
        )
        hostFunctions.add(
            HostFunction(
                "env", "paramString",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
                { _: Instance, args: LongArray ->
                    val key = instance.memory().readString(args[0].toInt(), args[1].toInt())
                    val outPtr = args[2].toInt()
                    val outCap = args[3].toInt()
                    
                    val value = host.paramString(key)
                    if (value == null) {
                        longArrayOf(-1L)
                    } else {
                        val bytes = value.toByteArray(StandardCharsets.UTF_8)
                        val toCopy = Math.min(bytes.size, outCap)
                        instance.memory().write(outPtr, bytes, 0, toCopy)
                        longArrayOf(bytes.size.toLong())
                    }
                }
            )
        )
    }

    /**
     * The `bitmap` capability, WASM guests only. Pixels are straight RGBA8, `width * height * 4`
     * bytes, row-major from the top-left. A guest runs in an interpreter with a hard time limit, so
     * the host does the resampling: a guest asks for the layer already scaled down and hands its
     * result back at that smaller size.
     *
     *   bitmapWidth() -> i32, bitmapHeight() -> i32
     *       the layer's own size; 0 when it has no bitmap
     *   bitmapRead(maxDim, outPtr, outCap, dimsPtr) -> i32
     *       copies the layer's pixels, scaled so the long side is at most maxDim (0 = native size),
     *       writes the width and height as two little-endian i32 at dimsPtr, and returns the full
     *       byte size (copying at most outCap, like paramString); -1 when there is no bitmap or it
     *       is too large to hand over
     *   bitmapWrite(inPtr, len, width, height) -> i32
     *       stages the pixels as the layer's contents, scaled by the host if the size differs;
     *       1 when staged, -1 when refused
     *
     * A write is staged, not applied: the host swaps the layer's pixels in only once `run` returns,
     * so a guest that traps or times out leaves the layer untouched.
     */
    private fun bindBitmapFunctions() {
        hostFunctions.add(
            HostFunction(
                "env", "bitmapWidth",
                FunctionType.of(emptyList(), listOf(ValType.I32)),
                { _: Instance, _: LongArray -> longArrayOf(host.bitmapWidth().toLong()) }
            )
        )
        hostFunctions.add(
            HostFunction(
                "env", "bitmapHeight",
                FunctionType.of(emptyList(), listOf(ValType.I32)),
                { _: Instance, _: LongArray -> longArrayOf(host.bitmapHeight().toLong()) }
            )
        )
        bindBitmapRead()
        bindBitmapWrite()
    }

    private fun bindBitmapRead() {
        hostFunctions.add(
            HostFunction(
                "env", "bitmapRead",
                FunctionType.of(
                    listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32),
                    listOf(ValType.I32),
                ),
                { _: Instance, args: LongArray ->
                    val a = args.map { it.toInt() }
                    val (outPtr, outCap, dimsPtr) = a.drop(1)
                    val pixels = host.bitmapRead(a[0])
                    if (pixels == null) {
                        longArrayOf(-1L)
                    } else {
                        val toCopy = Math.min(pixels.rgba.size, Math.max(outCap, 0))
                        instance.memory().write(outPtr, pixels.rgba, 0, toCopy)
                        instance.memory().writeI32(dimsPtr, pixels.width)
                        instance.memory().writeI32(dimsPtr + I32_BYTES, pixels.height)
                        longArrayOf(pixels.rgba.size.toLong())
                    }
                }
            )
        )
    }

    private fun bindBitmapWrite() {
        hostFunctions.add(
            HostFunction(
                "env", "bitmapWrite",
                FunctionType.of(
                    listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32),
                    listOf(ValType.I32),
                ),
                { _: Instance, args: LongArray ->
                    val a = args.map { it.toInt() }
                    val (ptr, len) = a
                    val (width, height) = a.drop(2)
                    val accepted = len >= 0 && width > 0 && height > 0 &&
                        width.toLong() * height * BITMAP_BYTES_PER_PIXEL == len.toLong() &&
                        host.bitmapWrite(BitmapPixels(width, height, instance.memory().readBytes(ptr, len)))
                    longArrayOf(if (accepted) 1L else -1L)
                }
            )
        )
    }

    private fun bindOptionalParamFunctions() {
        // paramNumber/paramBool collapse "unset" into 0.0/false and are kept unchanged for
        // guests already built against them. These Opt variants distinguish absence, following
        // paramString's convention: they return -1 when the key has no value.
        //   paramNumberOpt(keyPtr, keyLen, outPtr) -> i32: 1 and writes the f64 at outPtr, or -1.
        //   paramBoolOpt(keyPtr, keyLen) -> i32: 1 (true), 0 (false), or -1 (unset).
        hostFunctions.add(
            HostFunction(
                "env", "paramNumberOpt",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
                { _: Instance, args: LongArray ->
                    val key = instance.memory().readString(args[0].toInt(), args[1].toInt())
                    val value = host.paramNumber(key)
                    if (value == null) {
                        longArrayOf(-1L)
                    } else {
                        instance.memory().writeF64(args[2].toInt(), value)
                        longArrayOf(1L)
                    }
                }
            )
        )
        hostFunctions.add(
            HostFunction(
                "env", "paramBoolOpt",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
                { _: Instance, args: LongArray ->
                    val key = instance.memory().readString(args[0].toInt(), args[1].toInt())
                    val value = host.paramBool(key)
                    longArrayOf(
                        when (value) {
                            null -> -1L
                            true -> 1L
                            false -> 0L
                        }
                    )
                }
            )
        )
    }

    private fun bindCapabilities(grantedCapabilities: Set<String>) {
        if ("canvas" in grantedCapabilities) {
            hostFunctions.add(
                HostFunction(
                    "env", "requestRedraw",
                    FunctionType.of(emptyList(), emptyList()),
                    { _: Instance, _: LongArray ->
                        host.requestRedraw()
                        null
                    }
                )
            )
            hostFunctions.add(
                HostFunction(
                    "env", "canvasWidth",
                    FunctionType.of(emptyList(), listOf(ValType.I32)),
                    { _: Instance, _: LongArray ->
                        longArrayOf(host.canvasWidth().toLong())
                    }
                )
            )
            hostFunctions.add(
                HostFunction(
                    "env", "canvasHeight",
                    FunctionType.of(emptyList(), listOf(ValType.I32)),
                    { _: Instance, _: LongArray ->
                        longArrayOf(host.canvasHeight().toLong())
                    }
                )
            )
            hostFunctions.add(
                HostFunction(
                    "env", "canvasDpi",
                    FunctionType.of(emptyList(), listOf(ValType.I32)),
                    { _: Instance, _: LongArray ->
                        longArrayOf(host.canvasDpi().toLong())
                    }
                )
            )
        }
        
        if ("layers" in grantedCapabilities) {
            hostFunctions.add(
                HostFunction(
                    "env", "layerCount",
                    FunctionType.of(emptyList(), listOf(ValType.I32)),
                    { _: Instance, _: LongArray ->
                        longArrayOf(host.layerCount().toLong())
                    }
                )
            )
        }

        if ("bitmap" in grantedCapabilities) {
            bindBitmapFunctions()
        }

        if ("params" in grantedCapabilities) {
            bindParamFunctions()
            bindOptionalParamFunctions()
        }

        if ("color" in grantedCapabilities) {
            hostFunctions.add(
                HostFunction(
                    "env", "colorActive",
                    FunctionType.of(listOf(ValType.I32), emptyList()),
                    { _: Instance, args: LongArray ->
                        val outPtr = args[0].toInt()
                        instance.memory().writeI32(outPtr, host.colorActive())
                        null
                    }
                )
            )
            hostFunctions.add(
                HostFunction(
                    "env", "colorSetActive",
                    FunctionType.of(listOf(ValType.I32), emptyList()),
                    { _: Instance, args: LongArray ->
                        val inPtr = args[0].toInt()
                        host.colorSetActive(instance.memory().readInt(inPtr))
                        null
                    }
                )
            )
        }
        
        if ("assets" in grantedCapabilities) {
            hostFunctions.add(
                HostFunction(
                    "env", "assetRead",
                    FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
                    { _: Instance, args: LongArray ->
                        val pPtr = args[0].toInt()
                        val pLen = args[1].toInt()
                        val outPtr = args[2].toInt()
                        val outCap = args[3].toInt()
                        
                        val path = instance.memory().readString(pPtr, pLen)
                        val data = host.assetRead(path)
                        
                        if (data == null) {
                            longArrayOf(-1L)
                        } else {
                            val toCopy = Math.min(data.size, outCap)
                            instance.memory().write(outPtr, data, 0, toCopy)
                            longArrayOf(data.size.toLong())
                        }
                    }
                )
            )
        }
        
        if ("selection" in grantedCapabilities) {
            hostFunctions.add(
                HostFunction(
                    "env", "selectionSize",
                    FunctionType.of(emptyList(), listOf(ValType.I32)),
                    { _: Instance, _: LongArray ->
                        longArrayOf(host.selectionSize().toLong())
                    }
                )
            )
            hostFunctions.add(
                HostFunction(
                    "env", "selectionRead",
                    // Takes an explicit outCap, like paramString/assetRead above: without one, a
                    // guest that calls selectionSize() once and then allocates a stale/mismatched
                    // buffer would have the full mask written unconditionally, overrunning its own
                    // linear-memory buffer and corrupting adjacent guest heap/data.
                    FunctionType.of(listOf(ValType.I32, ValType.I32), emptyList()),
                    { _: Instance, args: LongArray ->
                        val outPtr = args[0].toInt()
                        val outCap = args[1].toInt()
                        val mask = host.selectionRead()
                        val toCopy = Math.min(mask.size, outCap)
                        instance.memory().write(outPtr, mask, 0, toCopy)
                        null
                    }
                )
            )
        }
    }
}
