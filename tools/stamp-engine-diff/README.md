# Stamp engine diff

Runs the same scripted painting scenarios through both GPU stamp backends on a Linux host and
compares every byte:

- `VulkanStampEngine` on Mesa lavapipe (software Vulkan)
- `GlesStampEngine` on Mesa llvmpipe (software OpenGL ES 3.2)

~~~
./run.sh
~~~

Covers upload/readback, max-combine and build-up stamping, stroke-max across batches, partial
readback with a reused buffer, canvas substrate, masked tips with grain and a secondary tip,
every Color Smudge mode (with and without reservoir pickup and Sample Merged), and clear.

Expected result: identical, apart from ±1–2 levels in a few dozen bytes of the smudge and
max-combine scenarios. That is rounding on exact half-values (`round()` is implementation-defined
in GLSL), and the two software drivers compile float math differently.

The paint-height scenario runs on GLES only. The Vulkan engine crashes on lavapipe inside
`vkUpdateDescriptorSets` in `uploadPaintHeight`. `paint_height_reference.py` checks the GLES output
against an independent NumPy implementation of the shader math instead. Build `run_gl.cpp` with
`-DWITH_PAINT_HEIGHT` to produce its inputs.

`shim/` stubs the two Android NDK headers the Vulkan engine includes, so it builds off-device.
