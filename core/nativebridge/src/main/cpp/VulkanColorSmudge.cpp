#include "include/VulkanStampEngine.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <unordered_map>

#include "ColorSmudge8Spv.h"
#include "ColorSmudge16Spv.h"

namespace graffux {
namespace {

struct SmudgePush {
    int32_t phase;
    int32_t centerX;
    int32_t centerY;
    int32_t radius;
    int32_t sampleRadius;
    int32_t smearAlpha;
    int32_t originX;
    int32_t originY;
    float soft;
    float smudgeRate;
    float colorRate;
    float opacity;
    float paintR;
    float paintG;
    float paintB;
    float paintA;
    float dilution;
    float hasSampleMerged;
};
static_assert(sizeof(SmudgePush) == 72);

std::mutex gBenchmarkMutex;
std::unordered_map<uint64_t, ColorSmudgeBenchmarkInfo> gBenchmarkCache;

bool vkOk(VkResult r) { return r == VK_SUCCESS; }

uint64_t deviceKey(uint32_t vendorId, uint32_t deviceId) {
    return (static_cast<uint64_t>(vendorId) << 32) | deviceId;
}

}  // namespace

bool VulkanStampEngine::ensureColorSmudgePipelines() {
    if (smudgePipeline8_ != VK_NULL_HANDLE && smudgePipeline16_ != VK_NULL_HANDLE) return true;
    if (!isInitialized()) return false;

    VkDescriptorSetLayoutBinding bindings[3]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    // Item 11 (Sample Merged): the pre-composited "what the artist can see" RGBA8 texture,
    // color_smudge.comp's sampleSourceTex.
    bindings[2].binding = 2;
    bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[2].descriptorCount = 1;
    bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 3;
    layoutInfo.pBindings = bindings;
    if (!vkOk(vkCreateDescriptorSetLayout(device_, &layoutInfo, nullptr, &smudgeDescriptorSetLayout_))) {
        destroyColorSmudgeResources();
        return false;
    }

    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.offset = 0;
    pushRange.size = sizeof(SmudgePush);

    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &smudgeDescriptorSetLayout_;
    pipelineLayoutInfo.pushConstantRangeCount = 1;
    pipelineLayoutInfo.pPushConstantRanges = &pushRange;
    if (!vkOk(vkCreatePipelineLayout(device_, &pipelineLayoutInfo, nullptr, &smudgePipelineLayout_))) {
        destroyColorSmudgeResources();
        return false;
    }

    VkDescriptorPoolSize poolSizes[3]{};
    poolSizes[0] = {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1};
    poolSizes[1] = {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1};
    poolSizes[2] = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1};
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 3;
    poolInfo.pPoolSizes = poolSizes;
    if (!vkOk(vkCreateDescriptorPool(device_, &poolInfo, nullptr, &smudgeDescriptorPool_))) {
        destroyColorSmudgeResources();
        return false;
    }

    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = smudgeDescriptorPool_;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &smudgeDescriptorSetLayout_;
    if (!vkOk(vkAllocateDescriptorSets(device_, &allocInfo, &smudgeDescriptorSet_))) {
        destroyColorSmudgeResources();
        return false;
    }

    VkDescriptorImageInfo imageInfo{};
    imageInfo.imageView = layerImageView_;
    imageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet imageWrite{};
    imageWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    imageWrite.dstSet = smudgeDescriptorSet_;
    imageWrite.dstBinding = 0;
    imageWrite.descriptorCount = 1;
    imageWrite.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    imageWrite.pImageInfo = &imageInfo;
    vkUpdateDescriptorSets(device_, 1, &imageWrite, 0, nullptr);

