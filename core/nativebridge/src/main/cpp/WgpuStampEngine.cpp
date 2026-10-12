// FILE: core/nativebridge/src/main/cpp/WgpuStampEngine.cpp
#include "include/WgpuStampEngine.h"

#include <dlfcn.h>

#include <cstdio>
#include <cstdlib>
#include <mutex>

#ifdef __ANDROID__
#include <android/log.h>
#include <android/native_window.h>
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
    // adapter then falls back to StampEngine's defaults (no residency, whole-layer rect reports).
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
    // Optional, all or nothing: impasto v1 height and relief.
    bool (*uploadHeight)(GfxWgpuEngine*, const float*, int32_t);
    bool (*readHeight)(GfxWgpuEngine*, float*, int32_t);
    bool (*depositHeight)(GfxWgpuEngine*, const float*, int32_t, float, float);
    bool (*shadeRelief)(GfxWgpuEngine*, int32_t, int32_t, int32_t, int32_t, float, float, float);
    // Optional, all or nothing: the document store.
    bool (*docOpen)(GfxWgpuEngine*);
    uint64_t (*docLayer)(GfxWgpuEngine*, int32_t, uint64_t, uint64_t, const int32_t*, const uint8_t*, size_t);
    bool (*docLayerDrop)(GfxWgpuEngine*, uint64_t);
    uint64_t (*docLayerBind)(GfxWgpuEngine*, uint64_t);
    uint64_t (*docLayerCommit)(GfxWgpuEngine*, uint64_t, uint64_t, int32_t*);
    bool (*docLayerRead)(GfxWgpuEngine*, uint64_t, int32_t, int32_t, int32_t, int32_t, uint8_t*, size_t);
    // Optional: undo snapshots of document layers.
    uint64_t (*docSnapshot)(GfxWgpuEngine*, int32_t, uint64_t, uint64_t, uint64_t, int32_t*);
    // Optional, all or nothing: selection at commit.
    bool (*setSelection)(GfxWgpuEngine*, const uint8_t*, size_t);
    bool (*selectionOp)(GfxWgpuEngine*, int32_t);
    bool (*strokeRect)(GfxWgpuEngine*, int32_t*);
    // Optional, all or nothing: live impasto display.
    bool (*shadeReliefDisplay)(GfxWgpuEngine*, int32_t, int32_t, int32_t, int32_t, float, float, float);
    bool (*readDisplay)(GfxWgpuEngine*, int32_t, int32_t, int32_t, int32_t, uint8_t*, size_t);
    // Optional: a new stroke on the layer as it is.
    bool (*beginStroke)(GfxWgpuEngine*);
    // Optional, all or nothing: impasto v2's wet material simulation.
    bool (*wetBegin)(GfxWgpuEngine*, const uint32_t*, const float*, const float*, int32_t,
                     const uint32_t*, int32_t, int32_t, const float*, int32_t);
    bool (*wetBuffer)(GfxWgpuEngine*, int32_t, const float*, int32_t);
    bool (*wetOp)(GfxWgpuEngine*, int32_t, const float*, int32_t);
    bool (*wetRead)(GfxWgpuEngine*, uint32_t*, float*, float*, int32_t);
    bool (*wetReadFlags)(GfxWgpuEngine*, uint32_t*, int32_t);
    // Optional (per-device tuning, telemetry): an older library creates untuned and reports
    // no GPU info or timings, so telemetry falls back to CPU wall time.
    GfxWgpuEngine* (*createTuned)(int32_t, int32_t, int32_t, int32_t, bool);
    size_t (*gpuInfo)(GfxWgpuEngine*, char*, size_t);
    size_t (*takePassTimings)(GfxWgpuEngine*, uint64_t*, size_t);
    // Optional (direct display): an older library has none, so direct display is unsupported and
    // strokes display through readback as before.
    uint32_t (*directCapabilities)(GfxWgpuEngine*);
    bool (*directAttach)(GfxWgpuEngine*, void*, int32_t, int32_t);
    void (*directDetach)(GfxWgpuEngine*);
    bool (*directBeginStroke)(GfxWgpuEngine*);
    bool (*directPresent)(GfxWgpuEngine*, const float*, bool);
    bool (*directEndStroke)(GfxWgpuEngine*);
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
        const bool impasto = optional(lib, "gfx_wgpu_upload_height", a.uploadHeight) &&
                             optional(lib, "gfx_wgpu_read_height", a.readHeight) &&
                             optional(lib, "gfx_wgpu_deposit_height", a.depositHeight) &&
                             optional(lib, "gfx_wgpu_shade_relief", a.shadeRelief);
        if (!impasto) a.uploadHeight = nullptr;  // all or nothing
        optional(lib, "gfx_wgpu_begin_stroke", a.beginStroke);
        const bool display = optional(lib, "gfx_wgpu_shade_relief_display", a.shadeReliefDisplay) &&
                             optional(lib, "gfx_wgpu_read_display", a.readDisplay);
        if (!display) a.shadeReliefDisplay = nullptr;  // all or nothing
        const bool selection = optional(lib, "gfx_wgpu_set_selection", a.setSelection) &&
                               optional(lib, "gfx_wgpu_selection_op", a.selectionOp) &&
                               optional(lib, "gfx_wgpu_stroke_rect", a.strokeRect);
        if (!selection) a.setSelection = nullptr;  // all or nothing
        const bool doc = optional(lib, "gfx_wgpu_doc_open", a.docOpen) &&
                         optional(lib, "gfx_wgpu_doc_layer", a.docLayer) &&
                         optional(lib, "gfx_wgpu_doc_layer_drop", a.docLayerDrop) &&
                         optional(lib, "gfx_wgpu_doc_layer_bind", a.docLayerBind) &&
                         optional(lib, "gfx_wgpu_doc_layer_commit", a.docLayerCommit) &&
                         optional(lib, "gfx_wgpu_doc_layer_read", a.docLayerRead);
        if (!doc) a.docOpen = nullptr;  // all or nothing
        optional(lib, "gfx_wgpu_doc_snapshot", a.docSnapshot);
        const bool wet = optional(lib, "gfx_wgpu_wet_begin", a.wetBegin) &&
                         optional(lib, "gfx_wgpu_wet_buffer", a.wetBuffer) &&
                         optional(lib, "gfx_wgpu_wet_op", a.wetOp) &&
                         optional(lib, "gfx_wgpu_wet_read", a.wetRead) &&
                         optional(lib, "gfx_wgpu_wet_read_flags", a.wetReadFlags);
        if (!wet) a.wetBegin = nullptr;  // all or nothing
        optional(lib, "gfx_wgpu_create_tuned", a.createTuned);
        optional(lib, "gfx_wgpu_gpu_info", a.gpuInfo);
        optional(lib, "gfx_wgpu_take_pass_timings", a.takePassTimings);
        const bool direct = optional(lib, "gfx_wgpu_direct_capabilities", a.directCapabilities) &&
                            optional(lib, "gfx_wgpu_direct_attach", a.directAttach) &&
                            optional(lib, "gfx_wgpu_direct_detach", a.directDetach) &&
                            optional(lib, "gfx_wgpu_direct_begin_stroke", a.directBeginStroke) &&
                            optional(lib, "gfx_wgpu_direct_present", a.directPresent) &&
                            optional(lib, "gfx_wgpu_direct_end_stroke", a.directEndStroke);
        if (!direct) a.directCapabilities = nullptr;  // all or nothing
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
    const StampTuning& tuning = stampTuning();
    engine_ = gApi.createTuned != nullptr
                  ? gApi.createTuned(width, height, 0, tuning.stampTile.load(), tuning.timestamps.load())
                  : gApi.create(width, height, 0);
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

