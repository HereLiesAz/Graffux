// FILE: core/nativebridge/src/main/cpp/VulkanStampEngine.cpp
#include "include/VulkanStampEngine.h"

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>

#include "StampSpv.h"
#include "StampMaskedSpv.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "VulkanStampEngine", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VulkanStampEngine", __VA_ARGS__)

namespace graffux {

namespace {

// A pooled VulkanStampEngine reuses maskImage_/grainImage_/secondaryMaskImage_ across strokes and
// across brushes; dimensions alone don't identify content, so two different tip/grain bitmaps
// that happen to share a size (common -- built-in tips and grain tiles are normalized to a
// handful of standard sizes) would otherwise be treated as "unchanged" and the second one's pixels
// would never actually upload, silently stamping with the first one's stale texture. FNV-1a over
// the raw bytes is cheap (these buffers are at most a few KB) and collision-safe enough for a
// same-process staleness check.
uint64_t fnv1a(const uint8_t* data, size_t length) {
    uint64_t hash = 0xcbf29ce484222325ULL;
    for (size_t i = 0; i < length; ++i) {
        hash ^= data[i];
        hash *= 0x100000001b3ULL;
    }
    return hash;
}

// Push constants for stamp.comp — layout and field order must match the shader's
// `layout(push_constant) uniform PushConstants` block exactly (std430 scalar packing: all four
// members are 4 bytes, so no padding is inserted between them).
struct PushConstants {
    uint32_t dabCount;
    float hardness;
    float colorR;
    float colorG;
    float colorB;
    float baseAlpha;
    int32_t originX;
    int32_t originY;
};

// Push constants for stamp_masked.comp -- same first 8 fields as PushConstants above (kept
// byte-identical so the two structs stay easy to compare), plus item 15's grain follow-up fields.
struct MaskedPushConstants {
    uint32_t dabCount;
    float hardness;
    float colorR;
    float colorG;
    float colorB;
    float baseAlpha;
    int32_t originX;
    int32_t originY;
    float grainCanvasLocked;
    float grainScale;
    float grainPhaseX;
    float grainPhaseY;
    float hasSecondary;
};

bool checkResult(VkResult result, const char* what) {
    if (result != VK_SUCCESS) {
        LOGE("%s failed: VkResult=%d", what, static_cast<int>(result));
        return false;
    }
    return true;
}

}  // namespace

VulkanStampEngine::~VulkanStampEngine() { destroy(); }

bool VulkanStampEngine::init(int width, int height) {
    if (device_ != VK_NULL_HANDLE) destroy();
    width_ = width;
    height_ = height;

    if (!createInstance()) { destroy(); return false; }
    if (!pickPhysicalDeviceAndQueueFamily()) { destroy(); return false; }
    if (!createLogicalDeviceAndQueue({})) { destroy(); return false; }
    if (!createLayerImage(width, height)) { destroy(); return false; }
    if (!createDescriptorAndPipeline()) { destroy(); return false; }
    if (!allocateCommandBuffer()) { destroy(); return false; }

    LOGI("VulkanStampEngine initialized: %dx%d layer", width, height);
    return true;
}

bool VulkanStampEngine::initWithHardwareBuffer(int width, int height) {
    if (device_ != VK_NULL_HANDLE) destroy();
    width_ = width;
    height_ = height;

    if (!createInstance()) { destroy(); return false; }
    if (!pickPhysicalDeviceAndQueueFamily()) { destroy(); return false; }
    // VK_ANDROID_external_memory_android_hardware_buffer's own spec-mandated dependencies. All are
    // core in Vulkan 1.1 (this engine's apiVersion) EXCEPT VK_KHR_sampler_ycbcr_conversion, which
    // 1.1 made an optional core FEATURE rather than something automatically enabled — it still has
    // to be requested as a device extension here regardless of API version.
    static const std::vector<const char*> kAhbExtensions = {
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
        VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME,
        VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
    };
    if (!createLogicalDeviceAndQueue(kAhbExtensions)) { destroy(); return false; }
    if (!createLayerImageFromHardwareBuffer(width, height)) { destroy(); return false; }
    if (!createDescriptorAndPipeline()) { destroy(); return false; }
    if (!allocateCommandBuffer()) { destroy(); return false; }

    LOGI("VulkanStampEngine initialized (AHardwareBuffer-backed): %dx%d layer", width, height);
    return true;
}

bool VulkanStampEngine::createInstance() {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "graffux-stamp-engine";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "graffux";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    // Compute-only: no VK_KHR_surface/VK_KHR_android_surface needed, so no window/display
    // dependency and no Activity/Surface handoff required to use this engine.
    appInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    return checkResult(vkCreateInstance(&createInfo, nullptr, &instance_), "vkCreateInstance");
}

bool VulkanStampEngine::pickPhysicalDeviceAndQueueFamily() {
    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(instance_, &deviceCount, nullptr);
    if (deviceCount == 0) {
        LOGE("No Vulkan physical devices found");
        return false;
    }
    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(instance_, &deviceCount, devices.data());

    for (VkPhysicalDevice candidate : devices) {
        uint32_t queueFamilyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &queueFamilyCount, nullptr);
        std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &queueFamilyCount, queueFamilies.data());

        for (uint32_t i = 0; i < queueFamilyCount; i++) {
            if (queueFamilies[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
                physicalDevice_ = candidate;
                queueFamilyIndex_ = i;
                return true;
            }
        }
    }

    LOGE("No physical device exposes a compute-capable queue family");
    return false;
}

bool VulkanStampEngine::createLogicalDeviceAndQueue(const std::vector<const char*>& requiredExtensions) {
    if (!requiredExtensions.empty() && !deviceSupportsExtensions(requiredExtensions)) {
        return false;
    }

    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueCreateInfo{};
    queueCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueCreateInfo.queueFamilyIndex = queueFamilyIndex_;
    queueCreateInfo.queueCount = 1;
    queueCreateInfo.pQueuePriorities = &queuePriority;

    VkDeviceCreateInfo deviceCreateInfo{};
    deviceCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceCreateInfo.queueCreateInfoCount = 1;
    deviceCreateInfo.pQueueCreateInfos = &queueCreateInfo;
    deviceCreateInfo.enabledExtensionCount = static_cast<uint32_t>(requiredExtensions.size());
    deviceCreateInfo.ppEnabledExtensionNames = requiredExtensions.empty() ? nullptr : requiredExtensions.data();

    if (!checkResult(vkCreateDevice(physicalDevice_, &deviceCreateInfo, nullptr, &device_),
                      "vkCreateDevice")) {
        return false;
    }
    vkGetDeviceQueue(device_, queueFamilyIndex_, 0, &queue_);
    return true;
}

bool VulkanStampEngine::deviceSupportsExtensions(const std::vector<const char*>& names) const {
    uint32_t count = 0;
    vkEnumerateDeviceExtensionProperties(physicalDevice_, nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> available(count);
    vkEnumerateDeviceExtensionProperties(physicalDevice_, nullptr, &count, available.data());
    for (const char* name : names) {
        bool found = false;
        for (const auto& ext : available) {
            if (std::strcmp(ext.extensionName, name) == 0) { found = true; break; }
        }
        if (!found) {
            LOGE("Required device extension not supported: %s", name);
            return false;
        }
    }
    return true;
}

int32_t VulkanStampEngine::findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags properties) const {
    VkPhysicalDeviceMemoryProperties memProperties;
    vkGetPhysicalDeviceMemoryProperties(physicalDevice_, &memProperties);
    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((typeBits & (1u << i)) &&
            (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
            return static_cast<int32_t>(i);
        }
    }
    return -1;
}

bool VulkanStampEngine::createLayerImage(int width, int height) {
    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.extent = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    // STORAGE for the shader's imageLoad/imageStore, TRANSFER_SRC so readback() can copy it out
    // via a staging buffer (optimal-tiling images can't be mapped directly).
    imageInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                       VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (!checkResult(vkCreateImage(device_, &imageInfo, nullptr, &layerImage_), "vkCreateImage")) {
        return false;
    }

    VkMemoryRequirements memReq;
    vkGetImageMemoryRequirements(device_, layerImage_, &memReq);
    int32_t memType = findMemoryType(memReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType < 0) { LOGE("No device-local memory type for layer image"); return false; }

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = static_cast<uint32_t>(memType);
    if (!checkResult(vkAllocateMemory(device_, &allocInfo, nullptr, &layerImageMemory_),
                      "vkAllocateMemory(layerImage)")) {
        return false;
    }
    if (!checkResult(vkBindImageMemory(device_, layerImage_, layerImageMemory_, 0),
                      "vkBindImageMemory(layerImage)")) {
        return false;
    }

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = layerImage_;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (!checkResult(vkCreateImageView(device_, &viewInfo, nullptr, &layerImageView_),
                      "vkCreateImageView")) {
        return false;
    }

    layerImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    return createStagingBuffer(width, height);
}

