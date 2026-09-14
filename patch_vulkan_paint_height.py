from pathlib import Path

ROOT = Path('.')


def read(path):
    return (ROOT / path).read_text()


def write(path, text):
    (ROOT / path).write_text(text)


def replace_exact(text, old, new, count=1, label='anchor'):
    actual = text.count(old)
    if actual != count:
        raise RuntimeError(f'{label}: expected {count} occurrences, found {actual}')
    return text.replace(old, new, count)


def extract_function(text, signature):
    start = text.index(signature)
    brace = text.index('{', start)
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
    raise RuntimeError(f'unclosed function: {signature}')

# ---------------------------------------------------------------------------
# Kotlin bridge: full-resolution existing paint-height upload + per-wrapper ownership.
# ---------------------------------------------------------------------------
kpath = 'core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/VulkanStampEngine.kt'
k = read(kpath)
k = replace_exact(
    k,
    '    private var substrateHeightUploaded = false\n',
    '    private var substrateHeightUploaded = false\n    private var paintHeightUploaded = false\n',
    label='kotlin state',
)
k = replace_exact(
    k,
    '        substrateHeightUploaded = false\n        val key = PoolKey(width, height, hardwareBufferBacked)\n',
    '        substrateHeightUploaded = false\n        paintHeightUploaded = false\n        val key = PoolKey(width, height, hardwareBufferBacked)\n',
    label='kotlin initialize reset',
)
needle = '''    /** Historical stroke-level paint entry point. */\n'''
method = '''    /**\n     * Uploads the existing normalized per-pixel paint height used by [ImpastoEngine].\n     * This is a GPU mirror of the caller-owned FloatArray, not a second height model. The native\n     * engine requires full canvas dimensions so shader texel coordinates remain identical to the\n     * CPU heightMap[y * width + x] contract.\n     */\n    fun uploadPaintHeight(heightMap: FloatArray, width: Int, height: Int): Boolean {\n        if (!isInitialized || width <= 0 || height <= 0) return false\n        require(heightMap.size >= width * height) {\n            "heightMap too small: need ${width * height}, got ${heightMap.size}"\n        }\n        val ok = nativeUploadPaintHeight(nativeHandle, heightMap, width, height)\n        paintHeightUploaded = ok\n        if (!ok) healthy = false\n        return ok\n    }\n\n'''
k = replace_exact(k, needle, method + needle, label='kotlin upload method')
k = replace_exact(
    k,
    '            nativeHandle, flat, buildUp, cfg != null,\n',
    '            nativeHandle, flat, buildUp, cfg != null, cfg != null && paintHeightUploaded,\n',
    label='resolved paint-height dispatch',
)
k = replace_exact(
    k,
    '            cfg != null, cfg?.baseHeight ?: 0f, cfg?.heightScale ?: 0f, cfg?.textureScale ?: 1f,\n',
    '            cfg != null, cfg != null && paintHeightUploaded,\n            cfg?.baseHeight ?: 0f, cfg?.heightScale ?: 0f, cfg?.textureScale ?: 1f,\n',
    label='masked paint-height dispatch',
)
k = replace_exact(
    k,
    '        val handle = nativeHandle\n        if (handle == 0L) return\n',
    '        val handle = nativeHandle\n        substrateHeightUploaded = false\n        paintHeightUploaded = false\n        if (handle == 0L) return\n',
    label='destroy ownership reset',
)
k = replace_exact(
    k,
    '    private external fun nativeUploadSubstrateHeight(handle: Long, heightR8: ByteArray, width: Int, height: Int): Boolean\n',
    '    private external fun nativeUploadSubstrateHeight(handle: Long, heightR8: ByteArray, width: Int, height: Int): Boolean\n    private external fun nativeUploadPaintHeight(handle: Long, heightMap: FloatArray, width: Int, height: Int): Boolean\n',
    label='native upload declaration',
)
k = replace_exact(
    k,
    '        handle: Long, dabData: FloatArray, buildUp: Boolean, hasSubstrate: Boolean,\n',
    '        handle: Long, dabData: FloatArray, buildUp: Boolean, hasSubstrate: Boolean, hasPaintHeight: Boolean,\n',
    label='resolved native signature',
)
k = replace_exact(
    k,
    '        secondaryMaskWidth: Int, secondaryMaskHeight: Int, hasSubstrate: Boolean,\n',
    '        secondaryMaskWidth: Int, secondaryMaskHeight: Int, hasSubstrate: Boolean, hasPaintHeight: Boolean,\n',
    label='masked native signature',
)
write(kpath, k)

