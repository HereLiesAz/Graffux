// FILE: core/nativebridge/src/main/cpp/include/GlesStampEngine.h
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "StampEngine.h"

#include <EGL/egl.h>
#include <GLES3/gl31.h>

namespace graffux {

/**
 * The GPU stamp engine (docs/Native Rendering Engine Design.md §2): OpenGL ES 3.1 compute shaders
 * that stamp dabs onto a persistent RGBA8 layer entirely on the GPU, with the same per-dab
 * SRC_OVER compositing and hardness/radius coverage profile as StampBrushRenderer's CPU path.
 * The alternative to VulkanStampEngine (selected in Settings): its shaders are mechanical ports
 * of the Vulkan ones and produce the same pixels; GLES drivers are the older, more widely tested
 * stack on Android, and androidx.graphics' front-buffer presentation is GL-native.
 *
 * The layer lives in a shader storage buffer, one packed RGBA8 word per pixel (ES 3.1 cannot both
 * load and store an rgba8 image). Its bytes are exactly android.graphics.Bitmap ARGB_8888 memory
 * (premultiplied, R,G,B,A), so upload()/readback() are straight copies.
 *
 * Owns a private EGL context (surfaceless, or a 1x1 pbuffer where surfaceless isn't offered).
 * Every public call makes it current on the calling thread and restores whatever was current
 * before returning, so callers on different threads (the UI thread, the live-render worker, a
 * GLSurfaceView's own GL thread) never inherit or clobber each other's context. The engine is not
 * thread-safe: the Kotlin wrapper serializes every call on one instance.
 */
class GlesStampEngine final : public StampEngine {
public:
    GlesStampEngine() = default;
    ~GlesStampEngine() override;

    GlesStampEngine(const GlesStampEngine&) = delete;
    GlesStampEngine& operator=(const GlesStampEngine&) = delete;

    // Creates the context, programs and a `width`x`height` layer cleared to transparent black.
    // Returns false (leaving the engine unusable) when no ES 3.1 context can be created or a
    // shader fails to compile -- the caller falls back to the CPU path, not treat it as fatal.
    bool init(int width, int height) override;

    // init() plus an AHardwareBuffer the layer is published into after every write, so the JVM
    // side can wrap it as a hardware Bitmap (Bitmap.wrapHardwareBuffer) and display the layer with
    // no CPU readback. The copy into it stays on the GPU (layer SSBO -> texture via a pixel-unpack
    // buffer). Returns false -- falling back to init() is expected and safe -- off Android, or if
    // the allocation or the EGLImage import fails.
    bool initWithHardwareBuffer(int width, int height) override;

    // Clears the layer to transparent black without recreating anything; used when the Kotlin
    // wrapper checks a healthy engine back out of its reuse pool. Seeds a new stroke.
    bool clear() override;

    // The AHardwareBuffer the layer is published into (initWithHardwareBuffer only), else null.
    // Ownership stays with this engine; a JNI caller takes its own reference.
    struct AHardwareBuffer* hardwareBuffer() const override { return hardwareBuffer_; }

    // Seeds the layer with `inRgba8` (width*height*4, same layout readback() produces). Seeds a new
    // stroke. Returns false if the engine isn't initialized or `inSizeBytes` is too small.
    bool upload(const uint8_t* inRgba8, size_t inSizeBytes) override;

    // Canvas substrate-height tile (R8). Byte-identical same-size re-uploads are hash-skipped.
    bool uploadSubstrateHeight(const uint8_t* heightR8, int width, int height) override;

    // GPU mirror of Layer.heightMap (R32F). Dimensions must match the layer exactly; sampled only
    // when a dispatch sets hasPaintHeight.
    bool uploadPaintHeight(const float* heightMap, int width, int height) override;

    // Stamps `dabs` with `colorArgb` (Android ARGB int) and `hardness` (0..1).
    // `buildUp` false (default): each pixel takes its single strongest dab of this call (matching
    // paintRoundDabsMaxCombined); true composites sequentially in submission order (Airbrush).
    // `strokeMax` (ignored when buildUp) extends max-combine across every call since the last
    // upload()/clear(), so a stroke fed in per-frame batches renders exactly like one max-combined
    // call. Lazily allocates width*height*8 bytes of stroke state on first use.
    bool stampDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb, float hardness,
                   bool buildUp = false, SubstrateStampParams substrate = {},
                   bool strokeMax = false) override;

