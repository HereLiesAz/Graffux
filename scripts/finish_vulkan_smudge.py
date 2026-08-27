from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def p(path): return ROOT / path

def text(path): return p(path).read_text()

def write(path, value): p(path).write_text(value)

def replace(path, old, new, count=1):
    s = text(path)
    found = s.count(old)
    if found != count:
        raise RuntimeError(f"{path}: expected {count} occurrences, found {found}: {old[:120]!r}")
    write(path, s.replace(old, new, count))

# ---- Native header -------------------------------------------------------------------------
h = "core/nativebridge/src/main/cpp/include/VulkanStampEngine.h"
replace(h, '''struct GpuDab {
    float x;
    float y;
    float radius;
    float alpha;
    float angleDeg;
    float colorR = 0.0f;
    float colorG = 0.0f;
    float colorB = 0.0f;
    float colorA = 0.0f;
    float flow = 0.0f;
    float resolved = 0.0f;
    float pad0 = 0.0f;
};
''', '''struct GpuDab {
    float x;
    float y;
    float radius;
    float alpha;
    float angleDeg;
    float colorR = 0.0f;
    float colorG = 0.0f;
    float colorB = 0.0f;
    float colorA = 0.0f;
    float flow = 0.0f;
    float resolved = 0.0f;
    float pad0 = 0.0f;
};
static_assert(sizeof(GpuDab) == 48, "GpuDab must match the shader's 3xvec4 std430 record");

struct ColorSmudgeDab {
    float x;
    float y;
    float smudgeRate;
    float colorRate;
    float opacity;
    float smudgeRadius;
};

struct ColorSmudgeBenchmarkInfo {
    uint32_t vendorId = 0;
    uint32_t deviceId = 0;
    uint32_t selectedTileSize = 0;
    uint64_t nanos8 = 0;
    uint64_t nanos16 = 0;
};
''')
replace(h, '''    bool stampDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb, float hardness);

    // Blocks until all dispatched work completes, then reads the layer image back into
''', '''    bool stampDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb, float hardness);

    // Ordered read/modify/write Color Smudge pass on the same persistent layer image. `mode` is
    // 0=Smear, 1=Dulling. The first dab seeds Smear's carrier; later dabs are applied sequentially.
    bool colorSmudge(const std::vector<ColorSmudgeDab>& dabs, int mode, float radiusPx,
                     float feathering, bool smearAlpha, uint32_t paintColorArgb);
    ColorSmudgeBenchmarkInfo colorSmudgeBenchmarkInfo() const { return smudgeBenchmark_; }

    // Blocks until all dispatched work completes, then reads the layer image back into
''')
replace(h, '''    bool createDescriptorAndPipeline();
    bool createDabBuffer(size_t dabCount);
    bool allocateCommandBuffer();
''', '''    bool createDescriptorAndPipeline();
    bool createDabBuffer(size_t dabCount);
    bool allocateCommandBuffer();

    bool ensureColorSmudgePipelines();
    bool ensureColorSmudgeCarrier(size_t pixelCount);
    bool benchmarkColorSmudge(float radiusPx);
    bool runColorSmudgePlan(const std::vector<ColorSmudgeDab>& dabs, int mode, float radiusPx,
                            float feathering, bool smearAlpha, uint32_t paintColorArgb,
                            VkPipeline pipeline, uint32_t tileSize);
    void destroyColorSmudgeResources();
''')
replace(h, '''    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
''', '''    // Lazily-created Color Smudge resources. They live on the same persistent layer image as the
    // stamp compositor and survive Kotlin wrapper release while the native handle sits in the pool.
    VkBuffer smudgeCarrier_ = VK_NULL_HANDLE;
    VkDeviceMemory smudgeCarrierMemory_ = VK_NULL_HANDLE;
    size_t smudgeCarrierCapacity_ = 0;
    VkDescriptorSetLayout smudgeDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool smudgeDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet smudgeDescriptorSet_ = VK_NULL_HANDLE;
    VkPipelineLayout smudgePipelineLayout_ = VK_NULL_HANDLE;
    VkShaderModule smudgeShader8_ = VK_NULL_HANDLE;
    VkShaderModule smudgeShader16_ = VK_NULL_HANDLE;
    VkPipeline smudgePipeline8_ = VK_NULL_HANDLE;
    VkPipeline smudgePipeline16_ = VK_NULL_HANDLE;
    ColorSmudgeBenchmarkInfo smudgeBenchmark_{};

    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
''')

