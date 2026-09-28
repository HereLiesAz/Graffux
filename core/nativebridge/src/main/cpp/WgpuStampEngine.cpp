// FILE: core/nativebridge/src/main/cpp/WgpuStampEngine.cpp
#include "include/WgpuStampEngine.h"

#include <dlfcn.h>

#include <cstdio>
#include <cstdlib>
#include <mutex>

#ifdef __ANDROID__
#include <android/log.h>
#define WGPU_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "WgpuStampEngine", __VA_ARGS__)
#else
#define WGPU_LOGW(...) (std::fprintf(stderr, "WgpuStampEngine: " __VA_ARGS__), std::fputc('\n', stderr))
#endif

// The C ABI (core/wgpu-engine/include/graffux_wgpu.h), resolved at runtime -- see the class doc.
struct GfxWgpuSubstrate {
    int32_t enabled;
    int32_t hasPaintHeight;
    float baseHeight;
    float heightScale;
    float textureScale;
    float textureOffsetX;
    float textureOffsetY;
};

namespace graffux {
namespace {

struct Api {
    GfxWgpuEngine* (*create)(int32_t, int32_t, int32_t);
    void (*destroy)(GfxWgpuEngine*);
    bool (*clear)(GfxWgpuEngine*);
    bool (*upload)(GfxWgpuEngine*, const uint8_t*, size_t);
    bool (*uploadSubstrateHeight)(GfxWgpuEngine*, const uint8_t*, int32_t, int32_t);
    bool (*uploadPaintHeight)(GfxWgpuEngine*, const float*, int32_t, int32_t);
    bool (*stampDabs)(GfxWgpuEngine*, const void*, size_t, uint32_t, float, bool,
                      const GfxWgpuSubstrate*, bool);
    bool (*stampMaskedDabs)(GfxWgpuEngine*, const void*, size_t, uint32_t, float, const uint8_t*,
                            int32_t, int32_t, const uint8_t*, int32_t, int32_t, bool, float, float,
                            float, const void*, size_t, const uint8_t*, int32_t, int32_t,
                            const GfxWgpuSubstrate*);
    bool (*colorSmudge)(GfxWgpuEngine*, const void*, size_t, int32_t, float, float, bool, uint32_t,
                        float, const uint8_t*, int32_t, int32_t);
    void (*benchmarkInfo)(GfxWgpuEngine*, uint32_t*, uint64_t*);
    bool (*readback)(GfxWgpuEngine*, uint8_t*, size_t);
    size_t (*adapterDescription)(GfxWgpuEngine*, char*, size_t);
    // Optional (resident layers, rectangle readback): a library without them still loads; the
    // adapter then behaves like the other backends (no residency, whole-layer rect reports).
    bool (*readbackRect)(GfxWgpuEngine*, uint8_t*, size_t, int32_t*);
    uint64_t (*bindLayer)(GfxWgpuEngine*, uint64_t, uint64_t);
    uint64_t (*uploadLayer)(GfxWgpuEngine*, uint64_t, uint64_t, const uint8_t*, size_t);
    bool (*commitLayer)(GfxWgpuEngine*, uint64_t, uint64_t, uint64_t);
    bool (*refreshLayer)(GfxWgpuEngine*, uint64_t, uint64_t, uint64_t, const uint8_t*, size_t,
                         int32_t, int32_t, int32_t, int32_t);
    bool (*invalidateLayer)(GfxWgpuEngine*, uint64_t);
    void (*invalidateAllLayers)(GfxWgpuEngine*);
    void (*setResidentBudget)(GfxWgpuEngine*, uint64_t);
    // Optional (multipass rendering): absent in an older library; then multipass is unsupported.
    bool (*setMultipass)(GfxWgpuEngine*, const float*, size_t);
    int32_t (*refine)(GfxWgpuEngine*, float);
    bool (*flush)(GfxWgpuEngine*);
    size_t (*multipassStats)(GfxWgpuEngine*, double*, size_t);
};

std::once_flag gLoadOnce;
Api gApi{};
bool gLoaded = false;

template <class F>
bool resolve(void* lib, const char* name, F& out) {
    out = reinterpret_cast<F>(dlsym(lib, name));
    if (out == nullptr) WGPU_LOGW("missing symbol %s", name);
    return out != nullptr;
}

template <class F>
bool optional(void* lib, const char* name, F& out) {
    out = reinterpret_cast<F>(dlsym(lib, name));
    return out != nullptr;
}

void load() {
    // GRAFFUX_WGPU_LIB lets host tools (tools/stamp-engine-diff) point at a cargo build output.
    const char* override = std::getenv("GRAFFUX_WGPU_LIB");
    void* lib = dlopen(override != nullptr ? override : "libgraffux_wgpu.so", RTLD_NOW | RTLD_LOCAL);
    if (lib == nullptr) {
        WGPU_LOGW("libgraffux_wgpu.so unavailable: %s", dlerror());
        return;
    }
    Api a{};
    gLoaded = resolve(lib, "gfx_wgpu_create", a.create) && resolve(lib, "gfx_wgpu_destroy", a.destroy) &&
              resolve(lib, "gfx_wgpu_clear", a.clear) && resolve(lib, "gfx_wgpu_upload", a.upload) &&
              resolve(lib, "gfx_wgpu_upload_substrate_height", a.uploadSubstrateHeight) &&
              resolve(lib, "gfx_wgpu_upload_paint_height", a.uploadPaintHeight) &&
              resolve(lib, "gfx_wgpu_stamp_dabs", a.stampDabs) &&
              resolve(lib, "gfx_wgpu_stamp_masked_dabs", a.stampMaskedDabs) &&
              resolve(lib, "gfx_wgpu_color_smudge", a.colorSmudge) &&
              resolve(lib, "gfx_wgpu_benchmark_info", a.benchmarkInfo) &&
              resolve(lib, "gfx_wgpu_readback", a.readback) &&
              resolve(lib, "gfx_wgpu_adapter_description", a.adapterDescription);
    if (gLoaded) {
        optional(lib, "gfx_wgpu_readback_rect", a.readbackRect);
        const bool resident = optional(lib, "gfx_wgpu_bind_layer", a.bindLayer) &&
                              optional(lib, "gfx_wgpu_upload_layer", a.uploadLayer) &&
                              optional(lib, "gfx_wgpu_commit_layer", a.commitLayer) &&
                              optional(lib, "gfx_wgpu_refresh_layer", a.refreshLayer) &&
                              optional(lib, "gfx_wgpu_invalidate_layer", a.invalidateLayer) &&
                              optional(lib, "gfx_wgpu_invalidate_all_layers", a.invalidateAllLayers) &&
                              optional(lib, "gfx_wgpu_set_resident_budget", a.setResidentBudget);
        if (!resident) a.bindLayer = nullptr;  // all or nothing
        const bool multipass = optional(lib, "gfx_wgpu_set_multipass", a.setMultipass) &&
                               optional(lib, "gfx_wgpu_refine", a.refine) &&
                               optional(lib, "gfx_wgpu_flush", a.flush) &&
                               optional(lib, "gfx_wgpu_multipass_stats", a.multipassStats);
        if (!multipass) a.setMultipass = nullptr;  // all or nothing
        gApi = a;  // The library stays loaded for the process lifetime.
    }
}

GfxWgpuSubstrate toC(const SubstrateStampParams& s) {
    return GfxWgpuSubstrate{s.enabled ? 1 : 0, s.hasPaintHeight ? 1 : 0, s.baseHeight,
                            s.heightScale, s.textureScale, s.textureOffsetX, s.textureOffsetY};
}

}  // namespace

bool WgpuStampEngine::libraryAvailable() {
    std::call_once(gLoadOnce, load);
    return gLoaded;
}

WgpuStampEngine::~WgpuStampEngine() { destroy(); }

bool WgpuStampEngine::init(int width, int height) {
    destroy();
    if (width <= 0 || height <= 0 || !libraryAvailable()) return false;
    engine_ = gApi.create(width, height, 0);
    if (engine_ == nullptr) {
        WGPU_LOGW("no wgpu adapter with compute support");
        return false;
    }
    width_ = width;
    height_ = height;
    char name[256] = {};
    gApi.adapterDescription(engine_, name, sizeof(name));
    WGPU_LOGW("wgpu engine %dx%d on %s", width, height, name);
    return true;
}

bool WgpuStampEngine::initWithHardwareBuffer(int /*width*/, int /*height*/) {
    // No AHardwareBuffer interop in the wgpu engine (yet): callers fall back to readback display.
    destroy();
    return false;
}

bool WgpuStampEngine::clear() { return engine_ != nullptr && gApi.clear(engine_); }

bool WgpuStampEngine::upload(const uint8_t* inRgba8, size_t inSizeBytes) {
    return engine_ != nullptr && inRgba8 != nullptr && gApi.upload(engine_, inRgba8, inSizeBytes);
}

bool WgpuStampEngine::uploadSubstrateHeight(const uint8_t* heightR8, int width, int height) {
    return engine_ != nullptr && gApi.uploadSubstrateHeight(engine_, heightR8, width, height);
}

bool WgpuStampEngine::uploadPaintHeight(const float* heightMap, int width, int height) {
    return engine_ != nullptr && gApi.uploadPaintHeight(engine_, heightMap, width, height);
}

bool WgpuStampEngine::stampDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb, float hardness,
                                bool buildUp, SubstrateStampParams substrate, bool strokeMax) {
    if (engine_ == nullptr || dabs.empty()) return false;
    const GfxWgpuSubstrate s = toC(substrate);
    return gApi.stampDabs(engine_, dabs.data(), dabs.size(), colorArgb, hardness, buildUp, &s,
                          strokeMax);
}

