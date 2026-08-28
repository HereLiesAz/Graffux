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

**UI exposure:** Brush Studio's collapsed Dynamics section; useful default mappings ship out of the box (see item 17).

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

Settings: mode (Smear/Dulling), smudge rate, color rate, smudge radius, opacity, alpha carry behavior, and optional sensor/response-curve routes for Smudge Rate, Color Rate, Smudge Radius, and shared opacity. `Tool.SMUDGE`'s default maps to Smear with zero Color Rate.

**Determinism/replay:** Color Smudge settings are snapshotted onto each Smudge `StrokeCommand`, so undo/redo replays the mode, rates, radius, alpha behavior, and sensor routes used when the stroke was made rather than whatever Tool Options contains later.

**CPU reference:** Yes — the CPU engine is the correctness/reference fallback and parity target for the Vulkan path.

**Vulkan target:** Implemented — persistent read/modify/write compute path with device-adaptive tile size.

**UI exposure:** Mode, Smudge Rate, Color Rate, radius, opacity, and alpha-carry controls appear only in the transient Tool Options window while Smudge is active (see item 17).

**Tests:** Smear direction/alpha/strength/flat-color-invariance tests, Dulling weighted-sampling/radius tests, Color Rate/mixed-pigment tests, an instrumentation parity/benchmark test for the Vulkan path.

**Dependencies:** Items 1-2.

**Completion state:** IMPLEMENTED (CPU+GPU); Adreno/Mali physical-device benchmark numbers are still outstanding (see item 16) — build success on both ARM native targets is not treated as a substitute for running the instrumentation on physical hardware.

## 4. Dynamic spacing

**Krita behavior adopted:** Pixel Brush Engine spacing can be diameter-based (isotropic) or ratio-aware for elongated tips, and sensor-resolved size/spacing changes the actual dab cadence, not just appearance.

**Graffux contract:** `AzphaltBrush.spacingReferencePx`, `isotropicSpacing`, and `tipRatio` drive the dab-placement loop in `BrushStamps.kt`. Sensor-resolved size and spacing affect each successive placement step. Primary jitter keeps its established random stream; enabling a masked second tip uses a separate deterministic random stream so secondary scatter cannot perturb primary geometry or replay.