bool WgpuStampEngine::supportsImpasto() const {
    return engine_ != nullptr && gApi.uploadHeight != nullptr;
}

bool WgpuStampEngine::uploadImpastoHeight(const float* heights, size_t count) {
    return supportsImpasto() && heights != nullptr &&
           gApi.uploadHeight(engine_, heights, static_cast<int32_t>(count));
}

bool WgpuStampEngine::readImpastoHeight(float* out, size_t count) {
    return supportsImpasto() && out != nullptr && gApi.readHeight(engine_, out, static_cast<int32_t>(count));
}

bool WgpuStampEngine::depositImpastoHeight(const float* dabs, size_t dabCount, float hardness,
                                           float thickness) {
    return supportsImpasto() &&
           gApi.depositHeight(engine_, dabs, static_cast<int32_t>(dabCount), hardness, thickness);
}

bool WgpuStampEngine::shadeImpastoRelief(int x, int y, int w, int h, float azimuthDeg,
                                         float elevationDeg, float strength) {
    return supportsImpasto() &&
           gApi.shadeRelief(engine_, x, y, w, h, azimuthDeg, elevationDeg, strength);
}

bool WgpuStampEngine::docOpen() {
    return engine_ != nullptr && gApi.docOpen != nullptr && gApi.docOpen(engine_);
}