bool WgpuStampEngine::stampMaskedDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb,
                                      float hardness, const uint8_t* maskAlpha8, int maskWidth,
                                      int maskHeight, const uint8_t* grainAlpha8, int grainWidth,
                                      int grainHeight, bool grainCanvasLocked, float grainScale,
                                      float grainPhaseX, float grainPhaseY,
                                      const std::vector<GpuSecondaryDab>& secondaryDabs,
                                      const uint8_t* secondaryMaskAlpha8, int secondaryMaskWidth,
                                      int secondaryMaskHeight, SubstrateStampParams substrate) {
    if (engine_ == nullptr || dabs.empty()) return false;
    const GfxWgpuSubstrate s = toC(substrate);
    return gApi.stampMaskedDabs(engine_, dabs.data(), dabs.size(), colorArgb, hardness, maskAlpha8,
                                maskWidth, maskHeight, grainAlpha8, grainWidth, grainHeight,
                                grainCanvasLocked, grainScale, grainPhaseX, grainPhaseY,
                                secondaryDabs.empty() ? nullptr : secondaryDabs.data(),
                                secondaryDabs.size(), secondaryMaskAlpha8, secondaryMaskWidth,
                                secondaryMaskHeight, &s);
}

bool WgpuStampEngine::colorSmudge(const std::vector<ColorSmudgeDab>& dabs, int mode, float radiusPx,
                                  float feathering, bool smearAlpha, uint32_t paintColorArgb,
                                  float dilution, const uint8_t* sampleSourceRgba8,
                                  int sampleSourceWidth, int sampleSourceHeight) {
    static_assert(sizeof(ColorSmudgeDab) == 44, "ColorSmudgeDab must match the Rust record");
    if (engine_ == nullptr || dabs.size() < 2) return false;
    return gApi.colorSmudge(engine_, dabs.data(), dabs.size(), mode, radiusPx, feathering,
                            smearAlpha, paintColorArgb, dilution, sampleSourceRgba8,
                            sampleSourceWidth, sampleSourceHeight);
}