**Determinism/replay:** Deterministic given recorded telemetry and stream seeds (see item 1's replay rule).

**CPU reference:** Yes, full placement logic lives here.

**Vulkan target:** Vulkan consumes resolved dab geometry/count; the spacing decision itself is CPU-side.

**UI exposure:** Ratio/spacing controls near the top of Brush Studio (see item 17).

**Tests:** Legacy spacing compatibility, ratio-aware placement, dynamic spacing, independent primary/secondary random stream tests.

**Dependencies:** Items 1-2.

**Completion state:** IMPLEMENTED (CPU).

## 5. Generalized tip/mask generation and caching

**Krita behavior adopted:** brush-tip mask generation (round/elliptical/image) is expensive enough that mature engines cache masks keyed by raster-affecting parameters instead of regenerating per dab.

**Graffux contract:** `BrushTipMaskCache` (`feature/editor/.../BrushTipMaskCache.kt`) caches generated round/elliptical masks and scaled image-tip masks (`tipMask`, `generatedTip`, `scaledSource`) by the parameters that affect rasterization. Position, color, opacity, flow, and stroke seed stay outside the cache key because they are downstream operations. Evicted cache entries are not explicitly recycled while a renderer may still hold a reference; Bitmap/GC ownership avoids cross-worker recycled-bitmap races between live preview and history replay.

**Determinism/replay:** Cache identity does not affect output — cached and freshly generated masks are pixel-identical; replay recomputes or reuses cache transparently.

**CPU reference:** Yes, CPU-only.

**Vulkan target:** None yet — `stamp.comp` has no mask/texture logic; masked/textured brushes fall back to the CPU correctness renderer (tracked as item 14).

**UI exposure:** Masked Tip section in Brush Studio, collapsed by default (see item 17).

**Tests:** `KritaTipTextureMaskTest.kt`; tip-mask cache reuse and elliptical raster geometry coverage.

**Dependencies:** Items 1-2, 4.

**Completion state:** IMPLEMENTED (CPU).

## 6. Texture / grain

**Krita behavior adopted:** grain/texture is an orthogonal stage after primary/secondary tip masks are resolved, supporting brush-relative or canvas-locked coordinates and multiple mask transfer modes.

**Graffux contract:** `StampBrushRenderer.applyGrain` supports scale and strength, moving/brush-relative vs. canvas-locked coordinates, deterministic per-stroke phase, and Multiply/Subtract/Darken/Overlay-style transfer modes (`GrainBlendMode`). Moving grain repeats its local relationship to each dab; canvas-locked grain samples global layer coordinates so the texture stays stationary while the brush moves.

**Determinism/replay:** Per-stroke grain phase is deterministic and recorded with the stroke.

**CPU reference:** Yes, CPU-only.

**Vulkan target:** None — not referenced by `stamp.comp` (tracked as item 14).

**UI exposure:** Texture section in Brush Studio, collapsed by default (see item 17).

**Tests:** `KritaTipTextureMaskTest.kt`; moving-versus-canvas-locked grain phase coverage.

**Dependencies:** Item 5.

**Completion state:** IMPLEMENTED (CPU).

## 7. Masked / Dual Brush

**Krita behavior adopted:** Krita's Masked Brush composites a second, independently configured tip against the primary impression; the same primitive underlies dual-brush UIs.

**Graffux contract:** `MaskedBrushConfig` (`AzphaltBrush.kt`) lets a brush own a second independently configured tip with its own shape asset or generated tip, size ratio, tip ratio, hardness, opacity, flow, rotation/follow-stroke behavior, scatter, invert/combine mode, and sensor routes. `BrushStamps.kt` (`resolveStaticMask`) resolves the secondary tip independently, using a separate seed (`MASK_SEED_SALT`) so it cannot perturb primary geometry or replay, then masks the primary impression.

**Determinism/replay:** Independent RNG stream keeps primary and secondary tip resolution replay-stable independently.

**CPU reference:** Yes, CPU-only.

**Vulkan target:** None (tracked as item 14).

**UI exposure:** Masked Tip section in Brush Studio; a generated second tip can be enabled without first selecting an external bitmap.

**Tests:** Masked-tip clipping and deterministic repeated-rendering coverage.

**Dependencies:** Items 4-6.

**Completion state:** IMPLEMENTED (CPU).

## 8. Color Source + Mix

**Krita behavior adopted:** Krita's color source can be plain foreground, a foreground/background gradient, or per-dab uniform-random color, with the gradient/mix position itself sensor-routable.

**Graffux contract:** `BrushColorSource` enum (`PLAIN` / `GRADIENT` / `UNIFORM_RANDOM`) on `AzphaltBrush`; `colorMix` field plus a `BrushParameter.MIX` sensor target in `BrushSensorDynamics`. Resolution happens in `StampBrushRenderer.resolvedColor()`, which lerps `baseArgb` ↔ `secondaryArgb` by `dab.colorMix` (gradient) or `dab.sourceRandom` (uniform random), then applies the HSV shift from sensor routes on top.

**Determinism/replay:** Mix coordinate and random-source draws follow the same deterministic per-dab/per-stroke streams as other resolved dab fields; stamp brushes snapshot resolved color alongside geometry for replay.

**CPU reference:** Yes, complete — `resolvedColor()` is CPU-only.

**Vulkan target:** None — `stamp.comp` has no color-source/gradient/mix logic today (tracked as item 14).

**UI exposure:** Real foreground/secondary color wired through `EditorViewModel` (`secondaryColor` in ui state), `BrushStudioWindow` mix preview, and color-source picker controls in `SketchToolsDialog`.

**Tests:** `BrushColorSourceParsingTest.kt`, `ColorSourceMixTest.kt`, `KritaBrushStagesTest.kt`. `ColorSourceMixTest` now also covers: `UNIFORM_RANDOM` determinism under sensor-driven `dynamicDabs` placement (not just the static `dabs` path), sensor HSV shift composing correctly on top of an already-resolved `GRADIENT`/`UNIFORM_RANDOM` color, and the masked/dual-brush pipeline (`paintDabs` with `maskedBrush` set) painting the same resolved color source as the primary tip.

**Dependencies:** Items 4-6 (shares the resolved-dab pipeline).

**Completion state:** IMPLEMENTED (CPU), validation complete. GPU parity remains deferred to item 14 — `gpuCompatibleBrush` gating in `EditorViewModel` (live-preview GPU eligibility) correctly requires `colorSource == PLAIN`, so a non-plain color source falls back to the CPU path exactly as documented, confirmed by reading that gate directly rather than inferring it.

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

**Graffux contract:** not yet defined. **Correction to prior tracking:** the `feat/krita-overlay-sampling` branch (PR #197, merge commit `c9e11c1`) is mislabeled — its diff is identical to `fba9521` ("Add Krita brush tip, texture, mask, and color source semantics", covering items 5-8 of this document) and shipped no overlay/composite-sampling code. There is no composite/read-only sampling surface anywhere in `VulkanColorSmudge.cpp/.h` or `ColorSmudgeEngine.kt`. This item remains genuinely unstarted despite the misleading branch/PR name; do not treat that merge as evidence of progress here.

**Determinism/replay:** To be defined — an overlay sampling mode and the layer set it reads must be snapshotted onto the stroke command like other Color Smudge settings (item 3).

**CPU reference:** Not started.

**Vulkan target:** Not started.

**UI exposure:** Not started.

**Tests:** None.

**Dependencies:** Item 3 (Color Smudge).

**Completion state:** NOT STARTED.

## 12. Paint thickness / height / impasto

**Krita behavior adopted:** a height-map channel alongside color that lets brushes build visible paint thickness, later lit/rendered for an impasto look.

**Graffux contract:** not yet defined. No height-map, impasto, or paint-thickness state exists anywhere in the brush model or renderer (the only "thickness" hits in the codebase are unrelated UI/geometry, e.g. ruler bar width in `EditorScreen.kt`).

**Determinism/replay:** To be defined.

**CPU reference:** Not started.

**Vulkan target:** Not started.

**UI exposure:** Not started.

**Tests:** None.

**Dependencies:** Items 4-8 (shares the resolved-dab/color pipeline).

**Completion state:** NOT STARTED.

## 13. Airbrush / build-up / wash behavior

**Krita behavior adopted:** continuous paint deposit while the stylus/finger is held stationary (airbrush), and distinguishable build-up/wash accumulation curves for opacity over repeated passes.

**Graffux contract:** only a low-level primitive exists today — `BrushStamps.buildUp(current, flow)` implements simple per-dab alpha accumulation (`c + flow * (1 - c)`), and `EditorViewModel` has a per-dab "flow build-up" concept. There is no airbrush continuous-deposit-while-held timer, no distinct wash mode, and no UI toggle for airbrush behavior — the Krita-style tranche built on top of `buildUp` has not been started.

**Determinism/replay:** Any held-still timer driving airbrush deposit must be derived from recorded telemetry (event time), not wall-clock/frame-rate, to stay deterministic under replay.

**CPU reference:** Only the `buildUp` primitive; no airbrush/wash logic on top of it.

**Vulkan target:** Not started.

**UI exposure:** Not started.

**Tests:** None beyond whatever exercises `buildUp` incidentally.

**Dependencies:** Items 2-3.

**Completion state:** NOT STARTED.

## 14. Brush preset schema and Krita preset interoperability

**Krita behavior adopted:** Krita's `.kpp` preset format bundles brush-engine settings plus tip/texture/mask assets into a portable, versioned package.

**Graffux contract:** Graffux has its own package format (`spec/package-format.md`, the Azphalt/`.azp` format) which is unrelated to Krita's `.kpp`. No `.kpp`, `KritaPreset`, or `BrushPreset` symbols exist anywhere in the codebase. A Krita-interoperable schema (import at minimum; export is a separate, larger decision) has not been designed.

**Determinism/replay:** N/A until designed; imported presets must resolve to the same `AzphaltBrush`/`MaskedBrushConfig`/`BrushColorSource` primitives already used elsewhere in this document so replay guarantees are inherited rather than reinvented.

**CPU reference:** Not started.

**Vulkan target:** N/A (schema/import concern, not a renderer).

**UI exposure:** Not started.

**Tests:** None.

**Dependencies:** Items 4-8 (a preset must be able to faithfully represent every primitive those items define before interoperability is meaningful).

**Completion state:** NOT STARTED.

## 15. GPU-resident mask/texture/dual-brush/source pipeline

**Krita behavior adopted:** N/A directly — this is a Graffux performance tranche to bring items 5-8 (tip/mask caching, texture/grain, masked/dual brush, color source) onto Vulkan, matching the per-dab paint path already used for flow/H/S/V dynamics (item 3).

**Graffux contract:** not yet started. `core/nativebridge/src/main/cpp/shaders/stamp.comp` is a 65-line shader with no mask, texture, grain, secondary-tip, or color-source logic. This confirms the "deliberately CPU-first" statement already made in items 5-8: any brush using masks, texture, a masked/dual tip, or a non-plain color source currently falls back to the CPU correctness renderer, with no equivalent GPU path yet.

**Determinism/replay:** GPU path must reproduce CPU output for the same resolved dab record (per the architecture rule that CPU and GPU paths must produce equivalent visible behavior).

**CPU reference:** Already required and already implemented per items 5-8; this item is specifically about adding the missing GPU path.

**Vulkan target:** Not started — this item's entire scope.

**UI exposure:** None new; existing controls from items 5-8 would simply stop falling back to CPU once this ships.

**Tests:** None yet; would need CPU/GPU parity tests analogous to the existing Color Smudge parity/benchmark instrumentation (item 3).

**Dependencies:** Items 3, 5-8.

**Completion state:** NOT STARTED.

## 16. Tile / dirty-region rendering and tile-based undo

**Krita behavior adopted:** Krita tracks dirty tiles/regions per stroke and stores undo as tile deltas rather than whole-image snapshots, keeping large-canvas painting and undo cheap.

**Graffux contract:** not yet started. No `DirtyRegion`, `DirtyTile`, `TileGrid`, or `dirty_rect` symbols exist in the Kotlin or C++ sources. Existing undo (`EditHistory.kt`, `LayerStore.kt`) is whole-bitmap based; no tile infrastructure exists to build dirty-region tracking or tile-based undo on top of.

**Determinism/replay:** Tile boundaries and dirty-region bookkeeping must not change replayed pixel output — this is a performance/memory optimization only, not a semantic change.

**CPU reference:** Not started.

**Vulkan target:** Not started (would also affect how Vulkan uploads/reads back layer regions for Color Smudge, item 3).

**UI exposure:** None expected — internal rendering/undo architecture.

**Tests:** None.

**Dependencies:** Items 3, 15 (touches the same layer read/modify/write surface).

**Completion state:** NOT STARTED.

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