// Shared by createLayerImage() (engine-private layer memory) and
// createLayerImageFromHardwareBuffer() (imported AHardwareBuffer memory) — readback()/upload()
// round-trip through this same host-visible buffer regardless of which one backs layerImage_,
// so both paths need it created identically.
bool VulkanStampEngine::createStagingBuffer(int width, int height) {
    VkDeviceSize stagingSize = static_cast<VkDeviceSize>(width) * height * 4;
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = stagingSize;
    bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (!checkResult(vkCreateBuffer(device_, &bufferInfo, nullptr, &stagingBuffer_),
                      "vkCreateBuffer(staging)")) {
        return false;
    }
    VkMemoryRequirements stagingMemReq;
    vkGetBufferMemoryRequirements(device_, stagingBuffer_, &stagingMemReq);
    int32_t stagingMemType = findMemoryType(
        stagingMemReq.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (stagingMemType < 0) { LOGE("No host-visible memory type for staging buffer"); return false; }
    VkMemoryAllocateInfo stagingAllocInfo{};
    stagingAllocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    stagingAllocInfo.allocationSize = stagingMemReq.size;
    stagingAllocInfo.memoryTypeIndex = static_cast<uint32_t>(stagingMemType);
    if (!checkResult(vkAllocateMemory(device_, &stagingAllocInfo, nullptr, &stagingBufferMemory_),
                      "vkAllocateMemory(staging)")) {
        return false;
    }
    if (!checkResult(vkBindBufferMemory(device_, stagingBuffer_, stagingBufferMemory_, 0),
                      "vkBindBufferMemory(staging)")) {
        return false;
    }
    return true;
}

// docs/Native Rendering Engine Design.md §2's zero-copy interop: imports a freshly-allocated
// AHardwareBuffer as layerImage_'s backing memory (VK_ANDROID_external_memory_android_hardware_
// buffer) instead of engine-private device memory. Once bound, layerImage_/layerImageView_ are
// used identically to the plain init() path — stampDabs()/readback()/upload() need no awareness
// of which one is in effect — the only difference is hardwareBuffer_ being non-null afterward, so
// hardwareBuffer() can hand the SAME memory a compositing GPU write lands in to the JVM side for a
// zero-copy display (e.g. Bitmap.wrapHardwareBuffer), without a CPU round trip through readback().
bool VulkanStampEngine::createLayerImageFromHardwareBuffer(int width, int height) {
    layerImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;

    AHardwareBuffer_Desc desc{};
    desc.width = static_cast<uint32_t>(width);
    desc.height = static_cast<uint32_t>(height);
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    // GPU_COLOR_OUTPUT: this engine's compute shader writes it via imageStore (Vulkan treats a
    // storage-image write the same as a color-attachment write for AHB usage-flag purposes).
    // GPU_SAMPLED_IMAGE: so a future GL/presentation consumer can sample it directly.
    // CPU_READ_RARELY: upload()/readback() still work through the CPU staging path when needed
    // (e.g. the very first seed of an existing document's pixels), just not on every frame.
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                 AHARDWAREBUFFER_USAGE_CPU_READ_RARELY;
    desc.stride = 0;
    desc.rfu0 = 0;
    desc.rfu1 = 0;
    if (AHardwareBuffer_allocate(&desc, &hardwareBuffer_) != 0) {
        LOGE("AHardwareBuffer_allocate failed for %dx%d", width, height);
        hardwareBuffer_ = nullptr;
        return false;
    }

    // Loaded via vkGetDeviceProcAddr rather than linked directly: this app's minSdk (26) links
    // against an older platform's libvulkan.so loader stub that doesn't export this symbol at all
    // (confirmed by an actual link failure against the real build target, not a theoretical
    // concern) — direct linkage would make the WHOLE app fail to build. Standard Vulkan practice
    // for any extension function is to resolve it dynamically rather than link it, precisely
    // because loader stub coverage varies by platform version; a null result here just means this
    // device/NDK-linkage combination doesn't have it, which is exactly the "fall back to init()"
    // case initWithHardwareBuffer() already documents.
    auto pfnGetProps = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        vkGetDeviceProcAddr(device_, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    if (!pfnGetProps) {
        LOGE("vkGetAndroidHardwareBufferPropertiesANDROID not available on this device/loader");
        AHardwareBuffer_release(hardwareBuffer_);
        hardwareBuffer_ = nullptr;
        return false;
    }

    VkAndroidHardwareBufferFormatPropertiesANDROID formatProps{};
    formatProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID bufferProps{};
    bufferProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    bufferProps.pNext = &formatProps;
    if (!checkResult(pfnGetProps(device_, hardwareBuffer_, &bufferProps),
                      "vkGetAndroidHardwareBufferPropertiesANDROID")) {
        AHardwareBuffer_release(hardwareBuffer_);
        hardwareBuffer_ = nullptr;
        return false;
    }

    VkExternalMemoryImageCreateInfo extImageInfo{};
    extImageInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    extImageInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    // AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM is a standard (non-external-only) format, so the
    // driver reports a real VkFormat here — VK_FORMAT_R8G8B8A8_UNORM in practice — rather than
    // requiring the VK_ANDROID_external_format/sampler-Ycbcr-conversion path a YUV camera buffer
    // would need. Falling back to the same format the plain init() path hard-codes keeps this
    // engine on one code path if a driver ever reports VK_FORMAT_UNDEFINED here regardless.
    VkFormat format = formatProps.format != VK_FORMAT_UNDEFINED ? formatProps.format : VK_FORMAT_R8G8B8A8_UNORM;

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.pNext = &extImageInfo;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = format;
    imageInfo.extent = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                       VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (!checkResult(vkCreateImage(device_, &imageInfo, nullptr, &layerImage_), "vkCreateImage(AHB)")) {
        AHardwareBuffer_release(hardwareBuffer_);
        hardwareBuffer_ = nullptr;
        return false;
    }

    VkImportAndroidHardwareBufferInfoANDROID importInfo{};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.buffer = hardwareBuffer_;

    VkMemoryDedicatedAllocateInfo dedicatedInfo{};
    dedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicatedInfo.pNext = &importInfo;
    dedicatedInfo.image = layerImage_;

    int32_t memType = findMemoryType(bufferProps.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType < 0) memType = findMemoryType(bufferProps.memoryTypeBits, 0);
    if (memType < 0) {
        LOGE("No memory type compatible with the imported AHardwareBuffer");
        vkDestroyImage(device_, layerImage_, nullptr);
        layerImage_ = VK_NULL_HANDLE;
        AHardwareBuffer_release(hardwareBuffer_);
        hardwareBuffer_ = nullptr;
        return false;
    }

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.pNext = &dedicatedInfo;
    allocInfo.allocationSize = bufferProps.allocationSize;
    allocInfo.memoryTypeIndex = static_cast<uint32_t>(memType);
    if (!checkResult(vkAllocateMemory(device_, &allocInfo, nullptr, &layerImageMemory_), "vkAllocateMemory(AHB)")) {
        vkDestroyImage(device_, layerImage_, nullptr);
        layerImage_ = VK_NULL_HANDLE;
        AHardwareBuffer_release(hardwareBuffer_);
        hardwareBuffer_ = nullptr;
        return false;
    }
    if (!checkResult(vkBindImageMemory(device_, layerImage_, layerImageMemory_, 0), "vkBindImageMemory(AHB)")) {
        vkFreeMemory(device_, layerImageMemory_, nullptr);
        layerImageMemory_ = VK_NULL_HANDLE;
        vkDestroyImage(device_, layerImage_, nullptr);
        layerImage_ = VK_NULL_HANDLE;
        AHardwareBuffer_release(hardwareBuffer_);
        hardwareBuffer_ = nullptr;
        return false;
    }

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = layerImage_;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = format;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (!checkResult(vkCreateImageView(device_, &viewInfo, nullptr, &layerImageView_), "vkCreateImageView(AHB)")) {
        vkFreeMemory(device_, layerImageMemory_, nullptr);
        layerImageMemory_ = VK_NULL_HANDLE;
        vkDestroyImage(device_, layerImage_, nullptr);
        layerImage_ = VK_NULL_HANDLE;
        AHardwareBuffer_release(hardwareBuffer_);
        hardwareBuffer_ = nullptr;
        return false;
    }

    return createStagingBuffer(width, height);
}

bool VulkanStampEngine::createDescriptorAndPipeline() {
    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = kStampCompSpvWords * sizeof(uint32_t);
    shaderInfo.pCode = kStampCompSpv;
    if (!checkResult(vkCreateShaderModule(device_, &shaderInfo, nullptr, &shaderModule_),
                      "vkCreateShaderModule")) {
        return false;
    }

    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 2;
    layoutInfo.pBindings = bindings;
    if (!checkResult(vkCreateDescriptorSetLayout(device_, &layoutInfo, nullptr, &descriptorSetLayout_),
                      "vkCreateDescriptorSetLayout")) {
        return false;
    }

    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.offset = 0;
    pushRange.size = sizeof(PushConstants);

    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &descriptorSetLayout_;
    pipelineLayoutInfo.pushConstantRangeCount = 1;
    pipelineLayoutInfo.pPushConstantRanges = &pushRange;
    if (!checkResult(vkCreatePipelineLayout(device_, &pipelineLayoutInfo, nullptr, &pipelineLayout_),
                      "vkCreatePipelineLayout")) {
        return false;
    }

    VkPipelineShaderStageCreateInfo stageInfo{};
    stageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stageInfo.module = shaderModule_;
    stageInfo.pName = "main";

    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage = stageInfo;
    pipelineInfo.layout = pipelineLayout_;
    if (!checkResult(
            vkCreateComputePipelines(device_, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &pipeline_),
            "vkCreateComputePipelines")) {
        return false;
    }

    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = 1;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 1;

    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    if (!checkResult(vkCreateDescriptorPool(device_, &poolInfo, nullptr, &descriptorPool_),
                      "vkCreateDescriptorPool")) {
        return false;
    }

    VkDescriptorSetAllocateInfo dsAllocInfo{};
    dsAllocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsAllocInfo.descriptorPool = descriptorPool_;
    dsAllocInfo.descriptorSetCount = 1;
    dsAllocInfo.pSetLayouts = &descriptorSetLayout_;
    if (!checkResult(vkAllocateDescriptorSets(device_, &dsAllocInfo, &descriptorSet_),
                      "vkAllocateDescriptorSets")) {
        return false;
    }

    // The dab buffer (binding 0) is (re)written per stampDabs() call by createDabBuffer(), which
    // also updates this descriptor set's binding 0 write once the buffer exists. Binding 1 (the
    // layer image) is stable for this engine's lifetime, so it's written once, here.
    VkDescriptorImageInfo imageInfo{};
    imageInfo.imageView = layerImageView_;
    imageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkWriteDescriptorSet imageWrite{};
    imageWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    imageWrite.dstSet = descriptorSet_;
    imageWrite.dstBinding = 1;
    imageWrite.descriptorCount = 1;
    imageWrite.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    imageWrite.pImageInfo = &imageInfo;
    vkUpdateDescriptorSets(device_, 1, &imageWrite, 0, nullptr);

    return true;
}

bool VulkanStampEngine::createDabBuffer(size_t dabCount) {
    if (dabCount <= dabBufferCapacity_ && dabBuffer_ != VK_NULL_HANDLE) return true;

    // Every handle is nulled out immediately after being freed/failing — createDabBuffer() can be
    // re-entered on a later call (a bigger stroke) or the whole engine can be destroy()ed after a
    // failure here, and either path frees these same fields again; a stale non-null handle left
    // behind after this function already freed or never successfully created it is a double-free.
    if (dabBuffer_ != VK_NULL_HANDLE) {
        vkDestroyBuffer(device_, dabBuffer_, nullptr);
        dabBuffer_ = VK_NULL_HANDLE;
    }
    if (dabBufferMemory_ != VK_NULL_HANDLE) {
        vkFreeMemory(device_, dabBufferMemory_, nullptr);
        dabBufferMemory_ = VK_NULL_HANDLE;
    }
    if (dabStagingBuffer_ != VK_NULL_HANDLE) {
        vkDestroyBuffer(device_, dabStagingBuffer_, nullptr);
        dabStagingBuffer_ = VK_NULL_HANDLE;
    }
    if (dabStagingBufferMemory_ != VK_NULL_HANDLE) {
        vkFreeMemory(device_, dabStagingBufferMemory_, nullptr);
        dabStagingBufferMemory_ = VK_NULL_HANDLE;
    }
    dabBufferCapacity_ = 0;

    // Grow with headroom so a stroke whose dab count fluctuates near a boundary doesn't
    // reallocate on every single call.
    size_t newCapacity = dabCount + dabCount / 2 + 16;
    VkDeviceSize bufferSize = static_cast<VkDeviceSize>(newCapacity) * sizeof(GpuDab);

    VkBufferCreateInfo deviceBufferInfo{};
    deviceBufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    deviceBufferInfo.size = bufferSize;
    deviceBufferInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    deviceBufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (!checkResult(vkCreateBuffer(device_, &deviceBufferInfo, nullptr, &dabBuffer_),
                      "vkCreateBuffer(dabBuffer)")) {
        dabBuffer_ = VK_NULL_HANDLE;  // vkCreateBuffer leaves the handle unmodified on failure.
        return false;
    }
    VkMemoryRequirements memReq;
    vkGetBufferMemoryRequirements(device_, dabBuffer_, &memReq);
    int32_t memType = findMemoryType(memReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType < 0) {
        LOGE("No device-local memory type for dab buffer");
        vkDestroyBuffer(device_, dabBuffer_, nullptr);
        dabBuffer_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = static_cast<uint32_t>(memType);
    if (!checkResult(vkAllocateMemory(device_, &allocInfo, nullptr, &dabBufferMemory_),
                      "vkAllocateMemory(dabBuffer)")) {
        dabBufferMemory_ = VK_NULL_HANDLE;
        vkDestroyBuffer(device_, dabBuffer_, nullptr);
        dabBuffer_ = VK_NULL_HANDLE;
        return false;
    }
    if (!checkResult(vkBindBufferMemory(device_, dabBuffer_, dabBufferMemory_, 0),
                      "vkBindBufferMemory(dabBuffer)")) {
        vkDestroyBuffer(device_, dabBuffer_, nullptr); dabBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, dabBufferMemory_, nullptr); dabBufferMemory_ = VK_NULL_HANDLE;
        return false;
    }

    VkBufferCreateInfo stagingBufferInfo{};
    stagingBufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    stagingBufferInfo.size = bufferSize;
    stagingBufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    stagingBufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (!checkResult(vkCreateBuffer(device_, &stagingBufferInfo, nullptr, &dabStagingBuffer_),
                      "vkCreateBuffer(dabStaging)")) {
        dabStagingBuffer_ = VK_NULL_HANDLE;
        vkDestroyBuffer(device_, dabBuffer_, nullptr); dabBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, dabBufferMemory_, nullptr); dabBufferMemory_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryRequirements stagingMemReq;
    vkGetBufferMemoryRequirements(device_, dabStagingBuffer_, &stagingMemReq);
    int32_t stagingMemType = findMemoryType(
        stagingMemReq.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (stagingMemType < 0) {
        LOGE("No host-visible memory type for dab staging buffer");
        vkDestroyBuffer(device_, dabStagingBuffer_, nullptr); dabStagingBuffer_ = VK_NULL_HANDLE;
        vkDestroyBuffer(device_, dabBuffer_, nullptr); dabBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, dabBufferMemory_, nullptr); dabBufferMemory_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryAllocateInfo stagingAllocInfo{};
    stagingAllocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    stagingAllocInfo.allocationSize = stagingMemReq.size;
    stagingAllocInfo.memoryTypeIndex = static_cast<uint32_t>(stagingMemType);
    if (!checkResult(vkAllocateMemory(device_, &stagingAllocInfo, nullptr, &dabStagingBufferMemory_),
                      "vkAllocateMemory(dabStaging)")) {
        dabStagingBufferMemory_ = VK_NULL_HANDLE;
        vkDestroyBuffer(device_, dabStagingBuffer_, nullptr); dabStagingBuffer_ = VK_NULL_HANDLE;
        vkDestroyBuffer(device_, dabBuffer_, nullptr); dabBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, dabBufferMemory_, nullptr); dabBufferMemory_ = VK_NULL_HANDLE;
        return false;
    }
    if (!checkResult(vkBindBufferMemory(device_, dabStagingBuffer_, dabStagingBufferMemory_, 0),
                      "vkBindBufferMemory(dabStaging)")) {
        vkDestroyBuffer(device_, dabStagingBuffer_, nullptr); dabStagingBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, dabStagingBufferMemory_, nullptr); dabStagingBufferMemory_ = VK_NULL_HANDLE;
        vkDestroyBuffer(device_, dabBuffer_, nullptr); dabBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, dabBufferMemory_, nullptr); dabBufferMemory_ = VK_NULL_HANDLE;
        return false;
    }

    dabBufferCapacity_ = newCapacity;

    VkDescriptorBufferInfo bufInfo{};
    bufInfo.buffer = dabBuffer_;
    bufInfo.offset = 0;
    bufInfo.range = VK_WHOLE_SIZE;

    VkWriteDescriptorSet bufWrite{};
    bufWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    bufWrite.dstSet = descriptorSet_;
    bufWrite.dstBinding = 0;
    bufWrite.descriptorCount = 1;
    bufWrite.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bufWrite.pBufferInfo = &bufInfo;
    vkUpdateDescriptorSets(device_, 1, &bufWrite, 0, nullptr);

    return true;
}

bool VulkanStampEngine::allocateCommandBuffer() {
    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = queueFamilyIndex_;
    if (!checkResult(vkCreateCommandPool(device_, &poolInfo, nullptr, &commandPool_),
                      "vkCreateCommandPool")) {
        return false;
    }

    VkCommandBufferAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocInfo.commandPool = commandPool_;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;
    if (!checkResult(vkAllocateCommandBuffers(device_, &allocInfo, &commandBuffer_),
                      "vkAllocateCommandBuffers")) {
        return false;
    }

    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    return checkResult(vkCreateFence(device_, &fenceInfo, nullptr, &fence_), "vkCreateFence");
}

