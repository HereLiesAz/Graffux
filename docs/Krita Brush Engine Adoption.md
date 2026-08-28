# Krita Brush Engine Adoption

Graffux should borrow solved painting-engine ideas from Krita aggressively while keeping a distinctly phone-first product model.

Krita is the reference for mature brush-engine decomposition; Graffux is not trying to reproduce Krita's desktop/tablet interface. The invariant is **Krita underneath, Graffux on top**: deep engine capability, small transient controls, presets, gestures, and progressive disclosure that preserve canvas space on phones.

Every brush-engine tranche has one additional merge gate beyond ordinary unit/build success: it must preserve the phone interaction model. New engine capability may add transient or progressively disclosed controls, but it must not require permanent desktop-style panels or reduce the default drawing area.

This document is the canonical, single source of truth for the whole roadmap. It supersedes any description of tranche status in feature branches, PR descriptions, or prior conversation. Each item below records: the Krita behavior being adopted, Graffux's implementation contract, determinism/replay requirements, CPU reference requirement, Vulkan target, UI exposure, tests, dependencies, and completion state.

Completion states used throughout:

- **NOT STARTED** — no implementation exists.
- **IN PROGRESS** — partial implementation, not yet merged or not yet feature-complete.
- **IMPLEMENTED (CPU)** — feature-complete on the CPU correctness/reference renderer; no GPU path.
- **IMPLEMENTED (CPU+GPU)** — feature-complete on both CPU and Vulkan, with CPU/GPU parity.
- **VALIDATED** — implemented and confirmed with physical-device measurement (not just build success).

## 1. Canonical input telemetry

**Krita behavior adopted:** Krita's sensor system reads raw stylus/tablet events into a stable per-sample record before any brush logic touches them.

**Graffux contract:** Android input is normalized into `BrushSample` before rendering. A sample carries x/y position, event time, pressure, stylus tilt, stylus orientation, cumulative travelled distance, instantaneous speed, drawing angle, and whether the sample is prediction-only. `BrushSampleBuilder` derives the kinematic fields.

**Determinism/replay:** Predicted samples are presentation-only and are never allowed to become the basis of the next authoritative sample. Sensor kinematics remain in physical screen/hand space; viewport and layer transforms may remap x/y for rasterization, but zooming a canvas must not alter what the brush considers the same physical drawing speed or distance.

**CPU reference:** `BrushSample`/`BrushSampleBuilder` are the single source consumed by both CPU and Vulkan paths.

**Vulkan target:** N/A at this layer — telemetry feeds dab resolution before any renderer is chosen.

**UI exposure:** None directly; this is the substrate every dynamics/response-curve control in Brush Studio reads from.

**Tests:** `BrushSensorDynamicsTest.kt`.

**Dependencies:** None — foundation for every other item.

**Completion state:** IMPLEMENTED (CPU+GPU).

## 2. Sensor-to-parameter routing

**Krita behavior adopted:** sensors do not directly know about renderers; each is normalized, passed through a response curve, and routed to a brush parameter.

**Graffux contract:** `BrushSensorDynamics` (`core/common/.../azphalt/BrushSensorDynamics.kt`) routes pressure, speed, tilt, orientation, distance, time, drawing angle, per-dab random, and per-stroke random sensors to size, opacity, flow, spacing, scatter, rotation, hue, saturation, value, smudge rate, Color Rate, smudge radius, and (Color Source) mix.

**Determinism/replay:** Existing brushes have no routes by default, so their historical rendering path is unchanged. Seeded random sensors remain deterministic under replay.

**CPU reference:** Yes — `BrushStamps.dynamicDabs` resolves routed parameters before any renderer runs.

**Vulkan target:** Resolved dab values (not the routing logic itself) are consumed by the widened Vulkan dab ABI (see item 3 below and the Color Smudge section).

**UI exposure:** Brush Studio's collapsed Dynamics section; useful default mappings ship out of the box (see item 18).

**Tests:** `BrushSensorDynamicsTest.kt`.

**Dependencies:** Item 1 (telemetry).

**Completion state:** IMPLEMENTED (CPU+GPU).

## 3. Vulkan per-dab paint and Color Smudge

**Krita behavior adopted:** Krita's Pixel/Color Smudge engines separate per-dab geometry/paint resolution from rasterization, and Color Smudge performs a read/modify/write over the live layer rather than reading a static source.

**Graffux contract:** `BrushStamps.dynamicDabs` resolves geometry and per-dab paint dynamics into concrete dabs; the Android raster renderer consumes resolved instructions rather than reimplementing sensor logic. The Vulkan dab ABI has two compatible paths: legacy callers keep the original five-field dab plus stroke-level color/alpha push constants, while dynamic callers use a widened resolved-dab record with per-dab RGBA and flow. The shader selects the widened fields only when the dab marks itself resolved, so static brushes keep their established path while flow/H/S/V dynamics stay on Vulkan. `stamp.comp` is authoritative and its embedded `StampSpv.h` is regenerated by NDK `glslc` during CMake configure, preventing source/SPIR-V drift.

Color Smudge (Smear, Dulling, Color Rate — see below) has its own persistent Vulkan read/modify/write compute path (`VulkanColorSmudge.cpp`, `shaders/color_smudge.comp`) on the same layer image. A stroke uploads once, executes its ordered Smudge plans on the GPU, and reads back once at commit rather than round-tripping pixels for every dab. The engine carries both 8x8 and 16x16 compute variants, benchmarks them on the active Vulkan vendor/device, and caches the selected tile size for that device (`VulkanColorSmudge::benchmarkColorSmudge`, `ColorSmudgeBenchmarkInfo`, exposed via `VulkanStampEngine.kt:colorSmudgeBenchmarkInfo()`).

**Smudge modes:**
- *Smear*: carry the sampled source region from the previous dab toward the current dab. Graffux's historical directional smudge is the compatibility/default preset and is protected by pixel-equivalence tests.
- *Dulling*: sample a weighted color beneath the brush over a configurable smudge radius, then mix/fill the current dab with that sampled color while preserving the brush mask.
- *Color Rate*: deposit the current foreground paint separately from the smudge stage, independently controllable from smudge strength.

**One engine, not two: Krita Color Smudge and Procreate Wet Mix are the same model.** An earlier draft of `Native Rendering Engine Design.md` proposed Wet Mix (Charge/Dilution/Pull/Attack/Grade) as new fields on `AzphaltBrush` — a second, parallel paint-simulation system alongside this one. That proposal is now retracted: Procreate's vocabulary and Krita's vocabulary describe the same read/modify/write operation from two products' angles, so the reconciled design is two additional `ColorSmudgeEngine.Settings` fields rather than a second engine:

