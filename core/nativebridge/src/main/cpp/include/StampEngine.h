// FILE: core/nativebridge/src/main/cpp/include/StampEngine.h
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

struct ANativeWindow;

namespace graffux {

// One dab. The first five fields are the historical ABI. The resolved paint fields widen the
// buffer to 16 floats / 64 bytes; old aggregate initializers that provide only five values leave
// `resolved` at zero, so the shader falls back to the stroke-level colour exactly as before. New
// callers set resolved=1 and provide per-dab RGBA + flow. Keep this binary-identical to the wgpu
// engine's Dab record (core/wgpu-engine stamp.wgsl AND stamp_masked.wgsl share this layout).
// The fourth vec4 carries material state; it is ignored unless a dispatch explicitly enables
// substrate sampling, preserving every historical/legacy dab path.
//
// `tipRatio` is read only by the masked shader (height/width of the tip -- see AzphaltBrush.
// tipRatio). The round shader instead repurposes this same trailing float, for a *resolved* dab only, as
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
// the wgpu engine's stamp_masked.wgsl SecondaryDab struct, which this must stay binary-identical to.
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

// The Color Smudge workgroup benchmark: adapter vendor/device ids, chosen tile, and both timings.
struct ColorSmudgeBenchmarkInfo {
    uint32_t vendorId = 0;
    uint32_t deviceId = 0;
    uint32_t selectedTileSize = 0;
    uint64_t nanos8 = 0;
    uint64_t nanos16 = 0;
};

/**
 * The GPU stamp engine contract the JNI bridge sees. One implementation: WgpuStampEngine, the
 * adapter over the Rust wgpu engine (core/wgpu-engine). The Vulkan 1.1 and OpenGL ES 3.1 compute
 * implementations were retired (docs/Native Rendering Engine Design.md, "Retired backends"); the
 * interface stays so tools/stamp-engine-diff and the JNI bridge are engine-agnostic. Not
 * thread-safe: the Kotlin wrapper serializes every call on one instance.
 */
class StampEngine {
public:
    virtual ~StampEngine() = default;

    // false = no usable GPU/API on this device; the caller falls back to the CPU path.
    virtual bool init(int width, int height) = 0;
    // Transparent layer, new stroke; used when a pooled engine is reused.
    virtual bool clear() = 0;
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

    // ---- Optional: resident layers and rectangle readback ----------------------------------
    // Defaults (for a wgpu library that predates them): no resident layers (every bind misses and
    // every upload fails, so callers take the plain upload() path) and readbackRect() is readback()
    // reporting the whole layer. See core/wgpu-engine/include/graffux_wgpu.h for the contract.

    // Copies the rectangle dirtied since the last readback and reports it as {x, y, w, h}.
    virtual bool readbackRect(uint8_t* outRgba8, size_t outCapacityBytes, int32_t rect[4]) {
        const bool ok = readback(outRgba8, outCapacityBytes);
        if (ok) {
            rect[0] = 0;
            rect[1] = 0;
            rect[2] = width();
            rect[3] = height();
        }
        return ok;
    }
    virtual bool supportsResidentLayers() const { return false; }
    // Bind session (> 0) if layer `key` is resident at `generation`, else 0.
    virtual uint64_t bindLayer(uint64_t /*key*/, uint64_t /*generation*/) { return 0; }
    // Uploads a full layer image as resident layer `key`; bind session or 0.
    virtual uint64_t uploadLayer(uint64_t /*key*/, uint64_t /*generation*/,
                                 const uint8_t* /*rgba*/, size_t /*size*/) {
        return 0;
    }
    virtual bool commitLayer(uint64_t /*key*/, uint64_t /*session*/, uint64_t /*generation*/) {
        return false;
    }
    virtual bool refreshLayer(uint64_t /*key*/, uint64_t /*session*/, uint64_t /*generation*/,
                              const uint8_t* /*rgba*/, size_t /*size*/, int /*x*/, int /*y*/,
                              int /*w*/, int /*h*/) {
        return false;
    }
    virtual bool invalidateLayer(uint64_t /*key*/) { return false; }
    virtual void invalidateAllLayers() {}
    virtual void setResidentBudget(uint64_t /*bytes*/) {}

    // ---- Optional: multipass rendering (experimental) -----------------------------------------
    // Draft now, full quality in the time left over; see graffux_wgpu.h. Defaults: unsupported, and
    // every other call behaves exactly as without it.
    virtual bool setMultipass(const float* /*params*/, size_t /*count*/) { return false; }
    // 1 = more to do, 0 = idle / unsupported, -1 = failure.
    virtual int refine(float /*budgetMs*/) { return 0; }
    virtual bool flushMultipass() { return true; }
    // Diagnostics doubles written (0 = unsupported).
    virtual size_t multipassStats(double* /*out*/, size_t /*count*/) { return 0; }
    // ---- Optional: direct display (graffux_wgpu.h gfx_wgpu_direct_*) -------------------------
    // The engine presents the live stroke into a surface made from `window` (the overlay
    // SurfaceView's), straight from its GPU buffers. Defaults: unsupported, callers keep readback
    // display.
    // Capability bits as GFX_WGPU_DIRECT_* (1 window, 2 adapter, 4 attached, 8 stroke, 16 surface).
    virtual uint32_t directCapabilities() const { return 0; }
    // Holds its own reference to `window` until directDetach()/destroy().
    virtual bool directAttach(ANativeWindow* /*window*/, int /*width*/, int /*height*/) { return false; }
    virtual void directDetach() {}
    virtual bool directBeginStroke() { return false; }
    // `matrix`: 6 floats surface px -> layer px, or nullptr to reuse the previous one.
    virtual bool directPresent(const float* /*matrix*/, bool /*newBatch*/) { return false; }
    virtual bool directEndStroke() { return false; }
    // ---- Optional: telemetry (GpuTelemetry.kt) -----------------------------------------------
    // key=value lines: engine, backend, renderer, vendor_id, device_id, driver, driver_info, api,
    // timestamps, timestamps_copy, shader_f16, stamp_tile. Empty = unknown.
    virtual std::string gpuInfo() const { return {}; }
    // Drains GPU-timed passes as {kind, nanoseconds} pairs into `out` (PassKind below); returns
    // the pairs written. 0 = no GPU timestamps; the Kotlin side then reports CPU wall time.
    virtual size_t takePassTimings(uint64_t* /*out*/, size_t /*capacityPairs*/) { return 0; }

    virtual bool isInitialized() const = 0;
    virtual int width() const = 0;
    virtual int height() const = 0;
};

// Pass kinds for takePassTimings, shared with wgpu's timing.rs and Kotlin GpuPassKind.
enum class PassKind : uint32_t { Stamp = 0, Readback = 1, Composite = 2, Smudge = 3, Multipass = 4 };

// Process-wide per-device tuning (GpuTuning.kt via JNI), read by each engine at init().
// stampTile: 8 or 16 (the WGSL workgroup override constant). timestamps: time passes on the GPU.
struct StampTuning {
    std::atomic<int> stampTile{16};
    std::atomic<bool> timestamps{true};
};
StampTuning& stampTuning();

// Allocates an uninitialized engine (the wgpu adapter).
StampEngine* createStampEngine();

}  // namespace graffux
