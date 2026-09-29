// FILE: core/nativebridge/src/main/cpp/StampEngineFactory.cpp
#include "include/StampEngine.h"
#include "include/WgpuStampEngine.h"

namespace graffux {

StampTuning& stampTuning() {
    static StampTuning tuning;
    return tuning;
}

// wgpu is the only GPU backend; the Vulkan and GLES engines were retired. When it cannot start,
// init() returns false and the Kotlin side draws on the CPU.
StampEngine* createStampEngine() { return new WgpuStampEngine(); }

}  // namespace graffux