- `chargeDecayRate` (default `0`) generalizes `colorRate` into Procreate's actual Charge: `charge(t) = colorRate * exp(-chargeDecayRate * t)`, `t` the arc-length distance travelled in bitmap pixels. At the default `0`, `colorRate` stays flat — historical Krita Color Rate, byte-identical. Above `0`, deposition decays with distance and the brush settles into a pure `smudgeRate`-driven smudge once depleted, exactly matching Procreate's documented dry-brush end state — that behavior falls out of the decay formula by construction, not as a special case.
- `dilution` (default `0`) is Procreate's Dilution: the pigment about to be deposited is itself pre-mixed with the colour already under the brush before blending into the canvas at the (possibly decaying) `colorRate`. Default `0` deposits pure `paintColor`, matching historical Color Rate deposition exactly; `1` deposits the sampled/carried colour essentially unchanged, so no new pigment reaches the canvas even though `colorRate` is still being "spent".
- `smudgeRate` *is* Procreate's Pull, and needed no change — same quantity, different product's name for it.
- Procreate's remaining Wet Mix sliders (Attack, Grade, Blur, Wetness Jitter) needed no new fields: they already map onto `opacity`/`smudgeRate` (Attack), `Mode.DULLING`'s sample radius and `dynamics` sensor routes (Grade/Jitter), and `feathering` (Blur).

Both new fields resolve once, in the shared `resolve()` used by the CPU raster path and by `resolvePlans()` (the plan Vulkan will eventually consume) — the same "resolve before renderer-specific code" rule every other item in this document follows, so a future Vulkan Wet Mix path inherits the decay/dilution math instead of reimplementing it.

Settings: mode (Smear/Dulling), smudge rate, color rate, charge decay rate, dilution, smudge radius, opacity, alpha carry behavior, and optional sensor/response-curve routes for Smudge Rate, Color Rate, Smudge Radius, and shared opacity. `Tool.SMUDGE`'s default maps to Smear with zero Color Rate, zero charge decay, and zero dilution — all Wet Mix behavior is opt-in.

**Determinism/replay:** Color Smudge settings (including the two Wet Mix fields) are snapshotted onto each Smudge `StrokeCommand`, so undo/redo replays the mode, rates, decay, dilution, radius, alpha behavior, and sensor routes used when the stroke was made rather than whatever Tool Options contains later. Both new fields are pure functions of already-deterministic inputs (arc-length distance, the colour already at a pixel) — no new randomness or replay dependency.

**CPU reference:** Yes — the CPU engine is the correctness/reference fallback and parity target for the Vulkan path, including the two Wet Mix fields.

**Vulkan target:** Implemented for the pre-reconciliation feature set (Smear/Dulling/flat Color Rate) — persistent read/modify/write compute path with device-adaptive tile size. `chargeDecayRate`/`dilution` are not yet in `VulkanColorSmudge.cpp`'s shader; a stroke using either currently only has the CPU path (the same "resolved but not yet ported to `stamp.comp`/`color_smudge.comp`" pattern already used for masks/texture/color-source in item 15).

**UI exposure:** Mode, Smudge Rate, Color Rate, radius, opacity, and alpha-carry controls appear in the transient Tool Options window while Smudge is active, same as before; a collapsed "Wet Mix" section there now exposes Charge decay and Dilution, following the same progressive-disclosure pattern as Brush Studio's collapsed sections (see item 18).

**Tests:** Smear direction/alpha/strength/flat-color-invariance tests, Dulling weighted-sampling/radius tests, Color Rate/mixed-pigment tests, an instrumentation parity/benchmark test for the Vulkan path. `ColorSmudgeWetMixTest.kt` — default `chargeDecayRate`/`dilution` reproduce flat Color Rate exactly, charge decay deposits less pigment as the stroke travels, depleted charge settles into a pure smudge without tinting an untouched pixel, `resolvePlans` exposes the same decayed `colorRate` the CPU raster path consumes (the GPU-parity contract above), dilution mixes deposited pigment toward the colour already under the brush, and `dilution = 0` is byte-identical to omitting it.

**Dependencies:** Items 1-2.

**Completion state:** IMPLEMENTED (CPU+GPU) for the pre-reconciliation feature set; IMPLEMENTED (CPU) for the reconciled Wet Mix fields (`chargeDecayRate`/`dilution`) — Vulkan parity for those two in `color_smudge.comp` is outstanding, the same "resolved on CPU, not yet ported to the shader" gap item 15 tracks for `stamp.comp`'s mask/texture/color-source stages, just in the sibling Color Smudge compute path instead. Adreno/Mali physical-device benchmark numbers remain outstanding for the whole Vulkan path (see item 17) — build success on both ARM native targets is not treated as a substitute for running the instrumentation on physical hardware.

## 4. Dynamic spacing

**Krita behavior adopted:** Pixel Brush Engine spacing can be diameter-based (isotropic) or ratio-aware for elongated tips, and sensor-resolved size/spacing changes the actual dab cadence, not just appearance.

**Graffux contract:** `AzphaltBrush.spacingReferencePx`, `isotropicSpacing`, and `tipRatio` drive the dab-placement loop in `BrushStamps.kt`. Sensor-resolved size and spacing affect each successive placement step. Primary jitter keeps its established random stream; enabling a masked second tip uses a separate deterministic random stream so secondary scatter cannot perturb primary geometry or replay.