# ---- Existing Vulkan implementation: generated include + Smudge resource teardown ----------
cpp = "core/nativebridge/src/main/cpp/VulkanStampEngine.cpp"
replace(cpp, '#include "shaders/StampSpv.h"', '#include "StampSpv.h"')
replace(cpp, '''void VulkanStampEngine::destroy() {
    if (device_ != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device_);
    }

    if (fence_ != VK_NULL_HANDLE)''', '''void VulkanStampEngine::destroy() {
    if (device_ != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device_);
        destroyColorSmudgeResources();
    }

    if (fence_ != VK_NULL_HANDLE)''')

# ---- Kotlin bridge --------------------------------------------------------------------------
k = "core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/VulkanStampEngine.kt"
replace(k, '''    fun readback(bitmap: Bitmap): Boolean {
''', '''    /** Persistent Color Smudge pass. The image must already be seeded with [upload]. */
    fun colorSmudge(
        dabs: List<ColorSmudgeDab>,
        mode: Int,
        radiusPx: Float,
        feathering: Float,
        smearAlpha: Boolean,
        paintColorArgb: Int,
    ): Boolean {
        if (!isInitialized || dabs.size < 2) return false
        val flat = FloatArray(dabs.size * 6)
        for (i in dabs.indices) {
            val d = dabs[i]
            val base = i * 6
            flat[base] = d.x
            flat[base + 1] = d.y
            flat[base + 2] = d.smudgeRate
            flat[base + 3] = d.colorRate
            flat[base + 4] = d.opacity
            flat[base + 5] = d.smudgeRadius
        }
        val ok = nativeColorSmudge(
            nativeHandle, flat, mode, radiusPx, feathering, smearAlpha, paintColorArgb,
        )
        if (!ok) healthy = false
        return ok
    }

    /** Benchmark result chosen on this Vulkan physical device after the first Smudge call. */
    fun colorSmudgeBenchmarkInfo(): ColorSmudgeBenchmarkInfo? {
        if (!isInitialized) return null
        val values = nativeColorSmudgeBenchmarkInfo(nativeHandle) ?: return null
        if (values.size < 5 || values[2] == 0L) return null
        return ColorSmudgeBenchmarkInfo(
            vendorId = values[0].toInt(),
            deviceId = values[1].toInt(),
            selectedTileSize = values[2].toInt(),
            nanos8 = values[3],
            nanos16 = values[4],
        )
    }

    fun readback(bitmap: Bitmap): Boolean {
''')
replace(k, '''    private external fun nativeStampResolvedDabs(handle: Long, dabData: FloatArray, hardness: Float): Boolean
    private external fun nativeReadback(handle: Long, outBitmap: Bitmap): Boolean
''', '''    private external fun nativeStampResolvedDabs(handle: Long, dabData: FloatArray, hardness: Float): Boolean
    private external fun nativeColorSmudge(
        handle: Long,
        dabData: FloatArray,
        mode: Int,
        radiusPx: Float,
        feathering: Float,
        smearAlpha: Boolean,
        paintColorArgb: Int,
    ): Boolean
    private external fun nativeColorSmudgeBenchmarkInfo(handle: Long): LongArray?
    private external fun nativeReadback(handle: Long, outBitmap: Bitmap): Boolean
''')
replace(k, '''data class BrushDab(val x: Float, val y: Float, val radius: Float, val alpha: Float, val angleDeg: Float)

''', '''data class BrushDab(val x: Float, val y: Float, val radius: Float, val alpha: Float, val angleDeg: Float)

data class ColorSmudgeDab(
    val x: Float,
    val y: Float,
    val smudgeRate: Float,
    val colorRate: Float,
    val opacity: Float,
    val smudgeRadius: Float,
)

data class ColorSmudgeBenchmarkInfo(
    val vendorId: Int,
    val deviceId: Int,
    val selectedTileSize: Int,
    val nanos8: Long,
    val nanos16: Long,
)

''')

