from pathlib import Path

ROOT = Path('.')


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding='utf-8')


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding='utf-8')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one match, found {count}')
    return text.replace(old, new, 1)


def function_span(text: str, signature: str):
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f'function not found: {signature}')
    brace = text.find('{', start)
    if brace < 0:
        raise SystemExit(f'opening brace not found: {signature}')
    depth = 0
    i = brace
    while i < len(text):
        c = text[i]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return start, i + 1, text[start:i + 1]
        i += 1
    raise SystemExit(f'unclosed function: {signature}')


def replace_function(text: str, signature: str, transform) -> str:
    start, end, block = function_span(text, signature)
    updated = transform(block)
    if updated == block:
        raise SystemExit(f'function transform made no change: {signature}')
    return text[:start] + updated + text[end:]


# -----------------------------------------------------------------------------
# C++ ABI/header
# -----------------------------------------------------------------------------
header_path = 'core/nativebridge/src/main/cpp/include/VulkanStampEngine.h'
h = read(header_path)
h = replace_once(h, 'buffer to 12 floats / 48 bytes;', 'buffer to 16 floats / 64 bytes;', 'GpuDab size comment')
h = replace_once(h, 'shaders/stamp.comp AND shaders/stamp_masked.comp (both share this exact struct layout).',
                 'shaders/stamp.comp AND shaders/stamp_masked.comp (both share this exact struct layout).\n// The fourth vec4 carries Phase-3 material state; it is ignored unless a dispatch explicitly\n// enables substrate sampling, preserving every historical/legacy dab path.', 'GpuDab layout comment')
h = replace_once(
    h,
    '    float resolved = 0.0f;\n    float tipRatio = 1.0f;\n};\nstatic_assert(sizeof(GpuDab) == 48, "GpuDab must match the shader\'s 3xvec4 std430 record");',
    '    float resolved = 0.0f;\n    float tipRatio = 1.0f;\n    // Phase 3 material/deposition state. Defaults preserve historical output.\n    float contactDepth = 1.0f;\n    float reservoirLoad = 1.0f;\n    float depositionRate = 1.0f;\n    float substrateResponse = 0.0f;\n};\nstatic_assert(sizeof(GpuDab) == 64, "GpuDab must match the shader\'s 4xvec4 std430 record");',
    'GpuDab fields',
)
h = replace_once(
    h,
    '// Item 15\'s masked/dual-brush follow-up: the secondary tip stampMaskedDabs() composites onto a',
    '''struct SubstrateStampParams {
    bool enabled = false;
    float baseHeight = 0.0f;
    float heightScale = 0.0f;
    float textureScale = 1.0f;
    float textureOffsetX = 0.0f;
    float textureOffsetY = 0.0f;
};

// Item 15's masked/dual-brush follow-up: the secondary tip stampMaskedDabs() composites onto a''',
    'SubstrateStampParams insertion',
)
h = replace_once(
    h,
    '    bool upload(const uint8_t* inRgba8, size_t inSizeBytes);\n',
    '''    bool upload(const uint8_t* inRgba8, size_t inSizeBytes);

    // Phase 3: upload the immutable canvas substrate-height tile once per stroke/document context.
    // Repeated uploads of byte-identical same-size tiles are hash-skipped; stamp dispatches only
    // carry scalar profile parameters and per-dab contact/material state afterward.
    bool uploadSubstrateHeight(const uint8_t* heightR8, int width, int height);
''',
    'public substrate upload',
)
h = replace_once(
    h,
    '    bool stampDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb, float hardness,\n                    bool buildUp = false);',
    '    bool stampDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb, float hardness,\n                    bool buildUp = false, SubstrateStampParams substrate = {});',
    'stampDabs signature',
)
h = replace_once(
    h,
    '                         const uint8_t* secondaryMaskAlpha8 = nullptr, int secondaryMaskWidth = 0,\n                         int secondaryMaskHeight = 0);',
    '                         const uint8_t* secondaryMaskAlpha8 = nullptr, int secondaryMaskWidth = 0,\n                         int secondaryMaskHeight = 0, SubstrateStampParams substrate = {});',
    'stampMaskedDabs signature',
)
h = replace_once(
    h,
    '    bool ensureGrainTexture(int width, int height);\n    bool uploadGrainTexture(const uint8_t* alpha8, int width, int height);\n',
    '''    bool ensureGrainTexture(int width, int height);
    bool uploadGrainTexture(const uint8_t* alpha8, int width, int height);
    // Phase 3 canvas substrate: independent R8 tile so brush grain and canvas tooth can coexist.
    bool ensureSubstrateTexture(int width, int height);
    bool uploadSubstrateTexture(const uint8_t* heightR8, int width, int height);
''',
    'private substrate helpers',
)
h = replace_once(
    h,
    '    // R8_UNORM grain tile texture (item 15 follow-up), independent from the mask texture above --\n',
    '''    // Phase 3 substrate-height tile. Shared by round and masked stamp pipelines, sampled with
    // exact floor/wrap canvas coordinates in shader code. The Kotlin wrapper requires a fresh
    // upload before enabling substrate on each wrapper lifetime, preventing pooled-engine leakage.
    VkImage substrateImage_ = VK_NULL_HANDLE;
    VkDeviceMemory substrateImageMemory_ = VK_NULL_HANDLE;
    VkImageView substrateImageView_ = VK_NULL_HANDLE;
    VkSampler substrateSampler_ = VK_NULL_HANDLE;
    VkBuffer substrateStagingBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory substrateStagingBufferMemory_ = VK_NULL_HANDLE;
    int substrateWidth_ = 0;
    int substrateHeight_ = 0;
    VkImageLayout substrateImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    uint64_t substrateContentHash_ = 0;

    // R8_UNORM grain tile texture (item 15 follow-up), independent from the mask texture above --
''',
    'substrate resource fields',
)
write(header_path, h)