void VulkanStampEngine::ensureLayerImageGeneral(VkCommandBuffer cmd) {
    if (layerImageLayout_ == VK_IMAGE_LAYOUT_GENERAL) return;
    VkImageMemoryBarrier toGeneral{};
    toGeneral.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toGeneral.oldLayout = layerImageLayout_;
    toGeneral.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    // Coming from UNDEFINED (the only other state this ever tracks) there's nothing to flush, so
    // srcAccessMask 0 is correct — this only ever runs once per init(), the image's first use.
    toGeneral.srcAccessMask = 0;
    toGeneral.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;  // the clear below writes via TRANSFER.
    toGeneral.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toGeneral.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toGeneral.image = layerImage_;
    toGeneral.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                          0, nullptr, 0, nullptr, 1, &toGeneral);

    // A freshly created VkImage's contents are genuinely undefined, not "transparent" — VK_IMAGE_
    // LAYOUT_UNDEFINED describes the layout, not the pixel values, and nothing before this point
    // ever wrote to it. Without this clear, the FIRST stampDabs()/readback() call on an engine
    // that skipped upload() (VulkanStampEngineSelfTest, or a fresh round-tip stroke over an empty
    // layer) would composite onto — or read back — driver/GPU-memory garbage instead of the
    // transparent black every doc comment on this class promises.
    VkClearColorValue clearColor{};
    clearColor.float32[0] = clearColor.float32[1] = clearColor.float32[2] = clearColor.float32[3] = 0.0f;
    VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdClearColorImage(cmd, layerImage_, VK_IMAGE_LAYOUT_GENERAL, &clearColor, 1, &range);

    VkImageMemoryBarrier afterClear{};
    afterClear.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    afterClear.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    afterClear.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    afterClear.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    afterClear.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT |
                                VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    afterClear.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    afterClear.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    afterClear.image = layerImage_;
    afterClear.subresourceRange = range;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0,
                          0, nullptr, 0, nullptr, 1, &afterClear);

    layerImageLayout_ = VK_IMAGE_LAYOUT_GENERAL;
}

bool VulkanStampEngine::upload(const uint8_t* inRgba8, size_t inSizeBytes) {
    if (!isInitialized()) return false;
    size_t requiredBytes = static_cast<size_t>(width_) * height_ * 4;
    if (inSizeBytes < requiredBytes) {
        LOGE("upload buffer too small: need %zu, got %zu", requiredBytes, inSizeBytes);
        return false;
    }

    void* mapped = nullptr;
    if (!checkResult(vkMapMemory(device_, stagingBufferMemory_, 0, requiredBytes, 0, &mapped),
                      "vkMapMemory(upload)")) {
        return false;
    }
    std::memcpy(mapped, inRgba8, requiredBytes);
    vkUnmapMemory(device_, stagingBufferMemory_);

    if (!checkResult(vkResetCommandBuffer(commandBuffer_, 0), "vkResetCommandBuffer")) {
        return false;
    }
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (!checkResult(vkBeginCommandBuffer(commandBuffer_, &beginInfo), "vkBeginCommandBuffer(upload)")) {
        return false;
    }

    ensureLayerImageGeneral(commandBuffer_);

    VkBufferImageCopy region{};
    region.bufferOffset = 0;
    region.bufferRowLength = 0;
    region.bufferImageHeight = 0;
    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.imageOffset = {0, 0, 0};
    region.imageExtent = {static_cast<uint32_t>(width_), static_cast<uint32_t>(height_), 1};
    // GENERAL is a valid layout for a transfer destination too, so no further transition is needed
    // beyond ensureLayerImageGeneral() above — see stampDabs()'s and readback()'s doc comments for
    // why this engine keeps the layer permanently in GENERAL rather than juggling per-op layouts.
    vkCmdCopyBufferToImage(commandBuffer_, stagingBuffer_, layerImage_, VK_IMAGE_LAYOUT_GENERAL, 1,
                            &region);

    VkImageMemoryBarrier toShaderReadable{};
    toShaderReadable.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toShaderReadable.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    toShaderReadable.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    toShaderReadable.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toShaderReadable.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    toShaderReadable.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toShaderReadable.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toShaderReadable.image = layerImage_;
    toShaderReadable.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                          VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                          &toShaderReadable);

    if (!checkResult(vkEndCommandBuffer(commandBuffer_), "vkEndCommandBuffer(upload)")) return false;

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer_;

    vkResetFences(device_, 1, &fence_);
    if (!checkResult(vkQueueSubmit(queue_, 1, &submitInfo, fence_), "vkQueueSubmit(upload)")) return false;
    return checkResult(vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX),
                        "vkWaitForFences(upload)");
}

bool VulkanStampEngine::stampDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb, float hardness) {
    if (!isInitialized() || dabs.empty()) return false;
    if (!createDabBuffer(dabs.size())) return false;

    void* mapped = nullptr;
    VkDeviceSize uploadSize = dabs.size() * sizeof(GpuDab);
    if (!checkResult(vkMapMemory(device_, dabStagingBufferMemory_, 0, uploadSize, 0, &mapped),
                      "vkMapMemory(dabStaging)")) {
        return false;
    }
    std::memcpy(mapped, dabs.data(), uploadSize);
    vkUnmapMemory(device_, dabStagingBufferMemory_);

    if (!checkResult(vkResetCommandBuffer(commandBuffer_, 0), "vkResetCommandBuffer")) {
        return false;
    }
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (!checkResult(vkBeginCommandBuffer(commandBuffer_, &beginInfo), "vkBeginCommandBuffer")) {
        return false;
    }

    VkBufferCopy copyRegion{0, 0, uploadSize};
    vkCmdCopyBuffer(commandBuffer_, dabStagingBuffer_, dabBuffer_, 1, &copyRegion);

    VkBufferMemoryBarrier bufBarrier{};
    bufBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    bufBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    bufBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    bufBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    bufBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    bufBarrier.buffer = dabBuffer_;
    bufBarrier.offset = 0;
    bufBarrier.size = uploadSize;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                          VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &bufBarrier, 0,
                          nullptr);

    // The layer image starts VK_IMAGE_LAYOUT_UNDEFINED after init() and must transition to
    // GENERAL (what the descriptor was written with) before imageLoad/imageStore is legal — a
    // no-op on every call after the first (by this engine or by upload()), see
    // ensureLayerImageGeneral()'s doc comment for why GENERAL is kept permanently rather than
    // transitioned per-op.
    ensureLayerImageGeneral(commandBuffer_);

    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_, 0, 1,
                             &descriptorSet_, 0, nullptr);

    // Dispatch only the workgroups covering `dabs`' bounding box (padded by each dab's own
    // radius), not the whole layer — a full-image dispatch on every touch sample of an 8192x8192
    // document is ~67M shader invocations checking a handful of dabs each time, a real risk of
    // stalling the (synchronous, main-thread) caller or tripping a GPU driver watchdog on a large
    // canvas. The shader re-bases gl_GlobalInvocationID by (originX, originY) to recover the real
    // layer pixel coordinate — see stamp.comp's PushConstants doc comment.
    float minX = dabs[0].x - dabs[0].radius, maxX = dabs[0].x + dabs[0].radius;
    float minY = dabs[0].y - dabs[0].radius, maxY = dabs[0].y + dabs[0].radius;
    for (const GpuDab& d : dabs) {
        minX = std::min(minX, d.x - d.radius); maxX = std::max(maxX, d.x + d.radius);
        minY = std::min(minY, d.y - d.radius); maxY = std::max(maxY, d.y + d.radius);
    }
    int32_t originX = std::max(0, static_cast<int32_t>(std::floor(minX)));
    int32_t originY = std::max(0, static_cast<int32_t>(std::floor(minY)));
    int32_t endX = std::min(width_, static_cast<int32_t>(std::ceil(maxX)) + 1);
    int32_t endY = std::min(height_, static_cast<int32_t>(std::ceil(maxY)) + 1);
    int32_t regionW = std::max(0, endX - originX);
    int32_t regionH = std::max(0, endY - originY);
    // Every dab's padded bbox can fall entirely outside the layer (e.g. a scattered dab drifted
    // off-canvas) — dispatch is simply skipped then, but the command buffer built so far (the dab
    // upload, and possibly ensureLayerImageGeneral's first-use clear) still must be submitted
    // below rather than dropped, since layerImageLayout_ was already updated to reflect it.
    if (regionW > 0 && regionH > 0) {
        PushConstants pc{};
        pc.dabCount = static_cast<uint32_t>(dabs.size());
        pc.hardness = hardness;
        // ARGB -> normalized RGB; alpha is handled separately as baseAlpha so the shader can
        // combine it with each dab's own alpha, matching StampBrushRenderer.paintDabs's
        // `baseAlpha * d.alpha`.
        pc.colorR = static_cast<float>((colorArgb >> 16) & 0xFF) / 255.0f;
        pc.colorG = static_cast<float>((colorArgb >> 8) & 0xFF) / 255.0f;
        pc.colorB = static_cast<float>(colorArgb & 0xFF) / 255.0f;
        pc.baseAlpha = static_cast<float>((colorArgb >> 24) & 0xFF) / 255.0f;
        pc.originX = originX;
        pc.originY = originY;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                            sizeof(pc), &pc);

        uint32_t groupsX = (static_cast<uint32_t>(regionW) + 15) / 16;
        uint32_t groupsY = (static_cast<uint32_t>(regionH) + 15) / 16;
        vkCmdDispatch(commandBuffer_, groupsX, groupsY, 1);
    }

    if (!checkResult(vkEndCommandBuffer(commandBuffer_), "vkEndCommandBuffer")) return false;

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer_;

    vkResetFences(device_, 1, &fence_);
    if (!checkResult(vkQueueSubmit(queue_, 1, &submitInfo, fence_), "vkQueueSubmit")) return false;
    return checkResult(vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX),
                        "vkWaitForFences(stampDabs)");
}

// shaders/stamp_masked.comp resources -- see the header's field comments for why these are kept
// entirely separate from the plain round-dab resources above.

