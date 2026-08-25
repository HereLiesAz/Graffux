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

## 2. GPU compute backbone: GLES 3.1/3.2 compute shaders, not Vulkan

`core/nativebridge` already links `GLESv3` + `EGL` and runs a real OpenGL ES pipeline for
`MobileGS` (Gaussian-splat AR rendering) — there is a proven EGL context/surface pattern in this
codebase to build on (`core/nativebridge/src/main/cpp/MobileGS.cpp` and friends). GLES 3.1
(API 21+, universal by now) adds compute shaders and image load/store; GLES 3.2 adds a few
conveniences (geometry/tessellation, ASTC) we don't need. **Recommendation: target GLES 3.1
compute, not Vulkan.**

Two things point away from Vulkan specifically for this engine, not just "Vulkan is harder":

- `core/nativebridge/src/main/cpp/CMakeLists.txt` currently reads `# Define the native library
  (Removed VulkanBackend.cpp)` — there was a Vulkan backend in this codebase at some point and it
  was pulled. I don't have the history behind that (this session's shallow clone and GitHub commit
  search didn't surface it — worth asking whoever removed it, or digging with
  `git log --all --full-history -- '**/VulkanBackend*'` against a full clone, before ruling
  Vulkan back in). Re-proposing it blind, without knowing why it left, is a real risk.
- GLES compute gets us everything §3–§7 need (image load/store into a persistent layer texture,
  atomics for wet-mix accumulation, indirect dispatch for variable dab counts) without Vulkan's
  much larger surface area (explicit sync primitives, pipeline barriers, descriptor sets,
  SPIR-V toolchain). Vulkan's real advantage — explicit, fine-grained control over frame pacing
  and multi-queue submission — matters most once GLES compute's own frame-pacing ceiling becomes
  the bottleneck, which is a "phase 2, once we've measured it" problem, not a day-one one.

Core objects, per layer:

- A persistent `GL_TEXTURE_2D` (RGBA16F, see §7) holding that layer's pixels GPU-side, created
  once and mutated in place across strokes — the direct answer to `applyToolToBitmap` re-copying
  the whole `Bitmap` on every commit.
- A compute shader (`stamp.comp`) that takes a dab-centre buffer (positions, radii, alpha,
  rotation — literally `BrushStamps.Dab` already models this correctly) and rasterizes all of a
  stroke's pending dabs into the layer texture in one dispatch, replacing
  `StampBrushRenderer.paintDabs`'s per-dab `Canvas.drawCircle`/`drawBitmap` loop.
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
| GPU compute stamping (§2) | GLES 3.1 (API 21+, near-universal) | Today's CPU `Canvas` path, unchanged |
| Front-buffer presentation (§3) | API 29+ (`SurfaceControl`); best on 34+ (`HardwareBufferRenderer`) | Persistent-texture render + normal Compose frame |
| Touch prediction (§4) | `androidx.input.motionprediction` — works on any API level, quality varies by digitizer | No prediction; historical-only, as today |
| Wet Mix (§7) | GPU compute (same as §2) | Feature hidden on brushes that use it, or CPU-approximated at a coarser dab rate |

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
   urgently and porting them is separate, later work, not a blocker for the brush itself.
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
- **Why was `VulkanBackend.cpp` removed?** If there's a concrete reason (driver bugs on some
  vendor, build complexity, abandoned experiment), it directly bears on §2's recommendation and
  I'd rather have that context than guess at it.
- **Scope for a first cut.** §9's phase 1 (Catmull-Rom) is small enough to do this session if you
  want it now rather than waiting on sign-off for the rest. Say the word and I'll start there.
