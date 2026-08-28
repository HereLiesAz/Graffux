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
undo/redo step. There is no compute-shader parallelism, no persistent GPU-resident layer state, no
frame-pacing control beyond Compose's own recomposition, and no touch-prediction. This is why a
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

## 3. Front-buffer / low-latency presentation

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

What's missing is the predictive half. `androidx.input.motionprediction`
(`MotionEventPredictor`) is the direct Android analogue of `predictedTouches` — feed it the real
`MotionEvent` stream, it hands back an extrapolated point ~1 frame ahead. Same substitution model
the companion doc describes for Valkyrie: render the predicted dab(s) at the leading edge of the
stroke, then on the next real sample, discard and overwrite with ground truth. This wires in at
exactly the `DrawingCanvas.kt` pointerInput boundary that already threads `.historical` and
`.pressure` through — a predicted trailing point is architecturally the same shape as a historical
one, just extrapolated forward instead of interpolated backward.

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

Not every Android device in `minSdk 26`'s range can do all of this. Rather than one all-or-nothing
"GPU engine" flag, detect and fall back per capability, independently:

| Capability | Requirement | Fallback |
|---|---|---|
| GPU compute stamping (§2) | Vulkan 1.1+ (API 29 for the version guaranteed present; effectively near-universal by now, but see below) | Today's CPU `Canvas` path, unchanged |
| `AHardwareBuffer` GPU interop (§2) | API 26+ (`AHardwareBuffer` itself), API 29+ for the GL/Vulkan external-memory extensions this design actually needs | Same as above — no interop, no GPU path |
| Front-buffer presentation (§3) | API 29+ (`SurfaceControl`); best on 34+ (`HardwareBufferRenderer`) | Persistent-texture render + normal Compose frame |
| Touch prediction (§4) | `androidx.input.motionprediction` — works on any API level, quality varies by digitizer | No prediction; historical-only, as today |
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
5. **Touch prediction (§4)** — can land any time after step 1, independent of the GPU work;
   slots into the same `DrawingCanvas.kt` pointerInput boundary the pressure work this session
   already touched.
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
