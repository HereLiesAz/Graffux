// FILE: core/nativebridge/src/main/cpp/include/VulkanStampEngine.h
#pragma once

#include <cstdint>
#include <vector>

// Pulls in vulkan_android.h from vulkan.h's own platform guard — VK_ANDROID_external_memory_
// android_hardware_buffer's structs/functions initWithHardwareBuffer() needs.
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

struct AHardwareBuffer;

namespace graffux {

// One dab. The first five fields are the historical ABI. The resolved paint fields widen the
// buffer to 12 floats / 48 bytes; old aggregate initializers that provide only five values leave
// `resolved` at zero, so the shader falls back to the stroke-level push-constant colour exactly as
// before. New callers set resolved=1 and provide per-dab RGBA + flow. Keep this binary-identical to
// shaders/stamp.comp AND shaders/stamp_masked.comp (both share this exact struct layout).
//
// `tipRatio` is read only by stamp_masked.comp (height/width of the tip -- see AzphaltBrush.
// tipRatio), and is ignored entirely by stamp.comp's plain round-dab path -- its default of 1.0
// (a round/square tip) is meaningful only to a stampMaskedDabs() caller, which must set it per dab
// explicitly rather than relying on this default; there is no "legacy" masked caller to preserve
// compatibility for the way `resolved` preserves stamp.comp's original five-field callers.
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
};
static_assert(sizeof(GpuDab) == 48, "GpuDab must match the shader's 3xvec4 std430 record");

// Item 15's masked/dual-brush follow-up: the secondary tip stampMaskedDabs() composites onto a
// primary dab's coverage, one entry per primary dab (same index, parallel arrays) -- see
// shaders/stamp_masked.comp's SecondaryDab struct, which this must stay binary-identical to.
// `keepInside` is pre-resolved on the host from Krita's MaskedBrushBlendMode + invert into a
// single float (>0.5 = DST_IN/keep-inside, else DST_OUT/cut) rather than re-deriving that logic
// in the shader -- see StampBrushRenderer.paintMaskedDabs' `keepInside` local for the CPU
// reference this mirrors.
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
};

struct ColorSmudgeBenchmarkInfo {
    uint32_t vendorId = 0;
    uint32_t deviceId = 0;
    uint32_t selectedTileSize = 0;
    uint64_t nanos8 = 0;
    uint64_t nanos16 = 0;
};

/**
 * Phase 3 of docs/Native Rendering Engine Design.md: a Vulkan compute engine that stamps dabs
 * onto a persistent RGBA8 layer image entirely on the GPU, using the same per-dab SRC_OVER
 * compositing and hardness/radius coverage profile as StampBrushRenderer's CPU round-tip path
 * (see stamp.comp). It owns its own headless Vulkan instance/device — it does not share a context
 * with MobileGS's GLES renderer, since compute-only Vulkan usage on API 29+ needs no window
 * surface at all; the layer image round-trips to the rest of the (CPU/GLES) pipeline via
 * readback into a caller-provided pixel buffer, matching how the CPU path already exposes its
 * result as an ARGB_8888 android.graphics.Bitmap.
 *
 * Not thread-safe: unlike MobileGS/StereoProcessor/ImageWarper, GraffitiJNI.cpp's own JNI entry
 * points for this engine do NOT take gEngineMutex or any other lock — the caller owns serializing
 * its own use of one instance (a single jlong handle) entirely on its own; that contract, not a
 * lock this class or its JNI bridge provides, is what has to make per-instance access safe.
 */
class VulkanStampEngine {
public:
    VulkanStampEngine() = default;
    ~VulkanStampEngine();

    VulkanStampEngine(const VulkanStampEngine&) = delete;
    VulkanStampEngine& operator=(const VulkanStampEngine&) = delete;

    // Creates the instance/device/pipeline and a `width`x`height` RGBA8 layer image cleared to
    // transparent black. Returns false (and leaves the engine unusable) if no Vulkan 1.1 compute
    // capable device is present, the driver rejects VK_FORMAT_R8G8B8A8_UNORM as a storage image
    // format, or shader module creation fails — the caller is expected to fall back to the CPU
    // path in any of those cases, not treat them as fatal.
    bool init(int width, int height);