# -----------------------------------------------------------------------------
# Vulkan engine implementation
# -----------------------------------------------------------------------------
cpp_path = 'core/nativebridge/src/main/cpp/VulkanStampEngine.cpp'
cpp = read(cpp_path)
cpp = replace_once(
    cpp,
    '    float buildUp;\n};',
    '''    float buildUp;
    float hasSubstrate;
    float substrateBaseHeight;
    float substrateHeightScale;
    float substrateTextureScale;
    float substrateOffsetX;
    float substrateOffsetY;
};''',
    'round push constants',
)
cpp = replace_once(
    cpp,
    '    float hasSecondary;\n};',
    '''    float hasSecondary;
    float hasSubstrate;
    float substrateBaseHeight;
    float substrateHeightScale;
    float substrateTextureScale;
    float substrateOffsetX;
    float substrateOffsetY;
};''',
    'masked push constants',
)


def patch_create_descriptor(block: str) -> str:
    block = replace_once(block, 'VkDescriptorSetLayoutBinding bindings[2]{};',
                         'VkDescriptorSetLayoutBinding bindings[3]{};', 'plain binding array')
    block = replace_once(
        block,
        '    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;\n\n    VkDescriptorSetLayoutCreateInfo layoutInfo{};',
        '''    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[2].binding = 2;
    bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[2].descriptorCount = 1;
    bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo{};''',
        'plain substrate binding',
    )
    block = replace_once(block, 'layoutInfo.bindingCount = 2;', 'layoutInfo.bindingCount = 3;', 'plain binding count')
    block = replace_once(block, 'VkDescriptorPoolSize poolSizes[2]{};',
                         'VkDescriptorPoolSize poolSizes[3]{};', 'plain pool array')
    block = replace_once(
        block,
        '    poolSizes[1].descriptorCount = 1;\n\n    VkDescriptorPoolCreateInfo poolInfo{};',
        '''    poolSizes[1].descriptorCount = 1;
    poolSizes[2].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[2].descriptorCount = 1;

    VkDescriptorPoolCreateInfo poolInfo{};''',
        'plain substrate pool',
    )
    block = replace_once(block, 'poolInfo.poolSizeCount = 2;', 'poolInfo.poolSizeCount = 3;', 'plain pool count')
    block = replace_once(
        block,
        '    vkUpdateDescriptorSets(device_, 1, &imageWrite, 0, nullptr);\n\n    return true;',
        '''    vkUpdateDescriptorSets(device_, 1, &imageWrite, 0, nullptr);

    VkSamplerCreateInfo substrateSamplerInfo{};
    substrateSamplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    substrateSamplerInfo.magFilter = VK_FILTER_NEAREST;
    substrateSamplerInfo.minFilter = VK_FILTER_NEAREST;
    substrateSamplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    substrateSamplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    substrateSamplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    substrateSamplerInfo.unnormalizedCoordinates = VK_FALSE;
    if (!checkResult(vkCreateSampler(device_, &substrateSamplerInfo, nullptr, &substrateSampler_),
                      "vkCreateSampler(substrate)")) {
        return false;
    }
    // Keep the statically-used shader binding valid even when substrate is disabled. hasSubstrate
    // gates sampling, and zero height makes this dummy harmless if a driver speculatively reads it.
    if (!ensureSubstrateTexture(1, 1)) return false;
    const uint8_t dummySubstrate = 0;
    if (!uploadSubstrateTexture(&dummySubstrate, 1, 1)) return false;
    substrateContentHash_ = fnv1a(&dummySubstrate, 1);

    return true;''',
        'plain substrate sampler setup',
    )
    return block

cpp = replace_function(cpp, 'bool VulkanStampEngine::createDescriptorAndPipeline()', patch_create_descriptor)

# Clone the proven grain R8 allocation/upload implementation for the independent substrate tile.
_, _, grain_ensure = function_span(cpp, 'bool VulkanStampEngine::ensureGrainTexture')
_, _, grain_upload = function_span(cpp, 'bool VulkanStampEngine::uploadGrainTexture')
substrate_ensure = grain_ensure.replace('Grain', 'Substrate').replace('grain', 'substrate')
substrate_ensure = replace_once(
    substrate_ensure,
    '''    VkWriteDescriptorSet samplerWrite{};
    samplerWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    samplerWrite.dstSet = maskedDescriptorSet_;
    samplerWrite.dstBinding = 3;
    samplerWrite.descriptorCount = 1;
    samplerWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    samplerWrite.pImageInfo = &samplerImageInfo;
    vkUpdateDescriptorSets(device_, 1, &samplerWrite, 0, nullptr);''',
    '''    if (descriptorSet_ != VK_NULL_HANDLE) {
        VkWriteDescriptorSet roundWrite{};
        roundWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        roundWrite.dstSet = descriptorSet_;
        roundWrite.dstBinding = 2;
        roundWrite.descriptorCount = 1;
        roundWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        roundWrite.pImageInfo = &samplerImageInfo;
        vkUpdateDescriptorSets(device_, 1, &roundWrite, 0, nullptr);
    }
    if (maskedDescriptorSet_ != VK_NULL_HANDLE) {
        VkWriteDescriptorSet maskedWrite{};
        maskedWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        maskedWrite.dstSet = maskedDescriptorSet_;
        maskedWrite.dstBinding = 6;
        maskedWrite.descriptorCount = 1;
        maskedWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        maskedWrite.pImageInfo = &samplerImageInfo;
        vkUpdateDescriptorSets(device_, 1, &maskedWrite, 0, nullptr);
    }''',
    'substrate descriptor fanout',
)
substrate_upload = grain_upload.replace('Grain', 'Substrate').replace('grain', 'substrate')
substrate_public = '''

bool VulkanStampEngine::uploadSubstrateHeight(const uint8_t* heightR8, int width, int height) {
    if (!isInitialized() || heightR8 == nullptr || width <= 0 || height <= 0) return false;
    const size_t bytes = static_cast<size_t>(width) * static_cast<size_t>(height);
    const uint64_t hash = fnv1a(heightR8, bytes);
    const bool changed = substrateImage_ == VK_NULL_HANDLE || width != substrateWidth_ ||
                         height != substrateHeight_ || hash != substrateContentHash_;
    if (!ensureSubstrateTexture(width, height)) return false;
    if (changed) {
        if (!uploadSubstrateTexture(heightR8, width, height)) return false;
        substrateContentHash_ = hash;
    }
    return true;
}
'''
insert_anchor = '// Item 15 masked/dual-brush follow-up. Mirrors ensureGrainTexture()/uploadGrainTexture()'
idx = cpp.find(insert_anchor)
if idx < 0:
    raise SystemExit('substrate helper insertion anchor missing')
