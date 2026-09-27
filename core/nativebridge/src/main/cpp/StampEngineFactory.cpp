// FILE: core/nativebridge/src/main/cpp/StampEngineFactory.cpp
#include "include/GlesStampEngine.h"
#include "include/StampEngine.h"
#include "include/VulkanStampEngine.h"
#include "include/WgpuStampEngine.h"

namespace graffux {

StampEngine* createStampEngine(int backend) {
    if (backend == static_cast<int>(StampBackend::Gles)) return new GlesStampEngine();
    if (backend == static_cast<int>(StampBackend::Wgpu)) return new WgpuStampEngine();
    return new VulkanStampEngine();
}

}  // namespace graffux
