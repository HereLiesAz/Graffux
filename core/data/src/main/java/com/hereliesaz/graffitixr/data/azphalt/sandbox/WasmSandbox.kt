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

/**
 * Executes a WASM module within an isolated Chicory sandbox.
 * No ambient authority — the guest gets nothing beyond the capability-gated host functions bound
 * in [bindCapabilities], never filesystem/network/process access. [run] also bounds how long a
 * single invocation may run (see [runSandboxBounded]) and memory is capped at
 * [MAX_GUEST_MEMORY_PAGES] regardless of what the module itself declares as its own maximum.
 */
class WasmSandbox(
    wasmBytes: InputStream,
    private val host: AzphaltSandboxHost,
    grantedCapabilities: Set<String> = emptySet()
) {
    private val instance: Instance

    // Extracted host functions mapped to capabilities
    private val hostFunctions = mutableListOf<HostFunction>()

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
        runSandboxBounded { entry.apply() }
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

        if ("params" in grantedCapabilities) {
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