cpp = cpp[:idx] + substrate_ensure + '\n\n' + substrate_upload + substrate_public + '\n' + cpp[idx:]


def patch_masked_pipeline(block: str) -> str:
    block = replace_once(block, 'VkDescriptorSetLayoutBinding bindings[6]{};',
                         'VkDescriptorSetLayoutBinding bindings[7]{};', 'masked binding array')
    block = replace_once(
        block,
        '    bindings[5].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;\n\n    VkDescriptorSetLayoutCreateInfo layoutInfo{};',
        '''    bindings[5].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[6].binding = 6;
    bindings[6].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[6].descriptorCount = 1;
    bindings[6].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo{};''',
        'masked substrate binding',
    )
    block = replace_once(block, 'layoutInfo.bindingCount = 6;', 'layoutInfo.bindingCount = 7;', 'masked binding count')
    block = replace_once(block, 'VkDescriptorPoolSize poolSizes[6]{};',
                         'VkDescriptorPoolSize poolSizes[7]{};', 'masked pool array')
    block = replace_once(
        block,
        '    poolSizes[5].descriptorCount = 1;\n\n    VkDescriptorPoolCreateInfo poolInfo{};',
        '''    poolSizes[5].descriptorCount = 1;
    poolSizes[6].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[6].descriptorCount = 1;

    VkDescriptorPoolCreateInfo poolInfo{};''',
        'masked substrate pool',
    )
    block = replace_once(block, 'poolInfo.poolSizeCount = 6;', 'poolInfo.poolSizeCount = 7;', 'masked pool count')
    block = replace_once(
        block,
        '    vkUpdateDescriptorSets(device_, 1, &imageWrite, 0, nullptr);\n\n    VkSamplerCreateInfo samplerInfo{};',
        '''    vkUpdateDescriptorSets(device_, 1, &imageWrite, 0, nullptr);

    if (substrateImageView_ == VK_NULL_HANDLE || substrateSampler_ == VK_NULL_HANDLE) return false;
    VkDescriptorImageInfo substrateInfo{};
    substrateInfo.sampler = substrateSampler_;
    substrateInfo.imageView = substrateImageView_;
    substrateInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet substrateWrite{};
    substrateWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    substrateWrite.dstSet = maskedDescriptorSet_;
    substrateWrite.dstBinding = 6;
    substrateWrite.descriptorCount = 1;
    substrateWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    substrateWrite.pImageInfo = &substrateInfo;
    vkUpdateDescriptorSets(device_, 1, &substrateWrite, 0, nullptr);

    VkSamplerCreateInfo samplerInfo{};''',
        'masked substrate descriptor init',
    )
    return block

cpp = replace_function(cpp, 'bool VulkanStampEngine::ensureMaskedPipeline()', patch_masked_pipeline)


def patch_stamp_dabs(block: str) -> str:
    block = replace_once(
        block,
        'bool VulkanStampEngine::stampDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb, float hardness,\n                                   bool buildUp) {',
        'bool VulkanStampEngine::stampDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb, float hardness,\n                                   bool buildUp, SubstrateStampParams substrate) {',
        'stampDabs implementation signature',
    )
    block = replace_once(
        block,
        '        pc.buildUp = buildUp ? 1.0f : 0.0f;\n',
        '''        pc.buildUp = buildUp ? 1.0f : 0.0f;
        pc.hasSubstrate = substrate.enabled ? 1.0f : 0.0f;
        pc.substrateBaseHeight = std::clamp(substrate.baseHeight, 0.0f, 1.0f);
        pc.substrateHeightScale = std::clamp(substrate.heightScale, 0.0f, 1.0f);
        pc.substrateTextureScale = std::max(substrate.textureScale, 0.05f);
        pc.substrateOffsetX = substrate.textureOffsetX;
        pc.substrateOffsetY = substrate.textureOffsetY;
''',
        'round substrate push values',
    )
    return block

cpp = replace_function(cpp, 'bool VulkanStampEngine::stampDabs', patch_stamp_dabs)


def patch_stamp_masked(block: str) -> str:
    block = replace_once(
        block,
        '                                        const uint8_t* secondaryMaskAlpha8, int secondaryMaskWidth,\n                                        int secondaryMaskHeight) {',
        '                                        const uint8_t* secondaryMaskAlpha8, int secondaryMaskWidth,\n                                        int secondaryMaskHeight, SubstrateStampParams substrate) {',
        'masked implementation signature',
    )
    block = replace_once(
        block,
        '        pc.hasSecondary = haveSecondary ? 1.0f : 0.0f;\n',
        '''        pc.hasSecondary = haveSecondary ? 1.0f : 0.0f;
        pc.hasSubstrate = substrate.enabled ? 1.0f : 0.0f;
        pc.substrateBaseHeight = std::clamp(substrate.baseHeight, 0.0f, 1.0f);
        pc.substrateHeightScale = std::clamp(substrate.heightScale, 0.0f, 1.0f);
        pc.substrateTextureScale = std::max(substrate.textureScale, 0.05f);
        pc.substrateOffsetX = substrate.textureOffsetX;
        pc.substrateOffsetY = substrate.textureOffsetY;
''',
        'masked substrate push values',
    )
    return block