    auto createPipeline = [&](const uint32_t* words, size_t wordCount,
                              VkShaderModule& module, VkPipeline& pipeline) -> bool {
        VkShaderModuleCreateInfo shaderInfo{};
        shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        shaderInfo.codeSize = wordCount * sizeof(uint32_t);
        shaderInfo.pCode = words;
        if (!vkOk(vkCreateShaderModule(device_, &shaderInfo, nullptr, &module))) return false;

        VkPipelineShaderStageCreateInfo stage{};
        stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        stage.module = module;
        stage.pName = "main";
        VkComputePipelineCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        info.stage = stage;
        info.layout = smudgePipelineLayout_;
        return vkOk(vkCreateComputePipelines(device_, VK_NULL_HANDLE, 1, &info, nullptr, &pipeline));
    };

    if (!createPipeline(kColorSmudge8CompSpv, kColorSmudge8CompSpvWords,
                        smudgeShader8_, smudgePipeline8_) ||
        !createPipeline(kColorSmudge16CompSpv, kColorSmudge16CompSpvWords,
                        smudgeShader16_, smudgePipeline16_)) {
        destroyColorSmudgeResources();
        return false;
    }

    // Item 11 (Sample Merged): NEAREST/CLAMP sampler for descriptor-type consistency (the shader
    // itself only ever reads this texture via texelFetch, which ignores the sampler's filter/wrap
    // state -- see color_smudge.comp's sampleSourceTex doc comment). Binding 2 must reference a
    // valid image once the pipeline is used, so a 1x1 transparent-black dummy is bound here,
    // exactly like the masked-stamp pipeline's grain/secondary-mask dummies.
    VkSamplerCreateInfo sampleSourceSamplerInfo{};
    sampleSourceSamplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    sampleSourceSamplerInfo.magFilter = VK_FILTER_NEAREST;
    sampleSourceSamplerInfo.minFilter = VK_FILTER_NEAREST;
    sampleSourceSamplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampleSourceSamplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampleSourceSamplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampleSourceSamplerInfo.unnormalizedCoordinates = VK_FALSE;
    if (!vkOk(vkCreateSampler(device_, &sampleSourceSamplerInfo, nullptr, &smudgeSampleSourceSampler_))) {
        destroyColorSmudgeResources();
        return false;
    }
    if (!ensureSampleSourceTexture(1, 1)) {
        destroyColorSmudgeResources();
        return false;
    }
    const uint8_t dummySampleSource[4] = {0, 0, 0, 0};
    if (!uploadSampleSourceTexture(dummySampleSource, 1, 1)) {
        destroyColorSmudgeResources();
        return false;
    }
    return true;
}

