# Graffux Native Rendering Engine — Design Proposal

*Companion to `docs/Procreate Brush Engine Technical Analysis.md`. Where that document explains
Valkyrie, this one maps each of its techniques onto Android and this codebase specifically, and
proposes what to actually build. It is a design, not a changelog — nothing here is implemented yet.*

## 0. Where we actually are today

Before proposing anything, an honest inventory of the current pipeline, because "rival Valkyrie"
is meaningless without a baseline to measure against.

Graffux's brush rendering is **100% CPU-bound `android.graphics` Canvas/Paint**, in three places
that all have to agree pixel-for-pixel (`feature/editor/src/main/java/.../EditorViewModel.kt`,
`ImageProcessor.kt`, `DrawingEngine.kt`):

- **Live preview** (`onStrokeStart`/`onStrokePoint`): a background-thread `Canvas` over a
  `Bitmap` copy of the layer, redrawn incrementally per touch sample (throttled to
  `inputSampleRateHz`, default 60).
- **Commit / undo-redo replay** (`ImageProcessor.applyToolToBitmap`, `DrawingEngine.composite`):
  the authoritative path — re-rasterizes the whole recorded `StrokeCommand` list from scratch on
  every undo/redo.
- **Stamp brushes** (`StampBrushRenderer`, `BrushStamps`): per-dab `RadialGradient`/`Bitmap`
  draws via `Canvas.drawCircle`/`drawBitmap`, one dab per arc-length step.

Dynamics that already exist and are worth keeping as-is conceptually:

- `BrushDynamics` — velocity-based thinning + start taper + (as of this session) pressure, all
  deterministic and replay-safe.
- `StrokeStabilizer` — a single weighted-moving-average smoother (Valkyrie's "Stabilization"
  tier only; no StreamLine, no Motion Filtering — see §4).
- `AzphaltBrush`/`BrushStamps` — a real stamp-brush model (spacing, hardness, jitter, scatter,
  follow-stroke, flow build-up) that already mirrors Procreate's Shape+dynamics concept.
- The just-added whole-stroke `Opacity` ceiling (offscreen mask + one composite) is, not
  coincidentally, a primitive version of what Valkyrie's compute shaders do per-frame — we did it
  once per commit on the CPU because that's cheap enough there; the same operation needs to run
  per *dab*, live, for wet mix (§6).