bool VulkanStampEngine::ensureMaskedPipeline() {
    if (maskedPipeline_ != VK_NULL_HANDLE) return true;

    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = kStampMaskedCompSpvWords * sizeof(uint32_t);
    shaderInfo.pCode = kStampMaskedCompSpv;
    if (!checkResult(vkCreateShaderModule(device_, &shaderInfo, nullptr, &maskedShaderModule_),
                      "vkCreateShaderModule(masked)")) {
        return false;
    }

    VkDescriptorSetLayoutBinding bindings[6]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[2].binding = 2;
    bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[2].descriptorCount = 1;
    bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[3].binding = 3;
    bindings[3].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[3].descriptorCount = 1;
    bindings[3].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    // Item 15 masked/dual-brush follow-up: secondary tip mask sampler + secondary dab buffer.
    bindings[4].binding = 4;
    bindings[4].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[4].descriptorCount = 1;
    bindings[4].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[5].binding = 5;
    bindings[5].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[5].descriptorCount = 1;
    bindings[5].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 6;
    layoutInfo.pBindings = bindings;
    if (!checkResult(
            vkCreateDescriptorSetLayout(device_, &layoutInfo, nullptr, &maskedDescriptorSetLayout_),
            "vkCreateDescriptorSetLayout(masked)")) {
        return false;
    }

    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.offset = 0;
    pushRange.size = sizeof(MaskedPushConstants);

    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &maskedDescriptorSetLayout_;
    pipelineLayoutInfo.pushConstantRangeCount = 1;
    pipelineLayoutInfo.pPushConstantRanges = &pushRange;
    if (!checkResult(
            vkCreatePipelineLayout(device_, &pipelineLayoutInfo, nullptr, &maskedPipelineLayout_),
            "vkCreatePipelineLayout(masked)")) {
        return false;
    }

    VkPipelineShaderStageCreateInfo stageInfo{};
    stageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stageInfo.module = maskedShaderModule_;
    stageInfo.pName = "main";

    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage = stageInfo;
    pipelineInfo.layout = maskedPipelineLayout_;
    if (!checkResult(vkCreateComputePipelines(device_, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr,
                                               &maskedPipeline_),
                      "vkCreateComputePipelines(masked)")) {
        return false;
    }

    VkDescriptorPoolSize poolSizes[6]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = 1;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 1;
    poolSizes[2].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[2].descriptorCount = 1;
    poolSizes[3].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[3].descriptorCount = 1;
    poolSizes[4].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[4].descriptorCount = 1;
    poolSizes[5].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[5].descriptorCount = 1;

    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 6;
    poolInfo.pPoolSizes = poolSizes;
    if (!checkResult(vkCreateDescriptorPool(device_, &poolInfo, nullptr, &maskedDescriptorPool_),
                      "vkCreateDescriptorPool(masked)")) {
        return false;
    }

    VkDescriptorSetAllocateInfo dsAllocInfo{};
    dsAllocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsAllocInfo.descriptorPool = maskedDescriptorPool_;
    dsAllocInfo.descriptorSetCount = 1;
    dsAllocInfo.pSetLayouts = &maskedDescriptorSetLayout_;
    if (!checkResult(vkAllocateDescriptorSets(device_, &dsAllocInfo, &maskedDescriptorSet_),
                      "vkAllocateDescriptorSets(masked)")) {
        return false;
    }

    // Binding 1 (the layer image) is stable for this engine's lifetime, same as the round-dab
    // pipeline's descriptorSet_ -- written once, here. Binding 0 (dab buffer) is written whenever
    // ensureMaskedDabBuffer() (re)creates it; binding 2 (mask sampler) whenever ensureMaskTexture()
    // (re)creates the mask image/view.
    VkDescriptorImageInfo imageInfo{};
    imageInfo.imageView = layerImageView_;
    imageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkWriteDescriptorSet imageWrite{};
    imageWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    imageWrite.dstSet = maskedDescriptorSet_;
    imageWrite.dstBinding = 1;
    imageWrite.descriptorCount = 1;
    imageWrite.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    imageWrite.pImageInfo = &imageInfo;
    vkUpdateDescriptorSets(device_, 1, &imageWrite, 0, nullptr);

    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.unnormalizedCoordinates = VK_FALSE;
    if (!checkResult(vkCreateSampler(device_, &samplerInfo, nullptr, &maskSampler_),
                      "vkCreateSampler(mask)")) {
        return false;
    }

    // Grain sampler: REPEAT wrap + NEAREST filter, matching StampBrushRenderer.applyGrain's
    // explicit modulo/floor tiling exactly (LINEAR would blur between grain texels, which the CPU
    // reference never does).
    VkSamplerCreateInfo grainSamplerInfo{};
    grainSamplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    grainSamplerInfo.magFilter = VK_FILTER_NEAREST;
    grainSamplerInfo.minFilter = VK_FILTER_NEAREST;
    grainSamplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    grainSamplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    grainSamplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    grainSamplerInfo.unnormalizedCoordinates = VK_FALSE;
    if (!checkResult(vkCreateSampler(device_, &grainSamplerInfo, nullptr, &grainSampler_),
                      "vkCreateSampler(grain)")) {
        return false;
    }

    // Binding 3 must always reference a valid image once the pipeline is used -- a stroke with no
    // grain binds this 1x1 all-white dummy so the shader's coverage multiply is a no-op.
    if (!ensureGrainTexture(1, 1)) return false;
    const uint8_t dummyGrain = 255;
    if (!uploadGrainTexture(&dummyGrain, 1, 1)) return false;

    // Secondary (dual-brush) tip mask sampler: same LINEAR/CLAMP convention as the primary tip
    // mask -- CPU rasterizes the secondary tip through the same BrushTipMaskCache.tipMask() path
    // as the primary one, so the two should filter identically.
    VkSamplerCreateInfo secondarySamplerInfo{};
    secondarySamplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    secondarySamplerInfo.magFilter = VK_FILTER_LINEAR;
    secondarySamplerInfo.minFilter = VK_FILTER_LINEAR;
    secondarySamplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    secondarySamplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    secondarySamplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    secondarySamplerInfo.unnormalizedCoordinates = VK_FALSE;
    if (!checkResult(vkCreateSampler(device_, &secondarySamplerInfo, nullptr, &secondaryMaskSampler_),
                      "vkCreateSampler(secondaryMask)")) {
        return false;
    }

    // Binding 4/5 must always reference valid resources once the pipeline is used -- a stroke with
    // no dual-brush config binds a 1x1 dummy mask and a single dummy dab; pc.hasSecondary gates
    // whether the shader ever reads them, so their content doesn't matter when it's 0.
    if (!ensureSecondaryMaskTexture(1, 1)) return false;
    const uint8_t dummySecondaryMask = 255;
    if (!uploadSecondaryMaskTexture(&dummySecondaryMask, 1, 1)) return false;
    return ensureSecondaryDabBuffer(1);
}

bool VulkanStampEngine::ensureMaskedDabBuffer(size_t dabCount) {
    if (dabCount <= maskedDabBufferCapacity_ && maskedDabBuffer_ != VK_NULL_HANDLE) return true;

    if (maskedDabBuffer_ != VK_NULL_HANDLE) {
        vkDestroyBuffer(device_, maskedDabBuffer_, nullptr);
        maskedDabBuffer_ = VK_NULL_HANDLE;
    }
    if (maskedDabBufferMemory_ != VK_NULL_HANDLE) {
        vkFreeMemory(device_, maskedDabBufferMemory_, nullptr);
        maskedDabBufferMemory_ = VK_NULL_HANDLE;
    }
    if (maskedDabStagingBuffer_ != VK_NULL_HANDLE) {
        vkDestroyBuffer(device_, maskedDabStagingBuffer_, nullptr);
        maskedDabStagingBuffer_ = VK_NULL_HANDLE;
    }
    if (maskedDabStagingBufferMemory_ != VK_NULL_HANDLE) {
        vkFreeMemory(device_, maskedDabStagingBufferMemory_, nullptr);
        maskedDabStagingBufferMemory_ = VK_NULL_HANDLE;
    }
    maskedDabBufferCapacity_ = 0;

    size_t newCapacity = dabCount + dabCount / 2 + 16;
    VkDeviceSize bufferSize = static_cast<VkDeviceSize>(newCapacity) * sizeof(GpuDab);

    VkBufferCreateInfo deviceBufferInfo{};
    deviceBufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    deviceBufferInfo.size = bufferSize;
    deviceBufferInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    deviceBufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (!checkResult(vkCreateBuffer(device_, &deviceBufferInfo, nullptr, &maskedDabBuffer_),
                      "vkCreateBuffer(maskedDabBuffer)")) {
        maskedDabBuffer_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryRequirements memReq;
    vkGetBufferMemoryRequirements(device_, maskedDabBuffer_, &memReq);
    int32_t memType = findMemoryType(memReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType < 0) {
        LOGE("No device-local memory type for masked dab buffer");
        vkDestroyBuffer(device_, maskedDabBuffer_, nullptr); maskedDabBuffer_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = static_cast<uint32_t>(memType);
    if (!checkResult(vkAllocateMemory(device_, &allocInfo, nullptr, &maskedDabBufferMemory_),
                      "vkAllocateMemory(maskedDabBuffer)")) {
        vkDestroyBuffer(device_, maskedDabBuffer_, nullptr); maskedDabBuffer_ = VK_NULL_HANDLE;
        return false;
    }
    if (!checkResult(vkBindBufferMemory(device_, maskedDabBuffer_, maskedDabBufferMemory_, 0),
                      "vkBindBufferMemory(maskedDabBuffer)")) {
        vkDestroyBuffer(device_, maskedDabBuffer_, nullptr); maskedDabBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, maskedDabBufferMemory_, nullptr); maskedDabBufferMemory_ = VK_NULL_HANDLE;
        return false;
    }

    VkBufferCreateInfo stagingBufferInfo{};
    stagingBufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    stagingBufferInfo.size = bufferSize;
    stagingBufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    stagingBufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (!checkResult(vkCreateBuffer(device_, &stagingBufferInfo, nullptr, &maskedDabStagingBuffer_),
                      "vkCreateBuffer(maskedDabStaging)")) {
        maskedDabStagingBuffer_ = VK_NULL_HANDLE;
        vkDestroyBuffer(device_, maskedDabBuffer_, nullptr); maskedDabBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, maskedDabBufferMemory_, nullptr); maskedDabBufferMemory_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryRequirements stagingMemReq;
    vkGetBufferMemoryRequirements(device_, maskedDabStagingBuffer_, &stagingMemReq);
    int32_t stagingMemType = findMemoryType(
        stagingMemReq.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (stagingMemType < 0) {
        LOGE("No host-visible memory type for masked dab staging buffer");
        vkDestroyBuffer(device_, maskedDabStagingBuffer_, nullptr); maskedDabStagingBuffer_ = VK_NULL_HANDLE;
        vkDestroyBuffer(device_, maskedDabBuffer_, nullptr); maskedDabBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, maskedDabBufferMemory_, nullptr); maskedDabBufferMemory_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryAllocateInfo stagingAllocInfo{};
    stagingAllocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    stagingAllocInfo.allocationSize = stagingMemReq.size;
    stagingAllocInfo.memoryTypeIndex = static_cast<uint32_t>(stagingMemType);
    if (!checkResult(vkAllocateMemory(device_, &stagingAllocInfo, nullptr, &maskedDabStagingBufferMemory_),
                      "vkAllocateMemory(maskedDabStaging)")) {
        vkDestroyBuffer(device_, maskedDabStagingBuffer_, nullptr); maskedDabStagingBuffer_ = VK_NULL_HANDLE;
        vkDestroyBuffer(device_, maskedDabBuffer_, nullptr); maskedDabBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, maskedDabBufferMemory_, nullptr); maskedDabBufferMemory_ = VK_NULL_HANDLE;
        return false;
    }
    if (!checkResult(vkBindBufferMemory(device_, maskedDabStagingBuffer_, maskedDabStagingBufferMemory_, 0),
                      "vkBindBufferMemory(maskedDabStaging)")) {
        vkDestroyBuffer(device_, maskedDabStagingBuffer_, nullptr); maskedDabStagingBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, maskedDabStagingBufferMemory_, nullptr); maskedDabStagingBufferMemory_ = VK_NULL_HANDLE;
        vkDestroyBuffer(device_, maskedDabBuffer_, nullptr); maskedDabBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, maskedDabBufferMemory_, nullptr); maskedDabBufferMemory_ = VK_NULL_HANDLE;
        return false;
    }

    maskedDabBufferCapacity_ = newCapacity;

    VkDescriptorBufferInfo bufInfo{};
    bufInfo.buffer = maskedDabBuffer_;
    bufInfo.offset = 0;
    bufInfo.range = VK_WHOLE_SIZE;

    VkWriteDescriptorSet bufWrite{};
    bufWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    bufWrite.dstSet = maskedDescriptorSet_;
    bufWrite.dstBinding = 0;
    bufWrite.descriptorCount = 1;
    bufWrite.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bufWrite.pBufferInfo = &bufInfo;
    vkUpdateDescriptorSets(device_, 1, &bufWrite, 0, nullptr);
    return true;
}

// Item 15 masked/dual-brush follow-up. Mirrors ensureMaskedDabBuffer() exactly (grow-only
// device-local storage buffer + host-visible staging buffer), for GpuSecondaryDab at binding 5
// instead of GpuDab at binding 0.
bool VulkanStampEngine::ensureSecondaryDabBuffer(size_t dabCount) {
    if (dabCount <= secondaryDabBufferCapacity_ && secondaryDabBuffer_ != VK_NULL_HANDLE) return true;

    if (secondaryDabBuffer_ != VK_NULL_HANDLE) {
        vkDestroyBuffer(device_, secondaryDabBuffer_, nullptr);
        secondaryDabBuffer_ = VK_NULL_HANDLE;
    }
    if (secondaryDabBufferMemory_ != VK_NULL_HANDLE) {
        vkFreeMemory(device_, secondaryDabBufferMemory_, nullptr);
        secondaryDabBufferMemory_ = VK_NULL_HANDLE;
    }
    if (secondaryDabStagingBuffer_ != VK_NULL_HANDLE) {
        vkDestroyBuffer(device_, secondaryDabStagingBuffer_, nullptr);
        secondaryDabStagingBuffer_ = VK_NULL_HANDLE;
    }
    if (secondaryDabStagingBufferMemory_ != VK_NULL_HANDLE) {
        vkFreeMemory(device_, secondaryDabStagingBufferMemory_, nullptr);
        secondaryDabStagingBufferMemory_ = VK_NULL_HANDLE;
    }
    secondaryDabBufferCapacity_ = 0;

    size_t newCapacity = dabCount + dabCount / 2 + 16;
    VkDeviceSize bufferSize = static_cast<VkDeviceSize>(newCapacity) * sizeof(GpuSecondaryDab);

    VkBufferCreateInfo deviceBufferInfo{};
    deviceBufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    deviceBufferInfo.size = bufferSize;
    deviceBufferInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    deviceBufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (!checkResult(vkCreateBuffer(device_, &deviceBufferInfo, nullptr, &secondaryDabBuffer_),
                      "vkCreateBuffer(secondaryDabBuffer)")) {
        secondaryDabBuffer_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryRequirements memReq;
    vkGetBufferMemoryRequirements(device_, secondaryDabBuffer_, &memReq);
    int32_t memType = findMemoryType(memReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType < 0) {
        LOGE("No device-local memory type for secondary dab buffer");
        vkDestroyBuffer(device_, secondaryDabBuffer_, nullptr); secondaryDabBuffer_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = static_cast<uint32_t>(memType);
    if (!checkResult(vkAllocateMemory(device_, &allocInfo, nullptr, &secondaryDabBufferMemory_),
                      "vkAllocateMemory(secondaryDabBuffer)")) {
        vkDestroyBuffer(device_, secondaryDabBuffer_, nullptr); secondaryDabBuffer_ = VK_NULL_HANDLE;
        return false;
    }
    if (!checkResult(vkBindBufferMemory(device_, secondaryDabBuffer_, secondaryDabBufferMemory_, 0),
                      "vkBindBufferMemory(secondaryDabBuffer)")) {
        vkDestroyBuffer(device_, secondaryDabBuffer_, nullptr); secondaryDabBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, secondaryDabBufferMemory_, nullptr); secondaryDabBufferMemory_ = VK_NULL_HANDLE;
        return false;
    }

    VkBufferCreateInfo stagingBufferInfo{};
    stagingBufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    stagingBufferInfo.size = bufferSize;
    stagingBufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    stagingBufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (!checkResult(vkCreateBuffer(device_, &stagingBufferInfo, nullptr, &secondaryDabStagingBuffer_),
                      "vkCreateBuffer(secondaryDabStaging)")) {
        secondaryDabStagingBuffer_ = VK_NULL_HANDLE;
        vkDestroyBuffer(device_, secondaryDabBuffer_, nullptr); secondaryDabBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, secondaryDabBufferMemory_, nullptr); secondaryDabBufferMemory_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryRequirements stagingMemReq;
    vkGetBufferMemoryRequirements(device_, secondaryDabStagingBuffer_, &stagingMemReq);
    int32_t stagingMemType = findMemoryType(
        stagingMemReq.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (stagingMemType < 0) {
        LOGE("No host-visible memory type for secondary dab staging buffer");
        vkDestroyBuffer(device_, secondaryDabStagingBuffer_, nullptr); secondaryDabStagingBuffer_ = VK_NULL_HANDLE;
        vkDestroyBuffer(device_, secondaryDabBuffer_, nullptr); secondaryDabBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, secondaryDabBufferMemory_, nullptr); secondaryDabBufferMemory_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryAllocateInfo stagingAllocInfo{};
    stagingAllocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    stagingAllocInfo.allocationSize = stagingMemReq.size;
    stagingAllocInfo.memoryTypeIndex = static_cast<uint32_t>(stagingMemType);
    if (!checkResult(vkAllocateMemory(device_, &stagingAllocInfo, nullptr, &secondaryDabStagingBufferMemory_),
                      "vkAllocateMemory(secondaryDabStaging)")) {
        vkDestroyBuffer(device_, secondaryDabStagingBuffer_, nullptr); secondaryDabStagingBuffer_ = VK_NULL_HANDLE;
        vkDestroyBuffer(device_, secondaryDabBuffer_, nullptr); secondaryDabBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, secondaryDabBufferMemory_, nullptr); secondaryDabBufferMemory_ = VK_NULL_HANDLE;
        return false;
    }
    if (!checkResult(vkBindBufferMemory(device_, secondaryDabStagingBuffer_, secondaryDabStagingBufferMemory_, 0),
                      "vkBindBufferMemory(secondaryDabStaging)")) {
        vkDestroyBuffer(device_, secondaryDabStagingBuffer_, nullptr); secondaryDabStagingBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, secondaryDabStagingBufferMemory_, nullptr); secondaryDabStagingBufferMemory_ = VK_NULL_HANDLE;
        vkDestroyBuffer(device_, secondaryDabBuffer_, nullptr); secondaryDabBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, secondaryDabBufferMemory_, nullptr); secondaryDabBufferMemory_ = VK_NULL_HANDLE;
        return false;
    }

    secondaryDabBufferCapacity_ = newCapacity;

    VkDescriptorBufferInfo bufInfo2{};
    bufInfo2.buffer = secondaryDabBuffer_;
    bufInfo2.offset = 0;
    bufInfo2.range = VK_WHOLE_SIZE;

    VkWriteDescriptorSet bufWrite2{};
    bufWrite2.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    bufWrite2.dstSet = maskedDescriptorSet_;
    bufWrite2.dstBinding = 5;
    bufWrite2.descriptorCount = 1;
    bufWrite2.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bufWrite2.pBufferInfo = &bufInfo2;
    vkUpdateDescriptorSets(device_, 1, &bufWrite2, 0, nullptr);
    return true;
}