# ---------------------------------------------------------------------------
# JNI bridge: upload FloatArray and carry whether this wrapper owns a fresh height mirror.
# ---------------------------------------------------------------------------
jpath = 'core/nativebridge/src/main/cpp/VulkanStampDynamicsJNI.cpp'
j = read(jpath)
j = replace_exact(
    j,
    'SubstrateStampParams substrateParams(jboolean enabled, jfloat baseHeight, jfloat heightScale,\n                                     jfloat textureScale, jfloat offsetX, jfloat offsetY) {\n',
    'SubstrateStampParams substrateParams(jboolean enabled, jboolean hasPaintHeight,\n                                     jfloat baseHeight, jfloat heightScale,\n                                     jfloat textureScale, jfloat offsetX, jfloat offsetY) {\n',
    label='jni substrateParams signature',
)
j = replace_exact(
    j,
    '    out.enabled = enabled == JNI_TRUE;\n',
    '    out.enabled = enabled == JNI_TRUE;\n    out.hasPaintHeight = out.enabled && hasPaintHeight == JNI_TRUE;\n',
    label='jni substrateParams state',
)
insert_before = 'extern "C" JNIEXPORT jboolean JNICALL\nJava_com_hereliesaz_graffitixr_nativebridge_VulkanStampEngine_nativeStampResolvedDabs('
upload_fn = '''extern "C" JNIEXPORT jboolean JNICALL\nJava_com_hereliesaz_graffitixr_nativebridge_VulkanStampEngine_nativeUploadPaintHeight(\n        JNIEnv* env, jobject, jlong handle, jfloatArray heightMap, jint width, jint height) {\n    auto* engine = reinterpret_cast<VulkanStampEngine*>(handle);\n    if (!engine || !heightMap || width <= 0 || height <= 0) return JNI_FALSE;\n    const jsize count = env->GetArrayLength(heightMap);\n    if (static_cast<jlong>(count) < static_cast<jlong>(width) * height) return JNI_FALSE;\n    jfloat* data = env->GetFloatArrayElements(heightMap, nullptr);\n    if (!data) return JNI_FALSE;\n    const bool ok = engine->uploadPaintHeight(data, width, height);\n    env->ReleaseFloatArrayElements(heightMap, data, JNI_ABORT);\n    return ok ? JNI_TRUE : JNI_FALSE;\n}\n\n'''
j = replace_exact(j, insert_before, upload_fn + insert_before, label='jni paint upload function')
j = replace_exact(
    j,
    '        jboolean hasSubstrate, jfloat substrateBaseHeight, jfloat substrateHeightScale,\n',
    '        jboolean hasSubstrate, jboolean hasPaintHeight, jfloat substrateBaseHeight, jfloat substrateHeightScale,\n',
    count=1,
    label='jni resolved args',
)
j = replace_exact(
    j,
    '        hasSubstrate, substrateBaseHeight, substrateHeightScale, substrateTextureScale,\n',
    '        hasSubstrate, hasPaintHeight, substrateBaseHeight, substrateHeightScale, substrateTextureScale,\n',
    count=1,
    label='jni resolved params call',
)
j = replace_exact(
    j,
    '        jint secondaryMaskHeight, jboolean hasSubstrate, jfloat substrateBaseHeight,\n',
    '        jint secondaryMaskHeight, jboolean hasSubstrate, jboolean hasPaintHeight, jfloat substrateBaseHeight,\n',
    label='jni masked args',
)
j = replace_exact(
    j,
    '        substrateParams(hasSubstrate, substrateBaseHeight, substrateHeightScale,\n',
    '        substrateParams(hasSubstrate, hasPaintHeight, substrateBaseHeight, substrateHeightScale,\n',
    label='jni masked params call',
)
write(jpath, j)

