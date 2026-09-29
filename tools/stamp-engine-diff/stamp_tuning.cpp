// The process-wide StampTuning lives in StampEngineFactory.cpp, which also pulls in the engine
// (and the Android JNI build); the host harness links just this definition instead.
#include "include/StampEngine.h"

namespace graffux {
StampTuning& stampTuning() {
    static StampTuning tuning;
    return tuning;
}
}  // namespace graffux