bool VulkanStampEngine::ensureMaskTexture(int width, int height) {
    if (maskImage_ != VK_NULL_HANDLE && width == maskWidth_ && height == maskHeight_) return true;

    if (maskImageView_ != VK_NULL_HANDLE) { vkDestroyImageView(device_, maskImageView_, nullptr); maskImageView_ = VK_NULL_HANDLE; }
    if (maskImage_ != VK_NULL_HANDLE) { vkDestroyImage(device_, maskImage_, nullptr); maskImage_ = VK_NULL_HANDLE; }
    if (maskImageMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, maskImageMemory_, nullptr); maskImageMemory_ = VK_NULL_HANDLE; }
    if (maskStagingBuffer_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, maskStagingBuffer_, nullptr); maskStagingBuffer_ = VK_NULL_HANDLE; }
    if (maskStagingBufferMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, maskStagingBufferMemory_, nullptr); maskStagingBufferMemory_ = VK_NULL_HANDLE; }
    maskImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    maskWidth_ = 0;
    maskHeight_ = 0;

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8_UNORM;
    imageInfo.extent = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (!checkResult(vkCreateImage(device_, &imageInfo, nullptr, &maskImage_), "vkCreateImage(mask)")) {
        return false;
    }

    VkMemoryRequirements memReq;
    vkGetImageMemoryRequirements(device_, maskImage_, &memReq);
    int32_t memType = findMemoryType(memReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType < 0) {
        LOGE("No device-local memory type for mask image");
        vkDestroyImage(device_, maskImage_, nullptr); maskImage_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = static_cast<uint32_t>(memType);
    if (!checkResult(vkAllocateMemory(device_, &allocInfo, nullptr, &maskImageMemory_),
                      "vkAllocateMemory(mask)")) {
        vkDestroyImage(device_, maskImage_, nullptr); maskImage_ = VK_NULL_HANDLE;
        return false;
    }
    if (!checkResult(vkBindImageMemory(device_, maskImage_, maskImageMemory_, 0),
                      "vkBindImageMemory(mask)")) {
        vkDestroyImage(device_, maskImage_, nullptr); maskImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, maskImageMemory_, nullptr); maskImageMemory_ = VK_NULL_HANDLE;
        return false;
    }

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = maskImage_;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8_UNORM;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (!checkResult(vkCreateImageView(device_, &viewInfo, nullptr, &maskImageView_),
                      "vkCreateImageView(mask)")) {
        vkDestroyImage(device_, maskImage_, nullptr); maskImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, maskImageMemory_, nullptr); maskImageMemory_ = VK_NULL_HANDLE;
        return false;
    }

    VkDeviceSize stagingSize = static_cast<VkDeviceSize>(width) * height;
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = stagingSize;
    bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (!checkResult(vkCreateBuffer(device_, &bufferInfo, nullptr, &maskStagingBuffer_),
                      "vkCreateBuffer(maskStaging)")) {
        vkDestroyImageView(device_, maskImageView_, nullptr); maskImageView_ = VK_NULL_HANDLE;
        vkDestroyImage(device_, maskImage_, nullptr); maskImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, maskImageMemory_, nullptr); maskImageMemory_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryRequirements stagingMemReq;
    vkGetBufferMemoryRequirements(device_, maskStagingBuffer_, &stagingMemReq);
    int32_t stagingMemType = findMemoryType(
        stagingMemReq.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (stagingMemType < 0) {
        LOGE("No host-visible memory type for mask staging buffer");
        vkDestroyBuffer(device_, maskStagingBuffer_, nullptr); maskStagingBuffer_ = VK_NULL_HANDLE;
        vkDestroyImageView(device_, maskImageView_, nullptr); maskImageView_ = VK_NULL_HANDLE;
        vkDestroyImage(device_, maskImage_, nullptr); maskImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, maskImageMemory_, nullptr); maskImageMemory_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryAllocateInfo stagingAllocInfo{};
    stagingAllocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    stagingAllocInfo.allocationSize = stagingMemReq.size;
    stagingAllocInfo.memoryTypeIndex = static_cast<uint32_t>(stagingMemType);
    if (!checkResult(vkAllocateMemory(device_, &stagingAllocInfo, nullptr, &maskStagingBufferMemory_),
                      "vkAllocateMemory(maskStaging)")) {
        vkDestroyBuffer(device_, maskStagingBuffer_, nullptr); maskStagingBuffer_ = VK_NULL_HANDLE;
        vkDestroyImageView(device_, maskImageView_, nullptr); maskImageView_ = VK_NULL_HANDLE;
        vkDestroyImage(device_, maskImage_, nullptr); maskImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, maskImageMemory_, nullptr); maskImageMemory_ = VK_NULL_HANDLE;
        return false;
    }
    if (!checkResult(vkBindBufferMemory(device_, maskStagingBuffer_, maskStagingBufferMemory_, 0),
                      "vkBindBufferMemory(maskStaging)")) {
        vkDestroyBuffer(device_, maskStagingBuffer_, nullptr); maskStagingBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, maskStagingBufferMemory_, nullptr); maskStagingBufferMemory_ = VK_NULL_HANDLE;
        vkDestroyImageView(device_, maskImageView_, nullptr); maskImageView_ = VK_NULL_HANDLE;
        vkDestroyImage(device_, maskImage_, nullptr); maskImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, maskImageMemory_, nullptr); maskImageMemory_ = VK_NULL_HANDLE;
        return false;
    }

    maskWidth_ = width;
    maskHeight_ = height;

    VkDescriptorImageInfo samplerImageInfo{};
    samplerImageInfo.sampler = maskSampler_;
    samplerImageInfo.imageView = maskImageView_;
    samplerImageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkWriteDescriptorSet samplerWrite{};
    samplerWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    samplerWrite.dstSet = maskedDescriptorSet_;
    samplerWrite.dstBinding = 2;
    samplerWrite.descriptorCount = 1;
    samplerWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    samplerWrite.pImageInfo = &samplerImageInfo;
    vkUpdateDescriptorSets(device_, 1, &samplerWrite, 0, nullptr);
    return true;
}

bool VulkanStampEngine::uploadMaskTexture(const uint8_t* alpha8, int width, int height) {
    void* mapped = nullptr;
    VkDeviceSize uploadSize = static_cast<VkDeviceSize>(width) * height;
    if (!checkResult(vkMapMemory(device_, maskStagingBufferMemory_, 0, uploadSize, 0, &mapped),
                      "vkMapMemory(maskStaging)")) {
        return false;
    }
    std::memcpy(mapped, alpha8, uploadSize);
    vkUnmapMemory(device_, maskStagingBufferMemory_);

    if (!checkResult(vkResetCommandBuffer(commandBuffer_, 0), "vkResetCommandBuffer(mask)")) {
        return false;
    }
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (!checkResult(vkBeginCommandBuffer(commandBuffer_, &beginInfo), "vkBeginCommandBuffer(mask)")) {
        return false;
    }

    VkImageMemoryBarrier toTransferDst{};
    toTransferDst.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toTransferDst.oldLayout = maskImageLayout_;
    toTransferDst.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    toTransferDst.srcAccessMask = 0;
    toTransferDst.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toTransferDst.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransferDst.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransferDst.image = maskImage_;
    toTransferDst.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                          VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &toTransferDst);

    VkBufferImageCopy region{};
    region.bufferOffset = 0;
    region.bufferRowLength = 0;
    region.bufferImageHeight = 0;
    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.imageOffset = {0, 0, 0};
    region.imageExtent = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1};
    vkCmdCopyBufferToImage(commandBuffer_, maskStagingBuffer_, maskImage_, VK_IMAGE_LAYOUT_GENERAL,
                            1, &region);

    VkImageMemoryBarrier toShaderRead{};
    toShaderRead.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toShaderRead.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    toShaderRead.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    toShaderRead.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toShaderRead.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    toShaderRead.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toShaderRead.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toShaderRead.image = maskImage_;
    toShaderRead.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                          VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                          &toShaderRead);

    if (!checkResult(vkEndCommandBuffer(commandBuffer_), "vkEndCommandBuffer(mask)")) return false;

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer_;

    vkResetFences(device_, 1, &fence_);
    if (!checkResult(vkQueueSubmit(queue_, 1, &submitInfo, fence_), "vkQueueSubmit(mask)")) return false;
    if (!checkResult(vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX),
                      "vkWaitForFences(mask)")) {
        return false;
    }
    maskImageLayout_ = VK_IMAGE_LAYOUT_GENERAL;
    return true;
}

