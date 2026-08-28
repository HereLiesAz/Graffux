#include "include/VulkanStampEngine.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
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
};
static_assert(sizeof(SmudgePush) == 68);

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

    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 2;
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

    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0] = {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1};
    poolSizes[1] = {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1};
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 2;
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
        float dilution) {
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
        float dilution) {
    if (!isInitialized() || dabs.size() < 2) return false;
    const int radius = std::max(1, static_cast<int>(radiusPx));
    const int diameter = radius * 2 + 1;
    if (!ensureColorSmudgeCarrier(static_cast<size_t>(diameter) * diameter)) return false;
    if (!benchmarkColorSmudge(radiusPx)) return false;
    const uint32_t tile = smudgeBenchmark_.selectedTileSize;
    const VkPipeline pipeline = tile == 8 ? smudgePipeline8_ : smudgePipeline16_;
    return runColorSmudgePlan(
        dabs, mode, radiusPx, feathering, smearAlpha, paintColorArgb, pipeline, tile, dilution);
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
}

}  // namespace graffux
