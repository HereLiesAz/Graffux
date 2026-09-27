// The wgpu engine (core/wgpu-engine) through the same WgpuStampEngine adapter Android uses, on the
// same scenarios as run_vk.cpp / run_gl.cpp. WGPU_BACKEND=vulkan|gl picks the wgpu backend and
// GRAFFUX_WGPU_LIB points the adapter's dlopen at the cargo build.
#include "include/WgpuStampEngine.h"
#ifndef OUTDIR
#define OUTDIR "out_wgpu"
#endif
#include "scenarios.h"
int main() { return runAll<WgpuStampEngine>(); }