    // Masked-tip counterpart to stampDabs(): each dab samples `maskAlpha8` (R8 tip, white = full
    // coverage) in its own rotated/scaled space. `dabs[i].tipRatio` must be set per dab. Optional
    // grain tile (null disables) and optional dual-brush secondary tip (`secondaryDabs` empty
    // disables; otherwise exactly dabs.size() long). Textures are re-uploaded only when their
    // size or content hash changes.
    bool stampMaskedDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb, float hardness,
                         const uint8_t* maskAlpha8, int maskWidth, int maskHeight,
                         const uint8_t* grainAlpha8 = nullptr, int grainWidth = 0, int grainHeight = 0,
                         bool grainCanvasLocked = false, float grainScale = 1.0f,
                         float grainPhaseX = 0.0f, float grainPhaseY = 0.0f,
                         const std::vector<GpuSecondaryDab>& secondaryDabs = {},
                         const uint8_t* secondaryMaskAlpha8 = nullptr, int secondaryMaskWidth = 0,
                         int secondaryMaskHeight = 0, SubstrateStampParams substrate = {}) override;

    // Ordered read/modify/write Color Smudge pass on the layer. `mode` 0=Smear, 1=Dulling (+2 =
    // pigment mixing variants). Optional Sample Merged composite (`sampleSourceRgba8`, straight
    // RGBA8) is read for pickup instead of the layer when supplied.
    bool colorSmudge(const std::vector<ColorSmudgeDab>& dabs, int mode, float radiusPx,
                     float feathering, bool smearAlpha, uint32_t paintColorArgb,
                     float dilution = 0.0f, const uint8_t* sampleSourceRgba8 = nullptr,
                     int sampleSourceWidth = 0, int sampleSourceHeight = 0) override;
    ColorSmudgeBenchmarkInfo colorSmudgeBenchmarkInfo() const override { return smudgeBenchmark_; }

    // Copies the layer into `outRgba8` (width*height*4, premultiplied RGBA8, row-major). Only the
    // region written since the last readback is copied; everything else in `outRgba8` is left as
    // the caller's buffer already had it -- correct because every caller reuses one buffer for a
    // whole stroke. A live-preview primitive, not a "whole layer" snapshot.
    bool readback(uint8_t* outRgba8, size_t outCapacityBytes) override;

    // Releases every GL/EGL resource. Safe to call repeatedly; init() may be called again.
    void destroy() override;

    bool isInitialized() const override { return context_ != EGL_NO_CONTEXT && layerBuffer_ != 0; }
    int width() const override { return width_; }
    int height() const override { return height_; }

private:
    friend class ScopedCurrent;

    bool createContext();
    bool createPrograms();
    bool createLayer(int width, int height);
    bool publishRegion(int32_t x, int32_t y, int32_t w, int32_t h);
    void expandDirtyRect(int32_t originX, int32_t originY, int32_t w, int32_t h);
    void markLayerFullyDirty();
    bool ensureStrokeState();
    bool fillZero(GLuint buffer, size_t words);
    bool ensureTexture(GLuint& tex, int& texW, int& texH, uint64_t& hash, int w, int h,
                       GLenum internalFormat, GLenum format, GLenum type, const void* data,
                       size_t bytes, GLint filter, GLint wrap);
    bool ensureSmudgePrograms();
    bool runColorSmudgePlan(const std::vector<ColorSmudgeDab>& dabs, int mode, float radiusPx,
                            float feathering, bool smearAlpha, uint32_t paintColorArgb,
                            GLuint program, uint32_t tileSize, float dilution, bool hasSampleMerged);
    bool benchmarkColorSmudge(float radiusPx);
    void uploadDabs(GLuint& buffer, size_t& capacityBytes, const void* data, size_t bytes);

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;  // 1x1 pbuffer, only when surfaceless is unavailable

    int width_ = 0;
    int height_ = 0;
    // 16x16 workgroups unless the device can't run 256 invocations (ES 3.1 guarantees only 128).
    uint32_t stampTileSize_ = 16;

    GLuint layerBuffer_ = 0;          // SSBO, width*height packed RGBA8 words
    GLuint strokeStateBuffer_ = 0;    // SSBO, width*height uvec2 (stroke-max state), lazy
    bool strokeStateDirty_ = true;    // belongs to a previous stroke; zero before next use
    GLuint paramsBuffer_ = 0;         // UBO, per-dispatch parameters
    GLuint dabBuffer_ = 0;
    size_t dabBufferBytes_ = 0;
    GLuint secondaryDabBuffer_ = 0;
    size_t secondaryDabBufferBytes_ = 0;

    GLuint stampProgram_ = 0;
    GLuint maskedProgram_ = 0;
    GLuint fillProgram_ = 0;
    GLuint smudgeProgram8_ = 0;
    GLuint smudgeProgram16_ = 0;
    GLuint smudgeCarrier_ = 0;
    size_t smudgeCarrierBytes_ = 0;
    ColorSmudgeBenchmarkInfo smudgeBenchmark_{};

    // Sampled textures, each re-uploaded only when its size or content hash changes.
    GLuint maskTex_ = 0;            int maskW_ = 0, maskH_ = 0;                 uint64_t maskHash_ = 0;
    GLuint grainTex_ = 0;           int grainW_ = 0, grainH_ = 0;               uint64_t grainHash_ = 0;
    GLuint secondaryMaskTex_ = 0;   int secondaryW_ = 0, secondaryH_ = 0;       uint64_t secondaryHash_ = 0;
    GLuint substrateTex_ = 0;       int substrateW_ = 0, substrateH_ = 0;       uint64_t substrateHash_ = 0;
    GLuint paintHeightTex_ = 0;     int paintHeightW_ = 0, paintHeightH_ = 0;   uint64_t paintHeightHash_ = 0;
    GLuint sampleSourceTex_ = 0;    int sampleSourceW_ = 0, sampleSourceH_ = 0; uint64_t sampleSourceHash_ = 0;

    // Zero-copy display target (initWithHardwareBuffer only).
    struct AHardwareBuffer* hardwareBuffer_ = nullptr;
    void* hardwareBufferImage_ = nullptr;  // EGLImageKHR
    GLuint hardwareBufferTex_ = 0;

    // Everything written since the last readback(), in layer pixels; w/h 0 = nothing outstanding.
    int32_t dirtyOriginX_ = 0;
    int32_t dirtyOriginY_ = 0;
    int32_t dirtyWidth_ = 0;
    int32_t dirtyHeight_ = 0;
};

}  // namespace graffux
