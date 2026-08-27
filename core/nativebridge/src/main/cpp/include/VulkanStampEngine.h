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
// shaders/stamp.comp.
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
    float pad0 = 0.0f;
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
 * Not thread-safe: callers serialize access the same way GraffitiJNI.cpp already serializes
 * MobileGS/StereoProcessor/ImageWarper access (see gEngineMutex).
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

    // Blocks until all dispatched work completes, then reads the layer image back into
    // `outRgba8`, which must be at least width*height*4 bytes (RGBA8, straight alpha, row-major,
    // no padding — the same layout AndroidBitmap_lockPixels hands back for ARGB_8888, modulo the
    // R/B channel order the caller is responsible for reconciling, since this engine works in
    // RGBA to match the shader's `vec4`/imageStore convention rather than Android's packed ARGB).
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

    int32_t findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags properties) const;
    // Records a barrier moving layerImage_ from its current tracked layout to GENERAL, if it isn't
    // already there — a no-op after the first call, since every op (compute read/write, transfer
    // src/dst for upload()/readback()) stays in GENERAL from then on rather than juggling optimal
    // layouts per-operation. Must be called at the start of any command buffer that touches
    // layerImage_, before the actual compute dispatch or copy command.
    void ensureLayerImageGeneral(VkCommandBuffer cmd);

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

    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkShaderModule shaderModule_ = VK_NULL_HANDLE;
};

}  // namespace graffux