bool VulkanStampEngine::ensureSampleSourceTexture(int w, int h) {
    if (smudgeSampleSourceImage_ != VK_NULL_HANDLE && w == smudgeSampleSourceWidth_ &&
        h == smudgeSampleSourceHeight_) {
        return true;
    }

    if (smudgeSampleSourceImageView_ != VK_NULL_HANDLE) { vkDestroyImageView(device_, smudgeSampleSourceImageView_, nullptr); smudgeSampleSourceImageView_ = VK_NULL_HANDLE; }
    if (smudgeSampleSourceImage_ != VK_NULL_HANDLE) { vkDestroyImage(device_, smudgeSampleSourceImage_, nullptr); smudgeSampleSourceImage_ = VK_NULL_HANDLE; }
    if (smudgeSampleSourceImageMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, smudgeSampleSourceImageMemory_, nullptr); smudgeSampleSourceImageMemory_ = VK_NULL_HANDLE; }
    if (smudgeSampleSourceStagingBuffer_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, smudgeSampleSourceStagingBuffer_, nullptr); smudgeSampleSourceStagingBuffer_ = VK_NULL_HANDLE; }
    if (smudgeSampleSourceStagingBufferMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, smudgeSampleSourceStagingBufferMemory_, nullptr); smudgeSampleSourceStagingBufferMemory_ = VK_NULL_HANDLE; }
    smudgeSampleSourceImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    smudgeSampleSourceWidth_ = 0;
    smudgeSampleSourceHeight_ = 0;

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.extent = {static_cast<uint32_t>(w), static_cast<uint32_t>(h), 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (!vkOk(vkCreateImage(device_, &imageInfo, nullptr, &smudgeSampleSourceImage_))) return false;

    VkMemoryRequirements memReq;
    vkGetImageMemoryRequirements(device_, smudgeSampleSourceImage_, &memReq);
    int32_t memType = findMemoryType(memReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType < 0) {
        vkDestroyImage(device_, smudgeSampleSourceImage_, nullptr); smudgeSampleSourceImage_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = static_cast<uint32_t>(memType);
    if (!vkOk(vkAllocateMemory(device_, &allocInfo, nullptr, &smudgeSampleSourceImageMemory_))) {
        vkDestroyImage(device_, smudgeSampleSourceImage_, nullptr); smudgeSampleSourceImage_ = VK_NULL_HANDLE;
        return false;
    }
    if (!vkOk(vkBindImageMemory(device_, smudgeSampleSourceImage_, smudgeSampleSourceImageMemory_, 0))) {
        vkDestroyImage(device_, smudgeSampleSourceImage_, nullptr); smudgeSampleSourceImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, smudgeSampleSourceImageMemory_, nullptr); smudgeSampleSourceImageMemory_ = VK_NULL_HANDLE;
        return false;
    }

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = smudgeSampleSourceImage_;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (!vkOk(vkCreateImageView(device_, &viewInfo, nullptr, &smudgeSampleSourceImageView_))) {
        vkDestroyImage(device_, smudgeSampleSourceImage_, nullptr); smudgeSampleSourceImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, smudgeSampleSourceImageMemory_, nullptr); smudgeSampleSourceImageMemory_ = VK_NULL_HANDLE;
        return false;
    }

    VkDeviceSize stagingSize = static_cast<VkDeviceSize>(w) * h * 4;
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = stagingSize;
    bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (!vkOk(vkCreateBuffer(device_, &bufferInfo, nullptr, &smudgeSampleSourceStagingBuffer_))) {
        vkDestroyImageView(device_, smudgeSampleSourceImageView_, nullptr); smudgeSampleSourceImageView_ = VK_NULL_HANDLE;
        vkDestroyImage(device_, smudgeSampleSourceImage_, nullptr); smudgeSampleSourceImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, smudgeSampleSourceImageMemory_, nullptr); smudgeSampleSourceImageMemory_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryRequirements stagingMemReq;
    vkGetBufferMemoryRequirements(device_, smudgeSampleSourceStagingBuffer_, &stagingMemReq);
    int32_t stagingMemType = findMemoryType(
        stagingMemReq.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (stagingMemType < 0) {
        vkDestroyBuffer(device_, smudgeSampleSourceStagingBuffer_, nullptr); smudgeSampleSourceStagingBuffer_ = VK_NULL_HANDLE;
        vkDestroyImageView(device_, smudgeSampleSourceImageView_, nullptr); smudgeSampleSourceImageView_ = VK_NULL_HANDLE;
        vkDestroyImage(device_, smudgeSampleSourceImage_, nullptr); smudgeSampleSourceImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, smudgeSampleSourceImageMemory_, nullptr); smudgeSampleSourceImageMemory_ = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryAllocateInfo stagingAllocInfo{};
    stagingAllocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    stagingAllocInfo.allocationSize = stagingMemReq.size;
    stagingAllocInfo.memoryTypeIndex = static_cast<uint32_t>(stagingMemType);
    if (!vkOk(vkAllocateMemory(device_, &stagingAllocInfo, nullptr, &smudgeSampleSourceStagingBufferMemory_))) {
        vkDestroyBuffer(device_, smudgeSampleSourceStagingBuffer_, nullptr); smudgeSampleSourceStagingBuffer_ = VK_NULL_HANDLE;
        vkDestroyImageView(device_, smudgeSampleSourceImageView_, nullptr); smudgeSampleSourceImageView_ = VK_NULL_HANDLE;
        vkDestroyImage(device_, smudgeSampleSourceImage_, nullptr); smudgeSampleSourceImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, smudgeSampleSourceImageMemory_, nullptr); smudgeSampleSourceImageMemory_ = VK_NULL_HANDLE;
        return false;
    }
    if (!vkOk(vkBindBufferMemory(device_, smudgeSampleSourceStagingBuffer_, smudgeSampleSourceStagingBufferMemory_, 0))) {
        vkDestroyBuffer(device_, smudgeSampleSourceStagingBuffer_, nullptr); smudgeSampleSourceStagingBuffer_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, smudgeSampleSourceStagingBufferMemory_, nullptr); smudgeSampleSourceStagingBufferMemory_ = VK_NULL_HANDLE;
        vkDestroyImageView(device_, smudgeSampleSourceImageView_, nullptr); smudgeSampleSourceImageView_ = VK_NULL_HANDLE;
        vkDestroyImage(device_, smudgeSampleSourceImage_, nullptr); smudgeSampleSourceImage_ = VK_NULL_HANDLE;
        vkFreeMemory(device_, smudgeSampleSourceImageMemory_, nullptr); smudgeSampleSourceImageMemory_ = VK_NULL_HANDLE;
        return false;
    }

    smudgeSampleSourceWidth_ = w;
    smudgeSampleSourceHeight_ = h;

    VkDescriptorImageInfo samplerImageInfo{};
    samplerImageInfo.sampler = smudgeSampleSourceSampler_;
    samplerImageInfo.imageView = smudgeSampleSourceImageView_;
    samplerImageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkWriteDescriptorSet samplerWrite{};
    samplerWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    samplerWrite.dstSet = smudgeDescriptorSet_;
    samplerWrite.dstBinding = 2;
    samplerWrite.descriptorCount = 1;
    samplerWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    samplerWrite.pImageInfo = &samplerImageInfo;
    vkUpdateDescriptorSets(device_, 1, &samplerWrite, 0, nullptr);
    return true;
}

