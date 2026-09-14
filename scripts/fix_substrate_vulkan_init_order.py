from pathlib import Path

path = Path('core/nativebridge/src/main/cpp/VulkanStampEngine.cpp')
text = path.read_text(encoding='utf-8')
old_plain = '''    if (!createLogicalDeviceAndQueue({})) { destroy(); return false; }
    if (!createLayerImage(width, height)) { destroy(); return false; }
    if (!createDescriptorAndPipeline()) { destroy(); return false; }
    if (!allocateCommandBuffer()) { destroy(); return false; }
'''
new_plain = '''    if (!createLogicalDeviceAndQueue({})) { destroy(); return false; }
    if (!createLayerImage(width, height)) { destroy(); return false; }
    // Substrate descriptor setup seeds a static R8 tile through the shared transfer command
    // buffer, so command resources must exist before pipeline/descriptor initialization.
    if (!allocateCommandBuffer()) { destroy(); return false; }
    if (!createDescriptorAndPipeline()) { destroy(); return false; }
'''
old_ahb = '''    if (!createLogicalDeviceAndQueue(kAhbExtensions)) { destroy(); return false; }
    if (!createLayerImageFromHardwareBuffer(width, height)) { destroy(); return false; }
    if (!createDescriptorAndPipeline()) { destroy(); return false; }
    if (!allocateCommandBuffer()) { destroy(); return false; }
'''
new_ahb = '''    if (!createLogicalDeviceAndQueue(kAhbExtensions)) { destroy(); return false; }
    if (!createLayerImageFromHardwareBuffer(width, height)) { destroy(); return false; }
    if (!allocateCommandBuffer()) { destroy(); return false; }
    if (!createDescriptorAndPipeline()) { destroy(); return false; }
'''
for label, old, new in [('plain', old_plain, new_plain), ('AHB', old_ahb, new_ahb)]:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one init-order match, found {count}')
    text = text.replace(old, new, 1)
path.write_text(text, encoding='utf-8')
