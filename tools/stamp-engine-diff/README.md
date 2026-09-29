# Stamp engine diff

Runs the same scripted painting scenarios through the wgpu stamp engine (`core/wgpu-engine`, built
with cargo) on a Linux host, through `WgpuStampEngine`, the same C++ adapter Android uses, once on
wgpu's Vulkan backend (Mesa lavapipe) and once on its GL backend (Mesa llvmpipe), selected with
`WGPU_BACKEND`, and compares every byte.

~~~
./run.sh
~~~

Covers upload/readback, max-combine and build-up stamping, stroke-max across batches, partial
readback with a reused buffer, canvas substrate, masked tips with grain and a secondary tip,
every Color Smudge mode (with and without reservoir pickup and Sample Merged), and clear.

Expected result: Vulkan and GL identical apart from ±1–2 levels in a few dozen bytes of the smudge
and max-combine scenarios (rounding on exact half-values; the two software drivers compile float
math differently).

**Retired comparisons.** Until the Vulkan and GLES stamp engines were retired (docs/Native
Rendering Engine Design.md §2c) this tool also ran `VulkanStampEngine` on lavapipe and
`GlesStampEngine` on llvmpipe and compared wgpu with them. Measured on Mesa 25.2.8, the last time:

| pair | worst byte difference | bytes that differ (of 112,684) |
|---|---|---|
| wgpu (GL) vs GLES | 0 | 0 in every scenario |
| wgpu (Vulkan) vs Vulkan | 1 | at most 10, smudge and max-combine only |
| wgpu (Vulkan) vs wgpu (GL) | 2 | same pattern as Vulkan vs GLES |
| wgpu (both) vs GLES, paint-height build | 0 | 0 |

**Paint height.** `run.sh` builds `run_wgpu.cpp` with `-DWITH_PAINT_HEIGHT` on both wgpu backends
and checks each against `paint_height_reference.py`, an independent NumPy implementation of the
shader math (`python3 paint_height_reference.py <outdir>`).

**Multipass rendering.** `run.sh` also builds `run_wgpu.cpp` with `-DMULTIPASS`: the same
scenarios with the wgpu engine's experimental multipass rendering on (drafts at once, the layer
refined in 10-20 µs budgets between steps, everything landed before each result is read). Its
output must equal the plain wgpu run exactly, and does: 0 bytes differ in every scenario on both
wgpu backends (Mesa 25.2.8).

`run_wgpu_resident.cpp` checks resident layers through the same adapter: six strokes (round with
stroke-max frame batches, masked, smudge) with three undos in between, painted once by uploading
the whole layer before every stroke and once through `bindLayer`/`uploadLayer` plus
`refreshLayer` after a CPU commit, as Android does. Every stroke must be byte-identical, and only
the first stroke and the first stroke after each undo may upload. Measured on Mesa 25.2.8: 0 bytes
differ on both wgpu backends, 3 of 6 uploads avoided.
