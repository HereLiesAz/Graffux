// FILE: core/nativebridge/src/main/cpp/VulkanStampEngineReuse.cpp
#include <jni.h>
#include <android/log.h>

#include "include/VulkanStampEngine.h"

#define REUSE_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "VulkanStampEngine", __VA_ARGS__)

namespace {

bool reuseCheck(VkResult result, const char* what) {
    if (result == VK_SUCCESS) return true;
    REUSE_LOGE("%s failed while resetting pooled engine: VkResult=%d", what, static_cast<int>(result));
    return false;
}

}  // namespace

namespace graffux {

bool VulkanStampEngine::clear() {
    if (!isInitialized()) return false;

    if (!reuseCheck(vkResetCommandBuffer(commandBuffer_, 0), "vkResetCommandBuffer(clear)")) {
        return false;
    }

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (!reuseCheck(vkBeginCommandBuffer(commandBuffer_, &beginInfo), "vkBeginCommandBuffer(clear)")) {
        return false;
    }

    // A never-used image may still be UNDEFINED; the existing helper transitions it to GENERAL and
    // performs its first transparent clear. A previously used pooled image is already GENERAL, so
    // this is a no-op in the normal reuse path.
    ensureLayerImageGeneral(commandBuffer_);

    // Every public operation in VulkanStampEngine waits on the same fence before returning, so no
    // work is concurrently touching this image. Keep the explicit barrier anyway: it makes the
    // transfer clear's dependency correct even if the implementation later stops using a blocking
    // fence for every stamp/readback.
    VkImageMemoryBarrier beforeClear{};
    beforeClear.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    beforeClear.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    beforeClear.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    beforeClear.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT |
                                VK_ACCESS_TRANSFER_READ_BIT;
    beforeClear.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    beforeClear.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    beforeClear.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    beforeClear.image = layerImage_;
    beforeClear.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                         &beforeClear);

    VkClearColorValue transparent{};
    transparent.float32[0] = 0.0f;
    transparent.float32[1] = 0.0f;
    transparent.float32[2] = 0.0f;
    transparent.float32[3] = 0.0f;
    VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdClearColorImage(commandBuffer_, layerImage_, VK_IMAGE_LAYOUT_GENERAL, &transparent, 1, &range);

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
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1,
                         &afterClear);

    if (!reuseCheck(vkEndCommandBuffer(commandBuffer_), "vkEndCommandBuffer(clear)")) {
        return false;
    }

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer_;

    if (!reuseCheck(vkResetFences(device_, 1, &fence_), "vkResetFences(clear)")) return false;
    if (!reuseCheck(vkQueueSubmit(queue_, 1, &submitInfo, fence_), "vkQueueSubmit(clear)")) return false;
    if (!reuseCheck(
            vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX),
            "vkWaitForFences(clear)"
        )) {
        return false;
    }
    // A clear touches every pixel, so the next readback() must copy the whole layer regardless of
    // whatever narrower region a prior stampDabs()/stampMaskedDabs() call had left dirty.
    markLayerFullyDirty();
    return true;
}

}  // namespace graffux

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_VulkanStampEngine_nativeClear(
        JNIEnv*, jobject, jlong handle) {
    auto* engine = reinterpret_cast<graffux::VulkanStampEngine*>(handle);
    if (!engine || !engine->isInitialized()) return JNI_FALSE;
    return engine->clear() ? JNI_TRUE : JNI_FALSE;
}
