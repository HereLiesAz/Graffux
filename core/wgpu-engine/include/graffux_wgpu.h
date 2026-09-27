// FILE: core/wgpu-engine/include/graffux_wgpu.h
// C ABI of the Rust wgpu stamp engine (core/wgpu-engine/src/capi.rs). Record layouts are
// binary-identical to StampEngine.h's GpuDab (64 B), GpuSecondaryDab (32 B) and ColorSmudgeDab
// (44 B), so C++ passes its vectors' data() straight through.
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct GfxWgpuEngine GfxWgpuEngine;

typedef struct GfxWgpuSubstrate {
    int32_t enabled;
    int32_t hasPaintHeight;
    float baseHeight;
    float heightScale;
    float textureScale;
    float textureOffsetX;
    float textureOffsetY;
} GfxWgpuSubstrate;

// backend: 0 = auto (Vulkan/Metal/DX12 first, then GL; WGPU_BACKEND overrides), 1 = Vulkan, 2 = GL.
// Returns NULL when no adapter with compute support exists.
GfxWgpuEngine* gfx_wgpu_create(int32_t width, int32_t height, int32_t backend);
void gfx_wgpu_destroy(GfxWgpuEngine* e);
int32_t gfx_wgpu_width(GfxWgpuEngine* e);
int32_t gfx_wgpu_height(GfxWgpuEngine* e);
bool gfx_wgpu_clear(GfxWgpuEngine* e);
bool gfx_wgpu_upload(GfxWgpuEngine* e, const uint8_t* rgba, size_t len);
bool gfx_wgpu_upload_substrate_height(GfxWgpuEngine* e, const uint8_t* r8, int32_t width, int32_t height);
bool gfx_wgpu_upload_paint_height(GfxWgpuEngine* e, const float* heights, int32_t width, int32_t height);
bool gfx_wgpu_stamp_dabs(GfxWgpuEngine* e, const void* dabs, size_t count, uint32_t colorArgb,
                         float hardness, bool buildUp, const GfxWgpuSubstrate* substrate,
                         bool strokeMax);
bool gfx_wgpu_stamp_masked_dabs(GfxWgpuEngine* e, const void* dabs, size_t count, uint32_t colorArgb,
                                float hardness, const uint8_t* mask, int32_t maskWidth,
                                int32_t maskHeight, const uint8_t* grain, int32_t grainWidth,
                                int32_t grainHeight, bool grainCanvasLocked, float grainScale,
                                float grainPhaseX, float grainPhaseY, const void* secondaryDabs,
                                size_t secondaryCount, const uint8_t* secondaryMask,
                                int32_t secondaryWidth, int32_t secondaryHeight,
                                const GfxWgpuSubstrate* substrate);
bool gfx_wgpu_color_smudge(GfxWgpuEngine* e, const void* dabs, size_t count, int32_t mode,
                           float radiusPx, float feathering, bool smearAlpha,
                           uint32_t paintColorArgb, float dilution, const uint8_t* sampleSource,
                           int32_t sampleWidth, int32_t sampleHeight);
// ids = {vendorId, deviceId, selectedTileSize}, nanos = {nanos8, nanos16}.
void gfx_wgpu_benchmark_info(GfxWgpuEngine* e, uint32_t* ids, uint64_t* nanos);
bool gfx_wgpu_readback(GfxWgpuEngine* e, uint8_t* out, size_t capacity);
size_t gfx_wgpu_adapter_description(GfxWgpuEngine* e, char* out, size_t capacity);

// Dirty-rect readback: gfx_wgpu_readback plus the rectangle copied, rect = {x, y, w, h} (zeros when
// nothing was dirty). Only that rectangle crosses from the GPU.
bool gfx_wgpu_readback_rect(GfxWgpuEngine* e, uint8_t* out, size_t capacity, int32_t* rect);
// An explicit rectangle, tightly packed (w*4 bytes per row); leaves the dirty rectangle alone.
bool gfx_wgpu_read_region(GfxWgpuEngine* e, int32_t x, int32_t y, int32_t w, int32_t h,
                          uint8_t* out, size_t capacity);

// Resident layers (core/wgpu-engine/src/resident.rs). A layer stays on the GPU across strokes,
// keyed by the caller's layer key and tagged with a content generation the caller makes unique
// across layers. bind/upload start a stroke on it and return a bind session (0 = miss/failure).
// After the stroke commits, commit_layer (the GPU result is the committed layer) or refresh_layer
// (the CPU committed; re-upload the stroke's rows plus rect x,y,w,h from rgba) retag it. Anything
// that changes the CPU layer otherwise must invalidate it. clear()/upload() leave resident layers
// alone and paint an anonymous layer, exactly as before.
uint64_t gfx_wgpu_bind_layer(GfxWgpuEngine* e, uint64_t key, uint64_t generation);
uint64_t gfx_wgpu_upload_layer(GfxWgpuEngine* e, uint64_t key, uint64_t generation,
                               const uint8_t* rgba, size_t len);
bool gfx_wgpu_commit_layer(GfxWgpuEngine* e, uint64_t key, uint64_t session, uint64_t generation);
bool gfx_wgpu_refresh_layer(GfxWgpuEngine* e, uint64_t key, uint64_t session, uint64_t generation,
                            const uint8_t* rgba, size_t len, int32_t x, int32_t y, int32_t w,
                            int32_t h);
bool gfx_wgpu_invalidate_layer(GfxWgpuEngine* e, uint64_t key);
void gfx_wgpu_invalidate_all_layers(GfxWgpuEngine* e);
void gfx_wgpu_set_resident_budget(GfxWgpuEngine* e, uint64_t bytes);
// out = {resident layer count, bytes held}.
void gfx_wgpu_resident_stats(GfxWgpuEngine* e, uint64_t* out);

#ifdef __cplusplus
}
#endif