# ---- CPU engine exposes a resolved plan consumed by the GPU path ----------------------------
e = "feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/util/ColorSmudgeEngine.kt"
replace(e, '''    private data class DabPoint(val position: Offset, val sample: BrushSample?)

    private data class Resolved(
''', '''    private data class DabPoint(val position: Offset, val sample: BrushSample?)

    /** Renderer-neutral, per-dab Color Smudge instruction. */
    data class ResolvedDab(
        val x: Float,
        val y: Float,
        val smudgeRate: Float,
        val colorRate: Float,
        val opacity: Float,
        val smudgeRadius: Float,
    )

    /** One ordered carrier lifetime. Symmetry twins are separate plans so Smear reseeds each one. */
    data class ResolvedPlan(val dabs: List<ResolvedDab>)

    private data class Resolved(
''')
insert = '''
    /**
     * Resolves the exact resampling and sensor curves the CPU implementation uses into renderer-
     * neutral dabs. Vulkan consumes this plan rather than reimplementing input/sensor semantics.
     */
    fun resolvePlans(
        stroke: List<Offset>,
        width: Int,
        height: Int,
        settings: Settings,
        samples: List<BrushSample> = emptyList(),
        strokeSeed: Long = 0L,
    ): List<ResolvedPlan> {
        if (stroke.isEmpty() || width <= 0 || height <= 0) return emptyList()
        fun one(points: List<Offset>, telemetry: List<BrushSample>): ResolvedPlan {
            val radius = settings.radiusPx.coerceAtLeast(1f)
            val path = resampleWithTelemetry(points, telemetry, (radius / 2f).coerceAtLeast(1f))
            val startTime = telemetry.firstOrNull()?.uptimeMillis ?: 0L
            return ResolvedPlan(path.mapIndexed { index, dab ->
                val r = resolve(settings, dab.sample, startTime, strokeSeed, index)
                ResolvedDab(
                    x = dab.position.x,
                    y = dab.position.y,
                    smudgeRate = r.smudgeRate,
                    colorRate = r.colorRate,
                    opacity = r.opacity,
                    smudgeRadius = r.smudgeRadius,
                )
            })
        }

        val plans = ArrayList<ResolvedPlan>()
        plans += one(stroke, samples)
        for (transform in symmetryTransforms(settings.symmetryMode, width.toFloat(), height.toFloat())) {
            val transformedSamples = if (samples.size == stroke.size) {
                samples.map { sample ->
                    val pos = transform(Offset(sample.x, sample.y))
                    sample.copy(x = pos.x, y = pos.y, predicted = false)
                }
            } else emptyList()
            plans += one(stroke.map(transform), transformedSamples)
        }
        return plans
    }

'''
anchor = '''    fun apply(
        pixels: IntArray,
'''
s = text(e)
if s.count(anchor) != 1: raise RuntimeError("ColorSmudgeEngine apply anchor")
write(e, s.replace(anchor, insert + anchor, 1))

