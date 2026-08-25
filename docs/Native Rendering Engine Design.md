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

## 7. Wet Mix: the genuinely new capability

Nothing in the current codebase does this — it's the one piece of the companion doc with no
existing analogue to extend, and the best argument for GPU compute over incrementally patching the
CPU path (a CPU implementation of per-dab wet blending, sampling and displacing existing pixels at
every dab, would be far too slow for a live stroke).

Per-stroke state (`Charge`, decaying per `BrushStamps.length()`-style arc distance already
computed for dab placement):

```
charge(t) = charge0 * exp(-decayRate * t)      // t = arc length travelled
```

Per-dab compute shader step, sampling the layer texture at the dab's leading edge and blending
toward it, gated by `charge(t)`:

```
effectiveColor = mix(canvasColorAtLeadingEdge, brushPigment, 1 - dilution)
outputColor    = mix(canvasColorAtCentre, effectiveColor, pull * charge(t))
// charge == 0 -> outputColor == canvasColorAtCentre: pure smudge, no new pigment,
// matching the companion doc's "dry brush" end state exactly.
```

This needs image load/store (read a neighbourhood of the *same* texture the shader is about to
write) — the concrete reason this can't be a `Canvas.drawCircle` loop and has to be a real compute
shader with an explicit memory barrier between dabs that overlap in the same dispatch.

`AzphaltBrush` (`core/common/.../azphalt/AzphaltBrush.kt`) is the natural home for the new fields
(`dilution`, `charge0`, `chargeDecayRate`, `pull`, `attack`, `grade` — all `0f` default, so every
existing brush stays bit-identical until authored otherwise), the same pattern `sizeJitter`/
`scatter`/`followStroke` already established there.

## 8. Device capability tiers

Not every Android device in `minSdk 26`'s range can do all of this. Rather than one all-or-nothing
"GPU engine" flag, detect and fall back per capability, independently:

| Capability | Requirement | Fallback |
|---|---|---|
| GPU compute stamping (§2) | Vulkan 1.1+ (API 29 for the version guaranteed present; effectively near-universal by now, but see below) | Today's CPU `Canvas` path, unchanged |
| `AHardwareBuffer` GPU interop (§2) | API 26+ (`AHardwareBuffer` itself), API 29+ for the GL/Vulkan external-memory extensions this design actually needs | Same as above — no interop, no GPU path |
| Front-buffer presentation (§3) | API 29+ (`SurfaceControl`); best on 34+ (`HardwareBufferRenderer`) | Persistent-texture render + normal Compose frame |
| Touch prediction (§4) | `androidx.input.motionprediction` — works on any API level, quality varies by digitizer | No prediction; historical-only, as today |
| Wet Mix (§7) | GPU compute (same as §2) | Feature hidden on brushes that use it, or CPU-approximated at a coarser dab rate |

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

1. **Catmull-Rom spline fitting (§5)** — pure math, CPU, `BrushStamps.kt` only. No architecture
   change, immediately visible quality win on fast strokes. Do this first regardless of anything
   else in this document.
2. **StreamLine + Motion Filtering (§6)** — pure Kotlin, extends `StrokeStabilizer`. Independent
   of §5 and of the GPU work; can land in parallel.
3. **GPU compute stamping (§2)**, scoped *only* to `Tool.BRUSH` (both the round brush and azphalt
   stamp brushes) — the highest-traffic path, and the one `BrushStamps`/`StampBrushRenderer`
   already model cleanly enough to port directly (dab centre + radius + alpha + rotation is
   already exactly the compute shader's input buffer layout). Every other tool (eraser, blur,
   smudge, clone, fill, liquify) stays on the CPU `ImageProcessor` path — they don't need it as
   urgently and porting them is separate, later work, not a blocker for the brush itself. This
   phase alone is the bulk of the new native surface area §2 describes: Vulkan instance/device
   setup, the `AHardwareBuffer`-backed layer image, and the `stamp.comp` kernel — get this working
   and correct (bit-identical to the CPU path at opacity/flow 1, same as this session's opacity
   work held itself to) before layering §4's presentation change or §6's wet-mix complexity on
   top of it.
4. **Front-buffer presentation (§3)** — once GPU stamping is landed and the persistent layer
   texture exists to composite into, this is a presentation-layer change on top of it, not a
   parallel rewrite.
5. **Touch prediction (§4)** — can land any time after step 1, independent of the GPU work;
   slots into the same `DrawingCanvas.kt` pointerInput boundary the pressure work this session
   already touched.
6. **Wet Mix (§7)** — last, because it's genuinely new surface area (new `AzphaltBrush` fields,
   new UI in Brush Studio, new compute shader with real GPU-side complexity) rather than a port of
   something that already exists.

Each phase keeps the *other* two paths (commit-time and undo/redo replay) correct by construction:
until a tool is ported, `DrawingEngine`/`ImageProcessor` keep being the single source of truth for
it, exactly as today. Nothing above proposes touching the `StrokeCommand`/co-op/undo model itself
— only what renders a stroke, not how one is recorded.

## 10. Open questions for you

- **Naming.** "Azphalt" is already the extension/brush-package system's name — this engine needs
  its own, distinct name before any of this lands in code. No proposal here; your call.
- **The API 29 floor (§8).** Vulkan-compute-plus-`AHardwareBuffer` is a narrower device floor
  than the GLES-3.1-everywhere version this document started with, for the sync/latency/async-
  queue properties §2 argues are worth it. If `minSdk 26–28` share is significant enough that this
  tradeoff isn't acceptable, say so and §2 reverts to GLES compute — the rest of this document
  (§3–§9) doesn't otherwise depend on which one wins.
- **Scope for a first cut.** §9's phase 1 (Catmull-Rom) is small enough to do this session if you
  want it now rather than waiting on sign-off for the rest. Say the word and I'll start there.