uint64_t WgpuStampEngine::docLayer(int op, uint64_t key, uint64_t other, const int* rect,
                                   const uint8_t* rgba, size_t len) {
    if (engine_ == nullptr || gApi.docOpen == nullptr) return 0;
    return gApi.docLayer(engine_, op, key, other, rect, rgba, len);
}

bool WgpuStampEngine::docLayerDrop(uint64_t key) {
    return engine_ != nullptr && gApi.docOpen != nullptr && gApi.docLayerDrop(engine_, key);
}

uint64_t WgpuStampEngine::docLayerBind(uint64_t key) {
    if (engine_ == nullptr || gApi.docOpen == nullptr) return 0;
    return gApi.docLayerBind(engine_, key);
}

uint64_t WgpuStampEngine::docLayerCommit(uint64_t key, uint64_t session, int* outRect) {
    if (engine_ == nullptr || gApi.docOpen == nullptr) return 0;
    return gApi.docLayerCommit(engine_, key, session, outRect);
}

uint64_t WgpuStampEngine::docSnapshot(int op, uint64_t a, uint64_t b, uint64_t c, int* rect) {
    if (engine_ == nullptr || gApi.docOpen == nullptr || gApi.docSnapshot == nullptr) return 0;
    return gApi.docSnapshot(engine_, op, a, b, c, rect);
}

bool WgpuStampEngine::docLayerRead(uint64_t key, int x, int y, int w, int h, uint8_t* out, size_t len) {
    return engine_ != nullptr && gApi.docOpen != nullptr && gApi.docLayerRead(engine_, key, x, y, w, h, out, len);
}

bool WgpuStampEngine::setSelection(const uint8_t* coverage, size_t len) {
    return engine_ != nullptr && gApi.setSelection != nullptr && gApi.setSelection(engine_, coverage, len);
}

bool WgpuStampEngine::strokeRect(int* out) {
    return engine_ != nullptr && gApi.setSelection != nullptr && gApi.strokeRect(engine_, out);
}

bool WgpuStampEngine::selectionOp(int op) {
    return engine_ != nullptr && gApi.setSelection != nullptr && gApi.selectionOp(engine_, op);
}

bool WgpuStampEngine::shadeImpastoReliefToDisplay(int x, int y, int w, int h, float azimuthDeg,
                                                  float elevationDeg, float strength) {
    return engine_ != nullptr && gApi.shadeReliefDisplay != nullptr &&
           gApi.shadeReliefDisplay(engine_, x, y, w, h, azimuthDeg, elevationDeg, strength);
}

bool WgpuStampEngine::readImpastoDisplay(int x, int y, int w, int h, uint8_t* out, size_t len) {
    return engine_ != nullptr && gApi.shadeReliefDisplay != nullptr && gApi.readDisplay(engine_, x, y, w, h, out, len);
}

bool WgpuStampEngine::beginStroke() {
    return engine_ != nullptr && gApi.beginStroke != nullptr && gApi.beginStroke(engine_);
}

bool WgpuStampEngine::supportsWet() const {
    return engine_ != nullptr && gApi.wetBegin != nullptr;
}

