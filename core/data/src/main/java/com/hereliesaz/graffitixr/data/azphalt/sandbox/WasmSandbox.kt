package com.hereliesaz.graffitixr.data.azphalt.sandbox

import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.types.ValType
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.Parser
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Executes a WASM module within an isolated Chicory sandbox.
 * Provides a highly secure environment with no ambient authority, per the azphalt capability model.
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
        instance = Instance.builder(wasmModule)
            .withImportValues(imports)
            .build()
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
        entry.apply()
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