cpp = replace_function(cpp, 'bool VulkanStampEngine::stampMaskedDabs', patch_stamp_masked)

# Resource teardown. Pooled handles keep these resources until true native destruction; wrapper
# ownership still requires a fresh upload before substrate-enabled stamps after checkout.
cpp = replace_once(
    cpp,
    '    if (grainSampler_ != VK_NULL_HANDLE) { vkDestroySampler(device_, grainSampler_, nullptr); grainSampler_ = VK_NULL_HANDLE; }\n',
    '''    if (substrateSampler_ != VK_NULL_HANDLE) { vkDestroySampler(device_, substrateSampler_, nullptr); substrateSampler_ = VK_NULL_HANDLE; }
    if (substrateImageView_ != VK_NULL_HANDLE) { vkDestroyImageView(device_, substrateImageView_, nullptr); substrateImageView_ = VK_NULL_HANDLE; }
    if (substrateImage_ != VK_NULL_HANDLE) { vkDestroyImage(device_, substrateImage_, nullptr); substrateImage_ = VK_NULL_HANDLE; }
    if (substrateImageMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, substrateImageMemory_, nullptr); substrateImageMemory_ = VK_NULL_HANDLE; }
    if (substrateStagingBuffer_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, substrateStagingBuffer_, nullptr); substrateStagingBuffer_ = VK_NULL_HANDLE; }
    if (substrateStagingBufferMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, substrateStagingBufferMemory_, nullptr); substrateStagingBufferMemory_ = VK_NULL_HANDLE; }
    substrateWidth_ = 0;
    substrateHeight_ = 0;
    substrateImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    substrateContentHash_ = 0;

    if (grainSampler_ != VK_NULL_HANDLE) { vkDestroySampler(device_, grainSampler_, nullptr); grainSampler_ = VK_NULL_HANDLE; }
''',
    'substrate resource destroy',
)
write(cpp_path, cpp)


# -----------------------------------------------------------------------------
# JNI bridge
# -----------------------------------------------------------------------------
jni_path = 'core/nativebridge/src/main/cpp/VulkanStampDynamicsJNI.cpp'
j = read(jni_path)
j = replace_once(j, 'using graffux::GpuSecondaryDab;\nusing graffux::VulkanStampEngine;',
                 'using graffux::GpuSecondaryDab;\nusing graffux::SubstrateStampParams;\nusing graffux::VulkanStampEngine;',
                 'JNI substrate using')
j = replace_once(
    j,
    '}\n\nextern "C" JNIEXPORT jboolean JNICALL\nJava_com_hereliesaz_graffitixr_nativebridge_VulkanStampEngine_nativeStampResolvedDabs(',
    '''
SubstrateStampParams substrateParams(jboolean enabled, jfloat baseHeight, jfloat heightScale,
                                     jfloat textureScale, jfloat offsetX, jfloat offsetY) {
    SubstrateStampParams out{};
    out.enabled = enabled == JNI_TRUE;
    out.baseHeight = std::clamp(baseHeight, 0.0f, 1.0f);
    out.heightScale = std::clamp(heightScale, 0.0f, 1.0f);
    out.textureScale = std::max(textureScale, 0.05f);
    out.textureOffsetX = offsetX;
    out.textureOffsetY = offsetY;
    return out;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_VulkanStampEngine_nativeUploadSubstrateHeight(
        JNIEnv* env, jobject, jlong handle, jbyteArray heightR8, jint width, jint height) {
    auto* engine = reinterpret_cast<VulkanStampEngine*>(handle);
    if (!engine || !heightR8 || width <= 0 || height <= 0) return JNI_FALSE;
    const jsize count = env->GetArrayLength(heightR8);
    if (static_cast<jlong>(count) < static_cast<jlong>(width) * height) return JNI_FALSE;
    jbyte* data = env->GetByteArrayElements(heightR8, nullptr);
    if (!data) return JNI_FALSE;
    const bool ok = engine->uploadSubstrateHeight(reinterpret_cast<const uint8_t*>(data), width, height);
    env->ReleaseByteArrayElements(heightR8, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_VulkanStampEngine_nativeStampResolvedDabs(''',
    'JNI helpers/upload',
)
j = replace_once(
    j,
    '        JNIEnv* env, jobject, jlong handle, jfloatArray dabData, jboolean buildUp) {',
    '''        JNIEnv* env, jobject, jlong handle, jfloatArray dabData, jboolean buildUp,
        jboolean hasSubstrate, jfloat substrateBaseHeight, jfloat substrateHeightScale,
        jfloat substrateTextureScale, jfloat substrateOffsetX, jfloat substrateOffsetY) {''',
    'resolved JNI signature',
)
j = replace_once(j, 'constexpr int kStride = 11;  // x,y,radius,alpha,angle,r,g,b,a,flow,hardness',
                 'constexpr int kStride = 15;  // old 11 fields + contactDepth,load,depositionRate,substrateResponse',
                 'resolved stride')
