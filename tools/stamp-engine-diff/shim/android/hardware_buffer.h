#pragma once
#include <cstdint>
struct AHardwareBuffer;
struct AHardwareBuffer_Desc { uint32_t width, height, layers, format; uint64_t usage; uint32_t stride, rfu0; uint64_t rfu1; };
enum { AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM = 1 };
enum : uint64_t { AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE = 1ULL << 8, AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT = 1ULL << 9, AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER = 1ULL << 24, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY = 2, AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY = 32 };
inline int AHardwareBuffer_allocate(const AHardwareBuffer_Desc*, AHardwareBuffer**) { return -1; }
inline void AHardwareBuffer_release(AHardwareBuffer*) {}
