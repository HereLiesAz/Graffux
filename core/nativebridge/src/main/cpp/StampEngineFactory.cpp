// FILE: core/nativebridge/src/main/cpp/StampEngineFactory.cpp
#include "include/GlesStampEngine.h"
#include "include/StampEngine.h"
#include "include/VulkanStampEngine.h"

namespace graffux {

StampEngine* createStampEngine(int backend) {
    if (backend == static_cast<int>(StampBackend::Gles)) return new GlesStampEngine();
    return new VulkanStampEngine();
}

}  // namespace graffux