# ---------------------------------------------------------------------------
# Native header: add one GPU mirror of the existing FloatArray and no new material model.
# ---------------------------------------------------------------------------
hpath = 'core/nativebridge/src/main/cpp/include/VulkanStampEngine.h'
h = read(hpath)
h = replace_exact(
    h,
    'struct SubstrateStampParams {\n    bool enabled = false;\n',
    'struct SubstrateStampParams {\n    bool enabled = false;\n    bool hasPaintHeight = false;\n',
    label='header params',
)
h = replace_exact(
    h,
    '    bool uploadSubstrateHeight(const uint8_t* heightR8, int width, int height);\n',
    '    bool uploadSubstrateHeight(const uint8_t* heightR8, int width, int height);\n\n    // GPU mirror of Layer.heightMap / ImpastoEngine normalized thickness. Dimensions must match\n    // this engine\'s layer exactly; this resource is only sampled when hasPaintHeight is true.\n    bool uploadPaintHeight(const float* heightMap, int width, int height);\n',
    label='header public upload',
)
h = replace_exact(
    h,
    '    bool ensureSubstrateTexture(int width, int height);\n    bool uploadSubstrateTexture(const uint8_t* heightR8, int width, int height);\n',
    '    bool ensureSubstrateTexture(int width, int height);\n    bool uploadSubstrateTexture(const uint8_t* heightR8, int width, int height);\n    bool ensurePaintHeightTexture(int width, int height);\n    bool uploadPaintHeightTexture(const float* heightMap, int width, int height);\n',
    label='header private methods',
)
field_anchor = '''    uint64_t substrateContentHash_ = 0;\n\n'''
fields = '''    uint64_t substrateContentHash_ = 0;\n\n    // Full-canvas R32_SFLOAT GPU mirror of the existing Layer.heightMap. It is deliberately a\n    // render resource only: the authoritative state remains the existing CPU FloatArray.\n    VkImage paintHeightImage_ = VK_NULL_HANDLE;\n    VkDeviceMemory paintHeightImageMemory_ = VK_NULL_HANDLE;\n    VkImageView paintHeightImageView_ = VK_NULL_HANDLE;\n    VkSampler paintHeightSampler_ = VK_NULL_HANDLE;\n    VkBuffer paintHeightStagingBuffer_ = VK_NULL_HANDLE;\n    VkDeviceMemory paintHeightStagingBufferMemory_ = VK_NULL_HANDLE;\n    int paintHeightWidth_ = 0;\n    int paintHeightHeight_ = 0;\n    VkImageLayout paintHeightImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;\n    uint64_t paintHeightContentHash_ = 0;\n\n'''
h = replace_exact(h, field_anchor, fields, label='header paint fields')
write(hpath, h)

# ---------------------------------------------------------------------------
# Native implementation.
# ---------------------------------------------------------------------------
cpath = 'core/nativebridge/src/main/cpp/VulkanStampEngine.cpp'
c = read(cpath)
# Push constants: one explicit resource-validity bit.
c = replace_exact(c, '    float hasSubstrate;\n    float substrateBaseHeight;\n',
                  '    float hasSubstrate;\n    float hasPaintHeight;\n    float substrateBaseHeight;\n',
                  count=2, label='push constant paint bit')

# Plain descriptor layout: add binding 3. Pool only needs one extra combined sampler descriptor.
c = replace_exact(c, '    VkDescriptorSetLayoutBinding bindings[3]{};\n',
                  '    VkDescriptorSetLayoutBinding bindings[4]{};\n', label='plain layout size')