    // Alternative to init(): the layer image's memory is a freshly-allocated AHardwareBuffer
    // imported via VK_ANDROID_external_memory_android_hardware_buffer instead of engine-private
    // device memory — docs/Native Rendering Engine Design.md §2's zero-copy interop. Once this
    // succeeds, hardwareBuffer() returns the same memory a stampDabs() write lands in, which the
    // JVM side can wrap as a hardware-backed android.graphics.Bitmap (Bitmap.wrapHardwareBuffer,
    // API 29+) with no CPU copy — upload()/readback() still work too, for the cases (seeding an
    // existing document's pixels, or a device where the zero-copy consumer can't be used) that
    // still need a CPU-visible round trip. Returns false — falling back to init() is expected and
    // safe — if the device lacks the AHB extension or its dependencies, the driver rejects AHB
    // import for this format/usage combination, or AHardwareBuffer_allocate itself fails.
    bool initWithHardwareBuffer(int width, int height);

    // Clears the existing layer image to transparent black without recreating the Vulkan instance,
    // device, AHardwareBuffer, descriptor set, pipeline, command pool, or staging buffers. Used when
    // a Kotlin wrapper checks a healthy engine back out of the bounded reuse pool. Synchronous like
    // upload()/readback()/stampDabs(): when it returns true the clear is complete.
    bool clear();

    // The AHardwareBuffer backing the layer image when initWithHardwareBuffer() was used, or
    // nullptr otherwise (including after plain init()). Ownership stays with this engine — a
    // caller that hands this to Java/JNI (AHardwareBuffer_toHardwareBuffer) needs its own
    // reference, which that function acquires internally; do not call AHardwareBuffer_release on
    // the pointer returned here directly, destroy() already owns that.
    struct AHardwareBuffer* hardwareBuffer() const { return hardwareBuffer_; }

    // Seeds the layer image with `inRgba8` (same width*height*4 RGBA8 layout readback() produces),
    // replacing whatever the layer currently holds. Used to prime a live-preview session with a
    // document's existing pixels before compositing new dabs on top of them — stampDabs() never
    // clears the layer itself, so without this every session would start from transparent black
    // (fine for `VulkanStampEngineSelfTest`'s throwaway canvas, wrong for painting into real
    // artwork). Returns false if the engine isn't initialized or `inSizeBytes` is too small.
    bool upload(const uint8_t* inRgba8, size_t inSizeBytes);