bool WgpuStampEngine::wetBegin(const uint32_t* raw, const float* height, const float* wetness,
                               size_t count, const uint32_t* active, size_t tiles, int tileSize,
                               const float* media, size_t mediaCount) {
    return supportsWet() &&
           gApi.wetBegin(engine_, raw, height, wetness, static_cast<int32_t>(count), active,
                         static_cast<int32_t>(tiles), tileSize, media, static_cast<int32_t>(mediaCount));
}

bool WgpuStampEngine::wetBuffer(int which, const float* data, size_t count) {
    return supportsWet() && gApi.wetBuffer(engine_, which, data, static_cast<int32_t>(count));
}

bool WgpuStampEngine::wetOp(int op, const float* args, size_t count) {
    return supportsWet() && gApi.wetOp(engine_, op, args, static_cast<int32_t>(count));
}

bool WgpuStampEngine::wetRead(uint32_t* raw, float* height, float* wetness, size_t count) {
    return supportsWet() && gApi.wetRead(engine_, raw, height, wetness, static_cast<int32_t>(count));
}

bool WgpuStampEngine::wetReadFlags(uint32_t* out, size_t count) {
    return supportsWet() && gApi.wetReadFlags(engine_, out, static_cast<int32_t>(count));
}

size_t WgpuStampEngine::multipassStats(double* out, size_t count) {
    if (engine_ == nullptr || gApi.setMultipass == nullptr) return 0;
    return gApi.multipassStats(engine_, out, count);
}

std::string WgpuStampEngine::gpuInfo() const {
    if (engine_ == nullptr || gApi.gpuInfo == nullptr) return {};
    std::string text(1024, '\0');
    const size_t n = gApi.gpuInfo(engine_, text.data(), text.size());
    text.resize(n < text.size() ? n : text.size() - 1);
    return text;
}

size_t WgpuStampEngine::takePassTimings(uint64_t* out, size_t capacityPairs) {
    if (engine_ == nullptr || gApi.takePassTimings == nullptr) return 0;
    return gApi.takePassTimings(engine_, out, capacityPairs);
}

uint32_t WgpuStampEngine::directCapabilities() const {
    if (engine_ == nullptr || gApi.directCapabilities == nullptr) return 0;
    return gApi.directCapabilities(engine_);
}

bool WgpuStampEngine::directAttach(ANativeWindow* window, int width, int height) {
#ifdef __ANDROID__
    if (engine_ == nullptr || gApi.directCapabilities == nullptr || window == nullptr) return false;
    if (window != directWindow_) {
        // A new window: drop the old surface before letting go of its window.
        directDetach();
        ANativeWindow_acquire(window);
        directWindow_ = window;
    }
    if (gApi.directAttach(engine_, window, width, height)) return true;
    directDetach();
    return false;
#else
    (void)window;
    (void)width;
    (void)height;
    return false;
#endif
}

void WgpuStampEngine::directDetach() {
    if (engine_ != nullptr && gApi.directCapabilities != nullptr) gApi.directDetach(engine_);
#ifdef __ANDROID__
    if (directWindow_ != nullptr) ANativeWindow_release(directWindow_);
#endif
    directWindow_ = nullptr;
}

bool WgpuStampEngine::directBeginStroke() {
    return engine_ != nullptr && gApi.directCapabilities != nullptr && gApi.directBeginStroke(engine_);
}

bool WgpuStampEngine::directPresent(const float* matrix, bool newBatch) {
    return engine_ != nullptr && gApi.directCapabilities != nullptr &&
           gApi.directPresent(engine_, matrix, newBatch);
}

bool WgpuStampEngine::directEndStroke() {
    return engine_ != nullptr && gApi.directCapabilities != nullptr && gApi.directEndStroke(engine_);
}

void WgpuStampEngine::destroy() {
    // The surface goes with the engine; only then may its window be released.
    if (engine_ != nullptr) gApi.destroy(engine_);
    engine_ = nullptr;
#ifdef __ANDROID__
    if (directWindow_ != nullptr) ANativeWindow_release(directWindow_);
#endif
    directWindow_ = nullptr;
    width_ = height_ = 0;
}

}  // namespace graffux
