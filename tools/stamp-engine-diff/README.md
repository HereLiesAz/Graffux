# Stamp engine diff

Runs the same scripted painting scenarios through every GPU stamp backend on a Linux host and
compares every byte:

- `VulkanStampEngine` on Mesa lavapipe (software Vulkan)
- `GlesStampEngine` on Mesa llvmpipe (software OpenGL ES 3.2)
- the wgpu engine (`core/wgpu-engine`, built with cargo) through `WgpuStampEngine`, the same C++
  adapter Android uses, once on wgpu's Vulkan backend (lavapipe) and once on its GL backend
  (llvmpipe), selected with `WGPU_BACKEND`

~~~
./run.sh
~~~

Covers upload/readback, max-combine and build-up stamping, stroke-max across batches, partial
readback with a reused buffer, canvas substrate, masked tips with grain and a secondary tip,
every Color Smudge mode (with and without reservoir pickup and Sample Merged), and clear.

Expected result: identical, apart from ±1–2 levels in a few dozen bytes of the smudge and
max-combine scenarios. That is rounding on exact half-values (`round()` is implementation-defined
in GLSL), and the two software drivers compile float math differently.

The wgpu engine matches the C++ engines at least as closely as they match each other. Measured
on Mesa 25.2.8:

| pair | worst byte difference | bytes that differ (of 112,684) |
|---|---|---|
| wgpu (GL) vs GLES | 0 | 0 in every scenario |
| wgpu (Vulkan) vs Vulkan | 1 | at most 10, smudge and max-combine only |
| wgpu (Vulkan) vs wgpu (GL) | 2 | same pattern as Vulkan vs GLES |
| wgpu (both) vs GLES, paint-height build | 0 | 0 |

The paint-height scenario runs on GLES only. The Vulkan engine crashes on lavapipe inside
`vkUpdateDescriptorSets` in `uploadPaintHeight`. `paint_height_reference.py` checks the GLES output
against an independent NumPy implementation of the shader math instead. Build `run_gl.cpp` with
`-DWITH_PAINT_HEIGHT` to produce its inputs. `run.sh` builds that variant of both GLES and wgpu and
compares them.

`run_wgpu_resident.cpp` checks resident layers through the same adapter: six strokes (round with
stroke-max frame batches, masked, smudge) with three undos in between, painted once by uploading
the whole layer before every stroke and once through `bindLayer`/`uploadLayer` plus
`refreshLayer` after a CPU commit, as Android does. Every stroke must be byte-identical, and only
the first stroke and the first stroke after each undo may upload. Measured on Mesa 25.2.8: 0 bytes
differ on both wgpu backends, 3 of 6 uploads avoided.

`shim/` stubs the two Android NDK headers the Vulkan engine includes, so it builds off-device.