    // Uploads `dabs` and dispatches the compute shader to stamp them onto the layer image using
    // `colorArgb` (standard Android ARGB int) and `hardness` (0..1, brush.hardness). Composites
    // in submission order, matching the CPU path's sequential canvas.drawCircle calls. No-op
    // (returns false) if the engine failed init() or `dabs` is empty.
    bool stampDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb, float hardness);

    // shaders/stamp_masked.comp counterpart to stampDabs(): each dab samples `maskAlpha8` (an
    // R8_UNORM alpha-only tip texture, `maskWidth`x`maskHeight`, white=full coverage) in its own
    // rotated/scaled local space instead of using the round stampCoverage() falloff -- the CPU-side
    // reference this must match is StampBrushRenderer's masked-tip path (docs/Krita Brush Engine
    // Adoption.md item 15). `dabs[i].tipRatio` must be set explicitly per dab (see GpuDab's doc
    // comment); `hardness` is accepted for call-site symmetry with stampDabs() but unused -- the
    // mask texture itself already encodes the tip's edge falloff. The mask texture is re-uploaded
    // whenever `maskWidth`/`maskHeight` differ from the previous call (a new stroke's tip), and
    // reused as-is when they match (repeated calls within the same stroke). Entirely additive: owns
    // its own descriptor set/pipeline/dab buffer/mask texture, so a caller that never uses this
    // leaves stampDabs()'s resources untouched. No-op (returns false) if the engine failed init(),
    // `dabs` is empty, or `maskAlpha8` is null.
    //
    // Item 15's texture/grain follow-up: `grainAlpha8` is an optional second R8_UNORM tile
    // (`grainWidth`x`grainHeight`, pre-baked exactly like BrushTipMaskCache.grainMask on the CPU
    // side, so this only ever multiplies coverage down) sampled per-pixel and multiplied into each
    // dab's coverage before compositing -- the GPU counterpart to StampBrushRenderer.applyGrain.
    // `grainCanvasLocked` selects GrainBehavior.CANVAS_LOCKED (true) vs MOVING (false);
    // `grainScale`/`grainPhaseX`/`grainPhaseY` mirror AzphaltBrush.grainScale and the caller's
    // already-resolved per-stroke phase (grainOffsetX/Y plus any grainRandomOffsetPerStroke draw).
    // Passing `grainAlpha8 = nullptr` disables grain for this call (a 1x1 all-white dummy texture
    // is bound instead, making the shader's multiply a no-op).
    //
    // Item 15's masked/dual-brush follow-up: `secondaryDabs`, when non-empty, must be exactly
    // `dabs.size()` long (one entry per primary dab, same index -- a per-STROKE feature, not
    // per-dab optional, matching how AzphaltBrush.maskedBrush attaches a MaskDab to every dab or
    // none). `secondaryMaskAlpha8`/`secondaryMaskWidth`/`secondaryMaskHeight` are the secondary
    // tip's own R8_UNORM mask texture, same convention as `maskAlpha8`. An empty `secondaryDabs`
    // disables dual-brush compositing entirely for this call.
    bool stampMaskedDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb, float hardness,
                         const uint8_t* maskAlpha8, int maskWidth, int maskHeight,
                         const uint8_t* grainAlpha8 = nullptr, int grainWidth = 0, int grainHeight = 0,
                         bool grainCanvasLocked = false, float grainScale = 1.0f,
                         float grainPhaseX = 0.0f, float grainPhaseY = 0.0f,
                         const std::vector<GpuSecondaryDab>& secondaryDabs = {},
                         const uint8_t* secondaryMaskAlpha8 = nullptr, int secondaryMaskWidth = 0,
                         int secondaryMaskHeight = 0);

    // Ordered read/modify/write Color Smudge pass on the same persistent layer image. `mode` is
    // 0=Smear, 1=Dulling. The first dab seeds Smear's carrier; later dabs are applied sequentially.
    //
    // Item 11 (Sample Merged) follow-up: `sampleSourceRgba8`, when non-null, is a `sampleSourceWidth`
    // x `sampleSourceHeight` RGBA8 composite of the other visible layers -- the GPU counterpart to
    // ColorSmudgeEngine.apply's `sampleSource` parameter. When supplied it is uploaded once here
    // (see ensureSampleSourceTexture()'s doc comment for why this re-uploads unconditionally rather
    // than gating on a size match), and every dab in this call reads pickup from it instead of the
    // active layer -- matching the CPU reference's `readSource` vs `pixels` split exactly, see
    // color_smudge.comp's `pickedUp`/`under` split. `nullptr` (the default) disables it for this
    // call, same "null disables" optionality every other optional-texture entry point in this
    // codebase follows (stampMaskedDabs()'s grain/secondary mask).
    bool colorSmudge(const std::vector<ColorSmudgeDab>& dabs, int mode, float radiusPx,
                     float feathering, bool smearAlpha, uint32_t paintColorArgb,
                     float dilution = 0.0f, const uint8_t* sampleSourceRgba8 = nullptr,
                     int sampleSourceWidth = 0, int sampleSourceHeight = 0);
    ColorSmudgeBenchmarkInfo colorSmudgeBenchmarkInfo() const { return smudgeBenchmark_; }

    // Blocks until all dispatched work completes, then reads the layer image back into
    // `outRgba8`, which must be at least width*height*4 bytes (RGBA8, PREMULTIPLIED alpha,
    // row-major, no padding — stamp.comp/stamp_masked.comp both compute the SRC_OVER blend as
    // `dst.rgb = srcRgb*srcA + dst.rgb*(1-srcA)` with no un-premultiply before imageStore, so the
    // bytes here are premultiplied, not straight; a translucent stroke's RGB channels are darkened
    // by its own alpha). No R/B channel-order reconciliation is needed either: this happens to be
    // byte-identical to Android's RGBA_8888 layout, and every consumer in this codebase (a
    // premultiplied-by-default android.graphics.Bitmap) is a straight memcpy of this buffer with no
    // channel swap — see GraffitiJNI.cpp's readback bridge.
    //
    // Only the region tracked by dirtyOriginX_/dirtyOriginY_/dirtyWidth_/dirtyHeight_ is actually
    // copied off the GPU — everywhere else in `outRgba8` is left exactly as the caller's buffer
    // already had it. This is correct ONLY because every caller in this codebase reuses the same
    // buffer across an entire stroke's readback() calls (EditorViewModel's `work`/`stampLiveBitmap`
    // Bitmap), so untouched pixels already hold the right value from a prior readback. A caller
    // that reused a *different* buffer between calls, or expects a full-canvas snapshot from a
    // single call, would see stale/garbage data outside the dirty rect — this is a live-preview
    // primitive, not a general "give me the whole layer" accessor (that's what upload()'s inverse,
    // a full-image readback, was before this — this comment marks the contract that changed).
    // On a live brush stroke this rect is a small fraction of a large canvas (a touch sample's
    // dab batch, not the whole layer), which is the entire point: this was previously the
    // dominant per-touch-sample cost (a full-canvas GPU->CPU copy every call, independent of how
    // much actually changed) -- see docs/Native Rendering Engine Design.md §9's on-device timing.
    bool readback(uint8_t* outRgba8, size_t outCapacityBytes);

    // Releases every Vulkan resource. Safe to call multiple times; init() may be called again
    // afterward to reuse this instance for a new stroke/layer size.
    void destroy();

    bool isInitialized() const { return device_ != VK_NULL_HANDLE; }
    int width() const { return width_; }
    int height() const { return height_; }

