// FILE: core/nativebridge/src/main/cpp/include/VulkanStampEngine.h
#pragma once

#include <cstdint>
#include <vector>

#include <vulkan/vulkan.h>

namespace graffux {

// One dab, laid out to match BrushStamps.Dab (x, y, radius, alpha, angleDeg) plus padding to a
// 32-byte stride — see shaders/stamp.comp's `Dab` struct, which this must stay binary-identical
// to (std430 layout rules pad a 5-float struct to 8 floats / 32 bytes regardless, so the padding
// here just makes that explicit instead of relying on the compiler to insert it invisibly).
struct GpuDab {
    float x;
    float y;
    float radius;
    float alpha;
    float angleDeg;
    float pad0;
    float pad1;
    float pad2;
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

private:
    bool createInstance();
    bool pickPhysicalDeviceAndQueueFamily();
    bool createLogicalDeviceAndQueue();
    bool createLayerImage(int width, int height);
    bool createDescriptorAndPipeline();
    bool createDabBuffer(size_t dabCount);
    bool allocateCommandBuffer();

    int32_t findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags properties) const;

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