# ---- DrawingEngine: GPU-first, CPU reference fallback ---------------------------------------
d = "feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/DrawingEngine.kt"
replace(d, '''import com.hereliesaz.graffitixr.nativebridge.SlamManager
''', '''import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.nativebridge.ColorSmudgeDab
import com.hereliesaz.graffitixr.nativebridge.VulkanStampEngine
''')
old = '''            val pixels = IntArray(width * height)
            target.getPixels(pixels, 0, width, 0, 0, width, height)
            val baseSettings = stroke.colorSmudgeSettings ?: ColorSmudgeEngine.Settings(
                mode = ColorSmudgeEngine.Mode.SMEAR,
                smudgeRate = 0.35f + stroke.intensity.coerceIn(0f, 1f) * 0.6f,
                colorRate = 0f,
                opacity = 1f,
                smearAlpha = true,
            )
            val mappedSamples = if (stroke.brushSamples.size == mapped.size) {
                stroke.brushSamples.mapIndexed { index, sample ->
                    val point = mapped[index]
                    sample.copy(x = point.x, y = point.y, predicted = false)
                }
            } else emptyList()
            ColorSmudgeEngine.apply(
                pixels,
                width,
                height,
                mapped,
                baseSettings.copy(
                    radiusPx = (stroke.brushSize * brushScale / 2f).coerceAtLeast(1f),
                    feathering = stroke.feathering,
                    wrapAround = false,
                    paintColor = stroke.brushColor,
                    symmetryMode = stroke.symmetryMode,
                ),
                samples = mappedSamples,
                strokeSeed = stroke.seed,
            )
            target.setPixels(pixels, 0, width, 0, 0, width, height)
            return SelectionMask.confine(bitmap, target, clipPath, featherRadius)
'''
new = '''            val baseSettings = stroke.colorSmudgeSettings ?: ColorSmudgeEngine.Settings(
                mode = ColorSmudgeEngine.Mode.SMEAR,
                smudgeRate = 0.35f + stroke.intensity.coerceIn(0f, 1f) * 0.6f,
                colorRate = 0f,
                opacity = 1f,
                smearAlpha = true,
            )
            val mappedSamples = if (stroke.brushSamples.size == mapped.size) {
                stroke.brushSamples.mapIndexed { index, sample ->
                    val point = mapped[index]
                    sample.copy(x = point.x, y = point.y, predicted = false)
                }
            } else emptyList()
            val settings = baseSettings.copy(
                radiusPx = (stroke.brushSize * brushScale / 2f).coerceAtLeast(1f),
                feathering = stroke.feathering,
                wrapAround = false,
                paintColor = stroke.brushColor,
                symmetryMode = stroke.symmetryMode,
            )
            val plans = ColorSmudgeEngine.resolvePlans(
                mapped, width, height, settings, mappedSamples, stroke.seed,
            )

            // Correctness-first Vulkan path: one upload, all ordered read/modify/write plans stay on
            // the persistent layer image, one readback. If Vulkan is unavailable or any stage fails,
            // discard the possibly-partial target and recompute from the pristine CPU source below.
            val gpuPainted = runCatching {
                val engine = VulkanStampEngine()
                try {
                    if (!engine.init(width, height) || !engine.upload(target)) return@runCatching false
                    val mode = if (settings.mode == ColorSmudgeEngine.Mode.SMEAR) 0 else 1
                    for (plan in plans) {
                        if (plan.dabs.size < 2) continue
                        val nativeDabs = plan.dabs.map { dab ->
                            ColorSmudgeDab(
                                dab.x, dab.y, dab.smudgeRate, dab.colorRate,
                                dab.opacity, dab.smudgeRadius,
                            )
                        }
                        if (!engine.colorSmudge(
                                nativeDabs, mode, settings.radiusPx, settings.feathering,
                                settings.smearAlpha, settings.paintColor,
                            )) return@runCatching false
                    }
                    engine.readback(target)
                } finally {
                    engine.destroy()
                }
            }.getOrDefault(false)

            if (!gpuPainted) {
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                ColorSmudgeEngine.apply(
                    pixels, width, height, mapped, settings,
                    samples = mappedSamples, strokeSeed = stroke.seed,
                )
                target.setPixels(pixels, 0, width, 0, 0, width, height)
            }
            return SelectionMask.confine(bitmap, target, clipPath, featherRadius)
'''
replace(d, old, new)

# ---- Instrumentation dependency --------------------------------------------------------------
g = "feature/editor/build.gradle.kts"
replace(g, '''    debugImplementation(libs.compose.ui.test.manifest)
''', '''    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
''')

print("persistent Vulkan Color Smudge integration patched")