bool VulkanStampEngine::ensureGrainTexture(int width, int height) {
    if (grainImage_ != VK_NULL_HANDLE && width == grainWidth_ && height == grainHeight_) return true;

    if (grainImageView_ != VK_NULL_HANDLE) { vkDestroyImageView(device_, grainImageView_, nullptr); grainImageView_ = VK_NULL_HANDLE; }
    if (grainImage_ != VK_NULL_HANDLE) { vkDestroyImage(device_, grainImage_, nullptr); grainImage_ = VK_NULL_HANDLE; }
    if (grainImageMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, grainImageMemory_, nullptr); grainImageMemory_ = VK_NULL_HANDLE; }
    if (grainStagingBuffer_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, grainStagingBuffer_, nullptr); grainStagingBuffer_ = VK_NULL_HANDLE; }
    if (grainStagingBufferMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, grainStagingBufferMemory_, nullptr); grainStagingBufferMemory_ = VK_NULL_HANDLE; }
    grainImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    grainWidth_ = 0;
    grainHeight_ = 0;

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8_UNORM;
    imageInfo.extent = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (!checkResult(vkCreateImage(device_, &imageInfo, nullptr, &grainImage_), "vkCreateImage(grain)")) {
        return false;
    }

    VkMemoryRequirements memReq;
    vkGetImageMemoryRequirements(device_, grainImage_, &memReq);
    int32_t memType = findMemoryType(memReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType < 0) {
        LOGE("No device-local memory type for grain image");
        vkDestroyImage(device_, grainImage_, nullptr); grainImage_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = static_cast<uint32_t>(memType);
    if (!checkResult(vkAllocateMemory(device_, &allocInfo, nullptr, &grainImageMemory_),
                      "vkAllocateMemory(grain)")) {
        vkDestroyImage(device_, grainImage_, nullptr); grainImage_ = VK_NULL_HANDLE;
        return false;
    }
    if (!checkResult(vkBindImageMemory(device_, grainImage_, grainImageMemory_, 0),
                      "vkBindImageMemory(grain)")) {
        vkDestroyImage(device_, grainImage_, nullptr); grainImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, grainImageMemory_, nullptr); grainImageMemory_ = VK_NULL_HANDLE;
        return false;
    }

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = grainImage_;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8_UNORM;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (!checkResult(vkCreateImageView(device_, &viewInfo, nullptr, &grainImageView_),
                      "vkCreateImageView(grain)")) {
        vkDestroyImage(device_, grainImage_, nullptr); grainImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, grainImageMemory_, nullptr); grainImageMemory_ = VK_NULL_HANDLE;
        return false;
    }

    VkDeviceSize stagingSize = static_cast<VkDeviceSize>(width) * height;
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = stagingSize;
    bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (!checkResult(vkCreateBuffer(device_, &bufferInfo, nullptr, &grainStagingBuffer_),
                      "vkCreateBuffer(grainStaging)")) {
        vkDestroyImageView(device_, grainImageView_, nullptr); grainImageView_ = VK_NULL_HANDLE;
        vkDestroyImage(device_, grainImage_, nullptr); grainImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, grainImageMemory_, nullptr); grainImageMemory_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryRequirements stagingMemReq;
    vkGetBufferMemoryRequirements(device_, grainStagingBuffer_, &stagingMemReq);
    int32_t stagingMemType = findMemoryType(
        stagingMemReq.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (stagingMemType < 0) {
        LOGE("No host-visible memory type for grain staging buffer");
        vkDestroyBuffer(device_, grainStagingBuffer_, nullptr); grainStagingBuffer_ = VK_NULL_HANDLE;
        vkDestroyImageView(device_, grainImageView_, nullptr); grainImageView_ = VK_NULL_HANDLE;
        vkDestroyImage(device_, grainImage_, nullptr); grainImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, grainImageMemory_, nullptr); grainImageMemory_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryAllocateInfo stagingAllocInfo{};
    stagingAllocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    stagingAllocInfo.allocationSize = stagingMemReq.size;
    stagingAllocInfo.memoryTypeIndex = static_cast<uint32_t>(stagingMemType);
    if (!checkResult(vkAllocateMemory(device_, &stagingAllocInfo, nullptr, &grainStagingBufferMemory_),
                      "vkAllocateMemory(grainStaging)")) {
        vkDestroyBuffer(device_, grainStagingBuffer_, nullptr); grainStagingBuffer_ = VK_NULL_HANDLE;
        vkDestroyImageView(device_, grainImageView_, nullptr); grainImageView_ = VK_NULL_HANDLE;
        vkDestroyImage(device_, grainImage_, nullptr); grainImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, grainImageMemory_, nullptr); grainImageMemory_ = VK_NULL_HANDLE;
        return false;
    }
    if (!checkResult(vkBindBufferMemory(device_, grainStagingBuffer_, grainStagingBufferMemory_, 0),
                      "vkBindBufferMemory(grainStaging)")) {
        vkDestroyBuffer(device_, grainStagingBuffer_, nullptr); grainStagingBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, grainStagingBufferMemory_, nullptr); grainStagingBufferMemory_ = VK_NULL_HANDLE;
        vkDestroyImageView(device_, grainImageView_, nullptr); grainImageView_ = VK_NULL_HANDLE;
        vkDestroyImage(device_, grainImage_, nullptr); grainImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, grainImageMemory_, nullptr); grainImageMemory_ = VK_NULL_HANDLE;
        return false;
    }

    grainWidth_ = width;
    grainHeight_ = height;

    VkDescriptorImageInfo samplerImageInfo{};
    samplerImageInfo.sampler = grainSampler_;
    samplerImageInfo.imageView = grainImageView_;
    samplerImageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkWriteDescriptorSet samplerWrite{};
    samplerWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    samplerWrite.dstSet = maskedDescriptorSet_;
    samplerWrite.dstBinding = 3;
    samplerWrite.descriptorCount = 1;
    samplerWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    samplerWrite.pImageInfo = &samplerImageInfo;
    vkUpdateDescriptorSets(device_, 1, &samplerWrite, 0, nullptr);
    return true;
}

bool VulkanStampEngine::uploadGrainTexture(const uint8_t* alpha8, int width, int height) {
    void* mapped = nullptr;
    VkDeviceSize uploadSize = static_cast<VkDeviceSize>(width) * height;
    if (!checkResult(vkMapMemory(device_, grainStagingBufferMemory_, 0, uploadSize, 0, &mapped),
                      "vkMapMemory(grainStaging)")) {
        return false;
    }
    std::memcpy(mapped, alpha8, uploadSize);
    vkUnmapMemory(device_, grainStagingBufferMemory_);

    if (!checkResult(vkResetCommandBuffer(commandBuffer_, 0), "vkResetCommandBuffer(grain)")) {
        return false;
    }
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (!checkResult(vkBeginCommandBuffer(commandBuffer_, &beginInfo), "vkBeginCommandBuffer(grain)")) {
        return false;
    }

    VkImageMemoryBarrier toTransferDst{};
    toTransferDst.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toTransferDst.oldLayout = grainImageLayout_;
    toTransferDst.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    toTransferDst.srcAccessMask = 0;
    toTransferDst.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toTransferDst.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransferDst.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransferDst.image = grainImage_;
    toTransferDst.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                          VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &toTransferDst);

    VkBufferImageCopy region{};
    region.bufferOffset = 0;
    region.bufferRowLength = 0;
    region.bufferImageHeight = 0;
    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.imageOffset = {0, 0, 0};
    region.imageExtent = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1};
    vkCmdCopyBufferToImage(commandBuffer_, grainStagingBuffer_, grainImage_, VK_IMAGE_LAYOUT_GENERAL,
                            1, &region);

    VkImageMemoryBarrier toShaderRead{};
    toShaderRead.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toShaderRead.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    toShaderRead.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    toShaderRead.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toShaderRead.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    toShaderRead.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toShaderRead.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toShaderRead.image = grainImage_;
    toShaderRead.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                          VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                          &toShaderRead);

    if (!checkResult(vkEndCommandBuffer(commandBuffer_), "vkEndCommandBuffer(grain)")) return false;

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer_;

    vkResetFences(device_, 1, &fence_);
    if (!checkResult(vkQueueSubmit(queue_, 1, &submitInfo, fence_), "vkQueueSubmit(grain)")) return false;
    if (!checkResult(vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX),
                      "vkWaitForFences(grain)")) {
        return false;
    }
    grainImageLayout_ = VK_IMAGE_LAYOUT_GENERAL;
    return true;
}

// Item 15 masked/dual-brush follow-up. Mirrors ensureGrainTexture()/uploadGrainTexture()
// exactly (an R8_UNORM sampled image, re-created only when width/height change), bound to
// binding 4 with LINEAR filtering (matching the primary tip mask's own sampler) instead of
// grain's REPEAT/NEAREST.
bool VulkanStampEngine::ensureSecondaryMaskTexture(int width, int height) {
    if (secondaryMaskImage_ != VK_NULL_HANDLE && width == secondaryMaskWidth_ && height == secondaryMaskHeight_) return true;

    if (secondaryMaskImageView_ != VK_NULL_HANDLE) { vkDestroyImageView(device_, secondaryMaskImageView_, nullptr); secondaryMaskImageView_ = VK_NULL_HANDLE; }
    if (secondaryMaskImage_ != VK_NULL_HANDLE) { vkDestroyImage(device_, secondaryMaskImage_, nullptr); secondaryMaskImage_ = VK_NULL_HANDLE; }
    if (secondaryMaskImageMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, secondaryMaskImageMemory_, nullptr); secondaryMaskImageMemory_ = VK_NULL_HANDLE; }
    if (secondaryMaskStagingBuffer_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, secondaryMaskStagingBuffer_, nullptr); secondaryMaskStagingBuffer_ = VK_NULL_HANDLE; }
    if (secondaryMaskStagingBufferMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, secondaryMaskStagingBufferMemory_, nullptr); secondaryMaskStagingBufferMemory_ = VK_NULL_HANDLE; }
    secondaryMaskImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    secondaryMaskWidth_ = 0;
    secondaryMaskHeight_ = 0;

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8_UNORM;
    imageInfo.extent = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (!checkResult(vkCreateImage(device_, &imageInfo, nullptr, &secondaryMaskImage_), "vkCreateImage(secondaryMask)")) {
        return false;
    }

    VkMemoryRequirements memReq;
    vkGetImageMemoryRequirements(device_, secondaryMaskImage_, &memReq);
    int32_t memType = findMemoryType(memReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType < 0) {
        LOGE("No device-local memory type for secondary mask image");
        vkDestroyImage(device_, secondaryMaskImage_, nullptr); secondaryMaskImage_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = static_cast<uint32_t>(memType);
    if (!checkResult(vkAllocateMemory(device_, &allocInfo, nullptr, &secondaryMaskImageMemory_),
                      "vkAllocateMemory(secondaryMask)")) {
        vkDestroyImage(device_, secondaryMaskImage_, nullptr); secondaryMaskImage_ = VK_NULL_HANDLE;
        return false;
    }
    if (!checkResult(vkBindImageMemory(device_, secondaryMaskImage_, secondaryMaskImageMemory_, 0),
                      "vkBindImageMemory(secondaryMask)")) {
        vkDestroyImage(device_, secondaryMaskImage_, nullptr); secondaryMaskImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, secondaryMaskImageMemory_, nullptr); secondaryMaskImageMemory_ = VK_NULL_HANDLE;
        return false;
    }

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = secondaryMaskImage_;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8_UNORM;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (!checkResult(vkCreateImageView(device_, &viewInfo, nullptr, &secondaryMaskImageView_),
                      "vkCreateImageView(secondaryMask)")) {
        vkDestroyImage(device_, secondaryMaskImage_, nullptr); secondaryMaskImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, secondaryMaskImageMemory_, nullptr); secondaryMaskImageMemory_ = VK_NULL_HANDLE;
        return false;
    }

    VkDeviceSize stagingSize = static_cast<VkDeviceSize>(width) * height;
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = stagingSize;
    bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (!checkResult(vkCreateBuffer(device_, &bufferInfo, nullptr, &secondaryMaskStagingBuffer_),
                      "vkCreateBuffer(secondaryMaskStaging)")) {
        vkDestroyImageView(device_, secondaryMaskImageView_, nullptr); secondaryMaskImageView_ = VK_NULL_HANDLE;
        vkDestroyImage(device_, secondaryMaskImage_, nullptr); secondaryMaskImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, secondaryMaskImageMemory_, nullptr); secondaryMaskImageMemory_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryRequirements stagingMemReq;
    vkGetBufferMemoryRequirements(device_, secondaryMaskStagingBuffer_, &stagingMemReq);
    int32_t stagingMemType = findMemoryType(
        stagingMemReq.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (stagingMemType < 0) {
        LOGE("No host-visible memory type for secondary mask staging buffer");
        vkDestroyBuffer(device_, secondaryMaskStagingBuffer_, nullptr); secondaryMaskStagingBuffer_ = VK_NULL_HANDLE;
        vkDestroyImageView(device_, secondaryMaskImageView_, nullptr); secondaryMaskImageView_ = VK_NULL_HANDLE;
        vkDestroyImage(device_, secondaryMaskImage_, nullptr); secondaryMaskImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, secondaryMaskImageMemory_, nullptr); secondaryMaskImageMemory_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryAllocateInfo stagingAllocInfo{};
    stagingAllocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    stagingAllocInfo.allocationSize = stagingMemReq.size;
    stagingAllocInfo.memoryTypeIndex = static_cast<uint32_t>(stagingMemType);
    if (!checkResult(vkAllocateMemory(device_, &stagingAllocInfo, nullptr, &secondaryMaskStagingBufferMemory_),
                      "vkAllocateMemory(secondaryMaskStaging)")) {
        vkDestroyBuffer(device_, secondaryMaskStagingBuffer_, nullptr); secondaryMaskStagingBuffer_ = VK_NULL_HANDLE;
        vkDestroyImageView(device_, secondaryMaskImageView_, nullptr); secondaryMaskImageView_ = VK_NULL_HANDLE;
        vkDestroyImage(device_, secondaryMaskImage_, nullptr); secondaryMaskImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, secondaryMaskImageMemory_, nullptr); secondaryMaskImageMemory_ = VK_NULL_HANDLE;
        return false;
    }
    if (!checkResult(vkBindBufferMemory(device_, secondaryMaskStagingBuffer_, secondaryMaskStagingBufferMemory_, 0),
                      "vkBindBufferMemory(secondaryMaskStaging)")) {
        vkDestroyBuffer(device_, secondaryMaskStagingBuffer_, nullptr); secondaryMaskStagingBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, secondaryMaskStagingBufferMemory_, nullptr); secondaryMaskStagingBufferMemory_ = VK_NULL_HANDLE;
        vkDestroyImageView(device_, secondaryMaskImageView_, nullptr); secondaryMaskImageView_ = VK_NULL_HANDLE;
        vkDestroyImage(device_, secondaryMaskImage_, nullptr); secondaryMaskImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, secondaryMaskImageMemory_, nullptr); secondaryMaskImageMemory_ = VK_NULL_HANDLE;
        return false;
    }

    secondaryMaskWidth_ = width;
    secondaryMaskHeight_ = height;

    VkDescriptorImageInfo samplerImageInfo{};
    samplerImageInfo.sampler = secondaryMaskSampler_;
    samplerImageInfo.imageView = secondaryMaskImageView_;
    samplerImageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkWriteDescriptorSet samplerWrite{};
    samplerWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    samplerWrite.dstSet = maskedDescriptorSet_;
    samplerWrite.dstBinding = 4;
    samplerWrite.descriptorCount = 1;
    samplerWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    samplerWrite.pImageInfo = &samplerImageInfo;
    vkUpdateDescriptorSets(device_, 1, &samplerWrite, 0, nullptr);
    return true;
}

