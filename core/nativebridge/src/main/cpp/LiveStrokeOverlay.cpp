// FILE: core/nativebridge/src/main/cpp/LiveStrokeOverlay.cpp
//
// Direct display of the live stroke (docs/Native Rendering Engine Design.md §3): a SurfaceControl
// layer above the canvas that a Vulkan compute pass writes straight into, front-buffered where the
// device supports it. New paint reaches the compositor without waiting for Compose to recompose,
// re-upload a bitmap and draw a frame.
//
// It owns its own small Vulkan device, independent of the per-stroke (pooled) stamp engines: the
// engine's layer is an AHardwareBuffer, so this imports that same memory at stroke start instead of
// sharing a VkDevice with an engine whose lifetime is a single stroke. The stamp engine waits for
// its GPU work before returning (Vulkan fence / GLES glFinish), so by the time present() reads the
// layer the paint is complete. Works with either stamp backend for the same reason.
//
// See shaders/live_overlay.comp for why the overlay holds only the stroke's own contribution.

#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <cstring>
#include <vector>

#include "LiveOverlaySpv.h"

#define OVL_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LiveStrokeOverlay", __VA_ARGS__)
#define OVL_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LiveStrokeOverlay", __VA_ARGS__)

namespace graffux {
namespace {

// ASurfaceControl is API 29 and this app's minSdk is 26: resolved at runtime from libandroid.so, so
// older devices simply report the overlay unavailable rather than failing to load the library.
struct ASurfaceControl;
struct ASurfaceTransaction;
struct SurfaceControlApi {
    ASurfaceControl* (*createFromWindow)(ANativeWindow*, const char*) = nullptr;
    void (*release)(ASurfaceControl*) = nullptr;
    ASurfaceTransaction* (*txnCreate)() = nullptr;
    void (*txnDelete)(ASurfaceTransaction*) = nullptr;
    void (*txnApply)(ASurfaceTransaction*) = nullptr;
    void (*setBuffer)(ASurfaceTransaction*, ASurfaceControl*, AHardwareBuffer*, int) = nullptr;
    void (*setVisibility)(ASurfaceTransaction*, ASurfaceControl*, int8_t) = nullptr;
    void (*setTransparency)(ASurfaceTransaction*, ASurfaceControl*, int8_t) = nullptr;

    bool load() {
        if (createFromWindow) return true;
        void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) return false;
        createFromWindow = reinterpret_cast<decltype(createFromWindow)>(dlsym(lib, "ASurfaceControl_createFromWindow"));
        release = reinterpret_cast<decltype(release)>(dlsym(lib, "ASurfaceControl_release"));
        txnCreate = reinterpret_cast<decltype(txnCreate)>(dlsym(lib, "ASurfaceTransaction_create"));
        txnDelete = reinterpret_cast<decltype(txnDelete)>(dlsym(lib, "ASurfaceTransaction_delete"));
        txnApply = reinterpret_cast<decltype(txnApply)>(dlsym(lib, "ASurfaceTransaction_apply"));
        setBuffer = reinterpret_cast<decltype(setBuffer)>(dlsym(lib, "ASurfaceTransaction_setBuffer"));
        setVisibility = reinterpret_cast<decltype(setVisibility)>(dlsym(lib, "ASurfaceTransaction_setVisibility"));
        setTransparency = reinterpret_cast<decltype(setTransparency)>(
            dlsym(lib, "ASurfaceTransaction_setBufferTransparency"));
        const bool ok = createFromWindow && release && txnCreate && txnDelete && txnApply &&
                        setBuffer && setVisibility && setTransparency;
        if (!ok) createFromWindow = nullptr;
        return ok;
    }
};
SurfaceControlApi gSc;

constexpr int8_t kVisibilityHide = 0;
constexpr int8_t kVisibilityShow = 1;
constexpr int8_t kTransparencyTranslucent = 1;
// AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY / _FRONT_BUFFER, spelled out: FRONT_BUFFER is API 33 and
// absent from older NDK headers. Allocation is retried without it where it isn't supported.
constexpr uint64_t kUsageComposerOverlay = 1ULL << 11;
constexpr uint64_t kUsageFrontBuffer = 1ULL << 32;

struct Push {
    float row0[4];
    float row1[4];
    int32_t region[4];
    int32_t mode;
};
constexpr int kModePresent = 0;
constexpr int kModeSnapshot = 1;
constexpr int kModeClear = 2;
constexpr uint32_t kTile = 16;

struct Image {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    int width = 0;
    int height = 0;
    bool general = false;  // transitioned to GENERAL yet
};

}  // namespace