plain_binding = '''    bindings[2].binding = 2;\n    bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;\n    bindings[2].descriptorCount = 1;\n    bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;\n'''
c = replace_exact(c, plain_binding, plain_binding + '''    bindings[3].binding = 3;\n    bindings[3].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;\n    bindings[3].descriptorCount = 1;\n    bindings[3].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;\n''', count=1, label='plain binding 3')
c = replace_exact(c, '    layoutInfo.bindingCount = 3;\n', '    layoutInfo.bindingCount = 4;\n', label='plain binding count')
# In the first descriptor pool only, the existing combined-sampler bucket now holds substrate + paint height.
start, end, plain_create = extract_function(c, 'bool VulkanStampEngine::createDescriptorAndPipeline()')
plain_create = replace_exact(plain_create, '    poolSizes[2].descriptorCount = 1;\n',
                             '    poolSizes[2].descriptorCount = 2;\n', label='plain pool sampler count')
c = c[:start] + plain_create + c[end:]

# Masked descriptor layout: binding 7 for full-canvas paint height.
c = replace_exact(c, '    VkDescriptorSetLayoutBinding bindings[7]{};\n',
                  '    VkDescriptorSetLayoutBinding bindings[8]{};\n', label='masked layout size')
masked_binding = '''    bindings[6].binding = 6;\n    bindings[6].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;\n    bindings[6].descriptorCount = 1;\n    bindings[6].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;\n'''
c = replace_exact(c, masked_binding, masked_binding + '''    bindings[7].binding = 7;\n    bindings[7].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;\n    bindings[7].descriptorCount = 1;\n    bindings[7].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;\n''', label='masked binding 7')
c = replace_exact(c, '    layoutInfo.bindingCount = 7;\n', '    layoutInfo.bindingCount = 8;\n', label='masked binding count')
c = replace_exact(c, '    VkDescriptorPoolSize poolSizes[7]{};\n',
                  '    VkDescriptorPoolSize poolSizes[8]{};\n', label='masked pool size')
masked_pool_tail = '''    poolSizes[6].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;\n    poolSizes[6].descriptorCount = 1;\n'''
c = replace_exact(c, masked_pool_tail, masked_pool_tail + '''    poolSizes[7].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;\n    poolSizes[7].descriptorCount = 1;\n''', label='masked pool paint sampler')
c = replace_exact(c, '    poolInfo.poolSizeCount = 7;\n', '    poolInfo.poolSizeCount = 8;\n', label='masked pool count')

# A newly-created masked pipeline must bind either the actual height image or the current substrate
# image as a valid unread dummy. texelFetch only runs when hasPaintHeight is set.
masked_substrate_write = '''    VkWriteDescriptorSet substrateWrite{};\n    substrateWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;\n    substrateWrite.dstSet = maskedDescriptorSet_;\n    substrateWrite.dstBinding = 6;\n    substrateWrite.descriptorCount = 1;\n    substrateWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;\n    substrateWrite.pImageInfo = &substrateInfo;\n    vkUpdateDescriptorSets(device_, 1, &substrateWrite, 0, nullptr);\n'''
masked_paint_write = masked_substrate_write + '''\n    VkDescriptorImageInfo paintHeightInfo{};\n    paintHeightInfo.sampler = paintHeightImage_ != VK_NULL_HANDLE ? paintHeightSampler_ : substrateSampler_;\n    paintHeightInfo.imageView = paintHeightImage_ != VK_NULL_HANDLE ? paintHeightImageView_ : substrateImageView_;\n    paintHeightInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;\n    VkWriteDescriptorSet paintHeightWrite{};\n    paintHeightWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;\n    paintHeightWrite.dstSet = maskedDescriptorSet_;\n    paintHeightWrite.dstBinding = 7;\n    paintHeightWrite.descriptorCount = 1;\n    paintHeightWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;\n    paintHeightWrite.pImageInfo = &paintHeightInfo;\n    vkUpdateDescriptorSets(device_, 1, &paintHeightWrite, 0, nullptr);\n'''
c = replace_exact(c, masked_substrate_write, masked_paint_write, label='masked initial paint binding')

