package com.hereliesaz.graffitixr.feature.editor.gpu

/**
 * What the GPU stamp engine says about the device, parsed from the `key=value` lines of
 * `StampEngine::gpuInfo()` (wgpu's `AdapterInfo`). Fields the engine cannot report are empty or 0:
 * wgpu does not expose the Vulkan API version. The `vulkan`/`gles` engine values are from the
 * retired C++ engines; stored telemetry and tier records may still carry them.
 */
data class GpuInfo(
    /** Which engine produced this: `vulkan`, `gles` or `wgpu`. */
    val engine: String = "",
    /** The graphics API underneath (`Vulkan`, `Gl`, ...). */
    val backend: String = "",
    val renderer: String = "",
    /** PCI-style vendor id (0x5143 Qualcomm, 0x13B5 Arm, 0x1010 Imagination, 0x144D Samsung). */
    val vendorId: Int = 0,
    val deviceId: Int = 0,
    /** GL_VENDOR string (GLES only). */
    val vendor: String = "",
    /** Vulkan: raw `driverVersion` as hex. wgpu: the driver name. GLES: `GL_VERSION`. */
    val driver: String = "",
    val driverInfo: String = "",
    /** Vulkan API version `major.minor.patch`, empty when the backend cannot say. */
    val apiVersion: String = "",
    /** Passes are timed with GPU timestamp queries. */
    val timestamps: Boolean = false,
    /** Copies (readback) are timed too (wgpu needs TIMESTAMP_QUERY_INSIDE_ENCODERS). */
    val timestampsCopy: Boolean = false,
    /** fp16 arithmetic is available (wgpu SHADER_F16 / VK_KHR_shader_float16_int8). Reported only. */
    val shaderF16: Boolean = false,
    /** Stamp workgroup edge the engine actually built (8 or 16); 0 = unknown / not applicable. */
    val stampTile: Int = 0,
) {
    /** Stable identity for persisting a calibration: GPU and driver, not the engine that asked. */
    val identityKey: String get() = "$renderer|$vendorId|$deviceId|$driver|$driverInfo"

    val isKnown: Boolean get() = renderer.isNotEmpty()

    /** Vendor name for reports, from [vendorId] or the GL vendor string. */
    val vendorLabel: String
        get() = when (vendorId) {
            VENDOR_QUALCOMM -> "Qualcomm"
            VENDOR_ARM -> "Arm"
            VENDOR_IMAGINATION -> "Imagination"
            VENDOR_SAMSUNG -> "Samsung"
            VENDOR_AMD -> "AMD"
            0 -> vendor.ifEmpty { "?" }
            else -> "0x%04x".format(vendorId)
        }

    companion object {
        const val VENDOR_QUALCOMM = 0x5143
        const val VENDOR_ARM = 0x13B5
        const val VENDOR_IMAGINATION = 0x1010
        const val VENDOR_SAMSUNG = 0x144D
        const val VENDOR_AMD = 0x1002

        /** Parses `StampEngine::gpuInfo()`; unknown keys are ignored, missing ones default. */
        fun parse(text: String?): GpuInfo {
            if (text.isNullOrBlank()) return GpuInfo()
            val map = text.lineSequence()
                .mapNotNull { line ->
                    val i = line.indexOf('=')
                    if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
                }
                .toMap()
            return GpuInfo(
                engine = map["engine"].orEmpty(),
                backend = map["backend"].orEmpty(),
                renderer = map["renderer"].orEmpty(),
                vendorId = map["vendor_id"]?.toIntOrNull() ?: 0,
                deviceId = map["device_id"]?.toIntOrNull() ?: 0,
                vendor = map["vendor"].orEmpty(),
                driver = map["driver"].orEmpty(),
                driverInfo = map["driver_info"].orEmpty(),
                apiVersion = map["api"].orEmpty(),
                timestamps = map["timestamps"] == "1",
                timestampsCopy = map["timestamps_copy"] == "1",
                shaderF16 = map["shader_f16"] == "1",
                stampTile = map["stamp_tile"]?.toIntOrNull() ?: 0,
            )
        }
    }
}