bool VulkanStampEngine::uploadSampleSourceTexture(const uint8_t* rgba8, int w, int h) {
    void* mapped = nullptr;
    VkDeviceSize uploadSize = static_cast<VkDeviceSize>(w) * h * 4;
    if (!vkOk(vkMapMemory(device_, smudgeSampleSourceStagingBufferMemory_, 0, uploadSize, 0, &mapped))) {
        return false;
    }
    std::memcpy(mapped, rgba8, uploadSize);
    vkUnmapMemory(device_, smudgeSampleSourceStagingBufferMemory_);

    if (!vkOk(vkResetCommandBuffer(commandBuffer_, 0))) return false;
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (!vkOk(vkBeginCommandBuffer(commandBuffer_, &beginInfo))) return false;

    VkImageMemoryBarrier toTransferDst{};
    toTransferDst.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toTransferDst.oldLayout = smudgeSampleSourceImageLayout_;
    toTransferDst.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    toTransferDst.srcAccessMask = 0;
    toTransferDst.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toTransferDst.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransferDst.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransferDst.image = smudgeSampleSourceImage_;
    toTransferDst.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                          VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &toTransferDst);

    VkBufferImageCopy region{};
    region.bufferOffset = 0;
    region.bufferRowLength = 0;
    region.bufferImageHeight = 0;
    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.imageOffset = {0, 0, 0};
    region.imageExtent = {static_cast<uint32_t>(w), static_cast<uint32_t>(h), 1};
    vkCmdCopyBufferToImage(commandBuffer_, smudgeSampleSourceStagingBuffer_, smudgeSampleSourceImage_,
                            VK_IMAGE_LAYOUT_GENERAL, 1, &region);

    VkImageMemoryBarrier toShaderRead{};
    toShaderRead.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toShaderRead.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    toShaderRead.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    toShaderRead.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toShaderRead.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    toShaderRead.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toShaderRead.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toShaderRead.image = smudgeSampleSourceImage_;
    toShaderRead.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                          VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                          &toShaderRead);

    if (!vkOk(vkEndCommandBuffer(commandBuffer_))) return false;

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer_;

    vkResetFences(device_, 1, &fence_);
    if (!vkOk(vkQueueSubmit(queue_, 1, &submitInfo, fence_))) return false;
    if (!vkOk(vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX))) return false;
    smudgeSampleSourceImageLayout_ = VK_IMAGE_LAYOUT_GENERAL;
    return true;
}