# Clone the proven substrate texture plumbing, changing only resource names, format, byte size and bindings.
_, _, ensure_sub = extract_function(c, 'bool VulkanStampEngine::ensureSubstrateTexture')
ensure_paint = ensure_sub.replace('ensureSubstrateTexture', 'ensurePaintHeightTexture')
ensure_paint = ensure_paint.replace('substrate', 'paintHeight').replace('Substrate', 'PaintHeight')
ensure_paint = ensure_paint.replace('VK_FORMAT_R8_UNORM', 'VK_FORMAT_R32_SFLOAT')
ensure_paint = ensure_paint.replace('roundWrite.dstBinding = 2;', 'roundWrite.dstBinding = 3;')
ensure_paint = ensure_paint.replace('maskedWrite.dstBinding = 6;', 'maskedWrite.dstBinding = 7;')
# R32 float staging is four bytes per texel.
ensure_paint = ensure_paint.replace(
    'VkDeviceSize stagingSize = static_cast<VkDeviceSize>(width) * height;',
    'VkDeviceSize stagingSize = static_cast<VkDeviceSize>(width) * height * sizeof(float);',
)
if 'VK_FORMAT_R32_SFLOAT' not in ensure_paint:
    raise RuntimeError('paint height ensure clone did not switch format')

_, _, upload_sub = extract_function(c, 'bool VulkanStampEngine::uploadSubstrateTexture')
upload_paint = upload_sub.replace('uploadSubstrateTexture', 'uploadPaintHeightTexture')
upload_paint = upload_paint.replace('substrate', 'paintHeight').replace('Substrate', 'PaintHeight')
upload_paint = upload_paint.replace('const uint8_t* heightR8', 'const float* heightMap')
upload_paint = upload_paint.replace('heightR8', 'heightMap')
upload_paint = upload_paint.replace(
    'VkDeviceSize bytes = static_cast<VkDeviceSize>(width) * height;',
    'VkDeviceSize bytes = static_cast<VkDeviceSize>(width) * height * sizeof(float);',
)
if 'const float* heightMap' not in upload_paint:
    raise RuntimeError('paint height upload clone signature failed')

# Insert helpers immediately before public substrate upload so related resources stay grouped.
public_sig = 'bool VulkanStampEngine::uploadSubstrateHeight(const uint8_t* heightR8, int width, int height) {'
public_pos = c.index(public_sig)
c = c[:public_pos] + ensure_paint + '\n\n' + upload_paint + '\n\n' + c[public_pos:]
# Insert public FloatArray mirror upload after the substrate upload function.
s_start, s_end, _ = extract_function(c, public_sig)
paint_public = '''\n\nbool VulkanStampEngine::uploadPaintHeight(const float* heightMap, int width, int height) {\n    if (!isInitialized() || heightMap == nullptr || width <= 0 || height <= 0) return false;\n    // Layer.heightMap is row-major and canvas-sized. Rejecting any other dimensions avoids silently\n    // inventing resampling semantics that the CPU reference does not have.\n    if (width != width_ || height != height_) return false;\n    const size_t bytes = static_cast<size_t>(width) * static_cast<size_t>(height) * sizeof(float);\n    const uint64_t hash = fnv1a(reinterpret_cast<const uint8_t*>(heightMap), bytes);\n    const bool changed = paintHeightImage_ == VK_NULL_HANDLE || width != paintHeightWidth_ ||\n                         height != paintHeightHeight_ || hash != paintHeightContentHash_;\n    if (!ensurePaintHeightTexture(width, height)) return false;\n    if (changed) {\n        if (!uploadPaintHeightTexture(heightMap, width, height)) return false;\n        paintHeightContentHash_ = hash;\n    }\n    return true;\n}\n'''
c = c[:s_end] + paint_public + c[s_end:]

