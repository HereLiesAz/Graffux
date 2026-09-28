package com.hereliesaz.graffitixr.feature.editor.gpu

/**
 * GPU architecture families the brush engine may tune for. Only [WorkgroupPolicy] and
 * [DriverWorkarounds] branch on this, and only where a choice is measured or documented.
 */
enum class GpuFamily(val label: String) {
    /** Qualcomm Adreno (Snapdragon). */
    ADRENO("adreno"),

    /** Arm Mali and Immortalis (MediaTek Dimensity, Exynos before 2200, Tensor G1-G4). */
    MALI("mali"),

    /** Samsung Xclipse, AMD RDNA-derived (Exynos 2200 and later). */
    XCLIPSE("xclipse"),

    /** Imagination PowerVR (some MediaTek/Unisoc parts, Tensor G5). */
    POWERVR("powervr"),

    UNKNOWN("unknown"),
}

/** [family] plus whether the SoC is a Google Tensor, which ships a Mali or a PowerVR GPU. */
data class DetectedGpu(val family: GpuFamily, val tensor: Boolean) {
    val label: String get() = if (tensor) "${family.label} (tensor)" else family.label
}

/**
 * Detects the [GpuFamily] from what the engine reports, falling back to the Vulkan vendor id.
 *
 * Tensor is not a GPU architecture of its own: Tensor G1 to G4 use Arm Mali (G78, G710, G715) and
 * Tensor G5 uses an Imagination PowerVR D-series, so the renderer string decides the family and the
 * SoC only adds the [DetectedGpu.tensor] tag. The SoC comes from `Build.SOC_MANUFACTURER` /
 * `Build.SOC_MODEL` (API 31+), which Pixels report as "Google" / "Tensor ...".
 */
object GpuFamilyDetector {
    fun detect(info: GpuInfo, socManufacturer: String = "", socModel: String = ""): DetectedGpu {
        val tensor = socModel.contains("tensor", ignoreCase = true) ||
            (socManufacturer.equals("google", ignoreCase = true) && socModel.isNotBlank())
        return DetectedGpu(family(info.renderer, info.vendorId), tensor)
    }

    fun family(renderer: String, vendorId: Int = 0): GpuFamily {
        val r = renderer.lowercase()
        return when {
            "adreno" in r -> GpuFamily.ADRENO
            "mali" in r || "immortalis" in r -> GpuFamily.MALI
            "xclipse" in r -> GpuFamily.XCLIPSE
            "powervr" in r -> GpuFamily.POWERVR
            vendorId == GpuInfo.VENDOR_QUALCOMM -> GpuFamily.ADRENO
            vendorId == GpuInfo.VENDOR_ARM -> GpuFamily.MALI
            vendorId == GpuInfo.VENDOR_SAMSUNG -> GpuFamily.XCLIPSE
            vendorId == GpuInfo.VENDOR_IMAGINATION -> GpuFamily.POWERVR
            else -> GpuFamily.UNKNOWN
        }
    }
}

/**
 * Stamp workgroup edge per family: 16 (16x16 = 256 invocations, the engines' default) or 8
 * (8x8 = 64). Passed to Vulkan as the choice between its two precompiled SPIR-V variants
 * (`stamp.comp` / `stamp8`) and to wgpu as the WGSL `STAMP_TILE` override constant; GLES has fixed
 * local sizes and ignores it. The size never changes pixels (each invocation is one pixel; the
 * wgpu test `stamp_tile_8_matches_16` checks byte equality), only scheduling.
 *
 * Every entry below says where it comes from. None of them is measured on a device here.
 */
object WorkgroupPolicy {
    const val DEFAULT_TILE = 16
    const val SMALL_TILE = 8

    fun stampTile(family: GpuFamily): Int = when (family) {
        // Arm Mali GPU Best Practices Developer Guide, "Workgroup sizes": use 64 invocations as the
        // baseline workgroup size and only go larger when profiling shows a benefit. 8x8 = 64.
        GpuFamily.MALI -> SMALL_TILE
        // Imagination's PowerVR performance recommendations ask for a multiple of 32 invocations;
        // 256 is one, so the default stands. No measurement says otherwise.
        GpuFamily.POWERVR -> DEFAULT_TILE
        // RDNA runs wave32/wave64; 256 is a multiple of both. Default kept, unmeasured.
        GpuFamily.XCLIPSE -> DEFAULT_TILE
        // Adreno: no public guidance that contradicts 256; default kept, unmeasured.
        GpuFamily.ADRENO -> DEFAULT_TILE
        GpuFamily.UNKNOWN -> DEFAULT_TILE
    }
}