j = replace_once(
    j,
    '        d.tipRatio = std::clamp(data[i + 10], 0.0f, 1.0f);\n        dabs.push_back(d);',
    '''        d.tipRatio = std::clamp(data[i + 10], 0.0f, 1.0f);
        d.contactDepth = std::clamp(data[i + 11], 0.0f, 1.0f);
        d.reservoirLoad = std::clamp(data[i + 12], 0.0f, 1.0f);
        d.depositionRate = std::clamp(data[i + 13], 0.0f, 1.0f);
        d.substrateResponse = std::clamp(data[i + 14], 0.0f, 1.0f);
        dabs.push_back(d);''',
    'resolved material unpack',
)
j = replace_once(
    j,
    '    return engine->stampDabs(dabs, 0xFFFFFFFFu, 1.0f, buildUp == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;',
    '''    const auto substrate = substrateParams(
        hasSubstrate, substrateBaseHeight, substrateHeightScale, substrateTextureScale,
        substrateOffsetX, substrateOffsetY);
    return engine->stampDabs(dabs, 0xFFFFFFFFu, 1.0f, buildUp == JNI_TRUE, substrate)
        ? JNI_TRUE : JNI_FALSE;''',
    'resolved native call',
)
j = replace_once(
    j,
    '        jfloatArray secondaryDabData, jbyteArray secondaryMaskAlpha8, jint secondaryMaskWidth,\n        jint secondaryMaskHeight) {',
    '''        jfloatArray secondaryDabData, jbyteArray secondaryMaskAlpha8, jint secondaryMaskWidth,
        jint secondaryMaskHeight, jboolean hasSubstrate, jfloat substrateBaseHeight,
        jfloat substrateHeightScale, jfloat substrateTextureScale, jfloat substrateOffsetX,
        jfloat substrateOffsetY) {''',
    'masked JNI signature',
)
j = replace_once(j, 'constexpr int kStride = 11;  // x,y,radius,alpha,angle,r,g,b,a,flow,tipRatio',
                 'constexpr int kStride = 15;  // old 11 fields + contactDepth,load,depositionRate,substrateResponse',
                 'masked stride')
j = replace_once(
    j,
    '        d.tipRatio = std::clamp(data[i + 10], 0.05f, 1.0f);\n        dabs.push_back(d);',
    '''        d.tipRatio = std::clamp(data[i + 10], 0.05f, 1.0f);
        d.contactDepth = std::clamp(data[i + 11], 0.0f, 1.0f);
        d.reservoirLoad = std::clamp(data[i + 12], 0.0f, 1.0f);
        d.depositionRate = std::clamp(data[i + 13], 0.0f, 1.0f);
        d.substrateResponse = std::clamp(data[i + 14], 0.0f, 1.0f);
        dabs.push_back(d);''',
    'masked material unpack',
)
j = replace_once(
    j,
    '        hasSecondaryArrays ? reinterpret_cast<const uint8_t*>(secondaryMaskData) : nullptr,\n        hasSecondaryArrays ? secondaryMaskWidth : 0, hasSecondaryArrays ? secondaryMaskHeight : 0);',
    '''        hasSecondaryArrays ? reinterpret_cast<const uint8_t*>(secondaryMaskData) : nullptr,
        hasSecondaryArrays ? secondaryMaskWidth : 0, hasSecondaryArrays ? secondaryMaskHeight : 0,
        substrateParams(hasSubstrate, substrateBaseHeight, substrateHeightScale,
                        substrateTextureScale, substrateOffsetX, substrateOffsetY));''',
    'masked native call',
)
write(jni_path, j)


# -----------------------------------------------------------------------------
# GLSL shaders
# -----------------------------------------------------------------------------
plain_path = 'core/nativebridge/src/main/cpp/shaders/stamp.comp'
s = read(plain_path)
s = replace_once(s, '// Explicit 3×vec4 std430 layout: exactly 48 bytes per dab, matching GpuDab\'s twelve floats.',
                 '// Explicit 4×vec4 std430 layout: exactly 64 bytes per dab, matching GpuDab\'s sixteen floats.',
                 'round shader layout comment')
s = replace_once(
    s,
    '    vec4 paint1;   // colorA, flow, resolved, hardness (resolved dabs only)\n};',
    '''    vec4 paint1;   // colorA, flow, resolved, hardness (resolved dabs only)
    vec4 material; // contactDepth, reservoirLoad, depositionRate, substrateResponse
};''',
    'round shader material vec4',
)
s = replace_once(s, 'layout(binding = 1, rgba8) uniform image2D layerImage;\n',
                 'layout(binding = 1, rgba8) uniform image2D layerImage;\nlayout(binding = 2) uniform sampler2D substrateTex;\n',
                 'round substrate binding')
s = replace_once(
    s,
    '    float buildUp;\n} pc;',
    '''    float buildUp;
    float hasSubstrate;
    float substrateBaseHeight;
    float substrateHeightScale;
    float substrateTextureScale;
    float substrateOffsetX;
    float substrateOffsetY;
} pc;''',
    'round shader push fields',
)
s = replace_once(
    s,
    'void main() {',
    '''float substrateDeposition(Dab d, vec2 canvasPoint) {
    if (pc.hasSubstrate <= 0.5) return 1.0;
    ivec2 size = textureSize(substrateTex, 0);
    if (size.x <= 0 || size.y <= 0) return 1.0;
    float scale = max(pc.substrateTextureScale, 0.05);
    ivec2 cell = ivec2(floor(canvasPoint / scale + vec2(pc.substrateOffsetX, pc.substrateOffsetY)));
    cell.x = ((cell.x % size.x) + size.x) % size.x;
    cell.y = ((cell.y % size.y) + size.y) % size.y;
    float tooth = texelFetch(substrateTex, cell, 0).r;
    float substrateHeight = clamp(
        clamp(pc.substrateBaseHeight, 0.0, 1.0) + tooth * clamp(pc.substrateHeightScale, 0.0, 1.0),
        0.0, 1.0);
    float contactDepth = clamp(d.material.x, 0.0, 1.0);
    float response = clamp(d.material.w, 0.0, 1.0);
    float penetrates = contactDepth >= substrateHeight ? 1.0 : 0.0;
    float gate = clamp((1.0 - response) + penetrates * response, 0.0, 1.0);
    return clamp(d.material.y, 0.0, 1.0) * clamp(d.material.z, 0.0, 1.0) * gate;
}

void main() {''',
    'round shader substrate function',
)
s = replace_once(
    s,
    '        if (coverage <= 0.0) continue;\n\n        vec3 srcRgb = hasResolvedPaint ? d.paint0.yzw : vec3(pc.colorR, pc.colorG, pc.colorB);',
    '''        if (coverage <= 0.0) continue;
        coverage *= substrateDeposition(d, p);
        if (coverage <= 0.0) continue;

        vec3 srcRgb = hasResolvedPaint ? d.paint0.yzw : vec3(pc.colorR, pc.colorG, pc.colorB);''',
    'round substrate application',
)
write(plain_path, s)