# When substrate is recreated and no actual paint-height texture exists, keep the unread dummy
# descriptor valid rather than leaving it pointing at the destroyed old substrate image.
sub_start, sub_end, sub_block = extract_function(c, 'bool VulkanStampEngine::ensureSubstrateTexture')
old_masked_tail = '''    if (maskedDescriptorSet_ != VK_NULL_HANDLE) {\n        VkWriteDescriptorSet maskedWrite{};\n        maskedWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;\n        maskedWrite.dstSet = maskedDescriptorSet_;\n        maskedWrite.dstBinding = 6;\n        maskedWrite.descriptorCount = 1;\n        maskedWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;\n        maskedWrite.pImageInfo = &samplerImageInfo;\n        vkUpdateDescriptorSets(device_, 1, &maskedWrite, 0, nullptr);\n    }\n'''
new_masked_tail = old_masked_tail + '''    if (paintHeightImage_ == VK_NULL_HANDLE && descriptorSet_ != VK_NULL_HANDLE) {\n        VkWriteDescriptorSet dummyPaintWrite{};\n        dummyPaintWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;\n        dummyPaintWrite.dstSet = descriptorSet_;\n        dummyPaintWrite.dstBinding = 3;\n        dummyPaintWrite.descriptorCount = 1;\n        dummyPaintWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;\n        dummyPaintWrite.pImageInfo = &samplerImageInfo;\n        vkUpdateDescriptorSets(device_, 1, &dummyPaintWrite, 0, nullptr);\n    }\n    if (paintHeightImage_ == VK_NULL_HANDLE && maskedDescriptorSet_ != VK_NULL_HANDLE) {\n        VkWriteDescriptorSet dummyMaskedPaintWrite{};\n        dummyMaskedPaintWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;\n        dummyMaskedPaintWrite.dstSet = maskedDescriptorSet_;\n        dummyMaskedPaintWrite.dstBinding = 7;\n        dummyMaskedPaintWrite.descriptorCount = 1;\n        dummyMaskedPaintWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;\n        dummyMaskedPaintWrite.pImageInfo = &samplerImageInfo;\n        vkUpdateDescriptorSets(device_, 1, &dummyMaskedPaintWrite, 0, nullptr);\n    }\n'''
sub_block = replace_exact(sub_block, old_masked_tail, new_masked_tail, label='substrate dummy paint refresh')
c = c[:sub_start] + sub_block + c[sub_end:]

# Dispatch state mirrors the CPU reference only when the wrapper supplied a current height map.
c = replace_exact(c, '        pc.hasSubstrate = substrate.enabled ? 1.0f : 0.0f;\n',
                  '        pc.hasSubstrate = substrate.enabled ? 1.0f : 0.0f;\n        pc.hasPaintHeight = substrate.hasPaintHeight ? 1.0f : 0.0f;\n',
                  count=2, label='push state assignment')

# Destroy all optional paint-height resources and reset their metadata.
destroy_anchor = '''    if (substrateStagingBufferMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, substrateStagingBufferMemory_, nullptr); substrateStagingBufferMemory_ = VK_NULL_HANDLE; }\n'''
destroy_extra = destroy_anchor + '''\n    if (paintHeightSampler_ != VK_NULL_HANDLE) { vkDestroySampler(device_, paintHeightSampler_, nullptr); paintHeightSampler_ = VK_NULL_HANDLE; }\n    if (paintHeightImageView_ != VK_NULL_HANDLE) { vkDestroyImageView(device_, paintHeightImageView_, nullptr); paintHeightImageView_ = VK_NULL_HANDLE; }\n    if (paintHeightImage_ != VK_NULL_HANDLE) { vkDestroyImage(device_, paintHeightImage_, nullptr); paintHeightImage_ = VK_NULL_HANDLE; }\n    if (paintHeightImageMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, paintHeightImageMemory_, nullptr); paintHeightImageMemory_ = VK_NULL_HANDLE; }\n    if (paintHeightStagingBuffer_ != VK_NULL_HANDLE) { vkDestroyBuffer(device_, paintHeightStagingBuffer_, nullptr); paintHeightStagingBuffer_ = VK_NULL_HANDLE; }\n    if (paintHeightStagingBufferMemory_ != VK_NULL_HANDLE) { vkFreeMemory(device_, paintHeightStagingBufferMemory_, nullptr); paintHeightStagingBufferMemory_ = VK_NULL_HANDLE; }\n'''
c = replace_exact(c, destroy_anchor, destroy_extra, label='destroy paint resources')
c = replace_exact(c, '    substrateContentHash_ = 0;\n',
                  '    substrateContentHash_ = 0;\n    paintHeightWidth_ = 0;\n    paintHeightHeight_ = 0;\n    paintHeightImageLayout_ = VK_IMAGE_LAYOUT_UNDEFINED;\n    paintHeightContentHash_ = 0;\n',
                  count=1, label='destroy paint metadata')