**Determinism/replay:** Deterministic given recorded telemetry and stream seeds (see item 1's replay rule).

**CPU reference:** Yes, full placement logic lives here.

**Vulkan target:** Vulkan consumes resolved dab geometry/count; the spacing decision itself is CPU-side.

**UI exposure:** Ratio/spacing controls near the top of Brush Studio (see item 18).

**Tests:** Legacy spacing compatibility, ratio-aware placement, dynamic spacing, independent primary/secondary random stream tests.

**Dependencies:** Items 1-2.

**Completion state:** IMPLEMENTED (CPU).

## 5. Generalized tip/mask generation and caching

**Krita behavior adopted:** brush-tip mask generation (round/elliptical/image) is expensive enough that mature engines cache masks keyed by raster-affecting parameters instead of regenerating per dab.

**Graffux contract:** `BrushTipMaskCache` (`feature/editor/.../BrushTipMaskCache.kt`) caches generated round/elliptical masks and scaled image-tip masks (`tipMask`, `generatedTip`, `scaledSource`) by the parameters that affect rasterization. Position, color, opacity, flow, and stroke seed stay outside the cache key because they are downstream operations. Evicted cache entries are not explicitly recycled while a renderer may still hold a reference; Bitmap/GC ownership avoids cross-worker recycled-bitmap races between live preview and history replay.

**Determinism/replay:** Cache identity does not affect output — cached and freshly generated masks are pixel-identical; replay recomputes or reuses cache transparently.

**CPU reference:** Yes, CPU-only.

**Vulkan target:** None yet — `stamp.comp` has no mask/texture logic; masked/textured brushes fall back to the CPU correctness renderer (tracked as item 15).

**UI exposure:** Masked Tip section in Brush Studio, collapsed by default (see item 18).

**Tests:** `KritaTipTextureMaskTest.kt`; tip-mask cache reuse and elliptical raster geometry coverage.

**Dependencies:** Items 1-2, 4.

**Completion state:** IMPLEMENTED (CPU).

## 6. Texture / grain

**Krita behavior adopted:** grain/texture is an orthogonal stage after primary/secondary tip masks are resolved, supporting brush-relative or canvas-locked coordinates and multiple mask transfer modes.

**Graffux contract:** `StampBrushRenderer.applyGrain` supports scale and strength, moving/brush-relative vs. canvas-locked coordinates, deterministic per-stroke phase, and Multiply/Subtract/Darken/Overlay-style transfer modes (`GrainBlendMode`). Moving grain repeats its local relationship to each dab; canvas-locked grain samples global layer coordinates so the texture stays stationary while the brush moves.

**Determinism/replay:** Per-stroke grain phase is deterministic and recorded with the stroke.

**CPU reference:** Yes, CPU-only.

**Vulkan target:** None — not referenced by `stamp.comp` (tracked as item 15).

**UI exposure:** Texture section in Brush Studio, collapsed by default (see item 18).

**Tests:** `KritaTipTextureMaskTest.kt`; moving-versus-canvas-locked grain phase coverage.

**Dependencies:** Item 5.

**Completion state:** IMPLEMENTED (CPU).

## 7. Masked / Dual Brush

**Krita behavior adopted:** Krita's Masked Brush composites a second, independently configured tip against the primary impression; the same primitive underlies dual-brush UIs.

**Graffux contract:** `MaskedBrushConfig` (`AzphaltBrush.kt`) lets a brush own a second independently configured tip with its own shape asset or generated tip, size ratio, tip ratio, hardness, opacity, flow, rotation/follow-stroke behavior, scatter, invert/combine mode, and sensor routes. `BrushStamps.kt` (`resolveStaticMask`) resolves the secondary tip independently, using a separate seed (`MASK_SEED_SALT`) so it cannot perturb primary geometry or replay, then masks the primary impression.

**Determinism/replay:** Independent RNG stream keeps primary and secondary tip resolution replay-stable independently.

**CPU reference:** Yes, CPU-only.

**Vulkan target:** None (tracked as item 15).

**UI exposure:** Masked Tip section in Brush Studio; a generated second tip can be enabled without first selecting an external bitmap.

**Tests:** Masked-tip clipping and deterministic repeated-rendering coverage.

**Dependencies:** Items 4-6.

**Completion state:** IMPLEMENTED (CPU).

## 8. Color Source + Mix

**Krita behavior adopted:** Krita's color source can be plain foreground, a foreground/background gradient, or per-dab uniform-random color, with the gradient/mix position itself sensor-routable.

**Graffux contract:** `BrushColorSource` enum (`PLAIN` / `GRADIENT` / `UNIFORM_RANDOM`) on `AzphaltBrush`; `colorMix` field plus a `BrushParameter.MIX` sensor target in `BrushSensorDynamics`. Resolution happens in `StampBrushRenderer.resolvedColor()`, which lerps `baseArgb` ↔ `secondaryArgb` by `dab.colorMix` (gradient) or `dab.sourceRandom` (uniform random), then applies the HSV shift from sensor routes on top.

**Determinism/replay:** Mix coordinate and random-source draws follow the same deterministic per-dab/per-stroke streams as other resolved dab fields; stamp brushes snapshot resolved color alongside geometry for replay.

**CPU reference:** Yes, complete — `resolvedColor()` is CPU-only.

**Vulkan target:** No new shader logic needed. `stamp.comp` itself has no color-source/gradient/mix math, but it never needs any: `StampBrushRenderer.resolvedColor()` already resolves `PLAIN`/`GRADIENT`/`UNIFORM_RANDOM` (plus any HSV sensor shift) to a final per-dab RGB on the CPU before the dab is packed into a `ResolvedBrushDab` and sent to the GPU via `VulkanStampEngine.stampResolvedDabs()` — the shader just paints whatever resolved RGB it's given, the same "resolve before renderer-specific code" contract every other item in this document follows. `EditorViewModel`'s live-preview GPU-eligibility gate (`gpuCompatibleStampBrush()`, extracted as its own testable function) previously required `colorSource == PLAIN` to enable that path; that condition was a leftover restriction with nothing backing it in the shader, and has been removed — the gate now depends only on the properties that genuinely are GPU-unsupported (a shaped tip, grain texture, a masked/dual brush, or `tipRatio != 1f`, tracked as item 15).

**UI exposure:** Real foreground/secondary color wired through `EditorViewModel` (`secondaryColor` in ui state), `BrushStudioWindow` mix preview, and color-source picker controls in `SketchToolsDialog`.

**Tests:** `BrushColorSourceParsingTest.kt`, `ColorSourceMixTest.kt`, `KritaBrushStagesTest.kt`. `ColorSourceMixTest` now also covers: `UNIFORM_RANDOM` determinism under sensor-driven `dynamicDabs` placement (not just the static `dabs` path), sensor HSV shift composing correctly on top of an already-resolved `GRADIENT`/`UNIFORM_RANDOM` color, and the masked/dual-brush pipeline (`paintDabs` with `maskedBrush` set) painting the same resolved color source as the primary tip. `GpuCompatibleStampBrushTest.kt` (new) covers the live-preview GPU-eligibility gate directly: a plain round brush is GPU-compatible, `GRADIENT`/`UNIFORM_RANDOM` color sources are too (the fix this item made), and a shaped tip/grain/masked-brush/non-round `tipRatio` each still force CPU.

**Dependencies:** Items 4-6 (shares the resolved-dab pipeline).

**Completion state:** IMPLEMENTED (CPU and GPU), validation complete. Color source has full GPU parity for the live-preview path — it never needed shader work, only an unnecessarily conservative gate removed. What item 15 still covers (shaped tips, texture/grain, masked/dual brush) is unrelated and remains CPU-only.

## 9. Taper / Fade / Lift-off

**Krita behavior adopted:** start/end size and opacity taper over the first/last portion of a stroke, plus (Graffux-specific) synthetic pressure derived from finger lift velocity where no real pressure sensor exists.

**Graffux contract:** `BrushTaper` (`AzphaltBrush.kt`) is a new primitive alongside `MaskedBrushConfig`/grain, distinct from the pre-existing, unrelated velocity-based taper in `feature/editor/.../util/ImageProcessor.kt` (`BrushDynamics`) that only serves the legacy path/stroke round-brush renderer. `BrushTaper` carries `startLengthPx`/`endLengthPx` (distance in canvas px over which each end ramps), `minSize`/`minOpacity` (the multiplier at the very start/end of a zone), and `liftOffSynthesizesPressure`. `BrushStamps.dynamicDabs` resolves a per-dab taper factor from the dab's arc-length position relative to the stroke start and end (using the smaller — more tapered — of the two zones when they overlap on a short stroke), and applies it multiplicatively to the same `resolvedDiameter`/`radius`/`alpha` terms sensor dynamics already modify, so it composes with pressure/speed routes rather than overriding them. Because `resolvedDiameter` also feeds the spacing-step calculation, a taper naturally narrows dab spacing as it shrinks, the same way sensor-driven size dynamics already did before this item.

Distance-only taper (`liftOffSynthesizesPressure = false`) is a pure function of recorded stroke geometry, so it works identically for stylus and finger input. When `liftOffSynthesizesPressure` is enabled, the end-taper factor is additionally scaled by each tail dab's interpolated recorded speed relative to the stroke's peak speed — a slow, deliberate lift fades out harder than a fast one cut off mid-motion, approximating what a real pressure sensor would have produced on a device whose touchscreen reports no usable pressure axis.

A taper alone (with no sensor `dynamics` and no `maskedBrush`) is enough to route a stroke through `dynamicDabs`'s sensor-aware placement instead of silently falling back to the legacy `dabs()` path, which would otherwise ignore it entirely — this is verified directly by test.

**Determinism/replay:** Purely a function of recorded arc-length position and (when lift-off is enabled) recorded interpolated speed — both already-deterministic replay inputs per item 1. `BrushTaper` is a field on `AzphaltBrush`, which is already snapshotted wholesale onto `StrokeCommand` (see item 3's snapshotting precedent), so taper settings replay from the recorded stroke rather than from whatever Brush Studio contains later, with no extra plumbing required.

**CPU reference:** Yes — `BrushStamps.dynamicDabs`, CPU-only.

**Vulkan target:** None — taper is resolved into `resolvedDiameter`/`radius`/`alpha` before any renderer runs, the same as other sensor dynamics; static (Vulkan-eligible) brushes are unaffected since `BrushTaper()`'s default is inactive.

**UI exposure:** Collapsed "Taper" section in Brush Studio (start/end length in px, min size, min opacity, and a "Finger lift-off" toggle), following the same progressive-disclosure pattern as Texture/Masked Tip/Color Source.

**Tests:** `BrushTaperTest.kt` — default-inactive compatibility, start-only taper, end-only taper, overlapping start/end zones taking the more-tapered factor, lift-off producing a stronger tail fade for a decelerating stroke than a constant-speed one (and no difference at all with lift-off disabled), taper alone forcing the sensor-aware placement path, and determinism for identical input. `AzphaltBrushTest.kt` — default-inactive/sanitize-is-a-no-op, extension-params parsing, and out-of-range clamping.

**Dependencies:** Items 1-2, 4.

**Completion state:** IMPLEMENTED (CPU).

## 10. Scatter / rotation refinement

**Krita behavior adopted:** independently tunable longitudinal (along-heading) and perpendicular (cross-heading) scatter axes, and rotation driven by accumulated distance rather than only instantaneous heading.

**Graffux contract:** the pre-existing perpendicular-only scatter (`brush.scatter`, `mag * cos(perpRad)` with `perpRad = heading + 90°`) is unchanged and remains the baseline covered by items 4/7. Two new independent primitives sit alongside it on `AzphaltBrush`:

- `scatterLongitudinal` offsets each dab along the *heading* direction (`mag * cos(headingRad)`/`sin(headingRad)`, no `+90°`) rather than across it, using its own deterministic RNG stream (`LONGITUDINAL_SEED_SALT`) so enabling it never perturbs the existing size/opacity/perpendicular-scatter draw sequence — verified directly by test. Both axes multiply the same sensor `SCATTER` route (`dynamic.scatterMultiplier`) so a single Scatter sensor binding scales them together, matching how Krita treats scatter amount as one sensor-routable quantity regardless of axis.
- `rotationPerPx` adds `rotationPerPx * distance` to a dab's angle, where `distance` is cumulative arc length from the stroke's start (`at` in the sensor-aware path, `index * step` in the legacy path — exactly equivalent for a fixed-step stroke). This is Krita's Distance rotation sensor: rotation driven by how far the stroke has travelled, independent of instantaneous heading, and additive with `followStroke`'s heading-based rotation and any sensor `ROTATION` route rather than replacing them.

Both fields are implemented in *both* `BrushStamps.dabs` (the legacy/static path) and `BrushStamps.dynamicDabs` (the sensor-aware path) — unlike taper (item 9), which only works through `dynamicDabs`, scatter/rotation refinement needed no change to `dynamicDabs`'s fallback gate because `dabs()` itself now supports them directly.

**Determinism/replay:** Longitudinal scatter's RNG stream and rotation's arc-length term are both pure functions of the existing deterministic seed/telemetry inputs from item 1; no new randomness source or replay dependency introduced.

**CPU reference:** Yes — `BrushStamps.dabs` and `BrushStamps.dynamicDabs`, CPU-only.

**Vulkan target:** None — resolved into dab `x`/`y`/`angleDeg` before any renderer runs, same as existing scatter/rotation.

**UI exposure:** "Longitudinal scatter" and "Spin per px" sliders alongside the existing top-level "Scatter" slider in Brush Studio (no new collapsed section needed, matching how perpendicular scatter is already exposed at the top level rather than behind a toggle).

**Tests:** `BrushScatterRotationTest.kt` — default fields are a no-op, longitudinal scatter perturbs only the along-heading axis (verified against the existing perpendicular-only baseline), longitudinal scatter is deterministic and does not perturb perpendicular scatter or size/opacity jitter, distance rotation accumulates linearly on both the static and sensor-aware paths, distance rotation composes additively with a static `angle` rather than overriding it, and extension-param round-tripping/clamping.

**Dependencies:** Items 4, 7.

**Completion state:** IMPLEMENTED (CPU). Not addressed in this pass: propagating either primitive to the secondary/masked tip (`MaskedBrushConfig` keeps its own simpler independent `scatter`/`angle` fields, unchanged) — the roadmap's "richer angle sensors shared by primary and secondary tips" phrasing is only partially met; extending `MaskedBrushConfig` with the same two primitives is a reasonable, separately-scoped follow-up.

## 11. Overlay / all-layer Color Smudge sampling

**Krita behavior adopted:** "Sample Merged"-style smudge sampling reads a composite of all visible layers (or a chosen overlay set) instead of only the active layer.

**Graffux contract:** **Correction to prior tracking, still accurate as of this pass:** the `feat/krita-overlay-sampling` branch (PR #197, merge commit `c9e11c1`) was mislabeled — its diff was identical to `fba9521` ("Add Krita brush tip, texture, mask, and color source semantics", covering items 5-8) and shipped no overlay/composite-sampling code. That misleading branch name is still not evidence of progress here.

What this pass actually adds is the CPU engine primitive, not the full end-to-end feature. `ColorSmudgeEngine.apply` (`feature/editor/.../util/ColorSmudgeEngine.kt`) now takes an optional `sampleSource: IntArray?`, and `Settings.sampleMerged: Boolean` (default `false`). When `sampleMerged` is true and a same-size `sampleSource` is supplied, Smear's carrier pickup and Dulling's weighted-average pickup read from `sampleSource` instead of the active layer's own pixels; painting (the actual `pixels[idx] = out` write) always still targets the active layer regardless. A null or mismatched-size `sampleSource` degrades safely to the historical single-layer behavior rather than crashing — verified by test. Smear's carrier can still *carry* a picked-up composite color forward past the composite's original extent, same as it always carried picked-up color forward; that's existing Smear behavior, not new.

**What is deliberately not done in this pass, and why:** no caller supplies a real `sampleSource` yet. Producing one means compositing the document's other visible layers into the *active layer's own bitmap coordinate space* — a different problem than `ExportManager.compositeLayers`, which already composites the visible stack but into canvas/screen space, at a size and alignment that generally differs from an individual layer's own bitmap (each layer can have its own scale/offset/rotationZ). Getting that per-pixel coordinate remap right, and verifying it, needs either running the app on a device/emulator with multiple non-trivially-transformed layers or substantial new test infrastructure for it — neither of which this pass attempted, rather than risk an unverified remap. `DrawingEngine`'s `Tool.SMUDGE` branch (`DrawingEngine.kt`) is therefore unchanged and does not pass `sampleSource`; a "Sample Merged" toggle was deliberately *not* added to Tool Options, because a toggle with no wired effect would violate the phone-first UI's own honesty bar.

**Determinism/replay:** `sampleMerged` is a field on `Settings`, which is already snapshotted onto the Smudge `StrokeCommand` (item 3's precedent) — so once a caller wires a real source, the setting itself already replays correctly with no further plumbing. The composite source itself is not part of replay in this pass (there isn't one yet).

**CPU reference:** Yes, for the primitive itself — `ColorSmudgeEngine.smear`/`dull`, CPU-only. Not yet exercised end-to-end by any real caller.

**Vulkan target:** Not started — `VulkanColorSmudge.cpp/.h` has no equivalent read-source parameter.

**UI exposure:** Not started, deliberately (see above).

**Tests:** `ColorSmudgeSampleMergedTest.kt` — default/no-source behaves identically to sampling the active layer, Smear and Dulling pickup both read the supplied composite when enabled, pickup ignores a supplied composite when `sampleMerged` is off, painting only ever mutates the active layer (the supplied composite is asserted unmutated), and a mismatched-size source degrades safely. Existing `ColorSmudgeEngineTest.kt`/`ColorSmudgeDynamicsTest.kt` pass unmodified, confirming the default path is byte-for-byte unchanged.

**Dependencies:** Item 3 (Color Smudge).

**Completion state:** IN PROGRESS — CPU engine primitive implemented and tested; wiring a real multi-layer composite into `DrawingEngine` (with correct per-layer coordinate remapping) and exposing a UI toggle remain outstanding, and are the actual remaining scope of this item.

## 12. Paint thickness / height / impasto

**Krita behavior adopted:** a height-map channel alongside color that lets brushes build visible paint thickness, later lit/rendered for an impasto look.

**Graffux contract:** `ImpastoEngine` (`core/common/.../azphalt/ImpastoEngine.kt`) is a new, renderer-neutral primitive alongside `BrushStamps`/`Dab`, following the same pattern established for item 11 (Sample Merged): implement and thoroughly test the CPU engine primitive itself, without wiring it into layer persistence yet.

`ImpastoEngine.deposit`/`depositStroke` accumulate a normalized 0..1 height value into a caller-owned `FloatArray` height map, using the *same* disc/hardness coverage falloff as colour dabs (`BrushStamps.stampCoverage`) so a dab's thickness footprint lines up with its colour footprint, and the *same* asymptotic accumulation curve alpha build-up already uses (`BrushStamps.buildUp`), so repeated passes thicken paint without ever exceeding 1. `ImpastoEngine.shade` renders a height map into a relief highlight/shadow: a central-difference height gradient approximates a surface normal, dotted against a light direction derived from azimuth/elevation, and expressed as a per-pixel RGB multiplier *relative to a perfectly flat region's baseline* — so an unpainted or flat-height area is provably unchanged regardless of light angle or strength (verified by test), and only sloped/built-up paint gets highlighted or shadowed. A dab's `tipRatio` (elongated tips) is intentionally not modelled in the height footprint — always circular — a documented simplification, not an oversight.

**What is deliberately not done in this pass, same rationale as item 11:** nothing here attaches a height map to a `Layer`, threads one through `LayerStore`/save-load/export, calls `deposit` from `StampBrushRenderer`/`DrawingEngine`, or exposes any UI. A real height-map channel needs to be persisted per layer (doubling that layer's per-pixel storage), included in undo/redo snapshots, and composited into every render path (live preview, commit, replay, export) — a materially larger and riskier change than the primitive itself, and one this pass did not attempt without the ability to verify it visually on a running app. Wiring it in, choosing where in the paint pipeline `deposit` is called (per dab, gated by flow/pressure the same way alpha build-up already is), and exposing light-angle/strength controls in Tool Options or Brush Studio are the item's actual remaining scope.

**Determinism/replay:** Both `deposit` and `shade` are pure functions of their inputs (dab geometry/alpha/flow, and the height map itself) — no hidden state, no randomness. Once a height map is threaded through the stroke pipeline, it would need the same recorded-telemetry-only determinism rule as every other resolved-dab quantity (item 1); nothing in this primitive violates that, but nothing yet exercises it end-to-end either.

**CPU reference:** Yes, for the primitive itself — `ImpastoEngine`, CPU-only, pure Kotlin (no Android dependency, unlike most of `feature/editor`'s renderer code).

**Vulkan target:** Not started — no equivalent exists in `stamp.comp` or `VulkanColorSmudge`.

**UI exposure:** Not started, deliberately (see above).

**Tests:** `ImpastoEngineTest.kt` — deposit raises height at a dab's centre and leaves distant pixels untouched, zero `thicknessRate`/non-positive radius is a no-op, repeated deposits build up asymptotically and never exceed 1, coverage follows hardness falloff the same way colour dabs do, a perfectly flat height map is left unchanged by `shade` regardless of light angle or strength, non-positive strength returns an unmodified copy, `shade` never mutates its inputs, a height bump shades its two opposing faces differently (light-direction-dependent), and both functions are deterministic for identical input.

**Dependencies:** Items 4-8 (shares the resolved-dab/color pipeline).

**Completion state:** IN PROGRESS — CPU engine primitive implemented and tested; attaching a height map to the layer model (persistence, undo/redo, export), calling `deposit` from the paint pipeline, and UI exposure remain outstanding, and are the actual remaining scope of this item.

## 13. Airbrush / build-up / wash behavior

**Krita behavior adopted:** continuous paint deposit while the stylus/finger is held stationary (airbrush), and distinguishable build-up/wash accumulation curves for opacity over repeated passes.

**Graffux contract:** `AirbrushEngine.heldDabs` (`core/common/.../azphalt/AirbrushEngine.kt`) is a new, renderer-neutral dab-producing primitive, following the same scoping strategy as items 11-12: implement and thoroughly test the CPU primitive, without wiring it into the live paint pipeline yet. `BrushStamps.dabs`/`dynamicDabs` are arc-length driven and never revisit the same position twice, so a held-still pointer alone produces at most one dab there — `AirbrushEngine.heldDabs` walks the recorded sample stream, detects runs where consecutive samples stay within a configurable radius of a "held" anchor position, and synthesizes additional dabs at a fixed cadence (`dabsPerSecond`) for the duration of each such run, resolving each synthetic dab's size/opacity through the same `BrushSensorEngine`/jitter machinery as ordinary dabs (held constant at the anchor's sensor values, since there is no new telemetry while the pointer isn't moving). A caller is meant to concatenate this output with `dynamicDabs`'s own movement-driven dabs. `BrushStamps.buildUp(current, flow)` (pre-existing) remains the separate low-level alpha-accumulation primitive both paths already share via ordinary alpha-over compositing — repeated dabs (real or synthetic) build up density the same way regardless of source.

Distinct "wash" accumulation curves (Krita's Wet/dry-brush-style behavior beyond plain alpha build-up) are not addressed by this primitive and remain unstarted — `heldDabs` only adds Krita's *airbrush* half of this item.

**Wired into the commit/replay path, not the live preview:** `AzphaltBrush` gained two fields, `airbrushDabsPerSecond` (0 disables, matching `heldDabs`' own contract) and `airbrushStillnessRadiusPx`, exposed as a collapsed "Airbrush" section in `BrushStudioWindow` (item 18's phone-first pattern). `DrawingEngine.kt`'s stamp-brush branch — the one-shot render `composite()`/`applySingleStroke()` (and therefore commit and every undo/redo replay) already use — now computes `BrushStamps.dynamicDabs(...)` and, when `airbrushDabsPerSecond > 0`, concatenates `AirbrushEngine.heldDabs(...)` onto it before painting, instead of calling the old `paintDynamicStroke` convenience wrapper (byte-identical to before when airbrush is off, since that wrapper did exactly the same two calls internally). This resolves integration risk (2) from the prior pass by sidestepping it entirely — this path has no GPU-eligibility gate to update, only `EditorViewModel`'s *live preview* does.

Risk (1) — the live preview's `stampStampedCount` incremental-repaint bookkeeping — is **not** resolved, and airbrush is deliberately **not** wired into that path: a stroke with airbrush enabled previews as an ordinary stroke while dragging, and only gains its held-still build-up once committed. This is a real, visible limitation (the live preview undersells what the final stroke will look like), stated plainly rather than either solving it unsafely or hiding the gap.

**What is deliberately not done in this pass:** the live-preview bookkeeping redesign above; a wash-curve primitive; and `heldDabs`' documented simplifications (scatter/rotation/masked-tip resolution not applied to held dabs) remain as before.

**Determinism/replay:** `heldDabs` is a pure function of the recorded sample stream (event time, not wall-clock/frame-rate), `brush`, and `seed`. Now exercised end-to-end: `StrokeCommand.brushSamples` (already recorded per item 1/2's telemetry pipeline) is exactly what `DrawingEngine` feeds it on both commit and replay, so an airbrush stroke's held-run build-up replays identically on undo/redo, not just in theory.

**CPU reference:** `AirbrushEngine.heldDabs`, CPU-only, pure Kotlin — now called from `DrawingEngine.kt`'s stamp-brush commit/replay path.

**Vulkan target:** Not started. (The commit/replay path this item now uses is CPU-only regardless of airbrush, same as every other azphalt stamp-brush commit — GPU is a live-preview-only accelerator, per item 15.)

**UI exposure:** Collapsed "Airbrush" section in `BrushStudioWindow` (Rate, Stillness radius sliders).

**Tests:** `AirbrushEngineTest.kt` (unchanged, still covers the primitive itself) plus new `AirbrushWiringTest.kt` (Robolectric, real `android.graphics`) — a held-still run with airbrush enabled deposits measurably more opacity than the same run without it; `airbrushDabsPerSecond = 0` renders byte-identical across separate calls (confirming the off-path is deterministic and doesn't accidentally engage); a moving stroke (past the stillness radius) is unaffected by airbrush being enabled, checked along its whole path.

**Dependencies:** Items 2-3.

**Completion state:** IN PROGRESS. Airbrush build-up: IMPLEMENTED (CPU), wired into commit/replay, tested end-to-end; NOT wired into the live stroke preview (stated limitation, not a bug). Wash-curve primitive: NOT STARTED.

## 14. Brush preset schema and Krita preset interoperability

**Krita behavior adopted:** Krita's `.kpp` preset format bundles brush-engine settings plus tip/texture/mask assets into a portable, versioned package.

**Graffux contract:** Graffux has its own package format (`spec/package-format.md`, the Azphalt/`.azp` format) which is unrelated to Krita's `.kpp`. `KritaPresetParser` (`feature/editor/.../util/KritaPresetParser.kt`) reads the `.kpp` *container*: a `.kpp` is a PNG whose `tEXt`/`iTXt` chunks carry a `"preset"` keyword holding an XML document, confirmed directly from Krita's own source (`KisPaintOpPreset::saveToDevice()`/`loadFromDevice()` in `libs/brushengine/kis_paintop_preset.cpp`, and `KisPropertiesConfiguration::toXML()`/`fromXML()` in `libs/image/kis_properties_configuration.cc`, fetched from the KDE/krita repository rather than guessed): root element `<Preset paintopid="..." name="..." embedded_resources="...">` with `<param name="..." type="...">value</param>` children. The parser recovers `paintopId`/`name`/`embeddedResourceCount` and the full `param` map as raw strings.

What it deliberately does **not** do: map those `param` keys onto Graffux's own primitives (`AzphaltBrush` / `ColorSmudgeEngine.Settings` / `MaskedBrushConfig`). Krita's per-paintop-engine parameter names (e.g. what the Color Smudge settings widget actually calls its rate/dilution keys in a real exported `.kpp`) have not been verified against a real preset file or the relevant settings-widget source, so semantic mapping is deliberately left for a follow-up rather than guessed — writing a mapping from invented key names would silently corrupt every imported preset. No `import` UI or pipeline exists yet; this item currently produces a `KritaPresetParser.Preset` value and nothing consumes it.

**Determinism/replay:** N/A until the semantic mapping exists; imported presets must resolve to the same `AzphaltBrush`/`MaskedBrushConfig`/`BrushColorSource` primitives already used elsewhere in this document so replay guarantees are inherited rather than reinvented.

**CPU reference:** `KritaPresetParser.parse()` — container format only (PNG text-chunk extraction + XML parsing), tested.

**Vulkan target:** N/A (schema/import concern, not a renderer).

**UI exposure:** None yet.

**Tests:** `KritaPresetParserTest.kt` — parses `paintopid`/`name`/`embedded_resources` and `param` entries (including a param with no `type` attribute) from a hand-built `tEXt` chunk; the same via `iTXt`; throws on a `.kpp`-shaped PNG with no `"preset"` chunk, on malformed XML, and on a non-`Preset` root element; returns null (not a crash) for non-PNG bytes.

**Dependencies:** Items 4-8 (a preset must be able to faithfully represent every primitive those items define before interoperability is meaningful) — still blocking, since the container parser alone doesn't touch those primitives.

**Completion state:** IN PROGRESS. Container format (PNG/XML) IMPLEMENTED (CPU) and tested. Semantic mapping from Krita's per-paintop `param` names to Graffux's brush primitives — the part that makes this actually useful — NOT STARTED, blocked on sourcing real per-paintop parameter names (e.g. from a real exported `.kpp` file or the relevant `KisColorSmudgeOpSettingsWidget`-style source) rather than guessing them.

## 15. GPU-resident mask/texture/dual-brush pipeline

**Krita behavior adopted:** N/A directly — this is a Graffux performance tranche to bring items 5-7 (tip/mask caching, texture/grain, masked/dual brush) onto Vulkan, matching the per-dab paint path already used for flow/H/S/V dynamics (item 3).

**Scope correction:** this item originally also listed item 8 (color source). It doesn't belong here: color source turned out to need no shader work at all, since it's fully resolved to a per-dab RGB on the CPU before a dab reaches the GPU — see item 8's Vulkan target for the actual fix (removing an unnecessarily conservative gate, not adding shader logic). This item's real scope is only the three capabilities that genuinely require sampling something in the shader a dab's geometry alone doesn't carry: a tip-mask/shape texture, grain, and a second (dual/masked) tip.

**Graffux contract:** shaped-tip sampling only, IN PROGRESS. `core/nativebridge/src/main/cpp/shaders/stamp.comp` is still the 65-line round-only shader with no mask/texture/grain/secondary-tip logic; a separate `shaders/stamp_masked.comp` samples an R8_UNORM alpha-only tip-mask texture per-dab in that dab's own rotated/scaled local space (using a `tipRatio` field on `GpuDab`, repurposing what was previously an unused padding float — a binary-compatible change, verified byte-for-byte against the existing `stamp.comp`/JNI packing sites) instead of using the round `stampCoverage()` falloff. `VulkanStampEngine::stampMaskedDabs()` is a fully separate resource set (its own descriptor set/pipeline/dab buffer/mask image+sampler) from the existing round-dab path, so this is purely additive — nothing about the already-verified round-tip pipeline changes. Texture/grain and masked/dual-brush sampling are NOT part of this — still genuinely NOT STARTED, this item's remaining scope.

`gpuCompatibleStampBrush()` in `EditorViewModel` now routes both a shaped tip and a non-round `tipRatio` to this path: `gpuPipelineUsesMaskedShader()` picks `stamp_masked.comp` for either case, still excluding grain/masked-brush entirely (both still force CPU). At stroke start, when the masked pipeline applies, `BrushTipMaskCache.tipMask()` (the same cache the CPU path already uses) rasterizes the stroke's tip once at a fixed 128×128 reference resolution — independent of any individual dab's radius, since the shader scales per dab via its own `tipRatio`/radius fields — and `alphaChannelBytes()` flattens it to the R8 byte layout `stampMaskedDabs()` expects. The live dispatch site (the azphalt stamp-brush GPU branch in `onStrokeStart`) now branches on `stampGpuUsesMaskedPipeline` to call `stampMaskedDabs()` (building `MaskedBrushDab`s, carrying each dab's own `tipRatio`) instead of `stampResolvedDabs()`, with the same "any failure disables GPU for the rest of this stroke, fall back to `StampBrushRenderer.paintDabs()`" contract the round-only path already had.

**Determinism/replay:** GPU path must reproduce CPU output for the same resolved dab record (per the architecture rule that CPU and GPU paths must produce equivalent visible behavior) — unverified for the masked path specifically: it now has a live caller, but nothing in this session's environment can compare its actual GPU output against the CPU reference (see Vulkan target below).

**CPU reference:** Already required and already implemented per items 5-7; this item is specifically about adding the missing GPU path.

**Vulkan target:** `stamp_masked.comp` + `VulkanStampEngine::stampMaskedDabs()`, now wired into the live stamp-brush stroke pipeline for shaped tips and non-round `tipRatio` — compiles clean via the NDK's `glslc` and full arm64-v8a/armeabi-v7a native builds, but **has never run on an actual GPU**: nobody working on this repo in this session has a physical Vulkan-capable device (the same hardware gap item 17 tracks), so correctness beyond "the shader compiles, the C++ builds, and the Kotlin call site type-checks" is unverified. A wrong result on real hardware would surface as a visibly wrong shaped-tip stroke, not a crash — the same fallback contract as the round-only path means a hard GPU failure (init/dispatch error) still degrades gracefully to CPU, but a shader logic bug that runs "successfully" with wrong pixels would not be caught by anything in this repo today. Texture/grain and masked/dual-brush sampling remain entirely unstarted.

**UI exposure:** None new in this step — existing shape/tip-ratio controls (already exposed via `BrushStudioWindow`, item 5) simply stop forcing CPU for the live preview once a Vulkan-capable device is present; there's no new UI surface, only a live-preview performance path that was previously unavailable for these brushes.

**Tests:** `GpuCompatibleStampBrushTest.kt` covers both gate functions: a shaped tip and a non-round `tipRatio` are now GPU-compatible via the masked pipeline (previously asserted CPU-only — updated, not just added to); grain and masked-brush still force CPU. `AlphaChannelBytesTest.kt` (new, Robolectric) covers the mask-texture byte-packing helper. None of this exercises the actual GPU dispatch or shader correctness — that needs CPU/GPU parity tests analogous to the existing Color Smudge parity/benchmark instrumentation (item 3), run on real hardware, which remains undone.

**Dependencies:** Items 3, 5-7.

**Completion state:** IN PROGRESS. Shaped-tip and non-round-tipRatio sampling: engine primitive written, compile-verified, and wired into the live stroke pipeline (`gpuCompatibleStampBrush()`/`gpuPipelineUsesMaskedShader()` route eligible strokes to it, with the same fail-safe CPU fallback contract as the round-only path) — but NOT runtime-verified on any GPU, since nobody working on this repo has physical Vulkan-capable hardware. Texture/grain and masked/dual-brush sampling: NOT STARTED.

## 16. Tile / dirty-region rendering and tile-based undo

**Krita behavior adopted:** Krita tracks dirty tiles/regions per stroke and stores undo as tile deltas rather than whole-image snapshots, keeping large-canvas painting and undo cheap.

**Graffux contract:** dirty-region bounding-box computation only, IN PROGRESS. `DirtyRegion` (`core/common/.../azphalt/DirtyRegion.kt`) computes the axis-aligned pixel bounds a set of dabs could have painted — each dab's own radius, plus its secondary (masked/dual) tip's radius if present, unioned across a stroke's dabs and clampable to canvas bounds. It mirrors the bounding-box math `VulkanStampEngine::stampDabs()`'s dispatch-region optimization already computes ad hoc in C++ (item 3), now as a reusable, tested Kotlin utility.

This is explicitly the smaller, safer half of the item. `DirtyTile`/`TileGrid` do not exist, and neither does tile-based undo: `EditHistory.kt`/`LayerStore.kt` remain entirely whole-bitmap-snapshot based, unchanged by this. Rewriting undo storage to tile deltas is a correctness-sensitive change to how every stroke's undo/redo works — real user data at stake if it's wrong — and wasn't attempted here; `DirtyRegion` has no consumer yet in either undo or rendering. It's a foundation a future dirty-region-aware partial redraw, partial GPU upload, or (eventually) tile-based undo could build on, not those things themselves.

**Determinism/replay:** N/A yet — `DirtyRegion` doesn't touch replay; nothing consumes its output. Once something does, tile boundaries and dirty-region bookkeeping must not change replayed pixel output, per this item's original framing (performance/memory optimization only, not a semantic change).

**CPU reference:** `DirtyRegion.fromDabs()`/`.union()`/`.clampTo()` — implemented and tested.

**Vulkan target:** Not started (would also affect how Vulkan uploads/reads back layer regions for Color Smudge, item 3).

**UI exposure:** None expected — internal rendering/undo architecture.

**Tests:** `DirtyRegionTest.kt` — single-dab bounds, multi-dab union, secondary masked-tip extent inclusion, `union()`, `clampTo()` (including producing an empty region when entirely outside canvas), and `width`/`height`/`isEmpty`.

**Dependencies:** Items 3, 15 (touches the same layer read/modify/write surface).

**Completion state:** IN PROGRESS. Dirty-region bounding-box utility: IMPLEMENTED (CPU), tested, no consumer yet. Tile-based undo storage: NOT STARTED.

## 17. Physical Adreno/Mali benchmarking and parity validation

**Krita behavior adopted:** N/A — this is validation work specific to Graffux's Vulkan tranches, not a Krita feature.

**Graffux contract:** the Color Smudge Vulkan engine already carries 8x8/16x16 compute variants and an instrumentation parity/benchmark test (item 3) that benchmarks the active Vulkan vendor/device and caches the selected tile size. What is missing is the physical-device data itself: no committed benchmark result files, no `docs/` table of Adreno/Mali numbers, and no confirmation that the parity test has actually been run on representative physical hardware (only build success on both ARM native targets is currently established).

**Determinism/replay:** N/A — this is a performance/parity validation activity, not a semantic feature.

**CPU reference:** N/A.

**Vulkan target:** The infrastructure to run this validation already exists (item 3); this item is the act of running it and recording results.

**UI exposure:** None.

**Tests:** The existing instrumentation parity/benchmark test (item 3) is the vehicle; this item tracks actually executing it on physical Adreno and Mali devices and committing the results.

**Dependencies:** Item 3.

**Completion state:** NOT VALIDATED — infrastructure present, physical-device data absent. As GPU-resident work expands (item 15), this item's scope grows to cover those paths too.

## 18. The phone-first interaction layer

**Krita behavior adopted:** N/A — this is Graffux's own product constraint layered over every item above (see the invariant stated at the top of this document).

**Graffux contract:** the engine may expose many sensors and targets without putting all of them permanently on screen. `BrushStudioWindow` keeps a single compact floating-window form; Dynamics, Texture, Masked Tip, and Color Source sections are collapsed by default, with `DynamicsPreset` quick-start buttons and `EnumButtons`-style pickers rather than permanent panels. Default dynamics mappings ship pre-wired:

- Pressure → Size
- Pressure → Opacity
- Speed → Thin
- Speed → Spacing
- Tilt → Rotation

Color Smudge follows the same rule: its Smear/Dulling mode, Smudge, Color Rate, radius, opacity, and alpha-carry controls appear only in the transient Tool Options window (`SketchToolsDialog`) while Smudge is active. Phone-specific inputs are treated as first-class: finger velocity, temporary thumb controls, long-press modifiers, device orientation where useful, and transient HUDs that disappear while painting.

**Determinism/replay:** N/A — UI layer only; the controls it exposes read/write the deterministic model described by items 1-14.

**CPU/Vulkan:** N/A — UI layer only.

**UI exposure:** This item *is* the UI-exposure story for items 1-9. Items 10-16 have no corresponding UI yet because their underlying engine work has not been built — there is nothing in Brush Studio today for refined scatter, overlay sampling, impasto, airbrush/wash, preset import, or GPU-resident toggles, since none of those primitives exist to expose.

**Tests:** No automated UI tests recorded here; verified by the merge-gate requirement stated at the top of this document (no permanent desktop-style panels, no reduction in default drawing area).

**Dependencies:** Every item above that ships a control.

**Completion state:** IMPLEMENTED for items 1-9's surface; N/A (nothing yet to expose) for items 10-16.

## 19. Non-negotiable architecture rules

- Prediction is disposable presentation state, never authoritative paint.
- Recorded physical gesture telemetry is deterministic replay data.
- View transforms alter geometry, not hand-motion sensor meaning.
- Static brushes keep their established rendering path unless an advanced feature requires the correctness pipeline.
- Brush behavior is resolved before renderer-specific code wherever practical.
- CPU and GPU paths must produce equivalent visible behavior.
- Randomness that belongs to an optional stage must not perturb unrelated stage randomness.
- Texture coordinates and tip coordinates are separate concepts.
- Engine power must not turn the phone interface into a desktop control panel.
- This document, not a feature-branch name or PR title, is authoritative on tranche completion state — branch names have been wrong before (see item 11) and must not be trusted as evidence of what shipped.