bool VulkanStampEngine::ensureColorSmudgeCarrier(size_t pixelCount) {
    if (pixelCount <= smudgeCarrierCapacity_ && smudgeCarrier_ != VK_NULL_HANDLE) return true;
    if (!ensureColorSmudgePipelines()) return false;

    if (smudgeCarrier_ != VK_NULL_HANDLE) {
        vkDestroyBuffer(device_, smudgeCarrier_, nullptr);
        smudgeCarrier_ = VK_NULL_HANDLE;
    }
    if (smudgeCarrierMemory_ != VK_NULL_HANDLE) {
        vkFreeMemory(device_, smudgeCarrierMemory_, nullptr);
        smudgeCarrierMemory_ = VK_NULL_HANDLE;
    }
    smudgeCarrierCapacity_ = 0;

    size_t capacity = pixelCount + pixelCount / 2 + 64;
    VkDeviceSize bytes = static_cast<VkDeviceSize>(capacity) * sizeof(float) * 4;
    VkBufferCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    info.size = bytes;
    info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (!vkOk(vkCreateBuffer(device_, &info, nullptr, &smudgeCarrier_))) return false;

    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(device_, smudgeCarrier_, &req);
    int32_t memoryType = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memoryType < 0) return false;
    VkMemoryAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = static_cast<uint32_t>(memoryType);
    if (!vkOk(vkAllocateMemory(device_, &ai, nullptr, &smudgeCarrierMemory_))) return false;
    if (!vkOk(vkBindBufferMemory(device_, smudgeCarrier_, smudgeCarrierMemory_, 0))) return false;

    smudgeCarrierCapacity_ = capacity;
    VkDescriptorBufferInfo bufferInfo{};
    bufferInfo.buffer = smudgeCarrier_;
    bufferInfo.offset = 0;
    bufferInfo.range = VK_WHOLE_SIZE;
    VkWriteDescriptorSet write{};
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = smudgeDescriptorSet_;
    write.dstBinding = 1;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    write.pBufferInfo = &bufferInfo;
    vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);
    return true;
}