ColorSmudgeBenchmarkInfo WgpuStampEngine::colorSmudgeBenchmarkInfo() const {
    ColorSmudgeBenchmarkInfo info{};
    if (engine_ == nullptr) return info;
    uint32_t ids[3] = {};
    uint64_t nanos[2] = {};
    gApi.benchmarkInfo(engine_, ids, nanos);
    info.vendorId = ids[0];
    info.deviceId = ids[1];
    info.selectedTileSize = ids[2];
    info.nanos8 = nanos[0];
    info.nanos16 = nanos[1];
    return info;
}

bool WgpuStampEngine::readback(uint8_t* outRgba8, size_t outCapacityBytes) {
    return engine_ != nullptr && outRgba8 != nullptr &&
           gApi.readback(engine_, outRgba8, outCapacityBytes);
}

bool WgpuStampEngine::readbackRect(uint8_t* outRgba8, size_t outCapacityBytes, int32_t rect[4]) {
    if (gApi.readbackRect == nullptr) return StampEngine::readbackRect(outRgba8, outCapacityBytes, rect);
    return engine_ != nullptr && outRgba8 != nullptr &&
           gApi.readbackRect(engine_, outRgba8, outCapacityBytes, rect);
}

bool WgpuStampEngine::supportsResidentLayers() const {
    return engine_ != nullptr && gApi.bindLayer != nullptr;
}

