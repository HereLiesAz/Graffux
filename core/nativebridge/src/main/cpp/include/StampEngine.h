// FILE: core/nativebridge/src/main/cpp/include/StampEngine.h
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

struct AHardwareBuffer;

namespace graffux {

// One dab. The first five fields are the historical ABI. The resolved paint fields widen the
// buffer to 16 floats / 64 bytes; old aggregate initializers that provide only five values leave
// `resolved` at zero, so the shader falls back to the stroke-level colour exactly as before. New
// callers set resolved=1 and provide per-dab RGBA + flow. Keep this binary-identical to
// shaders/stamp.comp AND shaders/stamp_masked.comp (both share this exact struct layout).
// The fourth vec4 carries material state; it is ignored unless a dispatch explicitly enables
// substrate sampling, preserving every historical/legacy dab path.
//
// `tipRatio` is read only by stamp_masked.comp (height/width of the tip -- see AzphaltBrush.
// tipRatio). stamp.comp instead repurposes this same trailing float, for a *resolved* dab only, as
// that dab's own hardness override (see AzphaltBrush.hardness / a HARDNESS BrushSensorBinding) --
// the two shaders never share a dab list, so the two interpretations never conflict.
struct GpuDab {
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
    float tipRatio = 1.0f;
    // Material/deposition state. Defaults preserve historical output.
    float contactDepth = 1.0f;
    float reservoirLoad = 1.0f;
    float depositionRate = 1.0f;
    float substrateResponse = 0.0f;
};
static_assert(sizeof(GpuDab) == 64, "GpuDab must match the shader's 4xvec4 std430 record");

struct SubstrateStampParams {
    bool enabled = false;
    bool hasPaintHeight = false;
    float baseHeight = 0.0f;
    float heightScale = 0.0f;
    float textureScale = 1.0f;
    float textureOffsetX = 0.0f;
    float textureOffsetY = 0.0f;
};

// Masked/dual-brush secondary tip, one entry per primary dab (same index, parallel arrays) -- see
// shaders/stamp_masked.comp's SecondaryDab struct, which this must stay binary-identical to.
// `keepInside` is pre-resolved on the host from MaskedBrushBlendMode + invert (>0.5 = DST_IN).
struct GpuSecondaryDab {
    float x;
    float y;
    float radius;
    float tipRatio;
    float alpha;
    float angleDeg;
    float flowMultiplier;
    float keepInside;
};
static_assert(sizeof(GpuSecondaryDab) == 32, "GpuSecondaryDab must match the shader's 2xvec4 std430 record");

struct ColorSmudgeDab {
    float x;
    float y;
    float smudgeRate;
    float colorRate;
    float opacity;
    float smudgeRadius;
    // Sensor-only multiplier before finite reservoir load is applied.
    float colorRateMultiplier = 1.0f;
    // Arc-length increment from the preceding resolved dab.
    float distanceDeltaPx = 0.0f;
    // Stroke-level reservoir configuration repeated on each dab by the JNI bridge, so the ordered
    // native pass evolves exactly the same state as BrushReservoirModel.
    float baseColorRate = 0.0f;
    float chargeDecayRate = 0.0f;
    float pickupRate = 0.0f;
};

// GL exposes no numeric vendor/device ids, so vendorId/deviceId are 0 and the choice is keyed on
// the GL_RENDERER string instead; the timings and tile choice mean what they always did.
struct ColorSmudgeBenchmarkInfo {
    uint32_t vendorId = 0;
    uint32_t deviceId = 0;
    uint32_t selectedTileSize = 0;
    uint64_t nanos8 = 0;
    uint64_t nanos16 = 0;
};

/**
 * The GPU stamp engine contract, implemented by two interchangeable backends: VulkanStampEngine
 * (Vulkan 1.1 compute) and GlesStampEngine (OpenGL ES 3.1 compute). Same shaders in spirit, same
 * dab/struct layouts, same pixels (docs/Native Rendering Engine Design.md §2); which one runs is a
 * Settings choice so they can be compared on a real device. The JNI bridge only ever sees this
 * interface. Not thread-safe: the Kotlin wrapper serializes every call on one instance. See the
 * implementations for each call's full contract.
 */
class StampEngine {
public:
    virtual ~StampEngine() = default;

    // false = no usable GPU/API on this device; the caller falls back to the CPU path.
    virtual bool init(int width, int height) = 0;
    // init() plus an AHardwareBuffer the layer is displayed from with no CPU readback.
    virtual bool initWithHardwareBuffer(int width, int height) = 0;
    // Transparent layer, new stroke; used when a pooled engine is reused.
    virtual bool clear() = 0;
    virtual struct AHardwareBuffer* hardwareBuffer() const = 0;
    virtual bool upload(const uint8_t* inRgba8, size_t inSizeBytes) = 0;
    virtual bool uploadSubstrateHeight(const uint8_t* heightR8, int width, int height) = 0;
    virtual bool uploadPaintHeight(const float* heightMap, int width, int height) = 0;
    virtual bool stampDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb, float hardness,
                           bool buildUp = false, SubstrateStampParams substrate = {},
                           bool strokeMax = false) = 0;
    virtual bool stampMaskedDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb, float hardness,
                                 const uint8_t* maskAlpha8, int maskWidth, int maskHeight,
                                 const uint8_t* grainAlpha8 = nullptr, int grainWidth = 0,
                                 int grainHeight = 0, bool grainCanvasLocked = false,
                                 float grainScale = 1.0f, float grainPhaseX = 0.0f,
                                 float grainPhaseY = 0.0f,
                                 const std::vector<GpuSecondaryDab>& secondaryDabs = {},
                                 const uint8_t* secondaryMaskAlpha8 = nullptr,
                                 int secondaryMaskWidth = 0, int secondaryMaskHeight = 0,
                                 SubstrateStampParams substrate = {}) = 0;
    virtual bool colorSmudge(const std::vector<ColorSmudgeDab>& dabs, int mode, float radiusPx,
                             float feathering, bool smearAlpha, uint32_t paintColorArgb,
                             float dilution = 0.0f, const uint8_t* sampleSourceRgba8 = nullptr,
                             int sampleSourceWidth = 0, int sampleSourceHeight = 0) = 0;
    virtual ColorSmudgeBenchmarkInfo colorSmudgeBenchmarkInfo() const = 0;
    virtual bool readback(uint8_t* outRgba8, size_t outCapacityBytes) = 0;
    virtual void destroy() = 0;
    virtual bool isInitialized() const = 0;
    virtual int width() const = 0;
    virtual int height() const = 0;
};

// Backend ids shared with the Kotlin wrapper (GpuStampEngine.Backend.nativeId).
// Wgpu = the Rust engine in core/wgpu-engine behind the WgpuStampEngine adapter.
enum class StampBackend : int { Vulkan = 0, Gles = 1, Wgpu = 2 };

// Allocates an uninitialized engine for `backend` (Vulkan for any unknown id).
StampEngine* createStampEngine(int backend);

}  // namespace graffux