bool VulkanStampEngine::uploadSecondaryMaskTexture(const uint8_t* alpha8, int width, int height) {
    void* mapped = nullptr;
    VkDeviceSize uploadSize = static_cast<VkDeviceSize>(width) * height;
    if (!checkResult(vkMapMemory(device_, secondaryMaskStagingBufferMemory_, 0, uploadSize, 0, &mapped),
                      "vkMapMemory(secondaryMaskStaging)")) {
        return false;
    }
    std::memcpy(mapped, alpha8, uploadSize);
    vkUnmapMemory(device_, secondaryMaskStagingBufferMemory_);

    if (!checkResult(vkResetCommandBuffer(commandBuffer_, 0), "vkResetCommandBuffer(secondaryMask)")) {
        return false;
    }
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (!checkResult(vkBeginCommandBuffer(commandBuffer_, &beginInfo), "vkBeginCommandBuffer(secondaryMask)")) {
        return false;
    }

    VkImageMemoryBarrier toTransferDst{};
    toTransferDst.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toTransferDst.oldLayout = secondaryMaskImageLayout_;
    toTransferDst.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    toTransferDst.srcAccessMask = 0;
    toTransferDst.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toTransferDst.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransferDst.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransferDst.image = secondaryMaskImage_;
    toTransferDst.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                          VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &toTransferDst);

    VkBufferImageCopy region{};
    region.bufferOffset = 0;
    region.bufferRowLength = 0;
    region.bufferImageHeight = 0;
    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.imageOffset = {0, 0, 0};
    region.imageExtent = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1};
    vkCmdCopyBufferToImage(commandBuffer_, secondaryMaskStagingBuffer_, secondaryMaskImage_, VK_IMAGE_LAYOUT_GENERAL,
                            1, &region);

    VkImageMemoryBarrier toShaderRead{};
    toShaderRead.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toShaderRead.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    toShaderRead.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    toShaderRead.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toShaderRead.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    toShaderRead.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toShaderRead.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toShaderRead.image = secondaryMaskImage_;
    toShaderRead.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                          VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                          &toShaderRead);

    if (!checkResult(vkEndCommandBuffer(commandBuffer_), "vkEndCommandBuffer(secondaryMask)")) return false;

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer_;

    vkResetFences(device_, 1, &fence_);
    if (!checkResult(vkQueueSubmit(queue_, 1, &submitInfo, fence_), "vkQueueSubmit(secondaryMask)")) return false;
    if (!checkResult(vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX),
                      "vkWaitForFences(secondaryMask)")) {
        return false;
    }
    secondaryMaskImageLayout_ = VK_IMAGE_LAYOUT_GENERAL;
    return true;
}

bool VulkanStampEngine::stampMaskedDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb,
                                        float hardness, const uint8_t* maskAlpha8, int maskWidth,
                                        int maskHeight, const uint8_t* grainAlpha8, int grainWidth,
                                        int grainHeight, bool grainCanvasLocked, float grainScale,
                                        float grainPhaseX, float grainPhaseY,
                                        const std::vector<GpuSecondaryDab>& secondaryDabs,
                                        const uint8_t* secondaryMaskAlpha8, int secondaryMaskWidth,
                                        int secondaryMaskHeight) {
    if (!isInitialized() || dabs.empty() || maskAlpha8 == nullptr) return false;
    if (maskWidth <= 0 || maskHeight <= 0) return false;
    // Dual-brush is per-stroke, not per-dab optional (see the header doc comment): a non-empty
    // secondaryDabs must line up 1:1 with dabs, or the shader would read past its own array (a
    // shorter secondaryDabs) or silently ignore trailing primary dabs (a longer one).
    if (!secondaryDabs.empty() && secondaryDabs.size() != dabs.size()) return false;
    if (!ensureMaskedPipeline()) return false;
    if (!ensureMaskedDabBuffer(dabs.size())) return false;
    uint64_t maskHash = fnv1a(maskAlpha8, static_cast<size_t>(maskWidth) * static_cast<size_t>(maskHeight));
    bool maskIsNew = maskImage_ == VK_NULL_HANDLE || maskWidth != maskWidth_ || maskHeight != maskHeight_ ||
                      maskHash != maskContentHash_;
    if (!ensureMaskTexture(maskWidth, maskHeight)) return false;
    if (maskIsNew) {
        if (!uploadMaskTexture(maskAlpha8, maskWidth, maskHeight)) return false;
        maskContentHash_ = maskHash;
    }

    // Grain (item 15 follow-up): a real tile if the caller supplied one, otherwise fall back to
    // the 1x1 dummy ensureMaskedPipeline() already bound -- re-requesting it here is a cheap
    // no-op via ensureGrainTexture()'s size check, and keeps this branch symmetric with the mask
    // handling just above rather than special-casing "no grain" as a distinct code path.
    bool haveGrain = grainAlpha8 != nullptr && grainWidth > 0 && grainHeight > 0;
    int effectiveGrainWidth = haveGrain ? grainWidth : 1;
    int effectiveGrainHeight = haveGrain ? grainHeight : 1;
    uint64_t grainHash = haveGrain
                              ? fnv1a(grainAlpha8, static_cast<size_t>(effectiveGrainWidth) *
                                                        static_cast<size_t>(effectiveGrainHeight))
                              : 0xFFULL;  // matches the dummy byte below (255), so a repeated no-grain call is a no-op
    bool grainIsNew = grainImage_ == VK_NULL_HANDLE || effectiveGrainWidth != grainWidth_ ||
                       effectiveGrainHeight != grainHeight_ || grainHash != grainContentHash_;
    if (!ensureGrainTexture(effectiveGrainWidth, effectiveGrainHeight)) return false;
    if (grainIsNew) {
        if (haveGrain) {
            if (!uploadGrainTexture(grainAlpha8, effectiveGrainWidth, effectiveGrainHeight)) return false;
        } else {
            const uint8_t dummyGrain = 255;
            if (!uploadGrainTexture(&dummyGrain, 1, 1)) return false;
        }
        grainContentHash_ = grainHash;
    }

    // Masked/dual-brush (item 15 follow-up): same "real if supplied, else 1x1 dummy" pattern as
    // grain above, but for two resources (mask texture + dab buffer) instead of one, since a
    // secondary tip needs both its own shape and its own per-dab geometry.
    bool haveSecondary = !secondaryDabs.empty() && secondaryMaskAlpha8 != nullptr &&
                          secondaryMaskWidth > 0 && secondaryMaskHeight > 0;
    int effectiveSecondaryMaskWidth = haveSecondary ? secondaryMaskWidth : 1;
    int effectiveSecondaryMaskHeight = haveSecondary ? secondaryMaskHeight : 1;
    uint64_t secondaryMaskHash =
        haveSecondary ? fnv1a(secondaryMaskAlpha8, static_cast<size_t>(effectiveSecondaryMaskWidth) *
                                                        static_cast<size_t>(effectiveSecondaryMaskHeight))
                      : 0xFFULL;  // matches the dummy byte below, so a repeated no-secondary call is a no-op
    bool secondaryMaskIsNew = secondaryMaskImage_ == VK_NULL_HANDLE ||
                               effectiveSecondaryMaskWidth != secondaryMaskWidth_ ||
                               effectiveSecondaryMaskHeight != secondaryMaskHeight_ ||
                               secondaryMaskHash != secondaryMaskContentHash_;
    if (!ensureSecondaryMaskTexture(effectiveSecondaryMaskWidth, effectiveSecondaryMaskHeight)) return false;
    if (secondaryMaskIsNew) {
        if (haveSecondary) {
            if (!uploadSecondaryMaskTexture(secondaryMaskAlpha8, effectiveSecondaryMaskWidth,
                                             effectiveSecondaryMaskHeight)) {
                return false;
            }
        } else {
            const uint8_t dummySecondaryMask = 255;
            if (!uploadSecondaryMaskTexture(&dummySecondaryMask, 1, 1)) return false;
        }
        secondaryMaskContentHash_ = secondaryMaskHash;
    }
    // The secondary dab buffer's *content* changes every dispatch (different dab geometry) even
    // when dab count doesn't, unlike the mask/grain textures (same tip for a whole stroke) -- so
    // this always re-uploads when dual-brush is active, not just on a size change.
    size_t secondaryDabCount = haveSecondary ? secondaryDabs.size() : 1;
    if (!ensureSecondaryDabBuffer(secondaryDabCount)) return false;
    if (haveSecondary) {
        void* secondaryMapped = nullptr;
        VkDeviceSize secondaryUploadSize = secondaryDabs.size() * sizeof(GpuSecondaryDab);
        if (!checkResult(
                vkMapMemory(device_, secondaryDabStagingBufferMemory_, 0, secondaryUploadSize, 0, &secondaryMapped),
                "vkMapMemory(secondaryDabStaging)")) {
            return false;
        }
        std::memcpy(secondaryMapped, secondaryDabs.data(), secondaryUploadSize);
        vkUnmapMemory(device_, secondaryDabStagingBufferMemory_);
    }

    void* mapped = nullptr;
    VkDeviceSize uploadSize = dabs.size() * sizeof(GpuDab);
    if (!checkResult(vkMapMemory(device_, maskedDabStagingBufferMemory_, 0, uploadSize, 0, &mapped),
                      "vkMapMemory(maskedDabStaging)")) {
        return false;
    }
    std::memcpy(mapped, dabs.data(), uploadSize);
    vkUnmapMemory(device_, maskedDabStagingBufferMemory_);

    if (!checkResult(vkResetCommandBuffer(commandBuffer_, 0), "vkResetCommandBuffer(stampMasked)")) {
        return false;
    }
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (!checkResult(vkBeginCommandBuffer(commandBuffer_, &beginInfo), "vkBeginCommandBuffer(stampMasked)")) {
        return false;
    }

    VkBufferCopy copyRegion{0, 0, uploadSize};
    vkCmdCopyBuffer(commandBuffer_, maskedDabStagingBuffer_, maskedDabBuffer_, 1, &copyRegion);

    VkBufferMemoryBarrier bufBarrier{};
    bufBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    bufBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    bufBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    bufBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    bufBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    bufBarrier.buffer = maskedDabBuffer_;
    bufBarrier.offset = 0;
    bufBarrier.size = uploadSize;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                          VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &bufBarrier, 0,
                          nullptr);

    if (haveSecondary) {
        VkDeviceSize secondaryUploadSize = secondaryDabs.size() * sizeof(GpuSecondaryDab);
        VkBufferCopy secondaryCopyRegion{0, 0, secondaryUploadSize};
        vkCmdCopyBuffer(commandBuffer_, secondaryDabStagingBuffer_, secondaryDabBuffer_, 1,
                         &secondaryCopyRegion);

        VkBufferMemoryBarrier secondaryBufBarrier{};
        secondaryBufBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        secondaryBufBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        secondaryBufBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        secondaryBufBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        secondaryBufBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        secondaryBufBarrier.buffer = secondaryDabBuffer_;
        secondaryBufBarrier.offset = 0;
        secondaryBufBarrier.size = secondaryUploadSize;
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                              VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1,
                              &secondaryBufBarrier, 0, nullptr);
    }

    ensureLayerImageGeneral(commandBuffer_);

    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, maskedPipeline_);
    vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, maskedPipelineLayout_, 0,
                             1, &maskedDescriptorSet_, 0, nullptr);

    // Same padded-bbox dispatch-region optimization as stampDabs() -- see its doc comment.
    float minX = dabs[0].x - dabs[0].radius, maxX = dabs[0].x + dabs[0].radius;
    float minY = dabs[0].y - dabs[0].radius, maxY = dabs[0].y + dabs[0].radius;
    for (const GpuDab& d : dabs) {
        minX = std::min(minX, d.x - d.radius); maxX = std::max(maxX, d.x + d.radius);
        minY = std::min(minY, d.y - d.radius); maxY = std::max(maxY, d.y + d.radius);
    }
    int32_t originX = std::max(0, static_cast<int32_t>(std::floor(minX)));
    int32_t originY = std::max(0, static_cast<int32_t>(std::floor(minY)));
    int32_t endX = std::min(width_, static_cast<int32_t>(std::ceil(maxX)) + 1);
    int32_t endY = std::min(height_, static_cast<int32_t>(std::ceil(maxY)) + 1);
    int32_t regionW = std::max(0, endX - originX);
    int32_t regionH = std::max(0, endY - originY);
    if (regionW > 0 && regionH > 0) {
        MaskedPushConstants pc{};
        pc.dabCount = static_cast<uint32_t>(dabs.size());
        pc.hardness = hardness;
        pc.colorR = static_cast<float>((colorArgb >> 16) & 0xFF) / 255.0f;
        pc.colorG = static_cast<float>((colorArgb >> 8) & 0xFF) / 255.0f;
        pc.colorB = static_cast<float>(colorArgb & 0xFF) / 255.0f;
        pc.baseAlpha = static_cast<float>((colorArgb >> 24) & 0xFF) / 255.0f;
        pc.originX = originX;
        pc.originY = originY;
        // No grain: scale=1/phase=0 would still be a no-op given the 1x1 all-white dummy texture,
        // but zeroing them here too keeps this call's push constants fully deterministic either way.
        pc.grainCanvasLocked = grainCanvasLocked ? 1.0f : 0.0f;
        pc.grainScale = haveGrain ? grainScale : 1.0f;
        pc.grainPhaseX = haveGrain ? grainPhaseX : 0.0f;
        pc.grainPhaseY = haveGrain ? grainPhaseY : 0.0f;
        pc.hasSecondary = haveSecondary ? 1.0f : 0.0f;
        vkCmdPushConstants(commandBuffer_, maskedPipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                            sizeof(pc), &pc);

        uint32_t groupsX = (static_cast<uint32_t>(regionW) + 15) / 16;
        uint32_t groupsY = (static_cast<uint32_t>(regionH) + 15) / 16;
        vkCmdDispatch(commandBuffer_, groupsX, groupsY, 1);
    }

    if (!checkResult(vkEndCommandBuffer(commandBuffer_), "vkEndCommandBuffer(stampMasked)")) return false;

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer_;

    vkResetFences(device_, 1, &fence_);
    if (!checkResult(vkQueueSubmit(queue_, 1, &submitInfo, fence_), "vkQueueSubmit(stampMasked)")) return false;
    return checkResult(vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX),
                        "vkWaitForFences(stampMasked)");
}