private:
    bool createInstance();
    bool pickPhysicalDeviceAndQueueFamily();
    bool createLogicalDeviceAndQueue(const std::vector<const char*>& requiredExtensions);
    bool deviceSupportsExtensions(const std::vector<const char*>& names) const;
    bool createLayerImage(int width, int height);
    bool createLayerImageFromHardwareBuffer(int width, int height);
    bool createStagingBuffer(int width, int height);
    bool createDescriptorAndPipeline();
    bool createDabBuffer(size_t dabCount);
    bool allocateCommandBuffer();

    bool ensureMaskedPipeline();
    bool ensureMaskedDabBuffer(size_t dabCount);
    bool ensureMaskTexture(int width, int height);
    bool uploadMaskTexture(const uint8_t* alpha8, int width, int height);
    // Item 15 grain follow-up. Same shape as ensureMaskTexture()/uploadMaskTexture() (an R8_UNORM
    // sampled image, re-created only when width/height change), bound to a different descriptor
    // (binding 3) and tracked independently so mask/grain re-uploads are decided separately.
    bool ensureGrainTexture(int width, int height);
    bool uploadGrainTexture(const uint8_t* alpha8, int width, int height);
    // Item 15 masked/dual-brush follow-up. ensureSecondaryMaskTexture()/uploadSecondaryMaskTexture()
    // mirror ensureMaskTexture()/uploadMaskTexture() exactly (binding 4 instead of 2).
    // ensureSecondaryDabBuffer() mirrors ensureMaskedDabBuffer() (binding 5, GpuSecondaryDab
    // instead of GpuDab, its own staging buffer).
    bool ensureSecondaryMaskTexture(int width, int height);
    bool uploadSecondaryMaskTexture(const uint8_t* alpha8, int width, int height);
    bool ensureSecondaryDabBuffer(size_t dabCount);
    void destroyMaskedResources();

    bool ensureColorSmudgePipelines();
    bool ensureColorSmudgeCarrier(size_t pixelCount);
    bool benchmarkColorSmudge(float radiusPx);
    bool runColorSmudgePlan(const std::vector<ColorSmudgeDab>& dabs, int mode, float radiusPx,
                            float feathering, bool smearAlpha, uint32_t paintColorArgb,
                            VkPipeline pipeline, uint32_t tileSize, float dilution = 0.0f,
                            bool hasSampleMerged = false);
    // Item 11 (Sample Merged) follow-up: an RGBA8 sampled image holding the composite of other
    // visible layers, mirroring ensureGrainTexture()/uploadGrainTexture()'s structure (own sampler/
    // image/view/staging buffer, re-created only when width/height change) but for 4-byte RGBA
    // texels at binding 2 on the smudge descriptor set -- the composite must preserve full colour
    // depth, unlike grain/mask's alpha-only R8 tiles. Unlike grain/mask (stable for a whole
    // stroke), colorSmudge() re-uploads unconditionally whenever a sample source is supplied: its
    // *content* (not just its dimensions) changes every stroke.
    bool ensureSampleSourceTexture(int w, int h);
    bool uploadSampleSourceTexture(const uint8_t* rgba8, int w, int h);
    void destroyColorSmudgeResources();

    int32_t findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags properties) const;
    // Records a barrier moving layerImage_ from its current tracked layout to GENERAL, if it isn't
    // already there — a no-op after the first call, since every op (compute read/write, transfer
    // src/dst for upload()/readback()) stays in GENERAL from then on rather than juggling optimal
    // layouts per-operation. Must be called at the start of any command buffer that touches
    // layerImage_, before the actual compute dispatch or copy command.
    void ensureLayerImageGeneral(VkCommandBuffer cmd);

    // Grows [dirtyOriginX_, dirtyOriginY_, dirtyWidth_, dirtyHeight_] to also cover the given
    // layer-image-space rectangle -- a no-op if w/h <= 0 (a dispatch that touched nothing, e.g. a
    // scattered dab that drifted entirely off-canvas). Called by every writer of layerImage_ after
    // a successful write, so the accumulated rect always covers everything changed since the last
    // successful readback() -- see readback()'s own doc comment for why this exists.
    void expandDirtyRect(int32_t originX, int32_t originY, int32_t w, int32_t h);
    // Marks the whole layer dirty -- the safe/correct default for any writer that doesn't (yet)
    // track its own bounding box: init()/initWithHardwareBuffer() (freshly created, readback must
    // reflect the whole thing), upload() (replaces every pixel), clear() (same), and colorSmudge()
    // (VulkanColorSmudge.cpp; its own dab-bbox tracking is a separate, not-yet-done optimization).
    void markLayerFullyDirty();

    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t queueFamilyIndex_ = 0;

    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;

    VkImage layerImage_ = VK_NULL_HANDLE;
    VkDeviceMemory layerImageMemory_ = VK_NULL_HANDLE;
    VkImageView layerImageView_ = VK_NULL_HANDLE;
    int width_ = 0;
    int height_ = 0;
    VkImageLayout layerImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    // Non-null only when initWithHardwareBuffer() created layerImage_/layerImageMemory_ — see
    // hardwareBuffer()'s doc comment.
    struct AHardwareBuffer* hardwareBuffer_ = nullptr;

    // Host-visible staging buffer the layer image is copied into for readback(); re-created
    // alongside the layer image so its size always matches width_*height_*4.
    VkBuffer stagingBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory stagingBufferMemory_ = VK_NULL_HANDLE;

    // Device-local storage buffer holding the current stampDabs() call's dab list. Grown (never
    // shrunk) on demand — see createDabBuffer()'s capacity check.
    VkBuffer dabBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory dabBufferMemory_ = VK_NULL_HANDLE;
    size_t dabBufferCapacity_ = 0;
    // Host-visible staging buffer dabs are written into before the device-local copy — storage
    // buffers used as compute shader inputs are not guaranteed host-visible on all Vulkan
    // implementations, so this indirection is required, not an optimization.
    VkBuffer dabStagingBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory dabStagingBufferMemory_ = VK_NULL_HANDLE;

    // Lazily-created Color Smudge resources. They live on the same persistent layer image as the
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

    // Item 11 (Sample Merged) follow-up -- see ensureSampleSourceTexture()'s doc comment above.
    // Bound to a 1x1 transparent-black dummy at pipeline setup time (ensureColorSmudgePipelines())
    // so binding 2 is never left unbound even when Sample Merged is off; pc.hasSampleMerged gates
    // whether the shader ever reads it, so the dummy's content doesn't matter when it's 0.
    VkImage smudgeSampleSourceImage_ = VK_NULL_HANDLE;
    VkDeviceMemory smudgeSampleSourceImageMemory_ = VK_NULL_HANDLE;
    VkImageView smudgeSampleSourceImageView_ = VK_NULL_HANDLE;
    VkSampler smudgeSampleSourceSampler_ = VK_NULL_HANDLE;
    VkBuffer smudgeSampleSourceStagingBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory smudgeSampleSourceStagingBufferMemory_ = VK_NULL_HANDLE;
    int smudgeSampleSourceWidth_ = 0;
    int smudgeSampleSourceHeight_ = 0;
    VkImageLayout smudgeSampleSourceImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;

    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkShaderModule shaderModule_ = VK_NULL_HANDLE;

    // stampMaskedDabs() (shaders/stamp_masked.comp) resources -- entirely separate from the plain
    // round-dab resources above so this feature is purely additive: a caller that never uses
    // stampMaskedDabs() never allocates any of this. Lazily created on first use by
    // ensureMaskedPipeline()/ensureMaskedDabBuffer()/ensureMaskTexture().
    VkDescriptorSetLayout maskedDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool maskedDescriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet maskedDescriptorSet_ = VK_NULL_HANDLE;
    VkPipelineLayout maskedPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline maskedPipeline_ = VK_NULL_HANDLE;
    VkShaderModule maskedShaderModule_ = VK_NULL_HANDLE;

    // Own dab buffer (not shared with dabBuffer_/dabStagingBuffer_ above) -- keeps
    // createDabBuffer()'s existing growth/descriptor-update logic for the round-dab path
    // untouched by this addition.
    VkBuffer maskedDabBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory maskedDabBufferMemory_ = VK_NULL_HANDLE;
    size_t maskedDabBufferCapacity_ = 0;
    VkBuffer maskedDabStagingBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory maskedDabStagingBufferMemory_ = VK_NULL_HANDLE;

    // R8_UNORM alpha-only tip mask texture, re-uploaded via uploadMaskTexture() whenever
    // stampMaskedDabs() is called with different maskWidth/maskHeight than last time (a new
    // stroke's tip); reused as-is across calls within the same stroke.
    VkImage maskImage_ = VK_NULL_HANDLE;
    VkDeviceMemory maskImageMemory_ = VK_NULL_HANDLE;
    VkImageView maskImageView_ = VK_NULL_HANDLE;
    VkSampler maskSampler_ = VK_NULL_HANDLE;
    VkBuffer maskStagingBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory maskStagingBufferMemory_ = VK_NULL_HANDLE;
    int maskWidth_ = 0;
    int maskHeight_ = 0;
    VkImageLayout maskImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    // Content hash of the last-uploaded mask, since two different tips can share maskWidth_/
    // maskHeight_ (see stampMaskedDabs()'s "maskIsNew" doc comment).
    uint64_t maskContentHash_ = 0;

    // R8_UNORM grain tile texture (item 15 follow-up), independent from the mask texture above --
    // re-uploaded via uploadGrainTexture() whenever stampMaskedDabs() is called with a different
    // grainWidth/grainHeight than last time. A call with no grain uploads a 1x1 all-white dummy
    // here (see stampMaskedDabs()'s doc comment), so this is never left unbound.
    VkImage grainImage_ = VK_NULL_HANDLE;
    VkDeviceMemory grainImageMemory_ = VK_NULL_HANDLE;
    VkImageView grainImageView_ = VK_NULL_HANDLE;
    VkSampler grainSampler_ = VK_NULL_HANDLE;
    VkBuffer grainStagingBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory grainStagingBufferMemory_ = VK_NULL_HANDLE;
    int grainWidth_ = 0;
    int grainHeight_ = 0;
    VkImageLayout grainImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    // Content hash of the last-uploaded grain tile, for the same reason as maskContentHash_.
    uint64_t grainContentHash_ = 0;

    // Masked/dual-brush secondary tip (item 15 follow-up). secondaryDabBuffer_/staging mirror
    // maskedDabBuffer_/staging (binding 5, GpuSecondaryDab instead of GpuDab); secondaryMaskImage_
    // etc. mirror maskImage_ (binding 4). A call with no dual-brush config uploads a 1x1 dummy mask
    // and a single dummy dab (keepInside doesn't matter -- the shader never reads index >= dabCount
    // and pc.hasSecondary gates whether secondaryDabs[] is read at all), so these bindings are
    // never left pointing at nothing once the pipeline exists.
    VkBuffer secondaryDabBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory secondaryDabBufferMemory_ = VK_NULL_HANDLE;
    size_t secondaryDabBufferCapacity_ = 0;
    VkBuffer secondaryDabStagingBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory secondaryDabStagingBufferMemory_ = VK_NULL_HANDLE;

    VkImage secondaryMaskImage_ = VK_NULL_HANDLE;
    VkDeviceMemory secondaryMaskImageMemory_ = VK_NULL_HANDLE;
    VkImageView secondaryMaskImageView_ = VK_NULL_HANDLE;
    VkSampler secondaryMaskSampler_ = VK_NULL_HANDLE;
    VkBuffer secondaryMaskStagingBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory secondaryMaskStagingBufferMemory_ = VK_NULL_HANDLE;
    int secondaryMaskWidth_ = 0;
    int secondaryMaskHeight_ = 0;
    VkImageLayout secondaryMaskImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    // Content hash of the last-uploaded secondary mask, for the same reason as maskContentHash_.
    uint64_t secondaryMaskContentHash_ = 0;

    // Bounding box (layer-image pixel coordinates, [origin, origin+extent)) of everything written
    // to layerImage_ since the last successful readback() -- see readback()'s doc comment. A
    // width/height of 0 means nothing is outstanding (a readback right now would be a no-op).
    int32_t dirtyOriginX_ = 0;
    int32_t dirtyOriginY_ = 0;
    int32_t dirtyWidth_ = 0;
    int32_t dirtyHeight_ = 0;
};

}  // namespace graffux