class LiveStrokeOverlay {
public:
    ~LiveStrokeOverlay() { destroy(); }

    bool create() {
        if (!gSc.load()) {
            OVL_LOGI("SurfaceControl NDK API unavailable (API < 29): overlay disabled");
            return false;
        }
        return createDevice() && createPipeline() && createCommands();
    }

    bool attach(ANativeWindow* window, int width, int height) {
        if (surface_ != nullptr && window == window_ && width == overlay_.width && height == overlay_.height) {
            return true;
        }
        detach();
        surface_ = gSc.createFromWindow(window, "graffux-live-stroke");
        if (surface_ == nullptr) return false;
        window_ = window;
        ANativeWindow_acquire(window_);

        AHardwareBuffer_Desc desc{};
        desc.width = static_cast<uint32_t>(width);
        desc.height = static_cast<uint32_t>(height);
        desc.layers = 1;
        desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        desc.usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                     kUsageComposerOverlay | kUsageFrontBuffer;
        frontBuffered_ = AHardwareBuffer_allocate(&desc, &overlayBuffer_) == 0;
        if (!frontBuffered_) {
            desc.usage &= ~kUsageFrontBuffer;
            if (AHardwareBuffer_allocate(&desc, &overlayBuffer_) != 0) {
                overlayBuffer_ = nullptr;
                detach();
                return false;
            }
        }
        if (!importBuffer(overlayBuffer_, width, height, overlay_) || !bindOverlay() ||
            !run(kModeClear, nullptr, 0, 0, width, height)) {
            detach();
            return false;
        }
        ASurfaceTransaction* txn = gSc.txnCreate();
        gSc.setTransparency(txn, surface_, kTransparencyTranslucent);
        gSc.setBuffer(txn, surface_, overlayBuffer_, -1);
        gSc.setVisibility(txn, surface_, kVisibilityHide);
        gSc.txnApply(txn);
        gSc.txnDelete(txn);
        OVL_LOGI("overlay attached %dx%d (front-buffered: %d)", width, height, frontBuffered_ ? 1 : 0);
        return true;
    }

    // Import the engine's layer, snapshot it as the stroke's base, clear and show the overlay.
    bool beginStroke(AHardwareBuffer* layer, int width, int height) {
        if (surface_ == nullptr || layer == nullptr) return false;
        releaseLayer();
        if (!importBuffer(layer, width, height, layer_)) return false;
        AHardwareBuffer_acquire(layer);
        layerBuffer_ = layer;
        if (base_.width != width || base_.height != height) {
            destroyImage(base_);
            if (!createPlainImage(width, height, base_)) return false;
        }
        bindLayerAndBase();
        if (!run(kModeSnapshot, nullptr, 0, 0, width, height) ||
            !run(kModeClear, nullptr, 0, 0, overlay_.width, overlay_.height)) {
            return false;
        }
        return show(true);
    }

    // `m` maps an overlay pixel centre to layer pixel coordinates; the region is in overlay pixels.
    bool present(const float m[6], int x, int y, int w, int h) {
        if (surface_ == nullptr || layer_.image == VK_NULL_HANDLE) return false;
        x = std::max(0, x);
        y = std::max(0, y);
        w = std::min(w, overlay_.width - x);
        h = std::min(h, overlay_.height - y);
        if (w <= 0 || h <= 0) return true;
        if (!run(kModePresent, m, x, y, w, h)) return false;
        return show(false);
    }