void VulkanStampEngine::destroyMaskedResources() {
    if (maskSampler_ != VK_NULL_HANDLE) { vkDestroySampler(device_, maskSampler_, nullptr); maskSampler_ = VK_NULL_HANDLE; }
    if (maskImageView_ != VK_NULL_HANDLE) { vkDestroyImageView(device_, maskImageView_, nullptr); maskImageView_ = VK_NULL_HANDLE; }
    if (maskImage_ != VK_NULL_HANDLE) { vkDestroyImage(device_, maskImage_, nullptr); maskImage_ = VK_NULL_HANDLE; }
    if (maskImageMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, maskImageMemory_, nullptr); maskImageMemory_ = VK_NULL_HANDLE; }
    if (maskStagingBuffer_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, maskStagingBuffer_, nullptr); maskStagingBuffer_ = VK_NULL_HANDLE; }
    if (maskStagingBufferMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, maskStagingBufferMemory_, nullptr); maskStagingBufferMemory_ = VK_NULL_HANDLE; }
    maskWidth_ = 0;
    maskHeight_ = 0;
    maskImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;

    if (grainSampler_ != VK_NULL_HANDLE) { vkDestroySampler(device_, grainSampler_, nullptr); grainSampler_ = VK_NULL_HANDLE; }
    if (grainImageView_ != VK_NULL_HANDLE) { vkDestroyImageView(device_, grainImageView_, nullptr); grainImageView_ = VK_NULL_HANDLE; }
    if (grainImage_ != VK_NULL_HANDLE) { vkDestroyImage(device_, grainImage_, nullptr); grainImage_ = VK_NULL_HANDLE; }
    if (grainImageMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, grainImageMemory_, nullptr); grainImageMemory_ = VK_NULL_HANDLE; }
    if (grainStagingBuffer_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, grainStagingBuffer_, nullptr); grainStagingBuffer_ = VK_NULL_HANDLE; }
    if (grainStagingBufferMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, grainStagingBufferMemory_, nullptr); grainStagingBufferMemory_ = VK_NULL_HANDLE; }
    grainWidth_ = 0;
    grainHeight_ = 0;
    grainImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;

    if (maskedDabBuffer_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, maskedDabBuffer_, nullptr); maskedDabBuffer_ = VK_NULL_HANDLE; }
    if (maskedDabBufferMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, maskedDabBufferMemory_, nullptr); maskedDabBufferMemory_ = VK_NULL_HANDLE; }
    if (maskedDabStagingBuffer_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, maskedDabStagingBuffer_, nullptr); maskedDabStagingBuffer_ = VK_NULL_HANDLE; }
    if (maskedDabStagingBufferMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, maskedDabStagingBufferMemory_, nullptr); maskedDabStagingBufferMemory_ = VK_NULL_HANDLE; }
    maskedDabBufferCapacity_ = 0;

    if (secondaryMaskSampler_ != VK_NULL_HANDLE) { vkDestroySampler(device_, secondaryMaskSampler_, nullptr); secondaryMaskSampler_ = VK_NULL_HANDLE; }
    if (secondaryMaskImageView_ != VK_NULL_HANDLE) { vkDestroyImageView(device_, secondaryMaskImageView_, nullptr); secondaryMaskImageView_ = VK_NULL_HANDLE; }
    if (secondaryMaskImage_ != VK_NULL_HANDLE) { vkDestroyImage(device_, secondaryMaskImage_, nullptr); secondaryMaskImage_ = VK_NULL_HANDLE; }
    if (secondaryMaskImageMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, secondaryMaskImageMemory_, nullptr); secondaryMaskImageMemory_ = VK_NULL_HANDLE; }
    if (secondaryMaskStagingBuffer_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, secondaryMaskStagingBuffer_, nullptr); secondaryMaskStagingBuffer_ = VK_NULL_HANDLE; }
    if (secondaryMaskStagingBufferMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, secondaryMaskStagingBufferMemory_, nullptr); secondaryMaskStagingBufferMemory_ = VK_NULL_HANDLE; }
    secondaryMaskWidth_ = 0;
    secondaryMaskHeight_ = 0;
    secondaryMaskImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;

    if (secondaryDabBuffer_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, secondaryDabBuffer_, nullptr); secondaryDabBuffer_ = VK_NULL_HANDLE; }
    if (secondaryDabBufferMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, secondaryDabBufferMemory_, nullptr); secondaryDabBufferMemory_ = VK_NULL_HANDLE; }
    if (secondaryDabStagingBuffer_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, secondaryDabStagingBuffer_, nullptr); secondaryDabStagingBuffer_ = VK_NULL_HANDLE; }
    if (secondaryDabStagingBufferMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, secondaryDabStagingBufferMemory_, nullptr); secondaryDabStagingBufferMemory_ = VK_NULL_HANDLE; }
    secondaryDabBufferCapacity_ = 0;

    if (maskedPipeline_ != VK_NULL_HANDLE) { vkDestroyPipeline(device_, maskedPipeline_, nullptr); maskedPipeline_ = VK_NULL_HANDLE; }
    if (maskedPipelineLayout_ != VK_NULL_HANDLE) { vkDestroyPipelineLayout(device_, maskedPipelineLayout_, nullptr); maskedPipelineLayout_ = VK_NULL_HANDLE; }
    if (maskedShaderModule_ != VK_NULL_HANDLE) { vkDestroyShaderModule(device_, maskedShaderModule_, nullptr); maskedShaderModule_ = VK_NULL_HANDLE; }
    if (maskedDescriptorPool_ != VK_NULL_HANDLE) { vkDestroyDescriptorPool(device_, maskedDescriptorPool_, nullptr); maskedDescriptorPool_ = VK_NULL_HANDLE; maskedDescriptorSet_ = VK_NULL_HANDLE; }
    if (maskedDescriptorSetLayout_ != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(device_, maskedDescriptorSetLayout_, nullptr); maskedDescriptorSetLayout_ = VK_NULL_HANDLE; }
}

bool VulkanStampEngine::readback(uint8_t* outRgba8, size_t outCapacityBytes) {
    if (!isInitialized()) return false;
    size_t requiredBytes = static_cast<size_t>(width_) * height_ * 4;
    if (outCapacityBytes < requiredBytes) {
        LOGE("readback buffer too small: need %zu, got %zu", requiredBytes, outCapacityBytes);
        return false;
    }

    if (!checkResult(vkResetCommandBuffer(commandBuffer_, 0), "vkResetCommandBuffer")) {
        return false;
    }
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (!checkResult(vkBeginCommandBuffer(commandBuffer_, &beginInfo), "vkBeginCommandBuffer(readback)")) {
        return false;
    }

    // Handles both cases: an engine that never had stampDabs()/upload() called on it yet (image
    // still UNDEFINED — the transition itself, dstAccessMask below doesn't matter since there's no
    // prior write to flush) and the normal case (already GENERAL — a no-op layout-wise, but the
    // access-mask barrier below is still needed to order the compute write before this read).
    ensureLayerImageGeneral(commandBuffer_);

    VkImageMemoryBarrier toTransferSrc{};
    toTransferSrc.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toTransferSrc.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    toTransferSrc.newLayout = VK_IMAGE_LAYOUT_GENERAL;  // GENERAL is valid as a transfer source too.
    toTransferSrc.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    toTransferSrc.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    toTransferSrc.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransferSrc.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransferSrc.image = layerImage_;
    toTransferSrc.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                          VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                          &toTransferSrc);

    VkBufferImageCopy region{};
    region.bufferOffset = 0;
    region.bufferRowLength = 0;
    region.bufferImageHeight = 0;
    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.imageOffset = {0, 0, 0};
    region.imageExtent = {static_cast<uint32_t>(width_), static_cast<uint32_t>(height_), 1};
    vkCmdCopyImageToBuffer(commandBuffer_, layerImage_, VK_IMAGE_LAYOUT_GENERAL, stagingBuffer_, 1,
                            &region);

    if (!checkResult(vkEndCommandBuffer(commandBuffer_), "vkEndCommandBuffer(readback)")) return false;

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer_;

    vkResetFences(device_, 1, &fence_);
    if (!checkResult(vkQueueSubmit(queue_, 1, &submitInfo, fence_), "vkQueueSubmit(readback)")) return false;
    if (!checkResult(vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX),
                      "vkWaitForFences(readback)")) {
        return false;
    }

    void* mapped = nullptr;
    if (!checkResult(vkMapMemory(device_, stagingBufferMemory_, 0, requiredBytes, 0, &mapped),
                      "vkMapMemory(readback)")) {
        return false;
    }
    std::memcpy(outRgba8, mapped, requiredBytes);
    vkUnmapMemory(device_, stagingBufferMemory_);
    return true;
}

void VulkanStampEngine::destroy() {
    if (device_ != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device_);
        destroyColorSmudgeResources();
        destroyMaskedResources();
    }

    if (fence_ != VK_NULL_HANDLE) { vkDestroyFence(device_, fence_, nullptr); fence_ = VK_NULL_HANDLE; }
    if (commandPool_ != VK_NULL_HANDLE) { vkDestroyCommandPool(device_, commandPool_, nullptr); commandPool_ = VK_NULL_HANDLE; }
    commandBuffer_ = VK_NULL_HANDLE;

    if (pipeline_ != VK_NULL_HANDLE) { vkDestroyPipeline(device_, pipeline_, nullptr); pipeline_ = VK_NULL_HANDLE; }
    if (pipelineLayout_ != VK_NULL_HANDLE) { vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr); pipelineLayout_ = VK_NULL_HANDLE; }
    if (shaderModule_ != VK_NULL_HANDLE) { vkDestroyShaderModule(device_, shaderModule_, nullptr); shaderModule_ = VK_NULL_HANDLE; }
    if (descriptorPool_ != VK_NULL_HANDLE) { vkDestroyDescriptorPool(device_, descriptorPool_, nullptr); descriptorPool_ = VK_NULL_HANDLE; }
    descriptorSet_ = VK_NULL_HANDLE;
    if (descriptorSetLayout_ != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(device_, descriptorSetLayout_, nullptr); descriptorSetLayout_ = VK_NULL_HANDLE; }

    if (dabBuffer_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, dabBuffer_, nullptr); dabBuffer_ = VK_NULL_HANDLE; }
    if (dabBufferMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, dabBufferMemory_, nullptr); dabBufferMemory_ = VK_NULL_HANDLE; }
    if (dabStagingBuffer_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, dabStagingBuffer_, nullptr); dabStagingBuffer_ = VK_NULL_HANDLE; }
    if (dabStagingBufferMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, dabStagingBufferMemory_, nullptr); dabStagingBufferMemory_ = VK_NULL_HANDLE; }
    dabBufferCapacity_ = 0;

    if (stagingBuffer_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, stagingBuffer_, nullptr); stagingBuffer_ = VK_NULL_HANDLE; }
    if (stagingBufferMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, stagingBufferMemory_, nullptr); stagingBufferMemory_ = VK_NULL_HANDLE; }

    if (layerImageView_ != VK_NULL_HANDLE) { vkDestroyImageView(device_, layerImageView_, nullptr); layerImageView_ = VK_NULL_HANDLE; }
    if (layerImage_ != VK_NULL_HANDLE) { vkDestroyImage(device_, layerImage_, nullptr); layerImage_ = VK_NULL_HANDLE; }
    if (layerImageMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, layerImageMemory_, nullptr); layerImageMemory_ = VK_NULL_HANDLE; }
    if (hardwareBuffer_ != nullptr) { AHardwareBuffer_release(hardwareBuffer_); hardwareBuffer_ = nullptr; }

    if (device_ != VK_NULL_HANDLE) { vkDestroyDevice(device_, nullptr); device_ = VK_NULL_HANDLE; }
    if (instance_ != VK_NULL_HANDLE) { vkDestroyInstance(instance_, nullptr); instance_ = VK_NULL_HANDLE; }

    physicalDevice_ = VK_NULL_HANDLE;
    queue_ = VK_NULL_HANDLE;
    width_ = 0;
    height_ = 0;
    layerImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
}

}  // namespace graffux
