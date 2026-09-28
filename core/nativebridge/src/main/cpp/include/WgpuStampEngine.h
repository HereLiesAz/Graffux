// FILE: core/nativebridge/src/main/cpp/include/WgpuStampEngine.h
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "StampEngine.h"

struct GfxWgpuEngine;

namespace graffux {

/**
 * The third StampEngine backend: an adapter over the Rust wgpu engine (core/wgpu-engine), the
 * long-term single brush engine shared with the desktop app. Its WGSL shaders are ports of the
 * same stamp.comp / stamp_masked.comp / color_smudge.comp math; tools/stamp-engine-diff compares
 * its pixels against the Vulkan and GLES engines.
 *
 * libgraffux_wgpu.so is dlopen()ed on first use rather than linked, so a build without the Rust
 * library (no cargo / Android targets on the build host) still links and runs: init() just returns
 * false and the caller falls back to the CPU path, exactly as with no usable GPU.
 *
 * No AHardwareBuffer output: initWithHardwareBuffer() returns false, so direct display
 * (LiveStrokeOverlay, raw Vulkan interop) is simply ineligible while wgpu is the selected engine.
 */
class WgpuStampEngine final : public StampEngine {
public:
    WgpuStampEngine() = default;
    ~WgpuStampEngine() override;

    WgpuStampEngine(const WgpuStampEngine&) = delete;
    WgpuStampEngine& operator=(const WgpuStampEngine&) = delete;

    /** True once libgraffux_wgpu.so and every symbol resolved (cached per process). */
    static bool libraryAvailable();

    bool init(int width, int height) override;
    bool initWithHardwareBuffer(int width, int height) override;
    bool clear() override;
    struct AHardwareBuffer* hardwareBuffer() const override { return nullptr; }
    bool upload(const uint8_t* inRgba8, size_t inSizeBytes) override;
    bool uploadSubstrateHeight(const uint8_t* heightR8, int width, int height) override;
    bool uploadPaintHeight(const float* heightMap, int width, int height) override;
    bool stampDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb, float hardness,
                   bool buildUp = false, SubstrateStampParams substrate = {},
                   bool strokeMax = false) override;
    bool stampMaskedDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb, float hardness,
                         const uint8_t* maskAlpha8, int maskWidth, int maskHeight,
                         const uint8_t* grainAlpha8 = nullptr, int grainWidth = 0,
                         int grainHeight = 0, bool grainCanvasLocked = false,
                         float grainScale = 1.0f, float grainPhaseX = 0.0f,
                         float grainPhaseY = 0.0f,
                         const std::vector<GpuSecondaryDab>& secondaryDabs = {},
                         const uint8_t* secondaryMaskAlpha8 = nullptr, int secondaryMaskWidth = 0,
                         int secondaryMaskHeight = 0, SubstrateStampParams substrate = {}) override;
    bool colorSmudge(const std::vector<ColorSmudgeDab>& dabs, int mode, float radiusPx,
                     float feathering, bool smearAlpha, uint32_t paintColorArgb,
                     float dilution = 0.0f, const uint8_t* sampleSourceRgba8 = nullptr,
                     int sampleSourceWidth = 0, int sampleSourceHeight = 0) override;
    ColorSmudgeBenchmarkInfo colorSmudgeBenchmarkInfo() const override;
    bool readback(uint8_t* outRgba8, size_t outCapacityBytes) override;
    void destroy() override;
    bool readbackRect(uint8_t* outRgba8, size_t outCapacityBytes, int32_t rect[4]) override;
    bool supportsResidentLayers() const override;
    uint64_t bindLayer(uint64_t key, uint64_t generation) override;
    uint64_t uploadLayer(uint64_t key, uint64_t generation, const uint8_t* rgba,
                         size_t size) override;
    bool commitLayer(uint64_t key, uint64_t session, uint64_t generation) override;
    bool refreshLayer(uint64_t key, uint64_t session, uint64_t generation, const uint8_t* rgba,
                      size_t size, int x, int y, int w, int h) override;
    bool invalidateLayer(uint64_t key) override;
    void invalidateAllLayers() override;
    void setResidentBudget(uint64_t bytes) override;
    std::string gpuInfo() const override;
    size_t takePassTimings(uint64_t* out, size_t capacityPairs) override;
    bool isInitialized() const override { return engine_ != nullptr; }
    int width() const override { return width_; }
    int height() const override { return height_; }

private:
    GfxWgpuEngine* engine_ = nullptr;
    int width_ = 0;
    int height_ = 0;
};

}  // namespace graffux