masked_path = 'core/nativebridge/src/main/cpp/shaders/stamp_masked.comp'
s = read(masked_path)
s = replace_once(s, '// Same 48-byte/3×vec4 std430 layout as stamp.comp\'s Dab -- binary-identical to GpuDab in',
                 '// Same 64-byte/4×vec4 std430 layout as stamp.comp\'s Dab -- binary-identical to GpuDab in',
                 'masked shader layout comment')
s = replace_once(
    s,
    '    vec4 paint1;   // colorA, flow, resolved, tipRatio\n};',
    '''    vec4 paint1;   // colorA, flow, resolved, tipRatio
    vec4 material; // contactDepth, reservoirLoad, depositionRate, substrateResponse
};''',
    'masked shader material vec4',
)
s = replace_once(s, 'layout(std430, binding = 5) readonly buffer SecondaryDabBuffer { SecondaryDab secondaryDabs[]; };\n',
                 'layout(std430, binding = 5) readonly buffer SecondaryDabBuffer { SecondaryDab secondaryDabs[]; };\nlayout(binding = 6) uniform sampler2D substrateTex;\n',
                 'masked substrate binding')
s = replace_once(
    s,
    '    float hasSecondary;\n} pc;',
    '''    float hasSecondary;
    float hasSubstrate;
    float substrateBaseHeight;
    float substrateHeightScale;
    float substrateTextureScale;
    float substrateOffsetX;
    float substrateOffsetY;
} pc;''',
    'masked shader push fields',
)
s = replace_once(
    s,
    'void main() {',
    '''float substrateDeposition(Dab d, vec2 canvasPoint) {
    if (pc.hasSubstrate <= 0.5) return 1.0;
    ivec2 size = textureSize(substrateTex, 0);
    if (size.x <= 0 || size.y <= 0) return 1.0;
    float scale = max(pc.substrateTextureScale, 0.05);
    ivec2 cell = ivec2(floor(canvasPoint / scale + vec2(pc.substrateOffsetX, pc.substrateOffsetY)));
    cell.x = ((cell.x % size.x) + size.x) % size.x;
    cell.y = ((cell.y % size.y) + size.y) % size.y;
    float tooth = texelFetch(substrateTex, cell, 0).r;
    float substrateHeight = clamp(
        clamp(pc.substrateBaseHeight, 0.0, 1.0) + tooth * clamp(pc.substrateHeightScale, 0.0, 1.0),
        0.0, 1.0);
    float contactDepth = clamp(d.material.x, 0.0, 1.0);
    float response = clamp(d.material.w, 0.0, 1.0);
    float penetrates = contactDepth >= substrateHeight ? 1.0 : 0.0;
    float gate = clamp((1.0 - response) + penetrates * response, 0.0, 1.0);
    return clamp(d.material.y, 0.0, 1.0) * clamp(d.material.z, 0.0, 1.0) * gate;
}

void main() {''',
    'masked shader substrate function',
)
s = replace_once(
    s,
    '        bool hasResolvedPaint = d.paint1.z >= 0.5;\n',
    '''        coverage *= substrateDeposition(d, p);
        if (coverage <= 0.0) continue;

        bool hasResolvedPaint = d.paint1.z >= 0.5;
''',
    'masked substrate application',
)
write(masked_path, s)


# -----------------------------------------------------------------------------
# Kotlin native wrapper
# -----------------------------------------------------------------------------
kt_path = 'core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/VulkanStampEngine.kt'
k = read(kt_path)
k = replace_once(k, '    private var hardwareBufferExported = false\n',
                 '    private var hardwareBufferExported = false\n    private var substrateHeightUploaded = false\n',
                 'wrapper substrate ownership flag')
k = replace_once(k, '        destroy()\n        val key = PoolKey(width, height, hardwareBufferBacked)',
                 '        destroy()\n        substrateHeightUploaded = false\n        val key = PoolKey(width, height, hardwareBufferBacked)',
                 'wrapper init reset')