    // Clears and hides the overlay and lets go of the layer. The committed layer is on screen by
    // the time the caller ends the stroke.
    bool endStroke() {
        if (surface_ == nullptr) return false;
        bool ok = run(kModeClear, nullptr, 0, 0, overlay_.width, overlay_.height);
        ASurfaceTransaction* txn = gSc.txnCreate();
        gSc.setBuffer(txn, surface_, overlayBuffer_, -1);
        gSc.setVisibility(txn, surface_, kVisibilityHide);
        gSc.txnApply(txn);
        gSc.txnDelete(txn);
        releaseLayer();
        return ok;
    }

    bool frontBuffered() const { return frontBuffered_; }

    void detach() {
        releaseLayer();
        if (surface_ != nullptr) {
            ASurfaceTransaction* txn = gSc.txnCreate();
            gSc.setVisibility(txn, surface_, kVisibilityHide);
            gSc.txnApply(txn);
            gSc.txnDelete(txn);
            gSc.release(surface_);
            surface_ = nullptr;
        }
        if (device_ != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);
        destroyImage(overlay_);
        if (overlayBuffer_ != nullptr) AHardwareBuffer_release(overlayBuffer_);
        overlayBuffer_ = nullptr;
        if (window_ != nullptr) ANativeWindow_release(window_);
        window_ = nullptr;
    }

    void destroy() {
        detach();
        if (device_ != VK_NULL_HANDLE) {
            destroyImage(base_);
            if (fence_) vkDestroyFence(device_, fence_, nullptr);
            if (commandPool_) vkDestroyCommandPool(device_, commandPool_, nullptr);
            if (pipeline_) vkDestroyPipeline(device_, pipeline_, nullptr);
            if (pipelineLayout_) vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
            if (descriptorPool_) vkDestroyDescriptorPool(device_, descriptorPool_, nullptr);
            if (setLayout_) vkDestroyDescriptorSetLayout(device_, setLayout_, nullptr);
            vkDestroyDevice(device_, nullptr);
        }
        if (instance_ != VK_NULL_HANDLE) vkDestroyInstance(instance_, nullptr);
        device_ = VK_NULL_HANDLE;
        instance_ = VK_NULL_HANDLE;
        fence_ = VK_NULL_HANDLE;
        commandPool_ = VK_NULL_HANDLE;
        pipeline_ = VK_NULL_HANDLE;
        pipelineLayout_ = VK_NULL_HANDLE;
        descriptorPool_ = VK_NULL_HANDLE;
        setLayout_ = VK_NULL_HANDLE;
    }

private:
    bool ok(VkResult r, const char* what) {
        if (r == VK_SUCCESS) return true;
        OVL_LOGE("%s failed: %d", what, static_cast<int>(r));
        return false;
    }

    bool createDevice() {
        VkApplicationInfo app{};
        app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        app.pApplicationName = "graffux-live-overlay";
        app.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo ici{};
        ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        ici.pApplicationInfo = &app;
        if (!ok(vkCreateInstance(&ici, nullptr, &instance_), "vkCreateInstance")) return false;

        uint32_t count = 0;
        vkEnumeratePhysicalDevices(instance_, &count, nullptr);
        if (count == 0) return false;
        std::vector<VkPhysicalDevice> devices(count);
        vkEnumeratePhysicalDevices(instance_, &count, devices.data());
        for (VkPhysicalDevice candidate : devices) {
            uint32_t families = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &families, nullptr);
            std::vector<VkQueueFamilyProperties> props(families);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &families, props.data());
            for (uint32_t i = 0; i < families; ++i) {
                if (props[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
                    physicalDevice_ = candidate;
                    queueFamily_ = i;
                    break;
                }
            }
            if (physicalDevice_ != VK_NULL_HANDLE) break;
        }
        if (physicalDevice_ == VK_NULL_HANDLE) return false;