bool VulkanStampEngine::runColorSmudgePlan(
        const std::vector<ColorSmudgeDab>& dabs,
        int mode,
        float radiusPx,
        float feathering,
        bool smearAlpha,
        uint32_t paintColorArgb,
        VkPipeline pipeline,
        uint32_t tileSize,
        float dilution,
        bool hasSampleMerged) {
    if (dabs.size() < 2 || pipeline == VK_NULL_HANDLE) return true;
    const int radius = std::max(1, static_cast<int>(radiusPx));
    const int diameter = radius * 2 + 1;
    if (!ensureColorSmudgeCarrier(static_cast<size_t>(diameter) * diameter)) return false;

    if (!vkOk(vkResetCommandBuffer(commandBuffer_, 0))) return false;
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (!vkOk(vkBeginCommandBuffer(commandBuffer_, &begin))) return false;
    ensureLayerImageGeneral(commandBuffer_);

    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, smudgePipelineLayout_,
                            0, 1, &smudgeDescriptorSet_, 0, nullptr);

    const float soft = 0.25f + std::clamp(feathering, 0.0f, 1.0f) * 0.7f;
    const float paintR = static_cast<float>((paintColorArgb >> 16) & 0xFF) / 255.0f;
    const float paintG = static_cast<float>((paintColorArgb >> 8) & 0xFF) / 255.0f;
    const float paintB = static_cast<float>(paintColorArgb & 0xFF) / 255.0f;
    const float paintA = static_cast<float>((paintColorArgb >> 24) & 0xFF) / 255.0f;
    const uint32_t brushGroups = (static_cast<uint32_t>(diameter) + tileSize - 1) / tileSize;

    auto barrier = [&]() {
        VkMemoryBarrier b{};
        b.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        b.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &b, 0, nullptr, 0, nullptr);
    };
    auto push = [&](int phase, const ColorSmudgeDab& d) {
        SmudgePush pc{};
        pc.phase = phase;
        pc.centerX = static_cast<int32_t>(d.x);
        pc.centerY = static_cast<int32_t>(d.y);
        pc.radius = radius;
        pc.sampleRadius = std::max(1, static_cast<int>(std::lround(radiusPx * d.smudgeRadius)));
        pc.smearAlpha = smearAlpha ? 1 : 0;
        pc.soft = soft;
        pc.smudgeRate = std::clamp(d.smudgeRate, 0.0f, 1.0f);
        pc.colorRate = std::clamp(d.colorRate, 0.0f, 1.0f);
        pc.opacity = std::clamp(d.opacity, 0.0f, 1.0f);
        pc.paintR = paintR; pc.paintG = paintG; pc.paintB = paintB; pc.paintA = paintA;
        pc.dilution = std::clamp(dilution, 0.0f, 1.0f);
        pc.hasSampleMerged = hasSampleMerged ? 1.0f : 0.0f;
        vkCmdPushConstants(commandBuffer_, smudgePipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0, sizeof(pc), &pc);
    };

    if (mode == 0) {
        push(0, dabs.front());
        vkCmdDispatch(commandBuffer_, brushGroups, brushGroups, 1);
        barrier();
        for (size_t i = 1; i < dabs.size(); ++i) {
            push(1, dabs[i]);
            vkCmdDispatch(commandBuffer_, brushGroups, brushGroups, 1);
            barrier();
        }
    } else {
        for (size_t i = 1; i < dabs.size(); ++i) {
            push(2, dabs[i]);
            vkCmdDispatch(commandBuffer_, 1, 1, 1);
            barrier();
            push(3, dabs[i]);
            vkCmdDispatch(commandBuffer_, brushGroups, brushGroups, 1);
            barrier();
        }
    }

    if (!vkOk(vkEndCommandBuffer(commandBuffer_))) return false;
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &commandBuffer_;
    if (!vkOk(vkResetFences(device_, 1, &fence_))) return false;
    if (!vkOk(vkQueueSubmit(queue_, 1, &submit, fence_))) return false;
    return vkOk(vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX));
}