write(cpath, c)

# ---------------------------------------------------------------------------
# Shaders: full-resolution local paint height lowers the tooth barrier exactly like CPU.
# ---------------------------------------------------------------------------
for spath, binding in [
    ('core/nativebridge/src/main/cpp/shaders/stamp.comp', 3),
    ('core/nativebridge/src/main/cpp/shaders/stamp_masked.comp', 7),
]:
    s = read(spath)
    substrate_decl = 'uniform sampler2D substrateTex;\n'
    s = replace_exact(s, substrate_decl,
        substrate_decl + f'layout(binding = {binding}) uniform sampler2D paintHeightTex;\n',
        label=f'{spath} paint binding')
    s = replace_exact(s, '    float hasSubstrate;\n    float substrateBaseHeight;\n',
        '    float hasSubstrate;\n    float hasPaintHeight;\n    float substrateBaseHeight;\n',
        label=f'{spath} push paint flag')
    old = '''    float contactDepth = clamp(d.material.x, 0.0, 1.0);\n    float response = clamp(d.material.w, 0.0, 1.0);\n    float penetrates = contactDepth >= substrateHeight ? 1.0 : 0.0;\n'''
    new = '''    float localPaintHeight = 0.0;\n    if (pc.hasPaintHeight > 0.5) {\n        ivec2 paintSize = textureSize(paintHeightTex, 0);\n        ivec2 paintPixel = ivec2(floor(canvasPoint));\n        if (paintPixel.x >= 0 && paintPixel.y >= 0 && paintPixel.x < paintSize.x && paintPixel.y < paintSize.y) {\n            localPaintHeight = max(texelFetch(paintHeightTex, paintPixel, 0).r, 0.0);\n        }\n    }\n    float barrier = clamp(substrateHeight - localPaintHeight, 0.0, 1.0);\n    float contactDepth = clamp(d.material.x, 0.0, 1.0);\n    float response = clamp(d.material.w, 0.0, 1.0);\n    float penetrates = contactDepth >= barrier ? 1.0 : 0.0;\n'''
    s = replace_exact(s, old, new, label=f'{spath} barrier formula')
    write(spath, s)

