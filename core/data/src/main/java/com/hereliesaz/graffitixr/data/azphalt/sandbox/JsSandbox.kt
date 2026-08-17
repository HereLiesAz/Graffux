package com.hereliesaz.graffitixr.data.azphalt.sandbox

import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.types.ValType
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasi.WasiOptions
import com.dylibso.chicory.wasi.WasiPreview1
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.types.MemoryLimits
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** WASM pages are 64 KiB each — QuickJS allocates its own JS heap out of this module's linear
 *  memory, so capping it here bounds how much memory a single extension's JS can consume (256
 *  MiB), regardless of what quickjs.wasm itself declares as its maximum. */
private const val MAX_GUEST_MEMORY_PAGES = 4096

/**
 * Executes a JavaScript extension payload inside the `quickjs.wasm` module using Chicory.
 * The `quickjs.wasm` provides the JS runtime, and we provide WASI functions + capability host
 * functions. [eval] bounds how long a single invocation may run (see [runSandboxBounded]), and
 * the guest's memory is capped at [MAX_GUEST_MEMORY_PAGES].
 */
class JsSandbox(
    private val jsCode: String,
    quickjsWasmBytes: InputStream,
    private val host: AzphaltSandboxHost,
    grantedCapabilities: Set<String> = emptySet()
) {
    private val instance: Instance
    
    // Extracted host functions for quickjs capabilities
    private val hostFunctions = mutableListOf<HostFunction>()
    
    init {
        // QuickJS-wasi requires specific host imports in the "env" module
        bindHostCall(grantedCapabilities)
        bindStubs()
        
        // Add Wasi preview 1 functions
        val logger = com.dylibso.chicory.log.SystemLogger()
        val wasiOpts = WasiOptions.builder().build()
        val wasi = WasiPreview1.builder()
            .withLogger(logger)
            .withOptions(wasiOpts)
            .build()

        val importsList = mutableListOf<HostFunction>()
        // AzphaltSandboxHost's contract is deny-by-default: a capability an extension didn't
        // request isn't mapped into the environment at all. WasmSandbox holds to that because it
        // binds nothing but its own capability-gated functions — but wasi.toHostFunctions() bundles
        // real wall-clock time and real system randomness in with the filesystem/proc-exit plumbing
        // QuickJS needs just to run, and every JS extension was getting the clock and RNG whether or
        // not its manifest ever declared Capability.TIME.
        //
        // Denying by OMITTING these three from the import list (the previous approach) doesn't
        // work: the bundled quickjs.wasm declares clock_time_get/random_get as mandatory WASI
        // imports it needs just to instantiate, not optional ones it merely calls if present.
        // Chicory refuses to link a module with an unsatisfied import, so leaving them out made
        // EVERY extension whose manifest doesn't request 'time' fail with UnlinkableException
        // before a single line of its JS ever ran — the exact opposite of "denied the clock,
        // still runs". Deny by REPLACING them with fixed, non-real answers instead: the import
        // is satisfied either way, but only a 'time'-granted extension gets the real clock/RNG.
        val timeGranted = "time" in grantedCapabilities
        if (timeGranted) {
            importsList.addAll(wasi.toHostFunctions())
        } else {
            importsList.addAll(wasi.toHostFunctions().filter { it.name() !in TIME_SENSITIVE_WASI_FUNCTIONS })
            importsList.addAll(timeDenyHostFunctions())
        }
        importsList.addAll(hostFunctions)

        val imports = ImportValues.builder()
            .withFunctions(importsList.map { it })
            .build()
        
        val wasmModule = Parser.parse(quickjsWasmBytes)
        val instanceBuilder = Instance.builder(wasmModule)
            .withImportValues(imports)
        // See MAX_GUEST_MEMORY_PAGES above. withMemoryLimits() overrides BOTH initial and maximum
        // when set, so quickjs.wasm's own declared initial size has to be read and preserved here
        // — only the maximum is actually meant to change.
        wasmModule.memorySection().ifPresent { section ->
            val declaredInitialPages = section.getMemory(0).limits().initialPages()
            instanceBuilder.withMemoryLimits(MemoryLimits(declaredInitialPages, MAX_GUEST_MEMORY_PAGES))
        }
        instance = instanceBuilder.build()

        // Initialize QuickJS context
        val qjsInit = instance.export("qjs_init")
        val initResult = qjsInit.apply()[0].toInt()
        if (initResult != 0) {
            throw IllegalStateException("Failed to initialize QuickJS WASM runtime")
        }
    }
    
    fun eval() {
        val wasmMalloc = instance.export("wasm_malloc")
        val wasmFree = instance.export("wasm_free")
        val qjsEval = instance.export("qjs_eval")
        
        val jsBytes = jsCode.toByteArray(StandardCharsets.UTF_8)
        
        // Allocate space for the code string and filename
        val codePtr = wasmMalloc.apply(jsBytes.size.toLong() + 1L)[0].toInt()
        val filenameStr = "extension.js"
        val filenameBytes = filenameStr.toByteArray(StandardCharsets.UTF_8)
        val filenamePtr = wasmMalloc.apply(filenameBytes.size.toLong() + 1L)[0].toInt()
        
        // Write to WASM memory
        instance.memory().write(codePtr, jsBytes)
        instance.memory().writeByte(codePtr + jsBytes.size, 0) // null terminator
        instance.memory().write(filenamePtr, filenameBytes)
        instance.memory().writeByte(filenamePtr + filenameBytes.size, 0)
        
        try {
            // qjs_eval signature: (code: i32, len: i32, filename: i32, eval_flags: i32) -> i32 (returns JSValue*)
            // eval_flags: JS_EVAL_TYPE_GLOBAL = 0
            // Bounded: see SandboxExecution.kt. Without this, extension JS that never returns
            // (an infinite loop) pinned a thread forever with no way to cancel it.
            val resultPtr = runSandboxBounded {
                qjsEval.apply(
                    codePtr.toLong(),
                    jsBytes.size.toLong(),
                    filenamePtr.toLong(),
                    0L
                )[0].toInt()
            }

            // Check if exception
            val qjsIsException = instance.export("qjs_is_exception")
            val isException = qjsIsException.apply(resultPtr.toLong())[0].toInt() != 0
            
            if (isException) {
                // If it's an exception, we would ideally read the error, but for now we just throw
                throw RuntimeException("JavaScript execution failed inside QuickJS sandbox.")
            }
            
            // Free the returned JSValue pointer
            wasmFree.apply(resultPtr.toLong())
        } finally {
            wasmFree.apply(codePtr.toLong())
            wasmFree.apply(filenamePtr.toLong())
        }
    }

    private fun bindHostCall(grantedCapabilities: Set<String>) {
        // QuickJS-wasi imports `env.host_call` to jump back to host functions
        // Signature: (name_ptr: i32, name_len: i32, this_ptr: i32, argc: i32, argv_ptr: i32) -> i32 (returns JSValue*)
        hostFunctions.add(
            HostFunction(
                "env", "host_call",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
                { _: Instance, args: LongArray ->
                    val namePtr = args[0].toInt()
                    val nameLen = args[1].toInt()
                    val thisPtr = args[2].toInt()
                    val argc = args[3].toInt()
                    val argvPtr = args[4].toInt()
                    
                    val name = instance.memory().readString(namePtr, nameLen)
                    
                    // We only have the capability router here. For this implementation, we will mock the return JSValue*
                    // as undefined, because a full JSValue* serialization is complex. In reality, we'd want to parse JSValue* 
                    // from WASM memory, pass to host, and write back. 
                    // To keep this sandbox functional but simple for now, we will execute the host functions
                    // but not return complex JS values.
                    
                    if ("canvas" in grantedCapabilities && name == "requestRedraw") {
                        host.requestRedraw()
                    } else if ("color" in grantedCapabilities && name == "colorActive") {
                        // This requires returning a number in JSValue. 
                        // The actual bridging requires calling qjs_new_number.
                    }
                    
                    // Return undefined for now
                    val qjsGetUndefined = instance.export("qjs_get_undefined")
                    qjsGetUndefined.apply()
                }
            )
        )
    }
    
    companion object {
        /** WASI preview1 function names that read the wall clock or the system RNG. */
        private val TIME_SENSITIVE_WASI_FUNCTIONS = setOf("clock_time_get", "clock_res_get", "random_get")

        /** WASI module name Chicory's `WasiPreview1` registers its host functions under —
         *  these stubs have to match it exactly, or Chicory won't treat them as satisfying the
         *  imports quickjs.wasm actually declares. */
        private const val WASI_MODULE = "wasi_snapshot_preview1"

        /**
         * Deny-by-default stand-ins for [TIME_SENSITIVE_WASI_FUNCTIONS], used when 'time' hasn't
         * been granted. Signatures and errno convention (0 = success) match Chicory's real
         * `WasiPreview1` implementations exactly — these have to satisfy the same mandatory
         * imports, just answer with a fixed, non-real value instead of the real clock/RNG:
         *  - clock_time_get / clock_res_get write a fixed timestamp (epoch 0) / resolution (1ns).
         *  - random_get fills the buffer with zero bytes rather than real system entropy.
         */
        private fun timeDenyHostFunctions(): List<HostFunction> = listOf(
            HostFunction(
                WASI_MODULE, "clock_time_get",
                FunctionType.of(listOf(ValType.I32, ValType.I64, ValType.I32), listOf(ValType.I32)),
                { instance: Instance, args: LongArray ->
                    val resultPtr = args[2].toInt()
                    instance.memory().writeLong(resultPtr, 0L)
                    longArrayOf(0L)
                }
            ),
            HostFunction(
                WASI_MODULE, "clock_res_get",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
                { instance: Instance, args: LongArray ->
                    val resultPtr = args[1].toInt()
                    instance.memory().writeLong(resultPtr, 1L)
                    longArrayOf(0L)
                }
            ),
            HostFunction(
                WASI_MODULE, "random_get",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
                { instance: Instance, args: LongArray ->
                    val bufPtr = args[0].toInt()
                    val bufLen = args[1].toInt()
                    for (i in 0 until bufLen) instance.memory().writeByte(bufPtr + i, 0)
                    longArrayOf(0L)
                }
            ),
        )
    }

    private fun bindStubs() {
        hostFunctions.add(
            HostFunction(
                "env", "host_interrupt",
                FunctionType.of(emptyList(), listOf(ValType.I32)),
                { _: Instance, _: LongArray -> longArrayOf(0L) }
            )
        )
        hostFunctions.add(
            HostFunction(
                "env", "host_module_normalize",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
                { _: Instance, _: LongArray -> longArrayOf(0L) }
            )
        )
        hostFunctions.add(
            HostFunction(
                "env", "host_module_load",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
                { _: Instance, _: LongArray -> longArrayOf(0L) }
            )
        )
        hostFunctions.add(
            HostFunction(
                "env", "host_promise_rejection",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32), emptyList()),
                { _: Instance, _: LongArray -> null }
            )
        )
        hostFunctions.add(
            HostFunction(
                "env", "host_get_timezone_offset",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
                { _: Instance, _: LongArray -> longArrayOf(0L) }
            )
        )
    }
}