bool VulkanStampEngine::benchmarkColorSmudge(float radiusPx) {
    if (smudgeBenchmark_.selectedTileSize != 0) return true;
    if (!ensureColorSmudgePipelines()) return false;

    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(physicalDevice_, &props);
    const uint64_t key = deviceKey(props.vendorID, props.deviceID);
    {
        std::lock_guard<std::mutex> lock(gBenchmarkMutex);
        auto it = gBenchmarkCache.find(key);
        if (it != gBenchmarkCache.end()) {
            smudgeBenchmark_ = it->second;
            return true;
        }
    }

    const int cx = std::max(0, width_ / 2);
    const int cy = std::max(0, height_ / 2);
    std::vector<ColorSmudgeDab> synthetic;
    synthetic.reserve(13);
    for (int i = 0; i < 13; ++i) {
        synthetic.push_back(ColorSmudgeDab{
            static_cast<float>(cx + i - 6), static_cast<float>(cy),
            0.65f, 0.0f, 0.0f, 1.0f,
        });
    }
    const float benchmarkRadius = std::clamp(radiusPx, 4.0f, 24.0f);

    // Warm both pipelines before timing to keep shader/pipeline first-use costs out of the choice.
    if (!runColorSmudgePlan(synthetic, 0, benchmarkRadius, 0.0f, true, 0xFFFFFFFFu,
                            smudgePipeline8_, 8)) return false;
    if (!runColorSmudgePlan(synthetic, 0, benchmarkRadius, 0.0f, true, 0xFFFFFFFFu,
                            smudgePipeline16_, 16)) return false;

    auto timed = [&](VkPipeline pipeline, uint32_t tile) -> uint64_t {
        const auto start = std::chrono::steady_clock::now();
        if (!runColorSmudgePlan(synthetic, 0, benchmarkRadius, 0.0f, true, 0xFFFFFFFFu,
                                pipeline, tile)) return UINT64_MAX;
        const auto end = std::chrono::steady_clock::now();
        return static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(end - start).count());
    };

    const uint64_t ns8 = timed(smudgePipeline8_, 8);
    const uint64_t ns16 = timed(smudgePipeline16_, 16);
    if (ns8 == UINT64_MAX || ns16 == UINT64_MAX) return false;
    smudgeBenchmark_ = ColorSmudgeBenchmarkInfo{
        props.vendorID, props.deviceID, ns8 <= ns16 ? 8u : 16u, ns8, ns16,
    };
    {
        std::lock_guard<std::mutex> lock(gBenchmarkMutex);
        gBenchmarkCache[key] = smudgeBenchmark_;
    }
    return true;
}

bool VulkanStampEngine::colorSmudge(
        const std::vector<ColorSmudgeDab>& dabs,
        int mode,
        float radiusPx,
        float feathering,
        bool smearAlpha,
        uint32_t paintColorArgb,
        float dilution,
        const uint8_t* sampleSourceRgba8,
        int sampleSourceWidth,
        int sampleSourceHeight) {
    if (!isInitialized() || dabs.size() < 2) return false;
    const int radius = std::max(1, static_cast<int>(radiusPx));
    const int diameter = radius * 2 + 1;
    if (!ensureColorSmudgeCarrier(static_cast<size_t>(diameter) * diameter)) return false;
    if (!benchmarkColorSmudge(radiusPx)) return false;

    // Item 11 (Sample Merged): the composite is recomputed once per stroke on the Kotlin side, not
    // per dab, so it's uploaded once here rather than per dispatch inside runColorSmudgePlan()'s
    // loop -- same "upload once per call" discipline stampMaskedDabs() uses for its dab buffers.
    // Unlike ensureGrainTexture()'s caller, which only re-uploads when maskWidth/maskHeight change
    // (a stable tip for a whole stroke), this always re-uploads whenever a sample source is
    // supplied: its *content* changes every stroke even when its dimensions match the previous
    // call, because it's a fresh composite of the other layers' current pixels, not a cached tip.
    const bool hasSampleMerged =
        sampleSourceRgba8 != nullptr && sampleSourceWidth > 0 && sampleSourceHeight > 0;
    if (hasSampleMerged) {
        if (!ensureSampleSourceTexture(sampleSourceWidth, sampleSourceHeight)) return false;
        if (!uploadSampleSourceTexture(sampleSourceRgba8, sampleSourceWidth, sampleSourceHeight)) {
            return false;
        }
    }

    const uint32_t tile = smudgeBenchmark_.selectedTileSize;
    const VkPipeline pipeline = tile == 8 ? smudgePipeline8_ : smudgePipeline16_;
    if (!runColorSmudgePlan(
            dabs, mode, radiusPx, feathering, smearAlpha, paintColorArgb, pipeline, tile, dilution,
            hasSampleMerged)) {
        return false;
    }
    // Unlike stampDabs()/stampMaskedDabs(), Color Smudge doesn't track its own per-dispatch
    // bounding box yet, so the conservative-but-correct choice is to mark the whole layer dirty --
    // narrowing this to the dabs' actual footprint is a separate, not-yet-done optimization.
    markLayerFullyDirty();
    return true;
}