        // Same AHardwareBuffer import extensions as VulkanStampEngine::initWithHardwareBuffer.
        const char* extensions[] = {
            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
            VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME,
            VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
        };
        float priority = 1.0f;
        VkDeviceQueueCreateInfo qci{};
        qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        qci.queueFamilyIndex = queueFamily_;
        qci.queueCount = 1;
        qci.pQueuePriorities = &priority;
        VkDeviceCreateInfo dci{};
        dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        dci.queueCreateInfoCount = 1;
        dci.pQueueCreateInfos = &qci;
        dci.enabledExtensionCount = 3;
        dci.ppEnabledExtensionNames = extensions;
        if (!ok(vkCreateDevice(physicalDevice_, &dci, nullptr, &device_), "vkCreateDevice")) return false;
        vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);
        getAhbProps_ = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
            vkGetDeviceProcAddr(device_, "vkGetAndroidHardwareBufferPropertiesANDROID"));
        return getAhbProps_ != nullptr;
    }

    bool createPipeline() {
        VkDescriptorSetLayoutBinding bindings[3]{};
        for (uint32_t i = 0; i < 3; ++i) {
            bindings[i].binding = i;
            bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            bindings[i].descriptorCount = 1;
            bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        VkDescriptorSetLayoutCreateInfo lci{};
        lci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        lci.bindingCount = 3;
        lci.pBindings = bindings;
        if (!ok(vkCreateDescriptorSetLayout(device_, &lci, nullptr, &setLayout_), "set layout")) return false;

        VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 3};
        VkDescriptorPoolCreateInfo pci{};
        pci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        pci.maxSets = 1;
        pci.poolSizeCount = 1;
        pci.pPoolSizes = &size;
        if (!ok(vkCreateDescriptorPool(device_, &pci, nullptr, &descriptorPool_), "descriptor pool")) return false;
        VkDescriptorSetAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        ai.descriptorPool = descriptorPool_;
        ai.descriptorSetCount = 1;
        ai.pSetLayouts = &setLayout_;
        if (!ok(vkAllocateDescriptorSets(device_, &ai, &set_), "descriptor set")) return false;

        VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(Push)};
        VkPipelineLayoutCreateInfo plci{};
        plci.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        plci.setLayoutCount = 1;
        plci.pSetLayouts = &setLayout_;
        plci.pushConstantRangeCount = 1;
        plci.pPushConstantRanges = &range;
        if (!ok(vkCreatePipelineLayout(device_, &plci, nullptr, &pipelineLayout_), "pipeline layout")) return false;

        VkShaderModuleCreateInfo smci{};
        smci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        smci.codeSize = sizeof(kLiveOverlayCompSpv);
        smci.pCode = kLiveOverlayCompSpv;
        VkShaderModule module = VK_NULL_HANDLE;
        if (!ok(vkCreateShaderModule(device_, &smci, nullptr, &module), "shader module")) return false;
        VkComputePipelineCreateInfo cpci{};
        cpci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        cpci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        cpci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        cpci.stage.module = module;
        cpci.stage.pName = "main";
        cpci.layout = pipelineLayout_;
        bool created = ok(vkCreateComputePipelines(device_, VK_NULL_HANDLE, 1, &cpci, nullptr, &pipeline_),
                          "compute pipeline");
        vkDestroyShaderModule(device_, module, nullptr);
        return created;
    }

    bool createCommands() {
        VkCommandPoolCreateInfo pci{};
        pci.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        pci.queueFamilyIndex = queueFamily_;
        if (!ok(vkCreateCommandPool(device_, &pci, nullptr, &commandPool_), "command pool")) return false;
        VkCommandBufferAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        ai.commandPool = commandPool_;
        ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ai.commandBufferCount = 1;
        if (!ok(vkAllocateCommandBuffers(device_, &ai, &cmd_), "command buffer")) return false;
        VkFenceCreateInfo fci{};
        fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        return ok(vkCreateFence(device_, &fci, nullptr, &fence_), "fence");
    }

    int32_t memoryType(uint32_t bits, VkMemoryPropertyFlags flags) {
        VkPhysicalDeviceMemoryProperties mp;
        vkGetPhysicalDeviceMemoryProperties(physicalDevice_, &mp);
        for (uint32_t i = 0; i < mp.memoryTypeCount; ++i) {
            if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & flags) == flags) {
                return static_cast<int32_t>(i);
            }
        }
        return -1;
    }

    bool makeView(Image& img, VkFormat format) {
        VkImageViewCreateInfo vci{};
        vci.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vci.image = img.image;
        vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vci.format = format;
        vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        return ok(vkCreateImageView(device_, &vci, nullptr, &img.view), "image view");
    }

    // Same import recipe as VulkanStampEngine::createLayerImageFromHardwareBuffer.
    bool importBuffer(AHardwareBuffer* buffer, int width, int height, Image& img) {
        VkAndroidHardwareBufferFormatPropertiesANDROID formatProps{};
        formatProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
        VkAndroidHardwareBufferPropertiesANDROID props{};
        props.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
        props.pNext = &formatProps;
        if (!ok(getAhbProps_(device_, buffer, &props), "AHB properties")) return false;
        const VkFormat format =
            formatProps.format != VK_FORMAT_UNDEFINED ? formatProps.format : VK_FORMAT_R8G8B8A8_UNORM;

        VkExternalMemoryImageCreateInfo ext{};
        ext.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
        ext.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
        VkImageCreateInfo ici{};
        ici.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        ici.pNext = &ext;
        ici.imageType = VK_IMAGE_TYPE_2D;
        ici.format = format;
        ici.extent = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1};
        ici.mipLevels = 1;
        ici.arrayLayers = 1;
        ici.samples = VK_SAMPLE_COUNT_1_BIT;
        ici.tiling = VK_IMAGE_TILING_OPTIMAL;
        ici.usage = VK_IMAGE_USAGE_STORAGE_BIT;
        ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        if (!ok(vkCreateImage(device_, &ici, nullptr, &img.image), "vkCreateImage(AHB)")) return false;

        VkImportAndroidHardwareBufferInfoANDROID import{};
        import.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
        import.buffer = buffer;
        VkMemoryDedicatedAllocateInfo dedicated{};
        dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
        dedicated.pNext = &import;
        dedicated.image = img.image;
        int32_t type = memoryType(props.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (type < 0) type = memoryType(props.memoryTypeBits, 0);
        if (type < 0) return false;
        VkMemoryAllocateInfo mai{};
        mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        mai.pNext = &dedicated;
        mai.allocationSize = props.allocationSize;
        mai.memoryTypeIndex = static_cast<uint32_t>(type);
        if (!ok(vkAllocateMemory(device_, &mai, nullptr, &img.memory), "vkAllocateMemory(AHB)")) return false;
        if (!ok(vkBindImageMemory(device_, img.image, img.memory, 0), "vkBindImageMemory(AHB)")) return false;
        img.width = width;
        img.height = height;
        img.general = false;
        return makeView(img, format);
    }

    bool createPlainImage(int width, int height, Image& img) {
        VkImageCreateInfo ici{};
        ici.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        ici.imageType = VK_IMAGE_TYPE_2D;
        ici.format = VK_FORMAT_R8G8B8A8_UNORM;
        ici.extent = {static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1};
        ici.mipLevels = 1;
        ici.arrayLayers = 1;
        ici.samples = VK_SAMPLE_COUNT_1_BIT;
        ici.tiling = VK_IMAGE_TILING_OPTIMAL;
        ici.usage = VK_IMAGE_USAGE_STORAGE_BIT;
        ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        if (!ok(vkCreateImage(device_, &ici, nullptr, &img.image), "vkCreateImage(base)")) return false;
        VkMemoryRequirements req;
        vkGetImageMemoryRequirements(device_, img.image, &req);
        int32_t type = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (type < 0) return false;
        VkMemoryAllocateInfo mai{};
        mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = static_cast<uint32_t>(type);
        if (!ok(vkAllocateMemory(device_, &mai, nullptr, &img.memory), "vkAllocateMemory(base)")) return false;
        if (!ok(vkBindImageMemory(device_, img.image, img.memory, 0), "vkBindImageMemory(base)")) return false;
        img.width = width;
        img.height = height;
        img.general = false;
        return makeView(img, VK_FORMAT_R8G8B8A8_UNORM);
    }

    void destroyImage(Image& img) {
        if (device_ == VK_NULL_HANDLE) return;
        if (img.view) vkDestroyImageView(device_, img.view, nullptr);
        if (img.image) vkDestroyImage(device_, img.image, nullptr);
        if (img.memory) vkFreeMemory(device_, img.memory, nullptr);
        img = Image{};
    }

    void releaseLayer() {
        if (device_ != VK_NULL_HANDLE && layer_.image != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);
        destroyImage(layer_);
        if (layerBuffer_ != nullptr) AHardwareBuffer_release(layerBuffer_);
        layerBuffer_ = nullptr;
    }

    void writeBinding(uint32_t binding, VkImageView view) {
        VkDescriptorImageInfo info{VK_NULL_HANDLE, view, VK_IMAGE_LAYOUT_GENERAL};
        VkWriteDescriptorSet w{};
        w.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w.dstSet = set_;
        w.dstBinding = binding;
        w.descriptorCount = 1;
        w.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        w.pImageInfo = &info;
        vkUpdateDescriptorSets(device_, 1, &w, 0, nullptr);
    }

    bool bindOverlay() {
        writeBinding(2, overlay_.view);
        // Until a stroke starts, point the layer/base bindings at the overlay too so the set is
        // always complete; mode 2 (clear) never reads them.
        if (layer_.view == VK_NULL_HANDLE) writeBinding(0, overlay_.view);
        if (base_.view == VK_NULL_HANDLE) writeBinding(1, overlay_.view);
        return true;
    }

    void bindLayerAndBase() {
        writeBinding(0, layer_.view);
        writeBinding(1, base_.view);
    }

    // Imported buffers are acquired from the foreign (display / other API) queue family every time,
    // since their producer is outside this device; the plain base image transitions once.
    void acquire(VkCommandBuffer cmd, Image& img, bool external) {
        if (img.image == VK_NULL_HANDLE) return;
        if (!external && img.general) return;
        VkImageMemoryBarrier b{};
        b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.srcAccessMask = 0;
        b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        b.oldLayout = img.general ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
        b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        b.srcQueueFamilyIndex = external ? VK_QUEUE_FAMILY_FOREIGN_EXT : VK_QUEUE_FAMILY_IGNORED;
        b.dstQueueFamilyIndex = external ? queueFamily_ : VK_QUEUE_FAMILY_IGNORED;
        b.image = img.image;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
        img.general = true;
    }

    // Hands the overlay buffer back to the foreign (compositor) queue family after writing it.
    void releaseToForeign(VkCommandBuffer cmd, Image& img) {
        VkImageMemoryBarrier b{};
        b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        b.dstAccessMask = 0;
        b.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        b.srcQueueFamilyIndex = queueFamily_;
        b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        b.image = img.image;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
    }

    bool run(int mode, const float* m, int x, int y, int w, int h) {
        if (!ok(vkResetCommandBuffer(cmd_, 0), "reset")) return false;
        VkCommandBufferBeginInfo bi{};
        bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (!ok(vkBeginCommandBuffer(cmd_, &bi), "begin")) return false;
        acquire(cmd_, overlay_, true);
        if (mode != kModeClear) acquire(cmd_, layer_, true);
        acquire(cmd_, base_, false);

        Push push{};
        if (m != nullptr) {
            push.row0[0] = m[0]; push.row0[1] = m[1]; push.row0[2] = m[2];
            push.row1[0] = m[3]; push.row1[1] = m[4]; push.row1[2] = m[5];
        }
        push.region[0] = x;
        push.region[1] = y;
        push.region[2] = w;
        push.region[3] = h;
        push.mode = mode;
        vkCmdBindPipeline(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
        vkCmdBindDescriptorSets(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_, 0, 1, &set_, 0, nullptr);
        vkCmdPushConstants(cmd_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), &push);
        vkCmdDispatch(cmd_, (static_cast<uint32_t>(w) + kTile - 1) / kTile,
                      (static_cast<uint32_t>(h) + kTile - 1) / kTile, 1);
        releaseToForeign(cmd_, overlay_);
        if (!ok(vkEndCommandBuffer(cmd_), "end")) return false;

        VkSubmitInfo si{};
        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd_;
        vkResetFences(device_, 1, &fence_);
        if (!ok(vkQueueSubmit(queue_, 1, &si, fence_), "submit")) return false;
        // Waited here, so the buffer is complete before SurfaceFlinger sees it (acquire fence -1).
        return ok(vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX), "wait");
    }

    bool show(bool visible) {
        ASurfaceTransaction* txn = gSc.txnCreate();
        // Front-buffered: the display already scans this memory; re-setting the buffer still lets
        // compositors that don't honour front-buffer usage latch the new pixels.
        gSc.setBuffer(txn, surface_, overlayBuffer_, -1);
        if (visible) gSc.setVisibility(txn, surface_, kVisibilityShow);
        gSc.txnApply(txn);
        gSc.txnDelete(txn);
        return true;
    }

    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = 0;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getAhbProps_ = nullptr;
    VkDescriptorSetLayout setLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet set_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    VkCommandBuffer cmd_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;

    ANativeWindow* window_ = nullptr;
    ASurfaceControl* surface_ = nullptr;
    AHardwareBuffer* overlayBuffer_ = nullptr;
    AHardwareBuffer* layerBuffer_ = nullptr;
    bool frontBuffered_ = false;
    Image overlay_;
    Image layer_;
    Image base_;
};

}  // namespace graffux