**The ceiling of this architecture**: every stroke operation allocates and walks full-resolution
`Bitmap`s on the CPU. `applyToolToBitmap` copies the entire layer bitmap per stroke commit and per
undo/redo step. There is no compute-shader parallelism, no persistent GPU-resident layer state (the
wgpu engine now has it, see §2b), no
frame-pacing control beyond Compose's own recomposition, and touch-prediction is presentation-only
(§4) — no predicted dab ever enters the paint path. This is why a
fast, heavy stroke on a large canvas visibly lags — it is doing exactly what Valkyrie was built to
stop doing (§9 of the companion doc: CSP's "8.7ms of CPU thumbnail regen" story is structurally
the same class of problem `applyToolToBitmap`'s full-bitmap copy is).

None of this is a criticism of the existing code — `BrushDynamics`, the stamp model, and the
whole-stroke-opacity fix are all correct, well-tested, and the right call *for a CPU pipeline*.
The point of this document is that the pipeline itself, not any one algorithm in it, is the
ceiling.

## 1. What "rival Valkyrie" can and can't mean here

Metal is Apple-silicon-and-iPadOS-exclusive; Valkyrie is not portable, and neither is a literal
clone of it. What *is* portable is the set of engineering decisions the companion doc identifies
as load-bearing:

1. GPU compute shaders own the per-dab/per-pixel math, not the CPU.
2. The display path bypasses the normal compositor queue for the in-progress stroke (front-buffer
   rendering) so a frame reflects the stylus position at the moment it's presented, not one
   buffer-swap behind.
3. Sub-frame input is captured (coalesced) and the leading edge is predicted, not just sampled
   once per display frame.
4. Stroke geometry is a proper spline through the input points, not a polyline.
5. Stroke filtration is a *choice* of algorithm (kinematic damping vs. moving average vs.
   frequency filtering), not one fixed smoother.
6. Wet/dry paint state is simulated per-dab and decays over the stroke (Charge/Dilution/Pull),
   not just a flat opacity.

Android has a real, if less mature, answer to every one of these (§2–§7). The honest gaps: no
equivalent to `presentsWithTransaction`'s guarantee prior to a specific dedicated low-latency
API (Android's front-buffer libraries are close but younger and less universally supported), no
first-party Apple-Pencil-grade tilt/azimuth/barrel-roll telemetry from most Android styluses (this
varies by OEM/digitizer — S Pen exposes tilt and some orientation, most others don't), and no
240Hz-class digitizer polling — most Android stylus/touch hardware tops out around 120–180Hz raw,
some far lower. The design below targets device capability tiers rather than assuming Apple-Pencil
parity everywhere (§8).

## 2. GPU compute backbone: Vulkan compute for stamping/wet-mix, GL for presentation

**Revised from this document's first draft.** The original version of this section recommended
GLES 3.1 compute across the board and treated `CMakeLists.txt`'s `# Removed VulkanBackend.cpp`
comment as an unknown risk worth avoiding. You've since confirmed that code predates this repo —
it's leftover from GraffitiXR, the app Graffux's editor was migrated out of, not a Vulkan attempt
made *in* Graffux that got pulled for cause. That removes the one reason the first draft had to
avoid Vulkan; the actual engineering tradeoff underneath still needs stating on its own merits,
which is what this section now does. **Recommendation: Vulkan compute for the stamping/wet-mix
kernels, GL/EGL kept only where Android's first-party low-latency library requires it, the two
bridged through `AHardwareBuffer`.**

Why split it rather than pick one wholesale:

- **Wet Mix (§7) is a read-your-own-write hazard** — every dab samples the *same* layer texture
  it's about to blend into, and dabs in one dispatch can overlap. GLES exposes this only through
  `glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)` — coarse, and it serializes the whole
  dispatch around it. Vulkan's `vkCmdPipelineBarrier` with explicit access/stage masks (or a
  render pass with a self-dependency) says exactly which reads must see which writes, which is
  what a correct per-dab wet-mix accumulation actually needs — this is Vulkan's explicit sync
  model solving a real problem this engine has, not sync-for-its-own-sake.
- **A dedicated async compute queue** lets dab stamping for the *next* frame's dabs run
  concurrently with the GPU still presenting the *current* frame — a genuine latency win in the
  same spirit as Valkyrie's own use of Metal's parallel command-buffer submission (companion doc
  §"Compute Shaders and Highly Parallel GPU Processing"). GLES has no equivalent to a second,
  independent queue; everything serializes through the one context.
- **Fence export to `SurfaceControl` is a better fit on Vulkan.** `ASurfaceTransaction_setBuffer`
  (the API under `androidx.graphics.lowlatency`, §3) wants a sync fence FD to know when a buffer
  is ready to present. Vulkan's `VK_KHR_external_fence_fd` produces that directly from a compute
  submission; GL's route there (`EGL_ANDROID_native_fence_sync`) works too but is one layer more
  indirect for a compute-only (non-EGL-surface) workload.

Why GL/EGL still has a real job, not just a legacy one: **`androidx.graphics.lowlatency`'s
`GLFrontBufferedRenderer` — the actual front-buffer presentation library this design relies on in
§3 — is GL-native.** There is no Vulkan equivalent shipped by Android today; hand-rolling raw
`SurfaceControl` + a Vulkan swapchain to replace it is a materially larger, riskier undertaking
than using the library Android already ships for exactly this. So GL keeps the presentation job,
Vulkan takes the compute job, and the two share GPU memory via `AHardwareBuffer` (Android 10+,
`AHardwareBuffer_allocate` + `EGL_ANDROID_get_native_client_buffer`/`VK_ANDROID_external_memory_
android_hardware_buffer` on each side) — the same object both APIs import as their respective
image/texture, no CPU-side copy between them. `core/nativebridge` already links `GLESv3` + `EGL`
and runs a real EGL context/surface for `MobileGS` (Gaussian-splat AR rendering,
`core/nativebridge/src/main/cpp/MobileGS.cpp`) — that pattern is what the presentation half reuses;
the compute half is genuinely new native surface area (a Vulkan instance/device, a compute
pipeline, SPIR-V shaders compiled at build time via `glslc`).

Be honest about the cost of this over the single-API version: two graphics APIs in one process is
more moving parts than one, `AHardwareBuffer` interop has its own format/usage-flag compatibility
matrix to get right per GPU vendor, and Vulkan's setup boilerplate (instance, physical device
selection, queue families, command pools) is real work with no equivalent in the GLES-only draft.
This is the right call because §7's hazard is real and §3's library is fixed, not because Vulkan is
categorically better — a stamping-only engine with no Wet Mix would have a much weaker case for
paying this complexity, and should probably have stayed on GLES compute alone.

Core objects, per layer:

- A persistent GPU image (RGBA16F, see §7) backed by an `AHardwareBuffer`, holding that layer's
  pixels, created once and mutated in place across strokes — the direct answer to
  `applyToolToBitmap` re-copying the whole `Bitmap` on every commit. Imported as a Vulkan
  `VkImage` for the compute kernels below and as a GL texture for presentation (§3) — same memory,
  two views.
- A Vulkan compute shader (`stamp.comp`, GLSL compiled to SPIR-V via `glslc` at build time) that
  takes a dab-centre buffer (positions, radii, alpha, rotation — literally `BrushStamps.Dab`
  already models this correctly) and rasterizes all of a stroke's pending dabs into the layer
  image in one dispatch, replacing `StampBrushRenderer.paintDabs`'s per-dab
  `Canvas.drawCircle`/`drawBitmap` loop.
- Readback to a CPU `Bitmap` only where the rest of the app still needs one: thumbnails, PNG
  export, the co-op wire format. Not for painting itself.

### Stroke-max mode and the main brush

The main brush is the bundled GPU stamp **Round** (`BuiltInBrushes.round`): the default selection
on Android and desktop, and the reference every other brush, bundled or imported, is felt against.
The legacy Catmull-Rom Round is no longer selectable.

Every stamp brush now goes GPU-first. Plain non-build-up rounds used to be pinned to the CPU's
`IncrementalRoundStampCompositor`, because `stamp.comp`'s max-combine (strongest dab per pixel)
only held within one dispatch: live strokes arrive in per-frame batches, so every frame boundary
compounded and a soft round hardened into dots. `stampResolvedDabs(..., strokeMax = true)` fixes
that on the GPU. Binding 4 holds per-pixel stroke state (`uvec2`: pre-stroke base, full-precision
stroke-best alpha), zeroed on the first strokeMax dispatch after `upload()`/`clear()`. A dab that beats
the stored best re-composites base-over-best, which is identical to one max-combined call over the
whole stroke. Cost: width x height x 8 bytes, allocated on first use. Above
`maxStorageBufferRange`, the call returns false and the stroke falls back to the CPU.

### 2a. Second backend: OpenGL ES 3.1 (selectable)

The stamp engine now has two interchangeable native backends behind one C++ interface
(`include/StampEngine.h`): `VulkanStampEngine` (above) and `GlesStampEngine` (OpenGL ES 3.1
compute). Settings → "GPU engine" picks which one new engines use (`GpuStampEngine.Backend`, Kotlin);
the feel reports name it (`gpu vulkan` / `gpu gles`), so the two can be compared on a real device.
Vulkan stays the default, and nothing is removed while a Vulkan/GLES hybrid and other options are
evaluated.

- **Shaders.** `shaders/gles/*.comp` are generated from the Vulkan GLSL by
  `shaders/gles/port_from_vulkan.py`: same math, with the layer held in an SSBO of packed RGBA8
  words, because ES 3.1 forbids load+store on an rgba8 image. `unpack/packUnorm4x8` does the same
  unorm conversion. Edit the Vulkan source and re-run the script; the two can't drift apart.
- **Context.** Private EGL context, surfaceless or a 1x1 pbuffer. Every call makes it current and
  restores whatever the thread had current before (e.g. a GLSurfaceView's context).
- **Zero-copy display.** `initWithHardwareBuffer` publishes each written region into an
  AHardwareBuffer-backed texture, GPU-side via a pixel-unpack buffer, so `AzphaltGpuDisplay` works
  unchanged.
- **Verified on host.** `tools/stamp-engine-diff/run.sh` runs 26 scenarios through both backends on
  Mesa. All are byte-identical except ±1–2 levels in a few dozen bytes: `round()` on exact halves,
  which GLSL leaves implementation-defined. The comparison also found that the Vulkan engine
  crashes on lavapipe in `uploadPaintHeight` (`vkUpdateDescriptorSets`). Whether real drivers hit
  the same crash is unverified.
- **Not yet measured:** on-device speed of either backend against the other. The feel reports
  answer that.

### 2b. Third backend and long-term single engine: wgpu

**Decision.** wgpu (`core/wgpu-engine`, Rust, WGSL compute shaders) is the long-term single brush
engine for both the Android app and the desktop app. Vulkan and GLES stay selectable for now, for
comparison and as fallbacks, and are not being deleted yet. Direct display (§3) for Vulkan/GLES
stays raw Vulkan through AHardwareBuffer interop; wgpu presents its own (§3, "wgpu direct
display").

Why wgpu rather than consolidating on Vulkan alone:

- **The desktop app.** A Vulkan-only engine does not run where Vulkan is missing, and the NDK C++
  engines do not build for desktop at all. wgpu picks Vulkan, DX12, Metal or GL per machine, so
  one engine serves Linux, Windows and Android. Before this, the desktop canvas composited on the
  CPU.
- **Automatic synchronization.** wgpu inserts barriers between dispatches and tracks resource
  state itself. Color Smudge's ordered phases (up to four dispatches per dab) need no hand-written
  compute-to-compute barriers, and there are no descriptor pools or command-buffer fences to manage.
- **One shader language.** WGSL replaces two GLSL dialects and the `port_from_vulkan.py` step.
  naga compiles it to SPIR-V, GLSL, HLSL or MSL as each backend needs.
- **Reach.** The same engine and shaders can run on WebGPU in a browser later.

**How it plugs in.**

- **Android.** `WgpuStampEngine.cpp` implements `StampEngine` over the crate's C ABI
  (`core/wgpu-engine/include/graffux_wgpu.h`), so every existing JNI entry point and Kotlin caller
  works unchanged. `StampEngineFactory` maps backend id 2 to it. Settings → GPU engine offers
  "wgpu" next to Vulkan and OpenGL ES; the default stays Vulkan. `libgraffux_wgpu.so` is
  `dlopen`ed on first use, so a build without it still links and runs: `init()` returns false and
  the stroke uses the CPU path. Gradle (`:core:nativebridge:cargoBuildWgpuAndroid`) cross-compiles
  it for arm64-v8a with cargo and the NDK clang. armeabi-v7a devices fall back to the CPU.
- **Desktop.** `:desktop` builds the crate for the host and bundles it as a classpath resource. The
  JNI wrapper `WgpuStampEngine` lives in `core:engine`'s `jvmShared` source set, the same class on
  Android and desktop. The canvas uses it whenever an adapter exists and keeps the tile-parallel CPU
  compositor as the fallback.
- **No AHardwareBuffer output.** `initWithHardwareBuffer()` returns false, so `LiveStrokeOverlay`
  and `AzphaltGpuDisplay`'s zero-copy path never see a wgpu layer. Direct display for wgpu is its
  own path instead (§3, "wgpu direct display").

**Shader port.** `stamp.wgsl`, `stamp_masked.wgsl` and `color_smudge.wgsl` port the GLSL statement
for statement. Layout decisions come from the GLES port: the layer is a storage buffer of packed
premultiplied RGBA8 words, and push constants are a uniform buffer (dynamic offsets for the smudge
phases). One deliberate change: substrate wrapping uses an explicit floored modulo instead of
`((c % s) + s) % s`. A remainder with a negative operand is undefined in GLSL, and naga's GL output
passes `%` through; on llvmpipe that sampled the wrong substrate texel.

**Verified on host (Mesa 25.2.8, no GPU).** `tools/stamp-engine-diff/run.sh` now runs all 26
scenarios through the wgpu engine on wgpu's Vulkan backend (lavapipe) and GL backend (llvmpipe):

| pair | worst byte difference | bytes that differ (of 112,684) |
|---|---|---|
| wgpu (GL) vs GlesStampEngine | 0 | 0 in every scenario |
| wgpu (Vulkan) vs VulkanStampEngine | 1 | at most 10, smudge and max-combine only |
| wgpu (both backends), paint height, vs GLES | 0 | 0 |

`cargo test` checks the round-tip shader against a scalar Rust port of `stamp.comp` (exact on both
backends), and `:desktop:test` compares the desktop GPU path with `RoundStampCompositor` through JNI
(alpha within 1 level, premultiplied colour within 2).

**Not verified:** anything on a real GPU or an Android device. That covers speed against the
Vulkan and GLES engines, driver quirks on Adreno/Mali/PowerVR, and whether wgpu's GL backend on
Android picks up EGL correctly.

#### Resident layers, rectangle readback and the render thread (wgpu only)

Built for the wgpu engine; Vulkan and GLES are unchanged. `StampEngine.h` gained optional methods
whose defaults keep the old behaviour (no resident layers, `readbackRect` = `readback` reporting
the whole layer).

- **Layers stay on the GPU across strokes.** `core/wgpu-engine/src/resident.rs` keeps one storage
  buffer per recently painted layer, keyed by a layer key and tagged with a content generation.
  `bind_layer(key, generation)` starts a stroke on the resident copy with no upload and no
  whole-layer first readback; a miss uploads with `upload_layer`. Stamping taints the copy. After
  the commit, `commit_layer` (the GPU result is the committed layer: desktop) or `refresh_layer`
  (the CPU committed: Android) retags it. `refresh_layer` re-uploads only the stroke's rows plus
  the rectangle where the CPU commit differs from the pre-stroke layer. A retag from a superseded
  bind session is refused. The budget is 256 MiB per engine, LRU, and the active layer is never
  evicted. `clear()`/`upload()` keep their old meaning on an anonymous scratch layer.
- **Generations (Android).** `GpuLayerResidency` (feature/editor) holds one generation per layer
  from a process-wide counter. A stroke keeps the generation only if the layer's bitmap is the
  very object the generation was recorded for. Any path that swaps the bitmap therefore misses
  and uploads, wired or not. The mutation paths also invalidate explicitly: undo and redo (both
  Draw and layer-list), the full replay and tile-delta fast path, co-op ops that change pixels
  (stroke, text, bitmap replace, layer remove), clear layer, fill, colour-fill, LUT, curves, warp
  (release, apply, cancel), transform-mode exit, selection move, Ink utensil commits, merge,
  flatten, imports (single, layered, Figma), new project, background image, model paint, text
  re-rasterize and layer URI reload. `LayerStore` invalidates on `initStrokes` (every content
  reset pairs with it), `removeLastStroke`, `remove` and `clear`. It does not invalidate on
  `putBase`, because baking old strokes leaves the pixels unchanged. A commit is adopted, and the
  copy refreshed, only when the generation and the base bitmap are still the ones the stroke
  bound.
- **Desktop.** `GpuStrokeRenderer` binds the resident canvas when a stroke starts from the image
  it last committed. At stroke end it refreshes the stroke's rows from the committed frame,
  re-premultiplied, because straight ARGB does not round-trip at low alpha.
- **Rectangle readback.** `readback_rect` copies only the dirty rectangle. A narrow rectangle is
  copied row by row into a compact staging block rather than as whole layer rows. It also reports
  which rectangle it copied. The dirty rectangle is now the dispatch's whole-workgroup footprint.
  A masked tip's rotated rectangle writes past its radius into that padding, and a radius-sized
  rectangle missed those pixels. The Vulkan and GLES engines still track the radius-sized
  rectangle and so still under-report those corner pixels in partial readbacks. That is a
  pre-existing gap, left alone here. Android's `GpuStampEngine.readback` uses the rectangle path
  for wgpu. The desktop converts only the reported rectangle to ARGB each frame.
- **Render thread.** Every wgpu native call goes through `GpuRenderThread`, one FIFO thread.
  Dab batches reach the GPU in generation order. A commit's refresh is queued behind the stroke's
  own batches, which is the stroke-end flush. An invalidation from undo is queued behind whatever
  was in flight. `destroy()` from the main thread posts instead of blocking. The stroke workers
  that call in were already off the main thread. Provisional ink and `strokePaintPresented` are
  untouched. (wgpu's direct display, added later, also runs every call on this thread; §3.)

**Measured, host only.** These are software-renderer numbers from Mesa 25.2.8 lavapipe (Vulkan)
and llvmpipe (GL) with no GPU. Only the before/after ratio means anything, and it says nothing
about a phone. `cargo run --release --example resident_bench` uses a 2048x2048 layer, median of
15 runs:

| | lavapipe | llvmpipe (GL) |
|---|---|---|
| first dab, before (upload + whole-layer readback) | 12.45 ms | 19.79 ms |
| first dab, resident hit | 2.11 ms | 2.35 ms |
| first dab, miss (`upload_layer`, rectangle readback) | 5.61 ms | 12.99 ms |
| per frame, before (dirty rows at full width) | 0.40 ms | 0.18 ms |
| per frame, after (rectangle) | 0.41 ms | 0.21 ms |

The first dab gets 6–8x cheaper. Most of what remains is clearing the stroke-max state buffer at
bind. The per-frame cost does not change measurably on a software renderer, where a row copy is a
memcpy. The rectangle path is expected to matter where readback crosses a real bus. That is
unmeasured.

**Parity.** Resident strokes are byte-identical to the full-upload path across multi-stroke
sequences with undos. Three checks cover it:

- `cargo test` (`tests/resident.rs`): GPU-commit and CPU-commit variants, on both backends.
- `tools/stamp-engine-diff/run_wgpu_resident.cpp`: round, masked and smudge strokes through the
  C++ adapter.
- `GpuStrokeParityTest.residentCanvasMatchesFullUploadAcrossUndo`: every desktop frame.

**Not done / not verified.**

- Anything on a device or a real GPU.
- The Android wiring is covered by unit tests of the invalidation points and the tracker. No
  instrumented test runs a wgpu stroke end to end.
- The pool keeps two native engines. Strokes that overlap (lift and redown before teardown) can
  land on the other engine and upload.
- Wet-mix/impasto side state (paint height, wetness) is uploaded per stroke as before. Only the
  colour layer is resident.
- No zero-copy display (AHardwareBuffer) for wgpu. Direct display presents from the GPU instead
  (§3, "wgpu direct display").

#### Device tuning: hardware floor, telemetry, calibration tiers, thermal budget

Applies to all three engines unless a line says otherwise. Code: `feature/editor/.../gpu/`,
`GpuStampEngine.kt` (tuning and timing hooks), `StampEngine.h` (`gpuInfo`, `takePassTimings`,
`stampTuning()`), `core/wgpu-engine/src/timing.rs`.

**Hardware floor.** Graffux (`:app`) now needs Android 10 (minSdk 29, was 26) and Vulkan 1.1
(`<uses-feature android.hardware.vulkan.version 0x401000>`, plus `vulkan.level 0`), so Play filters
out everything below. The library modules stay at minSdk 26 because GraffitiXR shares them. A
sideloaded install below the floor still runs: engine init fails cleanly, strokes take the CPU
path, and `HardwareFloorNotice` says so once.

**Telemetry.** The prediction-ranking "feel" block gains `gpu:` (renderer, vendor, driver, Vulkan
API version, engine/backend, timestamps yes/no), `gpu tier:`, `gpu passes:` (stamp, readback,
composite, smudge, multipass; p50/p95), `frame budget:` (stroke batches whose stamp+readback CPU
time exceeded one display frame) and `thermal:` (status and headroom at session start and at report
time). Pass times come from GPU timestamps where the engine has them and are labelled `gpu`;
otherwise CPU wall time around the native call, labelled `cpu`:

| engine | stamp | readback | smudge | how |
|---|---|---|---|---|
| Vulkan | gpu | gpu | cpu | `vkCmdWriteTimestamp` TOP/BOTTOM around the submitted pass, read after the fence |
| wgpu | gpu | gpu if `TIMESTAMP_QUERY_INSIDE_ENCODERS` | gpu | `TIMESTAMP_QUERY` pass writes, resolved in batches (`timing.rs`) |
| GLES | cpu | cpu | cpu | timer queries not wired |

`composite` is the editor's CPU layer composite (no GPU composite pass exists yet); `multipass` is
reserved for the scheduler. GPU timings are drained when a stroke's engine is released.

**Calibration and tiers.** `CalibrationCoordinator` runs a short offscreen benchmark
(`EngineCalibrationProbe`: stamp throughput, full-layer readback bandwidth, a 4-layer Canvas
composite) on the active backend, off the main thread, maps it through `GpuTierTable` (one table,
the only place tier values live) and stores the tier keyed by GPU+driver and app version
(`GpuTierStore`). A changed driver string or app version recalibrates.

When it runs is tied to the **mandatory project dialog** (`ProjectGateDialog`), shown whenever
there is no project to work in (first launch with no projects, and File > New). It has a name
field, Load… (system picker) and Save, and no cancel path: no close button, taps outside are
swallowed, and it installs no back handler, so Back keeps the app's existing root behaviour.
Calibration starts when the dialog opens (unless a tier is stored). Save shows "Saving…" while the
project is created and waits for calibration at most 2 s from the tap. Load shows "Loading…" while
the project is read, then waits at most 2 s more. Past the cap the canvas opens on the conservative
tier and the calibrated tier is applied when it lands (tiers only change draft/refinement
parameters, so switching mid-session is safe). The app leaving the screen (Home, or the picker
covering it: `onStop`) pauses the run; `onStart` resumes it, and a step that overlapped the pause is
thrown away and redone. A failed engine init or call mid-step counts as a lost context and the step
reruns on a fresh engine (up to three attempts). Nothing is stored until a run completes, so process
death just means the next dialog retries; completed steps of an interrupted run are kept in memory.
A picker cancelled with Back returns to the same dialog with calibration still running.

If the app opens straight into an existing project with no stored tier, it uses the conservative
default and calibrates at the next project dialog. It does not calibrate at idle: an idle
benchmark would compete with the first stroke for the GPU. Settings shows the tier and has
**Re-run calibration**, which runs in the background and is cancelled by the next stroke.

| tier | draft scale | quality levels | tile | resident budget | batch | fp16 | needs stamp / readback / composite |
|---|---|---|---|---|---|---|---|
| conservative (default) | 0.5 | 2 | 256 | 128 MiB | 64 | off | none |
| standard | 0.75 | 3 | 256 | 192 MiB | 128 | off | >= 20 dabs/ms, >= 1000 MB/s, <= 12 ms |
| high | 1.0 | 4 | 512 | 256 MiB | 256 | off | >= 60 dabs/ms, >= 3000 MB/s, <= 6 ms |

The thresholds are starting points, not measurements. `high`'s 256 MiB matches the wgpu engine's
existing default; each step roughly doubles the one below. Retune from the `gpu tier:` lines once
real devices report them.

**Per-family knobs.** `GpuFamilyDetector` (Adreno, Mali/Immortalis, Xclipse, PowerVR; Tensor is
tagged from `Build.SOC_MODEL` but keeps its GPU's family: Mali on G1-G4, PowerVR on G5).
`WorkgroupPolicy`: Mali uses an 8x8 stamp workgroup, following Arm's Mali GPU Best Practices guide
(64 invocations as the baseline workgroup size). Every other family keeps 16x16. Nothing contradicts
it and nothing is measured. The size reaches Vulkan as the choice between its two precompiled SPIR-V
variants and wgpu as the WGSL `STAMP_TILE` override constant. GLES ignores it. `cargo test`
(`stamp_tile_8_matches_16`) checks that 8x8 and 16x16 give byte-identical pixels. fp16 is detected
(wgpu `SHADER_F16`, `VK_KHR_shader_float16_int8` `shaderFloat16`) and reported but never enabled:
no fp16 shader exists and every tier row has it off. `DriverWorkarounds` holds the vendor +
driver-version-range table. It is empty, with the mechanism and its tests in place.

**Thermal.** `ThermalMonitor` reads `PowerManager` thermal status (API 29+, listener) and
`getThermalHeadroom(10 s)` (API 30+, polled every 10 s). `ThermalBudgetScaler` takes the smaller of
a status cap (moderate 0.6, severe 0.3, critical and above 0) and a headroom ramp (full budget up to
0.7, falling linearly to 0.25 at 1.0). `ThermalGpuBudgetProvider` applies that to the tier.
`RenderThreadHints` opens an ADPF `PerformanceHintManager` session (API 31+) on `GpuRenderThread`
targeting one display frame. That covers wgpu only: Vulkan and GLES strokes run on coroutine workers.

**Hook for the multipass scheduler.** `GpuBudgetProvider.budget: StateFlow<GpuBudget>`, from
`GpuTuningController.get(context).budgetProvider`. `GpuBudget` carries the tier (draft scale, tile
size) and the thermally scaled `qualityLevels`, `dispatchBatchSize`, `refinementFraction` and
`residentBudgetBytes`. Today only the wgpu resident budget consumes it. No stroke path has a batch
cap, and none was added. The scheduler should collect the flow rather than read it once: the tier
can change mid-session.

**Verified here (no GPU, no device):** `cargo test` on Mesa lavapipe and llvmpipe (timestamps
resolve on both, the 8x8 override matches 16x16, pass timings drain). Kotlin unit tests cover the
tier mapping, persistence, family detection, the workaround mechanism, thermal scaling, the
telemetry format and the calibration orchestration, and a Robolectric UI test covers the dialog.
**Not verified:** any of it on a phone. That includes the timestamp values, whether the Mali 8x8
choice helps, the tier thresholds, ADPF behaviour, and the Vulkan and GLES native changes, which
compile but are not exercised by any host test.

#### Multipass drying (experimental, wgpu only, off by default)

**Goal.** Full-quality rendering must never hold the user back, on any hardware. The user must never
look at where the finger just was and wait for anything to appear. There is no deadline for full
quality: it arrives whenever the hardware gets to it. Settings -> "Multipass drying (experimental)"
(Android) and the desktop Tool Options checkbox turn it on; "Drying transition" (0/80/150/300 ms on
Android, a slider on desktop) is the cosmetic ease when a finished area is swapped in. Off, every
engine call takes exactly the pre-multipass path.

Code: `core/wgpu-engine/src/scheduler.rs` (pure scheduler), `progress.rs` (ETA and per-tile display
animation), `draft.rs` (the feather-trim rule), `multipass.rs` (engine side), shaders
`draft_stamp.wgsl`, `draft_masked.wgsl`, `display.wgsl`. Kotlin: `MultipassSettings`
(core:engine), `GpuStampEngine` + `MultipassRefiner` (Android), `GpuStrokeRenderer` (desktop).

**Why the scheduler lives in Rust, in the engine.** Batching items into single dispatches and
splitting final work into exact chunks needs the pipelines, buffers and dispatch geometry, which
only the engine has. It also serves Android and desktop with one implementation and is unit-tested
by `cargo test`. The Kotlin side only decides *when* to give it time (after each frame, and on an
idle tick).

##### What a pass is

Each stamp call (one frame's batch of dabs) becomes up to N pass items:

- **Pass 1, the draft**: the same stamp (same tip mask, orientation, colour, grain, secondary tip,
  substrate) rendered at `1/scale` of the layer resolution into a separate draft buffer, over
  transparent. The mask is sampled from the mip level that matches a draft texel, never coarser than an 8x8 level (a 1x1 or 2x2 level averages the falloff to a constant that the footprint clips to a solid shape). Dabs smaller than
  a draft texel are widened with their alpha lowered by the area ratio, so bristle-size dabs stay
  present. It is never a stand-in shape.
- **The final pass**: exactly the dispatch the engine makes with multipass off, into the layer.
- Clarity passes in between: the scheduler supports N levels; the engine renders two real levels
  (draft, final) and makes the *display* continuous in between (below). An intermediate real level
  was not built: a batch's clarity level would have to replace its draft inside a buffer that
  also holds other batches' drafts, which needs either a per-level buffer per batch or a tile
  rebuild from the whole backlog. The display animation gives the continuous look without that
  re-stamping cost.

The feather rule, from `stamp.wgsl`'s real falloff: coverage is 1 up to `core = hardness` and falls
linearly to 0 at `outer = 1` (or `h + 0.001` for a hard tip), in units of the radius. The draft's
visible edge sits at

~~~text
edge = core + f * (outer - core),   f in [1/3, 1/2], default 0.4
~~~

per axis for elliptical tips. Coverage there is `1 - f`, so for masked tips the same fraction is the
mask threshold `1 - f`. The draft keeps the real coverage profile inside the edge. The draft shaders
store a *feather key* per texel (0 in the core, 1 at the outer edge); the display reveals only keys
up to an edge that grows from `f` to the full feather.

**The trim never flattens a soft stamp** (fixed after the owner's on-device report of flat,
hard-edged discs on a soft brush at stroke start and after a pause). The rule is that a draft is the
real stamp at lower quality, never a solid shape:

- *Soft tips are not trimmed.* The stored key is multiplied by `trim_weight(hardness)`
  (`draft.rs`): 0 up to hardness 0.5, smoothstep to 1 at 0.9. Trimming a soft falloff at `f` cut
  through paint still carrying ~60% of the dab's alpha, so the first readback of a stroke (q = 0,
  before any ETA or throughput exists) showed a flat disc with a hard rim. Hard tips keep the trim,
  where it only hides the thin rim low resolution would blur.
- *Masked drafts are not trimmed* (key 0): the mask carries the tip's own falloff.
- *Build-up never hides piled-up paint.* In sequential (build-up) drafts the key is also capped at
  `1 - alpha`, so repeated dabs at one spot (a held pointer) are revealed as they will land.
- *Held dabs carry the brush's hardness.* `AirbrushEngine.heldDabs` and
  `IncrementalAirbrushGenerator` left `Dab.hardness` at its default of 1, so every hold-to-build-up
  deposit (Soft Round, Airbrush, Ink Pen) was a hard, flat disc, live and committed alike, with or
  without multipass. They now use `brush.hardness * hardnessMultiplier` like movement dabs.
- *No seams at tile edges.* The display's bilinear draft sample used to clamp to its own 32x32
  tile, so the upsampled draft went flat for the last half texel of every tile and then jumped (up
  to ~25 levels at draft scale 8): periodic ribbing along a soft stroke. It now reads across an edge
  into a neighbour whose draft is still live (final work queued over it) and clamps only next to a
  landed neighbour, whose draft texels are cleared. A landed tile never reads neighbours, so it
  still shows exactly its base.

Tests: `tests/multipass_soft.rs` (stroke-start falloff and no hard edge for large soft rounds up to
164 px at draft scales 2/4/8; held build-up with starved refinement is soft, as wide as the final,
and the layer is byte-identical to off; masked soft tips keep their falloff),
`draft.rs::soft_tips_are_never_trimmed_and_hard_tips_fully`, and
`AirbrushEngineTest` (held dabs carry the brush hardness; batch and incremental generators agree).
The remaining max-combine ripple of a soft round at its preset spacing is pre-existing, identical
with multipass off, and not changed here.

##### Scheduling

~~~text
priority(k, age) = base_weight[k] + aging_rate[k] * max(0, age - age_threshold[k])
~~~

- Drafts are a hard guarantee, not a priority: every readback and every refinement call runs *all*
  pending drafts first (`Scheduler::take_drafts`). Aging never lets refinement delay a draft.
- Among refinement passes (2..N), the eligible queue head with the highest priority runs. Defaults:
  `base_weight[k] = N - k` (strictly decreasing with pass index), one `aging_rate = 1 /
  overtake_ms` (default 50 ms), thresholds 0. A pass-k item then outranks a fresh pass-j item
  (j < k) once it has waited `(k - j) * overtake_ms` longer. Ties go to the lower pass, then the
  older item. With N = 2 (what the engine renders today) there is one refinement queue and aging has
  nothing to choose between; the tests exercise N = 3 and 4.
- Budget: refinement gets what is left of the frame, measured, times the device budget's
  refinement fraction: `((period - since_frame_start) * 0.8 - 1 ms) * refine_fraction`, where the
  period is measured from the readback cadence. Chunks are sized from a measured cost per
  pixel-times-dab plus a measured fixed per-submit overhead, at most half of what is left, capped by
  the tier's refinement tile size. At least one minimal chunk runs per call, so refinement always
  progresses.
- Batching: drafts of consecutive submissions with compatible parameters share one dispatch. Final
  items are never merged: each is today's dispatch for its call.
- Device budget: `GpuTuningController` collects `GpuBudget` into `GpuStampEngine.multipassBudget`,
  so the tier's draft scale, quality levels, thermally scaled refinement fraction and tile size
  reach live engines when they change. The tier's draft scale is a floor; if drafts take more than
  30% of the measured frame, the engine coarsens the draft (x2, up to 1/8) at the next stroke, and
  relaxes it back toward the floor when they are cheap.

##### Correctness

- **Per-dab ordering.** Pass k of a submission is eligible only when no earlier pass of it is still
  queued.
- **Hazards: the committed result is canonical by construction.** The final queue runs strictly in
  submission order into the layer, one item at a time. A final item may be split into chunks only in
  ways that are exact: spatially (every pixel is independent), and across its dab list where that
  is exact (sequential SRC_OVER, strokeMax; not per-call max-combine). Drafts write only the draft
  buffer. So the layer is byte-identical to multipass off whatever the interleaving, budget or draft
  scale. This is option (a) of the brief in its strongest form: the only reordering that ever
  touches the committed layer is none. The live display is allowed to differ transiently (it shows
  drafts), and converges to exactly the layer (below).
- Textures a queued call referenced (substrate, paint height) are versioned: an upload while work is
  queued allocates a new texture instead of writing the one the queued work will bind. Masks, grain
  and Sample Merged sources are captured by value.
- Colour Smudge reads the layer it writes, so a smudge call first lands everything queued and then
  runs at full quality at once, like multipass off. It has no draft yet.
- **Flush points.** `flush()` lands everything and finishes every ease instantly. It runs inside
  every engine call that needs the layer itself (`read_region`, `read_all`, `commit_layer`,
  `upload_rows`, Colour Smudge, turning multipass off). On Android the CPU commit is the committed
  layer, so undo, save, co-op send, export and layer switch never wait for refinement: at stroke end
  the resident refresh *drops* queued refinement and re-uploads its footprint from the CPU pixels,
  and a pooled engine's `clear()` drops it too. Where a layer switch or upload would drop queued work
  that a resident copy was relying on, that copy is invalidated (one upload later, never wrong
  pixels). On desktop the committed image is the GPU frame, so stroke end lands queued work (off the
  UI thread, the draft stays on screen) and then renders the committed frame exactly as the plain
  path's last frame. **That desktop stroke end is the one place that blocks for refinement**; a
  stroke started meanwhile waits for it.
- **Frame-rate independence.** The committed result has no time dependence at all. The display
  animation is driven by timestamps, not frame counts.

##### The display

Buffers: `base` (per tile, the layer as it was when the tile last landed), `draft`, `display` (what
readback returns). For each 32x32 tile still waiting for final work:

~~~text
shown = base OVER reveal(bilinear(draft), edge(q))
edge(q) = f + q * (1 + band - f)
~~~

`q` runs from 0 toward a cap (0.8), paced so it arrives there at the tile's estimated landing time:
the ETA is the final-queue work up to the last item over the tile, divided by measured refinement
throughput (units per GPU ms) times the measured duty cycle (share of wall time spent refining).
Re-estimated every frame, so a wrong ETA retimes smoothly; it never moves faster than the landing
ease. When the tile's final work lands, the display snapshots what is on screen, the tile's base
becomes the layer, its draft texels are cleared, and it eases from the snapshot to the layer over
`transition_ms`, strongest changes first so the soft edge settles outward. At weight 1 the output is
exactly the layer. Only tiles whose displayed state changed are recomposited.

##### Measured (SOFTWARE RENDERERS: Mesa 25.2.8 lavapipe / llvmpipe, no GPU)

`cargo run --release --example multipass_bench` (1536x1024 layer, a 120-frame drag at 60 Hz; a
batch's touch-to-visible runs from its arrival to the end of the readback that shows it; the engine
is driven like the render thread: stamp, read back, refine in the rest of the frame). A 4-core
container shared with other builds: only runs taken under low load are reported. A final run at
load average 15-42 was discarded. Absolute numbers say nothing about a phone.

Cheap brushes (the full dab takes 1-3 ms per batch here), ms:

| backend | brush | off p50 / p95 | multipass p50 / p95 | layer final after last batch | display settled | draft / composite / readback per frame |
|---|---|---|---|---|---|---|
| lavapipe | soft round r40 | 1.4 / 3.1 | 4.9 / 7.8 | 21 | 168 | 0.8 / 1.4 / 0.9 |
| lavapipe | masked + grain + dual r48 | 2.8 / 5.4 | 5.7 / 8.7 | 22 | 169 | 1.4 / 1.6 / 1.1 |
| llvmpipe | soft round r40 | 1.1 / 3.7 | 3.3 / 6.9 | 20 | 168 | 0.4 / 1.3 / 0.4 |
| llvmpipe | masked + grain + dual r48 | 1.7 / 4.4 | 4.5 / 9.3 | 20 | 168 | 1.0 / 1.6 / 0.5 |

Here multipass is a fixed 2-4 ms per frame of overhead (draft, per-tile composite, a wider readback)
and first paint is slower. "Display settled" is dominated by the 150 ms cosmetic ease.

Heavy brush (masked + grain + dual tip, r160, 24 dabs per frame: the full dab takes ~24 ms per batch,
more than a frame), lavapipe, ms:

| mode | touch-to-visible p50 | p95 | max | layer final after last batch |
|---|---|---|---|---|
| off | 525 | 965 | 1002 | 0 (every batch waits for the full dab; the backlog grows) |
| multipass (draft 1/4, adapted) | 9.1 | 14.9 | 18.4 | 13,200 |
| multipass, refinement 10x more expensive | 7.4 | 9.5 | 15.9 | 872,000 |

The key property holds: first paint does not depend on refinement cost (10x more expensive
refinement left p50/p95 flat or lower, because refinement simply got less of each frame). Time to
full quality is where the cost goes. It is poor on a software renderer: ~13 s for ~3 s of full-dab
work, and the 10x run was worse than 10x because its ballast then issued a submit per repeat; that
was since moved into the chunk's own submit (not re-measured under low load).

Tests (all on both lavapipe and llvmpipe): the layer is byte-identical with multipass on and off for
random sessions of round, masked, smudge, row-restore and texture-upload calls, under random
readback/refinement interleavings and budgets (1 µs to 4 ms) and draft scales 1/1 to 1/8, and with
the 8x8 stamp workgroup; `tools/stamp-engine-diff` runs every scenario with multipass on through the
Android C++ adapter (0 bytes differ); the desktop parity test commits identical frames. The draft of
a textured masked brush correlates with its final at 0.92 (1/2) and 0.90 (1/4) trimmed, 0.995 or
better fully revealed (luminance, 8x8 box-downsampled); soft round 0.93-0.94 trimmed. The display
lands exactly on the layer, every pixel monotonically, the quality parameter never falls and never
steps by more than 0.2 in a frame. Scheduler tests cover base order, aging overtaking, no starvation,
drafts never delayed behind refinement, progress under a zero budget, per-dab ordering and budget
adherence; the ETA tests cover synthetic backlogs, a throughput change and bounded error under
steady load.

**Not done / not verified.**

- Anything on a phone or a real GPU: the latency win, the per-frame composite cost, thermal
  behaviour, the tier numbers.
- Real clarity levels between draft and final (see above).
- A draft for Colour Smudge: smudge calls run at full quality immediately.
- On Android with readback display, the refinement shows through each batch's readback and the
  CPU commit replaces the display at stroke end; while the pen rests mid-stroke, refinement
  continues but the bitmap only updates on the next batch. With wgpu direct display (§3) the ease
  is presented after every refinement tick, pen resting or not, with no readback.
- On a software renderer with a *cheap* brush, multipass costs more than it saves (the draft,
  composite and wider readback are fixed per-frame costs); it pays off where the full dab does not
  fit a frame.

## 3. Front-buffer / low-latency presentation

**Built (Vulkan, behind Settings → Direct display, off by default):** `LiveStrokeOverlay`.

- **Where it draws.** A transparent SurfaceView over the canvas parents an `ASurfaceControl` child
  layer. Its buffer is an AHardwareBuffer allocated with `FRONT_BUFFER | COMPOSER_OVERLAY` usage,
  retried without FRONT_BUFFER where that's unsupported.
- **Its own Vulkan device.** The overlay renderer has its own small device. At stroke start it
  imports the stamp engine's hardware-buffer layer and snapshots it as the stroke's base.
- **Per batch.** `live_overlay.comp` writes only the stroke's own contribution (see the shader) for
  the batch's dab bounds, mapped through the layer's real on-screen affine (`OverlayGeometry`).
  The buffer is then handed to SurfaceFlinger. No Compose frame, bitmap re-upload or readback.
- **Canvas.** While the overlay is active the canvas keeps the pre-stroke pixels, so the stroke
  never shows twice. The overlay clears two frames after the committed layer is published.
- **Eligibility.** Only layers whose compositing the overlay reproduces exactly: SRC_OVER, full
  opacity, no colour adjustments, clip, 3D tilt or parent group, nothing visible above, no impasto
  shading. Anything else takes the Compose path as before. API 29+ (SurfaceControl NDK).
- **Not with wgpu.** The wgpu engine has no AHardwareBuffer output, so this overlay never sees a
  wgpu layer. wgpu has its own direct display, below.
- **Unverified on a device:** that importing another device's AHardwareBuffer preserves its
  contents on every driver (foreign-queue acquire from UNDEFINED), how front-buffer usage behaves
  per vendor, and the measured latency. The feel reports tag `display direct` / `display compose`.

### wgpu direct display (same Settings switch; wgpu engine only)

Built so the wgpu engine gets the same path to the screen the Vulkan overlay gives Vulkan/GLES:
new paint goes from the GPU to the compositor with no readback, no bitmap upload and no Compose
frame. Code: `core/wgpu-engine/src/direct.rs` + `shaders/direct.wgsl` (engine), `gfx_wgpu_direct_*`
(`graffux_wgpu.h`), `StampEngine::direct*` / `WgpuStampEngine.cpp`, `GpuStampEngine` JNI
(`beginDirectDisplay`, `presentDirect`, `DirectSurface`) and `WgpuDirectDisplay.kt` (which handle
owns the window).

**Design choice: a wgpu surface on the overlay window, not AHardwareBuffer import.** Two options
were weighed:

1. *wgpu presents into an Android surface.* `Instance::create_surface_unsafe` on the overlay
   SurfaceView's `ANativeWindow` (the SurfaceView the Vulkan overlay already parents its
   SurfaceControl to), configured and presented with wgpu's public API.
2. *Import the overlay's AHardwareBuffer into wgpu through wgpu-hal Vulkan interop*, and write the
   front buffer from wgpu.

Option 1 was chosen as the more robust:

- It uses only wgpu's public, backend-neutral API. It works the same on wgpu's Vulkan and GL
  backends. Option 2 is Vulkan-only, and wgpu's GL backend is the fallback on exactly the devices
  most likely to need help.
- Option 2 needs `VK_ANDROID_external_memory_android_hardware_buffer` (and its dependencies)
  enabled on wgpu's device, which wgpu does not request. That means opening the device through
  wgpu-hal with a custom extension list. It then needs `VK_QUEUE_FAMILY_FOREIGN_EXT` acquire and
  release barriers around every access to the imported image, which wgpu's automatic resource
  tracking has no way to express. The whole thing is `unsafe` hal code, tied to wgpu-hal internals
  that change between wgpu releases (the crate is on wgpu 30).
- Option 1 fails cleanly and early. Surface creation, `is_surface_supported`, the surface's
  formats, alpha modes and usages are all checked at attach, before any stroke depends on them.
  The failure is reported through capability bits, and the stroke falls back to readback.

The cost is that a swapchain is not a front buffer. Each present queues an image, so there can
be one more frame of latency than a front-buffered SurfaceControl buffer. The present mode is
Mailbox where offered (a newer image replaces a queued one, and there is no tearing), else FIFO,
with `desired_maximum_frame_latency = 1`. The Compose frame, bitmap upload and CPU readback that
the readback display pays are all gone either way. If front-buffer latency turns out to matter,
option 2 can be added later behind the same C ABI.

**What is drawn.** `direct.wgsl` is the WGSL port of `live_overlay.comp`: the stroke's own
contribution, `Sa = 1 - (1 - out.a) / (1 - base.a)`, `S = out - base * (1 - Sa)`, over a snapshot of
the layer taken at stroke start (`gfx_wgpu_direct_begin_stroke`, one GPU buffer copy). The canvas
underneath keeps the pre-stroke pixels, as with the Vulkan overlay, and the same eligibility applies
(`overlayMatricesFor`: plain SRC_OVER, full opacity, and so on), plus no impasto shading. It is
one full-surface fragment pass per present, a single triangle with no blending. Swapchain images
are not persistent, so there are no incremental dirty regions. The fragment stage reads the
layer's storage buffer directly. This needs `DownlevelFlags::FRAGMENT_STORAGE` and at least two
storage buffers per stage; the `ADAPTER` capability bit reports whether the adapter has them.
Linear RGBA8/BGRA8 targets are preferred. An sRGB-only surface works too: the shader decodes, so
the stored bytes are unchanged. Premultiplied or Inherit alpha is preferred, and PostMultiplied
alpha is un-premultiplied in the shader. An opaque-only surface is refused.

**Multipass.** With multipass on, each present first runs every pending draft (never refinement:
drafts are never delayed) and advances the display composite. It then draws from the multipass
`display` buffer instead of the layer: the draft first, then the refinement ease, converging to
exactly the layer. `MultipassRefiner`'s ticks re-present after each refinement call, so the ease
keeps moving while the pen rests. That is something the readback display could not do (§2b,
"Multipass drying"). A batch present counts as a displayed frame for the multipass cadence and
duty-cycle measurements, just as a readback does.

**Committed layer.** Nothing in `direct.rs` writes the layer. The only engine-side change outside
it is that `mp_readback_rect`'s frame bookkeeping moved into `Multipass::begin_frame`, now shared
with present. The Android commit is still the CPU commit.

**Android flow.** `LiveStrokeOverlayHost` now always hosts the SurfaceView while Direct display is
on. It hands the surface to both `LiveStrokeOverlay` (child SurfaceControl, Vulkan/GLES) and
`GpuStampEngine.DirectSurface.set` (wgpu swapchain on the SurfaceView's own surface). At wgpu
stroke start, after the layer is seeded or resident-bound, `beginDirectDisplay` attaches the
engine and snapshots the base. A window has one producer, but the pool holds two wgpu handles, so
`WgpuDirectDisplay` detaches the previous owner first. Each batch then skips the readback and calls
`presentDirect`. On the first failure, direct display turns off for the rest of the stroke, and one
catch-up readback brings `work` up to date. Nothing was read back while direct display ran, so the
engine's dirty rectangle spans the whole stroke. The Compose path then takes over. If the engine
itself fails, the batch replays the stroke on the CPU as the zero-copy path does. At commit, the
surface is cleared two frames after the committed layer is published, as the Vulkan overlay is.
`surfaceDestroyed` blocks until the swapchain has let go of the window.

**Verified on host (Mesa 25.2.8 lavapipe and llvmpipe, no GPU, no window).** `cargo test`
(`tests/direct.rs`) drives the whole present path into an offscreen target that follows the same
format rules as a surface, on both backends:

- the target shows exactly `stroke_contribution` of the layer over the base, to within one level;
- the on-screen affine is honoured, and a singular affine is refused;
- the committed layer is byte-identical with direct display on or off, with multipass on or off;
- with multipass on, the draft shows on the first present with no refinement, and after a flush the
  target shows the final layer's contribution;
- stroke end clears the target, and detach and the capability bits behave.

The unit tests cover format, alpha and present-mode selection and the scalar contribution math.
`WgpuDirectDisplayTest` covers the ownership logic: the window moves between handles, a failure
falls back for the rest of the stroke, surface loss and handle destruction are handled.
`tools/stamp-engine-diff` gained a `-DDIRECT` build. On a host every direct entry point must be
inert (no window), and its output must equal the plain run: 0 bytes differ on both backends.
(The harness also gained `stamp_tuning.cpp`. Since per-device tuning moved `stampTuning()` into
`StampEngineFactory.cpp`, it had stopped linking.)

**Not verified (no device or real GPU here):**

- Anything on a phone: that `create_surface_unsafe` on the overlay SurfaceView's window succeeds;
  which formats, alpha modes and present modes Android drivers report for it; that Inherit really
  composites as premultiplied there; and that the swapchain layer stacks correctly with the Vulkan
  overlay's SurfaceControl child on the same SurfaceView.
- wgpu's GL backend on Android presenting to that window (EGL surface on an instance created
  without a display handle).
- The latency: first paint and per-frame, against the readback display and against the Vulkan
  front-buffered overlay. The feel reports still tag `display direct`.
- The cost of the full-surface fragment pass at phone resolutions, and the blocking in
  `get_current_texture` under FIFO.
- `surfaceDestroyed` blocking on the render thread while a slow batch is queued.
- The Android build of the Rust `cdylib` with the new symbols was type-checked
  (`cargo check --target aarch64-linux-android`), and `WgpuStampEngine.cpp` and `GraffitiJNI.cpp`
  were compiled `-fsyntax-only` with the NDK clang. The Gradle `assembleDebug` was killed for lack
  of memory on the build host before its native steps, so no APK was produced with this change.

### Jetpack Ink utensils (Ink Pen, Ink Marker, Ink Highlighter, Ink Dashed Line)

Jetpack Ink is not a hidden takeover of the round brush. Each public stock `BrushFamily` in the
pinned `androidx.ink` is its own art utensil in the brush rail (`grp.brushRail`), listed after the
bundled presets and before custom and installed brushes. This replaced an earlier Settings toggle
that routed only the legacy round brush through Ink, and that no longer had any effect because the
brush rail could not select the legacy round.

**What Ink 1.0.0 offers.** 1.0.0 is pinned in `gradle/libs.versions.toml` and is the newest stable
release (1.1.0 is alpha). Its `StockBrushes` has four public families: `pressurePen`, `marker`,
`highlighter(SelfOverlap)` and `dashedLine`. Each has only a `V1` version, and `LATEST` is `V1`.
Two more exist but are not offered. `pencilUnstable` is `@RestrictTo(LIBRARY_GROUP)` and needs a
client-supplied background texture. `emojiHighlighter` needs a client-supplied emoji texture.
Neither can be used as a stock utensil. A later stable Ink with a public pencil would be one new
`InkUtensil` entry.

| Utensil | Family | Notes |
|---|---|---|
| Ink Pen | `pressurePen(V1)` | pressure → width |
| Ink Marker | `marker(V1)` | constant width |
| Ink Highlighter | `highlighter(SelfOverlap.DISCARD, V1)` | self-overlap does not darken |
| Ink Dashed Line | `dashedLine(V1)` | |

The families are pinned to `V1`, not `LATEST`. A stroke replayed on undo, redo or bake, or rendered
by a co-op peer, then looks like the one that was drawn, even if a later Ink changes `LATEST`.

**Catalogue and routing.** `InkUtensil` (`:core:common`) is the catalogue: a stable `id` and a
display name, with no Ink import. `INK_UTENSIL_CATALOG` (`:app`) derives each utensil's rail id,
classifier and glyph from it. Adding an `InkUtensil` entry therefore adds it to every brush list,
including any list built from that catalogue, such as the bottom carousel. `InkStrokes.family` is
the one place a utensil becomes a `BrushFamily`.

`EditorViewModel.selectInkUtensil` does four things:

- sets `activeInkUtensil`
- clears `activeStampBrush` and its tip assets
- sets `activeBrushName` to the utensil's name
- selects the Brush tool

Every other brush selection (built-in, custom, extension, a Brush Studio draft) clears
`activeInkUtensil`, so exactly one of the two is ever in hand. `EditorScreen` hosts
`InkBrushCanvas` while an Ink utensil is in hand and the Brush tool is active. It then skips the
Direct display overlay, so the two never draw the same stroke.

**Settings.** Size (`effectivePaintBrushSize`), colour, opacity and the stabilizer apply to every
utensil. Opacity is folded into the colour's alpha, the only place a stock family takes it
(`InkColor.withOpacity`). Tool Options shows the opacity dial for an Ink utensil and hides flow,
which only a stamp brush has.

**Soft edges are deliberately not applied.** Each stock family has its own fixed tip. Softening the
Ink Pen would make it no longer a pen, and softening a highlighter or dashed line would make no
sense. The soft-round custom family (`InkSoftRound`, `InkSoftRoundTextures`) existed only to make
Ink imitate the legacy round's `BlurMaskFilter` edge, so it was removed together with the takeover
it served. It remains in git history if an Ink soft brush is ever wanted as its own utensil. The
hardness drag still changes `brushFeathering` for the other brushes. Ink strokes record
feathering 0.

**Settings migration.** The `jetpack_ink_brush` preference is deleted on the settings store's
first read, by `RetiredSettingsMigration` (a DataStore `DataMigration`). Its value is not carried
forward into "select the Ink Pen". The toggle only affected the unreachable legacy round, so an
"on" never changed what anyone drew. Honouring it now would swap a user's brush for a setting that
had no visible effect.

Code: `feature/editor/.../ink/`. `InkBrushCanvas` hosts Ink's front-buffered `InProgressStrokesView`.
The finished stroke becomes an ordinary `StrokeCommand` carrying `inkStroke` and `inkUtensil`.
`DrawingEngine` renders it through `CanvasStrokeRenderer` on commit and on every replay.

- **Input.** Touch reaches Ink through Compose (`motionEventSpy`), not the Android view, so the
  surface follows DrawingCanvas's rules: a second finger cancels the stroke and stops consuming,
  which leaves the gesture to `canvasNavigation` (pan/zoom/rotate) and `multiFingerTaps`; the
  `StrokeGate` is held only once the stroke has moved past touch slop, so a two-finger tap is still
  an undo. (Ink itself starts drawing at touch-down, before slop.)
- **Stabilizer.** At stabilizer level > 0 each sample goes through `InkStabilizer` — the editor's own
  `StrokeStabilizer`, on world-space points, reset per stroke — and into Ink's `StrokeInput` API
  instead of raw MotionEvents. At level 0 Ink gets the MotionEvents.
- **Feel and prediction reports (TEMPORARY).** Ink's `LatencyData` feeds `StrokeFeelMeter`'s Ink
  series (touch→paint per input, first dab from the stroke's START input) and the stabilizer lag.
  The setter is `@RestrictTo` in Ink 1.0 and hidden from Kotlin, so it is reached reflectively. On
  the stabilized route Ink has no OS event time, so touch→paint starts at view receipt there. The
  same `PredictionTournament` DrawingCanvas uses (`rememberPredictionSession`) records every real
  sample, and each stroke's ranking is filed with engine `jetpack-ink`.
- **History and brush identity.** `InkStrokeLedger` keeps each layer's Ink strokes in effect. A
  stroke is added on commit, removed on an undo that went through, restored on redo, and cleared on
  project change. The ledger outlives the stroke-list bake. Each Ink `StrokeCommand` records its
  `inkUtensil`, as a stamp stroke records `stampBrush`; that is where the undo history keeps brush
  identity. Stroke-data capture records `activeBrushName`, which is the utensil's name. Project
  files save layers as bitmaps and do not persist strokes or the brush in hand, so they have no
  per-stroke brush to record.
- **Export.** Export for Figma writes `<project>-ink.svg` next to the bundle when the visible layers
  hold Ink strokes: one `<path>` per stroke, in document pixels (`InkAffine.worldToDocument`).
- **Co-op.** An Ink stroke is sent as the same `Op.StrokeComplete` a round-brush stroke sends
  (bitmap-space points, pressures, opacity), plus `BrushStroke.inkUtensilId`. A guest that knows
  the id rebuilds the stroke through the same stock family (`InkStrokes.strokeFromPoints`) and
  commits it as an Ink `StrokeCommand`. A guest that doesn't know the id, or whose Ink fails, falls
  back to the round brush, which is what every Ink stroke did before the id existed.
  - The wire has no input timestamps, so the guest spaces its inputs evenly. The stock families
    shape strokes by distance, so this is invisible.
  - Alpha lock, wrap-around and the selection clip are not carried, as for every co-op stroke.
  - The field is null on round-brush strokes and is not encoded, so those strokes are unchanged on
    the wire.
  - No co-op transport is bound yet (`NoOpOpEmitter`). A future transport that decodes strictly
    needs `ignoreUnknownKeys` to accept the field from a newer build.

Android's answer to `CAMetalLayer` + `presentsWithTransaction` is
`androidx.graphics.lowlatency` (`GLFrontBufferedRenderer`, API 29+; wraps `SurfaceControl` +,
where available, `HardwareBufferRenderer` on API 34+ per the companion doc's own §"Bringing the
Experience to Android"). The pattern:

- While a stroke is in progress: render *only the new dabs since the last frame* into a
  front-buffered `Surface`, submitted straight to `SurfaceControl` — bypassing the normal
  double/triple-buffered compositor queue the companion doc identifies as the source of Android's
  baseline latency disadvantage.
- On finger-up: composite the front-buffer content into the persistent double-buffered layer
  texture (§2) and return to normal Compose-driven rendering for everything else on screen (rail,
  panels, other layers).
- Below API 29 (down to this project's `minSdk 26`): no front-buffer path exists. Fall back to
  today's behaviour — render into the persistent layer texture directly and let the normal
  Compose recomposition cycle display it. Strictly worse latency, but not worse than what ships
  today, and it's a capability tier (§8), not a crash.

## 4. Input telemetry

Already correct: `DrawingCanvas.kt`'s `pointerInput` reads `change.historical` — Compose's
coalesced-touch equivalent — so sub-frame samples aren't being thrown away today (confirmed
during the pressure work this session; `HistoricalChange` carries position + time, not pressure,
which is why the pressure change borrows the enclosing `change`'s reading for historical points).

**Correction, checked against the code directly rather than assumed:** this section previously
said prediction infrastructure was missing and proposed wiring `androidx.input.motionprediction`
in. That infrastructure already exists and is already wired — the gap is narrower and different
in kind than what was described here.

`DrawingCanvas.kt` runs a `PredictionTournament` (Google Ink's Kalman predictor, with
`LinearGesturePredictor` as the early-stroke fallback; see "Where it landed" below). The contract
plainly: "Brush latency prediction is presentation-only: predictors race to extend the visible
tail to the next frame, but predicted points are NEVER sent to `onStrokePoint`. Only real input
can enter the bitmap/history path" — rendered as `predictionTail`, a translucent overlay line, not
substituted into the dab stream. That is a deliberate, different design from the dab-substitution
model the companion doc describes for Valkyrie ("render the predicted dab(s) at the leading edge
of the stroke, then on the next real sample, discard and overwrite with ground truth") — Graffux
never paints a predicted dab at all, real or provisional; prediction only shows the artist where
the line is about to go.

**Where it landed.** Google Ink's Kalman predictor draws the tail. `LinearGesturePredictor` only
covers the first few samples of a stroke, before Ink's filters are stable. The tail reaches two
frames ahead (`PredictionTournament.TAIL_FRAMES`). The decision came from on-device rankings on a
Pixel 5 at 60 Hz (issues #425, #426 and #435-#439):

| Model | f1 error | f4 error | Why it went |
|---|---|---|---|
| Google Ink (Kalman, direct) | 20.5 px, 3.8 behind | 184 px | kept: predicts on nearly every sample, barely lags |
| AndroidX `MotionEventPredictor` | 13.3 px, 9.9 behind | 113 px, 80 behind | answered only ~30% of samples; can't carry the tail |
| Linear | 27-32 px | 223-255 px | kept only as the early-stroke fallback |
| Acceleration | 32.8 px | 439 px | overshot worst at every horizon |
| Damped tail (speed/turn shortening) | ~= undamped | -- | no gain; removed |

Every model's error roughly doubles per frame. Past two frames, the tail reads as a wrong line
rather than a lead.

**Rankings.** Each prediction still asks every running model for the next four frames. Each is
scored when real input passes its target time, against the true position interpolated between the
real samples on either side. Predictions still pending at pen-up are scored at the lift point
(`endStroke`), so overshoot past the end of a stroke counts. Scores carry **lead**, the signed error
along the direction of travel (+ = ran ahead). The session-long mean per model and horizon is logged
at every Brush stroke end (`adb logcat -s StrokePrediction`) and filed as a GitHub issue every 25
strokes when a token is set in Settings (temporary).

**Google Ink is used through its Kalman predictor alone.** The first hookup read
`StrokeModeler::Predict()`. That output is made for drawing a smoothed stroke: it starts at the
spring-mass position modeler's state, which deliberately trails the pen, and joins it to the
Kalman estimate with a cubic "connector". The next few frames landed on that connector, so the first
field report (issue #425) had Ink about 74 px *behind* the pen at every horizon.
`InkStrokePredictorJNI.cpp` now drives `KalmanPredictor` directly and returns its estimated state
(position, velocity, acceleration, jerk at the latest sample). Kotlin evaluates Ink's own cubic
(`EvaluateCubic`: p + v t + a t²/2 + j t³/6) at each frame time. On the device that took Ink from
74 px behind to 3.8 px behind one frame ahead (#435).

**Solo runs.** Settings → Developer → Stroke predictors pins one model (or "All"). Each report
begins with a `models:` line.

#### Stroke prediction to-do

Done:
- **The tail looks like the brush.** `drawPredictionTail` stamps soft round dabs along Ink's
  predicted curve, using the active brush's hardness for edge falloff and its spacing. All stamps
  go into one layer, composited at 45% opacity, so overlaps don't darken. Nothing is committed.
- **The tail covers the measured lag.** `EditorViewModel.predictionLeadMs` is the median
  touch-to-paint latency from `AzphaltLatencyTracker` (refreshed per stroke once 20 samples exist).
  The tail reaches that far, clamped to one to two frames. It is a curve through each frame's
  prediction, not a straight line.
- **Tuning and threading are now measurable.** Reports carry the prediction cost per sample on the
  UI thread (mean and max, in µs), and the Ink tuning in use. Settings → Developer → Google Ink tuning
  switches between `standard` (Ink's reference weights), `steady` and `responsive`.

Next, driven by the reports:
1. **Pick the Ink tuning** with the lowest error and lead near zero across a few sessions, then make
   it the default and drop the setting.
2. **Move prediction off the UI thread only if the reported cost warrants it** (roughly: mean above
   ~200 µs, or spikes that show up in frame timing).
3. **Train a Graffux model (later).** A small model on recorded Graffux strokes (pressure, tilt)
   could beat Ink; only worth it once the ranking proves the gap.

Adopting the companion doc's actual substitution model — painting provisional predicted dabs and
overwriting them once ground truth arrives — remains unimplemented and is a real, separate item
from what exists today: it would need `onStrokePoint`'s real-input-only invariant to grow a
provisional/authoritative distinction it currently doesn't have (predicted samples would need to
enter the live paint/replay path in a way that's still cleanly discardable), which is nontrivial
enough that it isn't sketched out further here. `prediction/GoogleInkGesturePredictor.kt` also
exists as a fourth predictor implementation but isn't included in `DrawingCanvas.kt`'s
`PredictionTournament` list — an unwired primitive, not a live gap in current behavior.

## 5. Catmull-Rom spline fitting

`BrushStamps.place()` currently resamples dabs along **straight-line segments** between input
points (`ax + (bx - ax) * t`) — correct arc-length spacing, but a polyline, not a curve. The
companion doc's §"Mathematical Interpolation" is a direct, mechanical upgrade: fit a Catmull-Rom
spline through each run of 4 consecutive points (real + predicted, from §4) and resample arc-length
along *that* curve instead of the raw chord. `core/common/.../PathEditing.kt` already computes
Catmull-Rom-style tangents for vector node editing — the math isn't new to this codebase, it's
just never been applied to raster stroke placement. This is a pure-math change (`BrushStamps.kt`
stays Android-free and unit-testable exactly as it is now) and doesn't strictly require the GPU
work above to land first — it's the one item in this document worth doing standalone, early, on
the current CPU pipeline, since jagged fast strokes are a visible, cheap-to-fix complaint on their
own.

## 6. Stroke filtration: three algorithms, not one

`StrokeStabilizer` today is Valkyrie's "Stabilization" tier only (moving average, velocity-
dependent). Add the other two as selectable modes on the same `stabilizerLevel`-shaped control:

- **StreamLine** (kinematic damping): instead of averaging raw positions, maintain a lagging
  "ink" point pulled toward the raw input with a spring/tension constant — the stroke trails the
  finger the way real Procreate StreamLine does, rather than a flattened average of recent
  positions. Also damps *pressure* change rate (companion doc's "Pressure" sub-parameter),
  smoothing jolts into tapers — `BrushDynamics` already isolates pressure as an independent
  multiplier (this session's pressure work), so a damped pressure signal is a drop-in replacement
  for the raw one at that call site.
- **Motion Filtering** (velocity-independent, frequency-domain): a low-pass/Kalman filter over
  the point stream instead of a windowed average — removes tremor without the "faster stroke =
  more smoothing" side effect the moving-average approach has, per the companion doc's own
  comparison table. Pair with an "Expression" parameter that re-injects a fraction of raw jitter
  so the result doesn't read as geometrically sterile.

All three stay pure Kotlin (like today's `StrokeStabilizer`), not GPU work — this is CPU-side
point-stream math regardless of where the rasterization ends up.

## 7. Wet Mix: reconciled into Color Smudge, not a second engine

**Revised from this document's first draft.** The original version of this section proposed Wet
Mix as an entirely new system — new `AzphaltBrush` fields (`dilution`, `charge0`,
`chargeDecayRate`, `pull`, `attack`, `grade`), with "nothing in the current codebase does this" as
the justification for building it from scratch. That framing was wrong on both counts, and both
have since been corrected directly in `docs/Krita Brush Engine Adoption.md` item 3 (read that
entry for the full parameter-mapping rationale; this section only covers what changes for the GPU
plan below):

- **Wrong home.** `AzphaltBrush` models a *stamp brush shape* (`Tool.BRUSH`); Wet Mix, like Color
  Smudge, is a *per-stroke wet-paint operation* (`Tool.SMUDGE`'s `ColorSmudgeEngine.Settings`,
  snapshotted onto `StrokeCommand` the same way every other Color Smudge setting already is). It
  was never going to compose cleanly as brush-shape fields.
- **Not a clean-slate capability.** Krita's Color Smudge (Smear/Dulling/Color Rate, item 3) is the
  *same* read/modify/write operation Wet Mix describes, expressed in a different product's
  vocabulary — Pull is Smear's existing `smudgeRate`, Charge is a decaying `colorRate`, Dilution is
  new. `ColorSmudgeEngine.Settings` now carries two new fields, `chargeDecayRate` and `dilution`,
  both defaulting to `0` (flat/undiluted — historical Krita behaviour, byte-identical), that
  generalize the existing engine into Wet Mix rather than replacing it. **Implemented and tested on
  CPU** (`ColorSmudgeEngine.kt`, `ColorSmudgeWetMixTest.kt`); a UI for it already exists (Tool
  Options' collapsed "Wet Mix" section while Smudge is active).

What §2's Vulkan-compute argument still needs, unchanged by the reconciliation: the *live-preview
GPU path* for this operation is still unbuilt. `VulkanColorSmudge.cpp`/`color_smudge.comp` today
implement Smear/Dulling/flat Color Rate only — `chargeDecayRate`/`dilution` resolve correctly on
CPU (`ColorSmudgeEngine.resolve()`, shared by the raster path and `resolvePlans()`, the plan the
Vulkan path is meant to consume) but have no shader-side implementation yet. Porting them is the
same kind of work already done for flat Color Rate, extended with the decay/dilution formulas
below — not a new read-your-own-write hazard beyond what Color Smudge's Vulkan path already solves.

Per-stroke state (`Charge`, decaying per arc distance — already computed on CPU in
`ColorSmudgeEngine.resolve()`; the shader needs the equivalent per-dispatch):

```
charge(t) = colorRate * exp(-chargeDecayRate * t)      // t = arc length travelled, in bitmap px
```

Per-dab compute shader step, sampling the layer texture at the dab's leading edge and blending
toward it, gated by `charge(t)` — the CPU reference this must match is
`ColorSmudgeEngine.dilutedPigment()` plus its caller's `colorRate`-weighted blend:

```
effectiveColor = mix(canvasColorAtLeadingEdge, brushPigment, 1 - dilution)
outputColor    = mix(canvasColorAtCentre, effectiveColor, smudgeRate /* Pull */ * charge(t))
// charge == 0 -> outputColor == canvasColorAtCentre: pure smudge, no new pigment,
// matching the companion doc's "dry brush" end state exactly — already verified on the CPU
// reference by ColorSmudgeWetMixTest's "depleted charge settles into a pure smudge" case.
```

This needs image load/store (read a neighbourhood of the *same* texture the shader is about to
write) — the concrete reason this can't be a `Canvas.drawCircle` loop and has to be a real compute
shader with an explicit memory barrier between dabs that overlap in the same dispatch. That part of
the original argument for Vulkan compute over GLES stands unchanged; only "build a new engine" is
retracted.

## 8. Device capability tiers

Update: Graffux now requires API 29 and Vulkan 1.1 (see §2b, "Device tuning"), so the per-device
floors below are enforced by Play for Graffux. They still apply to GraffitiXR, whose shared modules
remain at minSdk 26. Performance tiers within that floor are calibrated per device (§2b).

Not every Android device in `minSdk 26`'s range can do all of this. Rather than one all-or-nothing
"GPU engine" flag, detect and fall back per capability, independently:

| Capability | Requirement | Fallback |
|---|---|---|
| GPU compute stamping (§2) | Vulkan 1.1+ (API 29 for the version guaranteed present; effectively near-universal by now, but see below) | Today's CPU `Canvas` path, unchanged |
| `AHardwareBuffer` GPU interop (§2) | API 26+ (`AHardwareBuffer` itself), API 29+ for the GL/Vulkan external-memory extensions this design actually needs | Same as above — no interop, no GPU path |
| Front-buffer presentation (§3) | API 29+ (`SurfaceControl`); best on 34+ (`HardwareBufferRenderer`) | Persistent-texture render + normal Compose frame |
| Touch prediction (§4) | Google Ink Stroke Modeler's Kalman predictor (native, pinned) — works on any API level | Shipped, presentation-only: `PredictionTournament` extends the visible tail two frames; no predicted dab enters the paint path on any device |
| Wet Mix GPU port (§7) | GPU compute (same as §2) | Already the shipped behaviour: the full, non-approximated `ColorSmudgeEngine` CPU path, same as every device runs today — not a degraded fallback |

Net effect of the Vulkan-compute revision on this table: the GPU path's floor moved from "GLES
3.1, essentially every device" to "API 29+, `AHardwareBuffer` interop present" — a real reduction
in the device range that gets the GPU engine at all, in exchange for the correctness/latency
properties §2 argues for. Below API 29, this project's `minSdk 26` gets exactly what it gets
today: the CPU `Canvas` path, unchanged, same as it would if this whole document were never
implemented. Confirm this tradeoff is acceptable before committing to it — if `minSdk 26–28`
device share matters more than §2's wet-mix/latency argument, GLES 3.1 compute (this document's
first draft) is the one that keeps the wider floor, at the cost of a coarser wet-mix barrier and
no async compute queue.

This mirrors how Procreate Pocket scales the *same* Valkyrie engine down to a phone's thermal
envelope (companion doc §"Cross-Platform Scalability") rather than shipping a materially different
renderer per tier — one engine, capability-gated, not a fork.

## 9. Migration plan

A rewrite-everything-at-once approach is not realistic against a shipping app with three
independently-correct render paths (live/commit/replay) that already have to agree pixel-for-pixel
across undo/redo, co-op sync, and disk save. Proposed phasing, each shippable on its own:

1. ~~**Catmull-Rom spline fitting (§5)**~~ **Landed.** Both the authoritative commit/replay path
   and, via a one-point-lookahead sliding window (`feedLiveCurvePoint`), the round brush's LIVE
   drag too — a segment draws once it has real neighbours on both sides (a 4-point window), never
   revisited afterward, so the live curve never needs retroactive correction the way naively
   re-fitting a growing point list every frame would. `CatmullRom.kt`, `StampBrushRenderer.
   paintStroke`, `ImageProcessor.drawStrokeDynamic`, `EditorViewModel`'s live path and fast-stroke
   fallback.
2. ~~**StreamLine + Motion Filtering (§6)**~~ **Landed.** `StrokeStabilizer` now takes a
   `StabilizerAlgorithm` (Stabilization/StreamLine/Motion Filtering) alongside its existing level;
   StreamLine also damps pressure change rate. Picker in `ToolOptionsWindow`, shown once the
   stabilizer level is above 0.
3. **GPU compute stamping (§2)**, scoped *only* to `Tool.BRUSH` (both the round brush and azphalt
   stamp brushes) — the highest-traffic path, and the one `BrushStamps`/`StampBrushRenderer`
   already model cleanly enough to port directly (dab centre + radius + alpha + rotation is
   already exactly the compute shader's input buffer layout). Every other tool (eraser, blur,
   smudge, clone, fill, liquify) stays on the CPU `ImageProcessor` path — they don't need it as
   urgently and porting them is separate, later work, not a blocker for the brush itself.
   **First vertical slice landed:** `core/nativebridge/src/main/cpp/VulkanStampEngine.{h,cpp}` is
   a real, standalone-headless Vulkan 1.1 compute engine — instance/device/queue setup, a
   `VK_FORMAT_R8G8B8A8_UNORM` storage-image layer, a `stamp.comp` compute kernel matching
   `BrushStamps.Dab`'s x/y/radius/alpha/angleDeg layout and `StampBrushRenderer`'s
   hardness-then-fade coverage profile, per-dab SRC_OVER compositing in submission order (so a
   stroke at flow/opacity 1 is bit-identical to the CPU round-tip path by construction, not just
   by intent), JNI exports on `GraffitiJNI.cpp` (`nativeInit`/`nativeStampDabs`/`nativeReadback`/
   `nativeDestroy`), and a `VulkanStampEngine.kt` wrapper. `stamp.comp` is compiled to SPIR-V
   ahead of time (`glslc -mfmt=c`, checked in as `shaders/StampSpv.h`) so the CMake build never
   needs the shader compiler on the host — only `libvulkan.so` from the NDK platform sysroot,
   already linked in `CMakeLists.txt`. Verified by actually building: `:core:nativebridge:
   externalNativeBuildDebug` compiles and links this against the real OpenCV/Prefab dependency
   for both `arm64-v8a` and `armeabi-v7a`, and the four JNI symbols are present in the resulting
   `libgraffitixr.so` (checked with `nm -D`).
   **Wired into the live stamp-brush preview.** `EditorViewModel.onStrokeStart`'s azphalt-brush
   branch now inits a `VulkanStampEngine` at the live-preview bitmap's size and seeds it
   (`upload()`) with the layer's current pixels, whenever the stroke uses a generated round tip
   (`activeStampShape == null`; the shader has no textured-tip path). `onStrokePoint` then routes
   each new batch of dabs through `stampDabs()` + `readback()` into that same bitmap instead of
   `StampBrushRenderer.paintDabs`'s CPU loop — `flow` is pre-baked into the pushed color's alpha
   channel since the shader only multiplies `baseAlpha * dab.alpha`. A failure at *any* point
   (`init`/`upload`/`stampDabs`/`readback`) clears `stampGpuActive` for the rest of that stroke and
   every subsequent dab falls back to the CPU call on the same, already-correct bitmap — never a
   partially-composited GPU result left stale. `commitStampStroke` — the authoritative bake
   `DrawingEngine` replays for undo/redo/co-op — is untouched and always re-renders the whole
   stroke on the CPU from scratch, so a live preview that fell back partway through a stroke can
   never affect what's actually saved; only what you see while dragging goes through the GPU.
   **The round brush is dab-based AND GPU-wired now too.** `ImageProcessor.drawStrokeDynamic`
   (authoritative commit/replay) and `EditorViewModel.drawCurveRun` (live preview) no longer stroke
   a variable-width `Path` — both walk each Catmull-Rom-curved segment with `BrushStamps.place` at
   `ROUND_BRUSH_DAB_SPACING_FRACTION` of the segment's own dab diameter and stamp solid filled
   circles, the same rendering primitive azphalt stamp brushes use. `drawCurveRun` then routes
   those same dab centres through `stampDabs()`/`readback()` (`strokeGpuEngine`/`strokeGpuActive`,
   the round brush's counterpart to the stamp brush's `stampGpuEngine`/`stampGpuActive` — same
   per-stroke-only fallback contract, same untouched CPU-authoritative commit path), `paint.alpha`
   folded into the pushed color's alpha channel alongside its own alpha channel, hardness fixed at
   `1` (a solid `Paint.Style.FILL` circle is the CPU path's equivalent of the shader's hard-edge
   profile). Skipped for `wrapAroundMode` (would need each dab replicated 9x to match the CPU
   tiling — real work not done this pass), so a wraparound stroke stays CPU-only, same as a
   textured stamp tip does. Both live paths now create their engine via a shared
   `createSeededGpuEngine` helper that tries `initHardwareBufferBacked` first and falls back to
   plain `init` — see next paragraph. `createSeededGpuEngine` also catches `Throwable` around
   `VulkanStampEngine`'s construction: its native-library load throws (not returns false) when the
   `.so` genuinely isn't loadable — every unit test environment, and in principle any build variant
   that shipped without it — a real bug this pass hit and fixed via a failing
   `EditorViewModelTest` case before it could reach a device.
   **`AHardwareBuffer` zero-copy interop exists AND is now actually used** for real GPU memory
   (`VulkanStampEngine::initWithHardwareBuffer`, `hardwareBuffer()`,
   `VulkanStampEngine.kt`'s `initHardwareBufferBacked`/`getHardwareBuffer`) — both live-preview
   paths' `createSeededGpuEngine` helper tries it before falling back to `init()`, so a
   `stampDabs()` write during live drawing lands in an imported `AHardwareBuffer`, not
   engine-private memory, whenever the device/driver supports it. `vkGetAndroidHardwareBufferPropertiesANDROID`
   is resolved via `vkGetDeviceProcAddr` rather than linked directly — the app's actual minSdk-26
   build target doesn't export that symbol from its loader stub at all, confirmed by a real link
   failure against the full Gradle/CMake build, not a theoretical concern; direct linkage would
   have broken the whole app's build. **Still not a zero-copy DISPLAY path**: both live-preview
   call sites still `readback()` into a plain software `Bitmap` every frame exactly as before —
   what changed is *where the GPU write physically lands*, not how the CPU side consumes it. Skipping
   that CPU round trip (e.g. via `Bitmap.wrapHardwareBuffer`, API 29+) needs the live-preview
   bitmap itself to become hardware-backed, which a software `Canvas` can't draw into — that's
   still its own integration, not done here.
   **Also not yet done:** the GPU calls run synchronously on whatever thread calls
   `onStrokePoint` (the main thread, same as the CPU path today), so there is no pipelining/async
   dispatch yet — §3's front-buffer work is what actually addresses that; and none of this has been
   exercised on a physical device/GPU driver — compiling and linking real Vulkan code is verifiable
   in this environment, pixel-correctness and frame-timing on real hardware is not, and remains a
   manual QA step (Settings → Developer → "Test GPU Engine" gives a quick standalone check;
   drawing with either the round brush or an azphalt round-tip brush exercises the real live-preview
   wiring).
4. **Front-buffer presentation (§3)** — once GPU stamping is landed and the persistent layer
   texture exists to composite into, this is a presentation-layer change on top of it, not a
   parallel rewrite.
   **Interim, shipped:** provisional ink. `DrawingCanvas` stamps the brush itself from the raw
   `ACTION_DOWN` (before touch slop even decides it is a stroke) and follows the real samples
   until the editor reports the stroke's first real paint presented
   (`EditorViewModel.strokePaintPresented`), capped at 250 ms. Ink appears on the next composed
   frame instead of after the engine round trip, finger or stylus. Cleared on hold-to-eyedrop,
   pinch and lift. It still waits for one Compose frame; front-buffer is what removes that.
5. **Touch prediction (§4)** — the presentation-only tail (`PredictionTournament`) shipped and was
   later taken off screen at the user's call (the translucent run-ahead read as a taper); the
   models still run and are ranked;
   what remains is the dab-substitution model itself (provisional predicted dabs, overwritten by
   ground truth), which needs `onStrokePoint`'s real-input-only invariant to grow a
   provisional/authoritative distinction first — independent of the GPU work, but a real design
   change, not a drop-in slot into the existing pointerInput boundary.
6. **Wet Mix (§7) GPU port** — last among the GPU phases, though its CPU half (the `chargeDecayRate`/
   `dilution` reconciliation into `ColorSmudgeEngine`, with Tool Options UI) has already landed —
   see §7's revision note. What remains is porting `ColorSmudgeEngine.resolve()`'s decay/dilution
   formulas into `color_smudge.comp`, a real compute-shader change with the same read-your-own-write
   complexity as the rest of Color Smudge's Vulkan path, but a port of an existing, tested CPU
   reference rather than new surface area invented at the shader layer.

Each phase keeps the *other* two paths (commit-time and undo/redo replay) correct by construction:
until a tool is ported, `DrawingEngine`/`ImageProcessor` keep being the single source of truth for
it, exactly as today. Nothing above proposes touching the `StrokeCommand`/co-op/undo model itself
— only what renders a stroke, not how one is recorded.

## 10. Open questions for you

- **Naming.** "Azphalt" is already the extension/brush-package system's name — this engine needs
  its own, distinct name before any of this lands in code. No proposal here; your call.
- ~~The API 29 floor (§8).~~ **Resolved: acceptable.** §2's Vulkan-compute-plus-`AHardwareBuffer`
  recommendation stands as the narrower-but-better-for-Wet-Mix device floor.
- ~~Scope for a first cut.~~ **Resolved: started.** §9 phase 1 (Catmull-Rom spline fitting)
  landed — see `core/common/.../model/CatmullRom.kt`, wired into the round brush's authoritative
  commit/replay path (`ImageProcessor.drawStrokeDynamic`, plus the "fast stroke" fallback in
  `EditorViewModel.onStrokeEnd`) and the stamp brush's authoritative path
  (`StampBrushRenderer.paintStroke`). Deliberately NOT wired into either tool's live-preview path
  — see `CatmullRom`'s own doc comment for why (uniform Catmull-Rom's boundary handling makes the
  most-recently-fitted segment unstable under append-only growth, which the incremental
  "redraw only new dabs/segments" live paths assume never happens).