void VulkanStampEngine::destroyColorSmudgeResources() {
    if (device_ == VK_NULL_HANDLE) return;
    if (smudgePipeline8_ != VK_NULL_HANDLE) { vkDestroyPipeline(device_, smudgePipeline8_, nullptr); smudgePipeline8_ = VK_NULL_HANDLE; }
    if (smudgePipeline16_ != VK_NULL_HANDLE) { vkDestroyPipeline(device_, smudgePipeline16_, nullptr); smudgePipeline16_ = VK_NULL_HANDLE; }
    if (smudgeShader8_ != VK_NULL_HANDLE) { vkDestroyShaderModule(device_, smudgeShader8_, nullptr); smudgeShader8_ = VK_NULL_HANDLE; }
    if (smudgeShader16_ != VK_NULL_HANDLE) { vkDestroyShaderModule(device_, smudgeShader16_, nullptr); smudgeShader16_ = VK_NULL_HANDLE; }
    if (smudgePipelineLayout_ != VK_NULL_HANDLE) { vkDestroyPipelineLayout(device_, smudgePipelineLayout_, nullptr); smudgePipelineLayout_ = VK_NULL_HANDLE; }
    if (smudgeDescriptorPool_ != VK_NULL_HANDLE) { vkDestroyDescriptorPool(device_, smudgeDescriptorPool_, nullptr); smudgeDescriptorPool_ = VK_NULL_HANDLE; }
    smudgeDescriptorSet_ = VK_NULL_HANDLE;
    if (smudgeDescriptorSetLayout_ != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(device_, smudgeDescriptorSetLayout_, nullptr); smudgeDescriptorSetLayout_ = VK_NULL_HANDLE; }
    if (smudgeCarrier_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, smudgeCarrier_, nullptr); smudgeCarrier_ = VK_NULL_HANDLE; }
    if (smudgeCarrierMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, smudgeCarrierMemory_, nullptr); smudgeCarrierMemory_ = VK_NULL_HANDLE; }
    smudgeCarrierCapacity_ = 0;
    smudgeBenchmark_ = {};

    if (smudgeSampleSourceSampler_ != VK_NULL_HANDLE) { vkDestroySampler(device_, smudgeSampleSourceSampler_, nullptr); smudgeSampleSourceSampler_ = VK_NULL_HANDLE; }
    if (smudgeSampleSourceImageView_ != VK_NULL_HANDLE) { vkDestroyImageView(device_, smudgeSampleSourceImageView_, nullptr); smudgeSampleSourceImageView_ = VK_NULL_HANDLE; }
    if (smudgeSampleSourceImage_ != VK_NULL_HANDLE) { vkDestroyImage(device_, smudgeSampleSourceImage_, nullptr); smudgeSampleSourceImage_ = VK_NULL_HANDLE; }
    if (smudgeSampleSourceImageMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, smudgeSampleSourceImageMemory_, nullptr); smudgeSampleSourceImageMemory_ = VK_NULL_HANDLE; }
    if (smudgeSampleSourceStagingBuffer_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, smudgeSampleSourceStagingBuffer_, nullptr); smudgeSampleSourceStagingBuffer_ = VK_NULL_HANDLE; }
    if (smudgeSampleSourceStagingBufferMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, smudgeSampleSourceStagingBufferMemory_, nullptr); smudgeSampleSourceStagingBufferMemory_ = VK_NULL_HANDLE; }
    smudgeSampleSourceWidth_ = 0;
    smudgeSampleSourceHeight_ = 0;
    smudgeSampleSourceImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
}

}  // namespace graffux