k = replace_once(
    k,
    '    /** Historical stroke-level paint entry point. */\n',
    '''    /** Upload a static R8 canvas-height tile. Call once before substrate-enabled stamping. */
    fun uploadSubstrateHeight(heightR8: ByteArray, width: Int, height: Int): Boolean {
        if (!isInitialized || width <= 0 || height <= 0) return false
        require(heightR8.size >= width * height) {
            "heightR8 too small: need ${width * height}, got ${heightR8.size}"
        }
        val ok = nativeUploadSubstrateHeight(nativeHandle, heightR8, width, height)
        substrateHeightUploaded = ok
        if (!ok) healthy = false
        return ok
    }

    /** Historical stroke-level paint entry point. */
''',
    'wrapper upload method',
)
k = replace_once(
    k,
    '    fun stampResolvedDabs(dabs: List<ResolvedBrushDab>, buildUp: Boolean = false): Boolean {\n        if (!isInitialized || dabs.isEmpty()) return false\n        val flat = FloatArray(dabs.size * 11)',
    '''    fun stampResolvedDabs(
        dabs: List<ResolvedBrushDab>,
        buildUp: Boolean = false,
        substrate: VulkanSubstrateParams? = null,
    ): Boolean {
        if (!isInitialized || dabs.isEmpty()) return false
        if (substrate != null && !substrateHeightUploaded) return false
        val flat = FloatArray(dabs.size * 15)''',
    'resolved wrapper signature/stride',
)
k = replace_once(k, '            val base = i * 11\n', '            val base = i * 15\n', 'resolved base stride')
k = replace_once(
    k,
    '            flat[base + 10] = d.hardness.coerceIn(0f, 1f)\n        }\n        return nativeStampResolvedDabs(nativeHandle, flat, buildUp).also { if (!it) healthy = false }',
    '''            flat[base + 10] = d.hardness.coerceIn(0f, 1f)
            flat[base + 11] = d.contactDepth.coerceIn(0f, 1f)
            flat[base + 12] = d.reservoirLoad.coerceIn(0f, 1f)
            flat[base + 13] = d.depositionRate.coerceIn(0f, 1f)
            flat[base + 14] = d.substrateResponse.coerceIn(0f, 1f)
        }
        val cfg = substrate?.sanitized()
        return nativeStampResolvedDabs(
            nativeHandle, flat, buildUp, cfg != null,
            cfg?.baseHeight ?: 0f, cfg?.heightScale ?: 0f, cfg?.textureScale ?: 1f,
            cfg?.textureOffsetX ?: 0f, cfg?.textureOffsetY ?: 0f,
        ).also { if (!it) healthy = false }''',
    'resolved wrapper material/config',
)
k = replace_once(
    k,
    '        secondaryMaskWidth: Int = 0,\n        secondaryMaskHeight: Int = 0,\n    ): Boolean {\n        if (!isInitialized || dabs.isEmpty()) return false',
    '''        secondaryMaskWidth: Int = 0,
        secondaryMaskHeight: Int = 0,
        substrate: VulkanSubstrateParams? = null,
    ): Boolean {
        if (!isInitialized || dabs.isEmpty()) return false
        if (substrate != null && !substrateHeightUploaded) return false''',
    'masked wrapper signature',
)
# The second old 11-stride occurrence belongs to masked dabs; resolved was already changed.
k = replace_once(k, '        val flat = FloatArray(dabs.size * 11)\n',
                 '        val flat = FloatArray(dabs.size * 15)\n', 'masked flat stride')
k = replace_once(k, '            val base = i * 11\n', '            val base = i * 15\n', 'masked base stride')
k = replace_once(
    k,
    '            flat[base + 10] = d.tipRatio\n        }\n        val secondaryFlat = if (secondaryDabs.isNotEmpty()) {',
    '''            flat[base + 10] = d.tipRatio
            flat[base + 11] = d.contactDepth.coerceIn(0f, 1f)
            flat[base + 12] = d.reservoirLoad.coerceIn(0f, 1f)
            flat[base + 13] = d.depositionRate.coerceIn(0f, 1f)
            flat[base + 14] = d.substrateResponse.coerceIn(0f, 1f)
        }
        val secondaryFlat = if (secondaryDabs.isNotEmpty()) {''',
    'masked material packing',
)
k = replace_once(
    k,
    '        return nativeStampMaskedDabs(\n            nativeHandle, flat, hardness, maskAlpha8, maskWidth, maskHeight,\n            grainAlpha8, grainWidth, grainHeight, grainCanvasLocked, grainScale, grainPhaseX, grainPhaseY,\n            secondaryFlat, secondaryMaskAlpha8, secondaryMaskWidth, secondaryMaskHeight,\n        ).also { if (!it) healthy = false }',
    '''        val cfg = substrate?.sanitized()
        return nativeStampMaskedDabs(
            nativeHandle, flat, hardness, maskAlpha8, maskWidth, maskHeight,
            grainAlpha8, grainWidth, grainHeight, grainCanvasLocked, grainScale, grainPhaseX, grainPhaseY,
            secondaryFlat, secondaryMaskAlpha8, secondaryMaskWidth, secondaryMaskHeight,
            cfg != null, cfg?.baseHeight ?: 0f, cfg?.heightScale ?: 0f, cfg?.textureScale ?: 1f,
            cfg?.textureOffsetX ?: 0f, cfg?.textureOffsetY ?: 0f,
        ).also { if (!it) healthy = false }''',
    'masked wrapper config call',
)
k = replace_once(
    k,
    '        hardwareBufferExported = false\n        if (!mayPool) { nativeDestroy(handle); return }',
    '        hardwareBufferExported = false\n        substrateHeightUploaded = false\n        if (!mayPool) { nativeDestroy(handle); return }',
    'wrapper destroy reset',
)
k = replace_once(k, '    private external fun nativeUpload(handle: Long, inBitmap: Bitmap): Boolean\n',
                 '    private external fun nativeUpload(handle: Long, inBitmap: Bitmap): Boolean\n    private external fun nativeUploadSubstrateHeight(handle: Long, heightR8: ByteArray, width: Int, height: Int): Boolean\n',
                 'wrapper native upload declaration')
k = replace_once(
    k,
    '    private external fun nativeStampResolvedDabs(handle: Long, dabData: FloatArray, buildUp: Boolean): Boolean\n',
    '''    private external fun nativeStampResolvedDabs(
        handle: Long, dabData: FloatArray, buildUp: Boolean, hasSubstrate: Boolean,
        substrateBaseHeight: Float, substrateHeightScale: Float, substrateTextureScale: Float,
        substrateOffsetX: Float, substrateOffsetY: Float,
    ): Boolean
''',
    'wrapper native resolved declaration',
)
k = replace_once(
    k,
    '        secondaryMaskWidth: Int,\n        secondaryMaskHeight: Int,\n    ): Boolean',
    '''        secondaryMaskWidth: Int,
        secondaryMaskHeight: Int,
        hasSubstrate: Boolean,
        substrateBaseHeight: Float,
        substrateHeightScale: Float,
        substrateTextureScale: Float,
        substrateOffsetX: Float,
        substrateOffsetY: Float,
    ): Boolean''',
    'wrapper native masked declaration',
)
k = replace_once(
    k,
    '    val hardness: Float = 1f,\n)',
    '''    val hardness: Float = 1f,
    val contactDepth: Float = 1f,
    val reservoirLoad: Float = 1f,
    val depositionRate: Float = 1f,
    val substrateResponse: Float = 0f,
)''',
    'resolved dab material fields',
)
k = replace_once(
    k,
    '    val flow: Float,\n    val tipRatio: Float,\n)',
    '''    val flow: Float,
    val tipRatio: Float,
    val contactDepth: Float = 1f,
    val reservoirLoad: Float = 1f,
    val depositionRate: Float = 1f,
    val substrateResponse: Float = 0f,
)''',
    'masked dab material fields',
)
k = replace_once(
    k,
    'data class ResolvedBrushDab(\n',
    '''data class VulkanSubstrateParams(
    val baseHeight: Float = 0f,
    val heightScale: Float = 1f,
    val textureScale: Float = 1f,
    val textureOffsetX: Float = 0f,
    val textureOffsetY: Float = 0f,
) {
    fun sanitized(): VulkanSubstrateParams = copy(
        baseHeight = baseHeight.coerceIn(0f, 1f),
        heightScale = heightScale.coerceIn(0f, 1f),
        textureScale = textureScale.coerceAtLeast(0.05f),
    )
}

data class ResolvedBrushDab(
''',
    'VulkanSubstrateParams data class',
)
write(kt_path, k)