namespace {
graffux::LiveStrokeOverlay* fromHandle(jlong handle) {
    return reinterpret_cast<graffux::LiveStrokeOverlay*>(handle);
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_LiveStrokeOverlay_nativeCreate(JNIEnv*, jobject) {
    auto* overlay = new graffux::LiveStrokeOverlay();
    if (!overlay->create()) {
        delete overlay;
        return 0;
    }
    return reinterpret_cast<jlong>(overlay);
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_LiveStrokeOverlay_nativeAttach(
    JNIEnv* env, jobject, jlong handle, jobject surface, jint width, jint height) {
    auto* overlay = fromHandle(handle);
    if (!overlay || !surface || width <= 0 || height <= 0) return JNI_FALSE;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;
    const bool ok = overlay->attach(window, width, height);
    ANativeWindow_release(window);  // attach() holds its own reference
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_LiveStrokeOverlay_nativeBeginStroke(
    JNIEnv* env, jobject, jlong handle, jobject hardwareBuffer, jint width, jint height) {
    auto* overlay = fromHandle(handle);
    if (!overlay || !hardwareBuffer) return JNI_FALSE;
    AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    return overlay->beginStroke(buffer, width, height) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_LiveStrokeOverlay_nativePresent(
    JNIEnv* env, jobject, jlong handle, jfloatArray matrix, jintArray region) {
    auto* overlay = fromHandle(handle);
    if (!overlay || env->GetArrayLength(matrix) < 6 || env->GetArrayLength(region) < 4) return JNI_FALSE;
    float m[6];
    env->GetFloatArrayRegion(matrix, 0, 6, m);
    jint r[4];
    env->GetIntArrayRegion(region, 0, 4, r);
    return overlay->present(m, r[0], r[1], r[2], r[3]) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_LiveStrokeOverlay_nativeEndStroke(JNIEnv*, jobject, jlong handle) {
    auto* overlay = fromHandle(handle);
    return overlay && overlay->endStroke() ? JNI_TRUE : JNI_FALSE;
}


JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_LiveStrokeOverlay_nativeDetach(JNIEnv*, jobject, jlong handle) {
    if (auto* overlay = fromHandle(handle)) overlay->detach();
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_LiveStrokeOverlay_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete fromHandle(handle);
}

}  // extern "C"