# ---------------------------------------------------------------------------
# Physical-device coverage: valley filling, same-size refresh, masked parity, pooled isolation.
# ---------------------------------------------------------------------------
tpath = 'core/nativebridge/src/androidTest/java/com/hereliesaz/graffitixr/nativebridge/VulkanStampEngineInstrumentedTest.kt'
t = read(tpath)
test_anchor = '    @Test\n    fun pooledWrapperRequiresFreshSubstrateUploadBeforeEnablingIt() {'
new_tests = '''    @Test\n    fun existingPaintHeightLowersSubstrateBarrierForResolvedAndMaskedDabs() {\n        val engine = initializedEngine()\n        val blank = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(0x00000000) }\n        val substrate = VulkanSubstrateParams(heightScale = 1f, textureScale = 1f)\n        val shallow = ResolvedBrushDab(\n            x = SIZE / 2f, y = SIZE / 2f, radius = 8f, alpha = 1f, angleDeg = 0f,\n            colorArgb = COLOR_RED, flow = 1f, hardness = 1f,\n            contactDepth = 0.2f, reservoirLoad = 1f, depositionRate = 1f, substrateResponse = 1f,\n        )\n        assertTrue(engine.uploadSubstrateHeight(byteArrayOf(0xFF.toByte()), 1, 1))\n\n        val filledValley = FloatArray(SIZE * SIZE)\n        filledValley[(SIZE / 2) * SIZE + SIZE / 2] = 0.9f\n        assertTrue(engine.uploadPaintHeight(filledValley, SIZE, SIZE))\n        assertTrue(engine.upload(blank))\n        assertTrue(engine.stampResolvedDabs(listOf(shallow), substrate = substrate))\n        val resolved = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)\n        assertTrue(engine.readback(resolved))\n        assertNotEquals(\n            "existing paint height did not lower the full-tooth barrier for resolved dabs",\n            0x00000000, resolved.getPixel(SIZE / 2, SIZE / 2),\n        )\n\n        val maskSize = 8\n        val opaqueMask = ByteArray(maskSize * maskSize) { 0xFF.toByte() }\n        val masked = MaskedBrushDab(\n            x = SIZE / 2f, y = SIZE / 2f, radius = 8f, alpha = 1f, angleDeg = 0f,\n            colorArgb = COLOR_GREEN, flow = 1f, tipRatio = 1f,\n            contactDepth = 0.2f, reservoirLoad = 1f, depositionRate = 1f, substrateResponse = 1f,\n        )\n        assertTrue(engine.upload(blank))\n        assertTrue(\n            engine.stampMaskedDabs(\n                listOf(masked), hardness = 1f, maskAlpha8 = opaqueMask,\n                maskWidth = maskSize, maskHeight = maskSize, substrate = substrate,\n            ),\n        )\n        val maskedResult = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)\n        assertTrue(engine.readback(maskedResult))\n        assertNotEquals(\n            "masked shader did not consume the existing paint-height barrier reduction",\n            0x00000000, maskedResult.getPixel(SIZE / 2, SIZE / 2),\n        )\n\n        // Same canvas dimensions, changed FloatArray contents: the GPU mirror must refresh.\n        assertTrue(engine.uploadPaintHeight(FloatArray(SIZE * SIZE), SIZE, SIZE))\n        assertTrue(engine.upload(blank))\n        assertTrue(engine.stampResolvedDabs(listOf(shallow), substrate = substrate))\n        val refreshed = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)\n        assertTrue(engine.readback(refreshed))\n        assertEquals(\n            "same-size paint-height content refresh left stale valley fill on the GPU",\n            0x00000000, refreshed.getPixel(SIZE / 2, SIZE / 2),\n        )\n    }\n\n    @Test\n    fun pooledWrapperDoesNotReusePreviousOwnersPaintHeight() {\n        VulkanStampEngine.trimPool()\n        val first = initializedEngine()\n        val filled = FloatArray(SIZE * SIZE) { 1f }\n        assertTrue(first.uploadPaintHeight(filled, SIZE, SIZE))\n        first.destroy()\n\n        val second = engine()\n        assertTrue(second.init(SIZE, SIZE))\n        val blank = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(0x00000000) }\n        assertTrue(second.upload(blank))\n        assertTrue(second.uploadSubstrateHeight(byteArrayOf(0xFF.toByte()), 1, 1))\n        val shallow = ResolvedBrushDab(\n            x = SIZE / 2f, y = SIZE / 2f, radius = 8f, alpha = 1f, angleDeg = 0f,\n            colorArgb = COLOR_RED, flow = 1f, hardness = 1f,\n            contactDepth = 0.2f, reservoirLoad = 1f, depositionRate = 1f, substrateResponse = 1f,\n        )\n        assertTrue(second.stampResolvedDabs(listOf(shallow), substrate = VulkanSubstrateParams(heightScale = 1f)))\n        val result = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)\n        assertTrue(second.readback(result))\n        assertEquals(\n            "new wrapper sampled the previous owner's paint-height mirror",\n            0x00000000, result.getPixel(SIZE / 2, SIZE / 2),\n        )\n    }\n\n'''
t = replace_exact(t, test_anchor, new_tests + test_anchor, label='instrumentation tests')
write(tpath, t)

# Final static guards.
expected = {
    kpath, jpath, hpath, cpath,
    'core/nativebridge/src/main/cpp/shaders/stamp.comp',
    'core/nativebridge/src/main/cpp/shaders/stamp_masked.comp',
    tpath,
}
print('patched', len(expected), 'product/test files')