uint64_t WgpuStampEngine::bindLayer(uint64_t key, uint64_t generation) {
    return supportsResidentLayers() ? gApi.bindLayer(engine_, key, generation) : 0;
}

uint64_t WgpuStampEngine::uploadLayer(uint64_t key, uint64_t generation, const uint8_t* rgba,
                                      size_t size) {
    if (!supportsResidentLayers() || rgba == nullptr) return 0;
    return gApi.uploadLayer(engine_, key, generation, rgba, size);
}

bool WgpuStampEngine::commitLayer(uint64_t key, uint64_t session, uint64_t generation) {
    return supportsResidentLayers() && gApi.commitLayer(engine_, key, session, generation);
}

bool WgpuStampEngine::refreshLayer(uint64_t key, uint64_t session, uint64_t generation,
                                   const uint8_t* rgba, size_t size, int x, int y, int w, int h) {
    return supportsResidentLayers() && rgba != nullptr &&
           gApi.refreshLayer(engine_, key, session, generation, rgba, size, x, y, w, h);
}

bool WgpuStampEngine::invalidateLayer(uint64_t key) {
    return supportsResidentLayers() && gApi.invalidateLayer(engine_, key);
}

void WgpuStampEngine::invalidateAllLayers() {
    if (supportsResidentLayers()) gApi.invalidateAllLayers(engine_);
}

void WgpuStampEngine::setResidentBudget(uint64_t bytes) {
    if (supportsResidentLayers()) gApi.setResidentBudget(engine_, bytes);
}

bool WgpuStampEngine::setMultipass(const float* params, size_t count) {
    return engine_ != nullptr && gApi.setMultipass != nullptr &&
           gApi.setMultipass(engine_, params, count);
}

int WgpuStampEngine::refine(float budgetMs) {
    if (engine_ == nullptr || gApi.setMultipass == nullptr) return 0;
    return gApi.refine(engine_, budgetMs);
}

bool WgpuStampEngine::flushMultipass() {
    if (engine_ == nullptr || gApi.setMultipass == nullptr) return true;
    return gApi.flush(engine_);
}

size_t WgpuStampEngine::multipassStats(double* out, size_t count) {
    if (engine_ == nullptr || gApi.setMultipass == nullptr) return 0;
    return gApi.multipassStats(engine_, out, count);
}

void WgpuStampEngine::destroy() {
    if (engine_ != nullptr) gApi.destroy(engine_);
    engine_ = nullptr;
    width_ = height_ = 0;
}

}  // namespace graffux