# -----------------------------------------------------------------------------
# Physical-device instrumentation coverage
# -----------------------------------------------------------------------------
test_path = 'core/nativebridge/src/androidTest/java/com/hereliesaz/graffitixr/nativebridge/VulkanStampEngineInstrumentedTest.kt'
t = read(test_path)
new_tests = r'''
    @Test
    fun substrateHeightGatesResolvedAndMaskedDabsAndRefreshesSameSizeTileContent() {
        val engine = initializedEngine()
        val blank = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(0x00000000) }
        val substrate = VulkanSubstrateParams(heightScale = 1f, textureScale = 1f)
        val shallow = ResolvedBrushDab(
            x = SIZE / 2f, y = SIZE / 2f, radius = 8f, alpha = 1f, angleDeg = 0f,
            colorArgb = COLOR_RED, flow = 1f, hardness = 1f,
            contactDepth = 0.2f, reservoirLoad = 1f, depositionRate = 1f, substrateResponse = 1f,
        )

        assertTrue(engine.upload(blank))
        assertTrue(engine.uploadSubstrateHeight(byteArrayOf(0xFF.toByte()), 1, 1))
        assertTrue(engine.stampResolvedDabs(listOf(shallow), substrate = substrate))
        val blocked = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        assertTrue(engine.readback(blocked))
        assertEquals("full-height tooth should block shallow resolved contact", 0x00000000, blocked.getPixel(SIZE / 2, SIZE / 2))

        assertTrue(engine.upload(blank))
        val deep = shallow.copy(contactDepth = 1f)
        assertTrue(engine.stampResolvedDabs(listOf(deep), substrate = substrate))
        val penetrated = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        assertTrue(engine.readback(penetrated))
        assertNotEquals("full contact should penetrate full-height tooth", 0x00000000, penetrated.getPixel(SIZE / 2, SIZE / 2))

        // Same dimensions as the previous tile, different bytes: verifies content hashing prevents
        // pooled/static texture staleness, not merely dimension-based reuse.
        assertTrue(engine.upload(blank))
        assertTrue(engine.uploadSubstrateHeight(byteArrayOf(0), 1, 1))
        assertTrue(engine.stampResolvedDabs(listOf(shallow), substrate = substrate))
        val refreshed = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        assertTrue(engine.readback(refreshed))
        assertNotEquals("same-size lower tooth tile was not refreshed", 0x00000000, refreshed.getPixel(SIZE / 2, SIZE / 2))

        // The independent masked pipeline must consume the exact same substrate tile/contract.
        assertTrue(engine.upload(blank))
        assertTrue(engine.uploadSubstrateHeight(byteArrayOf(0xFF.toByte()), 1, 1))
        val maskSize = 8
        val opaqueMask = ByteArray(maskSize * maskSize) { 0xFF.toByte() }
        val masked = MaskedBrushDab(
            x = SIZE / 2f, y = SIZE / 2f, radius = 8f, alpha = 1f, angleDeg = 0f,
            colorArgb = COLOR_GREEN, flow = 1f, tipRatio = 1f,
            contactDepth = 0.2f, reservoirLoad = 1f, depositionRate = 1f, substrateResponse = 1f,
        )
        assertTrue(
            engine.stampMaskedDabs(
                listOf(masked), hardness = 1f, maskAlpha8 = opaqueMask,
                maskWidth = maskSize, maskHeight = maskSize, substrate = substrate,
            ),
        )
        val maskedBlocked = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        assertTrue(engine.readback(maskedBlocked))
        assertEquals("masked shader ignored substrate gate", 0x00000000, maskedBlocked.getPixel(SIZE / 2, SIZE / 2))
    }

    @Test
    fun pooledWrapperRequiresFreshSubstrateUploadBeforeEnablingIt() {
        VulkanStampEngine.trimPool()
        val first = initializedEngine()
        assertTrue(first.uploadSubstrateHeight(byteArrayOf(0xFF.toByte()), 1, 1))
        first.destroy()

        val second = engine()
        assertTrue(second.init(SIZE, SIZE))
        val dab = ResolvedBrushDab(
            x = SIZE / 2f, y = SIZE / 2f, radius = 8f, alpha = 1f, angleDeg = 0f,
            colorArgb = COLOR_RED, flow = 1f, hardness = 1f,
            contactDepth = 1f, substrateResponse = 1f,
        )
        assertFalse(
            "new wrapper silently reused the previous owner's substrate tile",
            second.stampResolvedDabs(listOf(dab), substrate = VulkanSubstrateParams(heightScale = 1f)),
        )
    }

'''
t = replace_once(t, '    companion object {\n', new_tests + '    companion object {\n', 'instrumented substrate tests')
write(test_path, t)

print('Vulkan substrate parity product patch applied successfully.')
