# Material-Aware Paint Engine — Implementation Status / TODO

Companion to:

- `Material-Aware Paint Engine Roadmap.md` — original material-aware 2.5D implementation plan.
- `Brush Stroke Mechanics Roadmap.md` — brush/contact mechanics plan that became the primary critical path during this session.

This file is the **authoritative implementation checkpoint and session TODO**. The roadmap documents describe intended architecture; this file records what has actually landed, what remains partial, and what has not started.

Status legend:

- ✅ **Implemented / landed** — code is on `main` and the hosted build/test gate for the tranche has been completed or merged through its validation PR.
- 🟡 **Partial / hardening remaining** — useful implementation exists, but one or more parity, hardware, persistence, UX, tuning, or performance gates remain.
- ⬜ **Pending** — not implemented in this session.
- 🔬 **Research only** — deliberately not on the critical path unless a cheaper approximation visibly fails.

## 2026-09-19 checkpoint

Current `main` includes the session's material, telemetry, brush-mechanics, morphology, physical-population, and validation work plus Vulkan reservoir parity, translucent-pickup hardening, the versioned media-profile boundary, the consolidated material golden matrix, and minimal material controls.

The session began with the material-aware 2.5D sequence:

```text
material scaffolding
→ pigment mixing
→ reservoir / depletion / pickup
→ substrate-aware deposition
→ wetness / bounded transport
→ Impasto v2 / wet-dry optics
→ coarse deformable tuft
→ media profiles / product polish
```

After the first material tranches landed, we explicitly changed the implementation priority: **brushstroke mechanics became the primary path**. Pressure, tilt, orientation, contact lifecycle, stable tuft mechanics, physical brush population, morphology, hover visualization, and live/replay parity were advanced before substrate/wetness/Impasto-v2. That reordering is intentional, not a skipped dependency.

The governing rule remains:

> **Brushstroke mechanics wins over material work unless material work is required to preserve correctness.**

---

# Master session checklist

## A. Original material-aware 2.5D roadmap

### Phase 0 — Measurement, fixtures, and material-state scaffolding — 🟡

Session-start items:

- ✅ Renderer-independent `MaterialColor` model in `core/engine`.
- ✅ Explicit `MaterialMixingModel` with legacy and pigment-like modes.
- ✅ Immutable `PaintMedium` configuration boundary.
- ✅ Stroke-local `BrushReservoirState` value model.
- ✅ Optional `MaterialChannels` declaration.
- ✅ Existing brushes remain on legacy behavior unless material behavior is explicitly selected.
- ✅ Basic/dry brush paths are not forced to allocate wet/material channels.
- 🟡 Latency/input telemetry infrastructure exists, but material-stage-specific timing/budget reporting is not yet a complete product validation matrix.
- ✅ `MaterialGoldenFixtureMatrixTest` now consolidates explicit numeric golden vectors for the current material contract: pigment mixing, long-stroke depletion, wet pickup, repeated crossings, and the legacy dry baseline. Future substrate/wetness/Impasto-v2 vectors extend this same matrix.
- ✅ Versioned renderer-independent `PaintMediaProfile` boundary landed with stable id/version, `PaintMedium`, channel requirements, initial reservoir state, and an exact `LEGACY_DRY` compatibility profile. Colour remains explicit stroke intent rather than profile identity.
- ⬜ Named/tuned semantic product profiles such as Heavy Oil/Acrylic/etc. are not yet the source of material behavior.
- ⬜ A recorded physical-device baseline matrix for all material stages has not been completed.

Original fixture list retained for completion:

- ✅ slow pressure-ramp coverage exists across brush/telemetry tests;
- ✅ fast motion/flick-related stroke behavior has existing baseline coverage in the brush engine;
- ✅ repeated crossings are pinned in the consolidated material golden fixture matrix;
- ✅ wet-over-wet behavior now has a persistent Phase-4 wetness field, bounded active-tile transport, deterministic drying, and Color Smudge integration;
- ✅ low-load substrate dry-drag/scumble behavior is covered by the Phase-3 substrate reference/renderer tests;
- ✅ existing Impasto has stab/height behavior, but Impasto-v2 material coupling is pending;
- 🟡 long-stroke determinism/performance is covered in pieces, but the full material stress benchmark matrix remains pending.

### Phase 1 — Pigment-like colour mixing — ✅ implementation; 🟡 hardware parity gate

Implemented:

- ✅ Deterministic CPU `MaterialColorMixer` reference.
- ✅ Explicit `LEGACY_RGB` compatibility mode.
- ✅ `PIGMENT_RYB` bounded subtractive/artist-space approximation.
- ✅ Pigment mixing integrated into Color Smudge only when explicitly selected.
- ✅ Exact matching RYB transform ported to the Vulkan Color Smudge shader.
- ✅ Native mode values preserve historical ABI meaning for legacy modes.
- ✅ Failed/unavailable GPU execution recomputes from the pristine source through the CPU reference.
- ✅ Golden coverage includes ratio endpoints, yellow + blue, cyan + red, high-chroma complements, black/white tinting, repeated mixing, order symmetry, and alpha preservation.
- ✅ Hosted unit/build/native-shader validation completed for the pigment tranche.
- 🟡 CPU ↔ Vulkan instrumentation is compiled, but real Android Vulkan-device pixel parity is still a hardware gate.
- ⬜ Full spectral/Kubelka-Munk mixing remains research-only and is not required unless the bounded mixer visibly fails.

Compatibility:

- ✅ Existing brushes/imported presets default to legacy RGB.
- ✅ Existing stroke replay does not opt into pigment behavior merely because the mixer exists.

### Phase 2 — Stateful reservoir, depletion, and pickup — ✅ implementation; 🟡 hardware parity gate

Implemented:

- ✅ Renderer-independent deterministic `BrushReservoirModel`.
- ✅ Analytic charge/load depletion compatible with historical Color Smudge Charge behavior.
- ✅ Bounded deposition that cannot overdraw reservoir load.
- ✅ Bounded pickup that cannot overfill the reservoir.
- ✅ Carried-colour contamination using the selected material mixer.
- ✅ Load-weighted carried material/wetness state transitions.
- ✅ Color Smudge Charge routes through the reservoir model.
- ✅ CPU reservoir pickup with `pickupRate`, default `0`.
- ✅ Pickup samples contact material before the dab mutates it and affects subsequent dabs rather than retroactively changing the sampling dab.
- ✅ Smear and Dulling both feed reservoir pickup while keeping their existing spatial behavior.
- ✅ Sample Merged can supply pickup from the same pre-composited source used by Color Smudge.
- ✅ Native/Vulkan reservoir depletion, pickup, carried-colour contamination, and ordered post-dab pickup are implemented in the existing Color Smudge backend.
- ✅ Renderer-neutral resolved plans carry per-dab distance and Color Rate dynamics into the native reservoir state machine.
- ✅ Real-device Vulkan parity instrumentation covers Smear, Dulling, pigment pickup, and translucent layer pickup.
- ✅ Non-Sample-Merged translucent pickup unpremultiplies `layerImage` before alpha-weighted sampling, preventing double-alpha darkening while preserving the straight-alpha Sample Merged path.
- ✅ Failed/unavailable Vulkan execution still falls back to the CPU reference rather than dropping reservoir state.
- ✅ Minimal artist-facing **Load / Pickup / Pigment Mixing** controls are wired to the existing Color Smudge/Wet Mix state rather than a second material backend.

Still pending:

- 🟡 Execute the existing CPU ↔ Vulkan pigment/reservoir parity suite on representative real Android Vulkan hardware and record the results.
- ✅ Persistent canvas wetness is now supplied by Phase 4; pickup samples real canvas wetness when canonical wet material exists instead of fabricating it from display colour.
- ⬜ Per-sub-tuft reservoir load; current reservoir is stroke-level.

Compatibility/backend gate:

- ✅ `pickupRate = 0` remains the historical default path.
- ✅ Imported Krita presets keep pickup at the Graffux default of zero.
- ✅ Positive pickup has native/Vulkan support; unsupported/failed GPU execution recomputes through the CPU reference instead of silently ignoring pickup.

### Phase 3 — Substrate-aware deposition and dry-brush breakup — 🟡 backend implemented; product activation/hardware gate remain

Implemented:

- ✅ Static substrate height/tooth field with canvas-locked tiled sampling.
- 🟡 Absorbency scalar/optional texture representation exists and is sampled; specialized absorbency-driven wet behavior is intentionally not fabricated into the dry-deposition solver.
- ⬜ Optional anisotropy/fibre direction remains a later extension.
- ✅ Contact-depth-vs-substrate-height deposition rule.
- ✅ Crest/tooth scumble and dry breakup under light contact.
- ✅ Pressure-driven substrate penetration/full coverage.
- ✅ Existing paint height fills substrate valleys in the CPU renderer and Vulkan path.
- ✅ CPU commit/replay integration uses the same substrate context.
- ✅ Vulkan has a dedicated static R8 substrate texture rather than hijacking brush grain, plus the existing-height channel needed for CPU/Vulkan valley-filling parity.
- ✅ Substrate response `0` preserves the historical renderer path.
- ✅ Hosted native/shader/editor validation for the Phase-3 Vulkan tranches is landed.

Still open:

- 🟡 The editor does not yet expose a selected substrate/media session state, so backend substrate profiles are not yet a normal product-facing canvas choice.
- 🟡 Real Android Vulkan execution remains a hardware parity gate.
- ⬜ Optional fibre-direction/anisotropy behavior.

Coarse stable tuft contacts now feed the same contact-depth model rather than requiring a separate bristle-specific substrate backend.

### Phase 4 — Persistent wetness field and bounded local transport — ✅ code-side exit gate complete

Implemented and merged in PR #394:

- ✅ Optional normalized per-layer wetness material channel, allocated lazily only after a wetness-producing stroke opts in.
- ✅ Wetness-controlled Color Smudge mobility and pickup eligibility using actual canvas wetness; no wetness is fabricated from RGB.
- ✅ Dilution/vehicle deposits wetness through the existing Color Smudge backend rather than creating a second wet-mix engine.
- ✅ Short-range bounded four-neighbour wetness transport plus bounded local ARGB/pigment-display transport.
- ✅ Pairwise conservation-aware transfer; zero-drying wetness transport and integer colour exchange conserve local material/channel mass within the bounded solver.
- ✅ Fixed small iteration budget (`2` by default, hard-capped at `4` for colour transport).
- ✅ Exponential drying transition driven only by explicit recorded sample-time deltas.
- ✅ Deterministic post-contact settling is a fixed transport quantum and deliberately does **not** double-count elapsed drying time.
- ✅ Active material tile set; inactive neighbouring tiles are not implicitly activated by transport.
- ✅ Dry/never-wet documents allocate no wetness state and incur effectively zero material-simulation work.
- ✅ Explicit deterministic simulation-time advancement for tests/replay; no wall-clock authority.
- ✅ `LayerStore` carries baked/live wetness snapshots through commit, undo/redo rebuild, and old-stroke baking.
- ✅ Undo back to a never-wet history clears stale live wetness state.
- ✅ New wetness deposition respects the active selection while already-wet material may continue its global deterministic evolution.
- ✅ Legacy/no-field Color Smudge remains byte-identical and stays eligible for the existing Vulkan path.
- ✅ Persistent-wetness Color Smudge intentionally uses the CPU correctness path until native Color Smudge owns an equivalent canonical wetness image; this is a performance/backend optimization, not a missing Phase-4 behavior.
- ✅ Hosted `testDebugUnitTest test` and `assembleDebug` passed on the Phase-4 merge head.

Phase-4 roadmap exit conditions are therefore met. Phase 5 now persists the shared canonical height + wetness state in the versioned per-layer material sidecar; Phase 4 still does not create a second wet-mix engine.

### Phase 5 — Impasto v2 / material height / wet-dry optics — ✅ implementation complete

The existing Impasto v1 path remains the compatibility baseline, and Phase 5 extends it only behind an explicit versioned opt-in.

- ✅ `ImpastoMaterialConfig` is versioned; version 1 / disabled is the exact legacy path, while version 2 explicitly enables the material model.
- ✅ Reservoir-load/contact-driven height transfer is deterministic and bounded by current load, contact depth, flow, and medium height response.
- ✅ Brush pickup removes real canvas height and cannot overfill the reservoir; deposition can free capacity that the same dab may subsequently refill.
- ✅ Wet height leveling is bounded to Phase-4 active material tiles/dirty regions and uses a fixed small iteration budget.
- ✅ Viscosity and wetness control mobility; yield-like structure recovery is derived analytically from wetness, so no second persistent structure image is required.
- ✅ Substrate height participates in deposition gating and wet leveling, allowing paint to collect in valleys and resist raised tooth according to medium response.
- ✅ Wetness drives roughness/specular presentation while canonical pigment colour, height, and wetness remain unmodified by lighting.
- ✅ Live v2 preview updates only newly touched dabs and regional material shading. Time-based settling is intentionally deferred to authoritative commit/replay so display batching cannot change canonical results.
- ✅ Commit/replay advances material time from recorded sample uptime and has sequential-vs-full-replay equality coverage for pixels, height, and wetness.
- ✅ Canonical per-layer height + wetness are persisted in a versioned sparse/tiled gzip sidecar. `Layer.heightMap` remains only the transient runtime mirror, keeping project JSON free of canvas-sized float arrays.
- ✅ Material sidecars restore on project load, participate in debounced saves and explicit flushes, and travel with project archive export/import. Color-only layers create no sidecar; stale/empty material deletes the old sidecar.
- ✅ Sidecar paths are hashed/contained and corrupt, mismatched, oversized, or invalid material data fails closed.
- ✅ Hosted unit/build validation covers the v2 configuration, transfer/leveling/substrate/optics model, live wet optics, deterministic replay, persistence codec, and layer material-cache behavior.

The Phase-5 **code-side roadmap exit gate is met**. As with the other physical/material phases, representative-device performance/visual validation remains part of the broader production validation matrix; it is not a missing Impasto-v2 engine behavior.

### Phase 6 — Coarse deformable tuft — ✅ foundation implemented ahead of material Phases 3–5

The original material roadmap described this as a later phase. It is now one of the strongest completed pieces of this session; see the brush-mechanics checklist below for detail.

### Phase 7 — Media profiles and artistic controls — ⬜ / 🟡

- ✅ The versioned `PaintMediaProfile` core boundary is implemented, including an exact `LEGACY_DRY` compatibility profile and the invariant that colour is not medium.
- ⬜ Tuned semantic product profiles such as Heavy Oil, Soft Oil, Acrylic, Gouache, Watercolour, Ink, Dry Bristle, and Marker-like legacy mode are not yet implemented as a catalogue/product layer.
- 🟡 Minimal **Load / Pickup / Pigment Mixing** controls are exposed on Color Smudge/Wet Mix; the broader artistic vocabulary — Wetness/Dilution, Flow/Body, Drying, Thickness, Texture Interaction — remains phase-dependent and intentionally incomplete.
- 🟡 Brush stiffness/splay mechanics now exist internally, but artist-facing tuning remains intentionally conservative until mechanics are stable.
- ✅ The rule **colour is not medium** remains intact; selecting RGB colour does not silently assign pigment/viscosity semantics.

---

## B. Device-aware telemetry foundation — ✅

Architecture implemented this session:

```text
raw device events
→ device-specific telemetry interpreter
→ values + availability + provenance + confidence
→ canonical mechanical-intent frame
→ brush physics
```

### High-quality stylus — ✅

- ✅ Continuous pressure.
- ✅ Tilt where available.
- ✅ Orientation/azimuth where available.
- ✅ Historical/coalesced samples.
- ✅ High confidence only for signals the device actually exposes.

### Basic stylus — ✅

- ✅ Pressure-only styluses remain basic styluses even with good pressure sensors.
- ✅ Missing tilt/orientation remain explicitly unavailable.
- ✅ Missing axes are never fabricated.

### Finger — ✅ foundation

- ✅ Distinct modality rather than fake stylus data.
- ✅ Touch contact geometry and pressure evidence where meaningful.
- ✅ Touch ellipse/orientation evidence where available.
- ✅ Motion, dwell, touchdown, and lift context remain available to mechanics.

### Shared metadata/replay contract — ✅

- ✅ Availability, confidence, and provenance survive serialization/replay.
- ✅ Contact phase is carried in replayable telemetry.
- ✅ Device attitude/presentation data is carried where available.
- ✅ Legacy samples remain replay-compatible.
- ✅ Device-aware telemetry and mechanics validation branches were merged, including the final telemetry-profile validation merge.

Still useful to add later:

- ⬜ Physical-device coverage matrix across representative premium stylus, pressure-only/basic stylus, and finger devices.
- ⬜ End-to-end artist calibration/tuning data for confidence thresholds; the architecture is intentionally ready before those empirical constants are final.

---

## C. Brushstroke mechanics roadmap

### M1 — Stateful contact mechanics — ✅ baseline implemented

Implemented:

- ✅ Speed-dependent bend.
- ✅ Drag/rake direction.
- ✅ Stiffness-controlled directional lag.
- ✅ Direction-change hysteresis.
- ✅ Recovery as motion slows.
- ✅ Pressure/contact-driven compression target.
- ✅ Tilt-driven lean target.
- ✅ Confidence-gated orientation steering.
- ✅ Contact-center drag.
- ✅ Width/splay and elongation output.
- ✅ Stateful rake/contact angle.
- ✅ Canonical replay integration.
- ✅ Incremental/live integration.
- ✅ Telemetry confidence changes mechanical authority without destroying provenance.
- ✅ Disabled/legacy configurations preserve the existing path.

Remaining hardening:

- 🟡 Artist tuning of the provisional pressure/tilt/orientation couplings.
- 🟡 Representative physical-device validation rather than only hosted deterministic tests.

### M2 — Stable coarse tuft topology and stateful bundle mechanics — ✅ foundation implemented

Implemented:

- ✅ Renderer-independent `BrushTuftConfig` / stable topology contract.
- ✅ Explicit tuft-emission compatibility gate; old brushes do not split into tuft dabs by accident.
- ✅ Stable deterministic identities with no RNG-based bristle scatter.
- ✅ Ferrule-local root placement.
- ✅ Width/contact weight, stiffness, morphology modifiers, cohesion/splay/bend/split/trailing factors.
- ✅ Renderer-independent tuft-to-ordinary-`Dab` expansion; renderers remain dumb consumers of resolved geometry.
- ✅ Same topology/emission path in canonical replay and incremental live generation.
- ✅ Parent spacing remains authoritative so tuft count does not multiply path density.
- ✅ Mask geometry and blot/extra stamps follow tuft expansion.
- ✅ Persistent per-tuft mechanical state.
- ✅ Damped bend/lag/recovery.
- ✅ Stateful separation/cohesion.
- ✅ Hysteretic split/rejoin transitions.
- ✅ Touchdown/lift/reversal state.
- ✅ Reversal persistence and temporary lag.
- ✅ Live/replay parity tests for stateful tuft behavior.
- ✅ Structured multi-contact output rather than random scatter.

### Physical bristle/bundle population law — ✅

This was a major additional requirement added during the session and is now landed:

- ✅ Real/bristle-scale diameter is fixed; increasing brush size increases represented population rather than scaling each hair/bundle up.
- ✅ Mechanical bundle diameter is fixed independently of parent brush radius.
- ✅ Population count is resolved from projected brush-tip area and bounded by configured min/max counts.
- ✅ Physical tuft layout is two-dimensional rather than one synthetic row.
- ✅ Elliptical/radial families use deterministic packed layouts; flat/rake families use deterministic grid-like layouts.
- ✅ Brush size is frozen for physical root spacing across the stroke; pressure/taper can deform/contact the population but does not shrink the ferrule's roots.
- ✅ Device roll/twist rotates the ferrule/root frame.
- ✅ Stroke direction bends/trails contacts but does not rotate the ferrule layout with the path.
- ✅ Pitch/yaw/lean biases contact-first engagement and contact weight.
- ✅ Fixed-diameter bundle dabs do not inherit the parent broad/chisel aspect as fake giant hairs.
- ✅ Size-law, projected topology, root-pose independence, and live/replay parity tests are landed.
- ✅ Physical population validation PRs were merged, including alternate-runner validation.

### M3 — Brush morphology / archetypes — ✅ initial set implemented

Initial physical morphologies landed and validated:

- ✅ Round.
- ✅ Flat.
- ✅ Filbert.
- ✅ Rigger/Liner.
- ✅ Fan.
- ✅ Rake/Comb.

The morphology layer changes stable topology and mechanical coefficients rather than merely swapping a static bitmap mask.

Remaining:

- 🟡 Artist-facing tuning of each archetype's stiffness/cohesion/splay/bend/split/trailing defaults.
- 🟡 More visual golden/reference strokes per archetype on representative hardware.

### M4 — Touchdown, dwell, cornering, reversal, and lift-off — 🟡

Implemented:

- ✅ Explicit touchdown mechanics.
- ✅ Compression/stab state.
- ✅ Transition toward drag as bend develops.
- ✅ Stateful corner lag through persistent tuft orientation.
- ✅ Reversal detection, split retention, and snap toward the new direction.
- ✅ Lift-off release and convergence/recovery behavior.
- ✅ Existing blot/dwell engine behavior remains available.

Still pending/refinement:

- ⬜ Per-tuft dwell deformation that is more physically explicit than the current touchdown/hold + existing blot/dwell behavior.
- 🟡 Fast-flick versus slow deliberate lift needs more artist-tuned differentiation.
- 🟡 Lifecycle inference for basic stylus/finger should continue to be tuned from real-device traces.

### M5 — Intent-model refinement — 🟡

The telemetry architecture is already present; this phase is now about replacing provisional mappings with better empirically informed ones.

Current provisional mappings already exist for:

- ✅ pressure → compression/splay/contact authority;
- ✅ tilt/lean → asymmetric engagement and bend/contact bias;
- ✅ orientation/device presentation → ferrule/presentation intent where available;
- ✅ finger contact evidence → touch-specific intent without pretending it is stylus tilt;
- ✅ kinematic disagreement → stateful lag/hysteresis instead of instantaneous angle jumps.

Still pending:

- ⬜ Empirical tuning/calibration from a representative device set and artist reference strokes.
- ⬜ Final per-morphology intent curves after visual evaluation.

### M6 — Native/GPU and performance hardening — 🟡

Already true:

- ✅ Mechanics resolve before rendering.
- ✅ CPU/Vulkan stamp paths consume resolved geometry rather than reinterpreting raw pressure/tilt/orientation.
- ✅ Incremental/live mechanics operate on new stroke work rather than recomputing total stroke history.
- ✅ Physical tuft counts are bounded.
- ✅ Basic/static brush remains the compatibility/latency baseline.

Still required:

- ⬜ Real-device Adreno/Mali performance matrix for the new physical population at representative brush sizes.
- ✅ Deterministic capability-tier policy is landed: constrained devices cap physical mechanical tufts at 16, balanced devices at 32, and full-tier devices retain the authored ceiling; the cap is frozen into the per-stroke brush snapshot so replay semantics do not depend on the current device.
- ⬜ Explicit performance thresholds for the physical population based on measured hardware data.
- ⬜ Real stylus/finger hardware validation of hover/contact presentation and parity.

---

## D. Hover / UI / editor integration — 🟡

Implemented:

- ✅ Size-aware brush-tip topology model used by the physical bristle mechanics.
- ✅ Topology-aware hover/preview geometry can show brush hull/cells and presentation.
- ✅ Editor routing sends physical bristle brushes through the mechanics path.
- ✅ Live physical-brush hover/presentation wiring landed.

Still pending:

- ✅ Minimal artist-facing material controls for **Load / Pickup / Pigment Mixing** are exposed on the existing Color Smudge/Wet Mix path.
- 🟡 Mechanical controls should stay minimal until archetype defaults are visually tuned; do not expose every solver coefficient as a permanent UI wall.
- ⬜ Media-profile selection/product UI for the Phase 7 material system.

---

## E. Compatibility and determinism contract — ✅ foundation, continuous gate

These are permanent requirements, not one-time tasks:

- ✅ Legacy behavior is the default unless a brush/material feature explicitly opts in.
- ✅ Tuft emission is explicitly gated.
- ✅ Physical population is activated by the physical bristle-tip configuration rather than silently changing legacy brush footprints.
- ✅ Missing stylus axes remain unavailable rather than synthesized.
- ✅ Finger input remains a distinct modality.
- ✅ Live and canonical paths share the same mechanical/topology logic.
- ✅ Renderers receive resolved geometry; they do not reinterpret device telemetry.
- ✅ Deterministic topology uses no RNG scatter.
- ✅ Existing pigment/reservoir features preserve legacy defaults when disabled.
- ✅ Prediction remains presentation-only; predicted samples do not become canonical paint.
- 🟡 Continue adding regression fixtures whenever a new physical/material feature is introduced.

---

## F. Validation / test matrix from this session

Implemented or substantially covered:

- ✅ legacy/default compatibility when new mechanics are disabled;
- ✅ deterministic telemetry classification and metadata preservation;
- ✅ high-quality stylus/basic stylus/finger profile separation;
- ✅ stable tuft identities;
- ✅ renderer-ready tuft expansion;
- ✅ explicit split-emission gating;
- ✅ stateful tuft lag and recovery;
- ✅ split/rejoin hysteresis;
- ✅ touchdown/reversal/lift mechanics;
- ✅ phase-aware live/replay parity;
- ✅ morphology live/replay parity;
- ✅ physical brush size law and projected topology;
- ✅ fixed-diameter physical bundles;
- ✅ ferrule/root pose independence from drag heading;
- ✅ pigment CPU reference/golden vectors;
- ✅ pigment Vulkan build integration;
- ✅ deterministic reservoir charge/depletion;
- ✅ deterministic CPU reservoir pickup and compatibility gate;
- ✅ native/Vulkan reservoir depletion + pickup implementation and device-test instrumentation;
- ✅ translucent non-Sample-Merged reservoir sampling parity regression coverage;
- ✅ consolidated current-material golden fixture matrix;
- ✅ versioned media-profile boundary and exact legacy-dry profile.

Still useful/required:

- ⬜ real-device CPU ↔ Vulkan pigment parity execution;
- 🟡 execute the landed native/Vulkan reservoir pickup parity tests on representative real Android Vulkan hardware;
- ⬜ explicit end-to-end finger-contact-change tuft geometry parity fixture;
- ⬜ larger visual golden suite for straight drag, 90° corner, 180° reversal, pressure ramp, tilt sweep, hover roll/lean, and each morphology;
- ⬜ physical performance baselines on Adreno high/mid and Mali high/mid tiers;
- ✅ substrate and Phase-4 wetness/replay/active-tile fixtures are landed; ⬜ Impasto-v2-specific material fixtures remain for Phase 5.

---

# Remaining prioritized TODO

This is the recommended order from the current state, not the original dependency order.

1. Run the existing **CPU ↔ Vulkan pigment + reservoir/pickup/substrate parity suite on real Android Vulkan hardware** and record the device/result matrix.
2. Complete the remaining Phase-0 measurement gap: physical-device material latency/performance baselines, including the landed Phase-5 material path.
3. Harden brush mechanics on real hardware: premium stylus, pressure-only/basic stylus, and finger traces; tune pressure/tilt/orientation confidence and lifecycle behavior.
4. Tune the initial **Round / Flat / Filbert / Rigger / Fan / Rake** archetypes with visual reference strokes rather than exposing raw solver coefficients prematurely.
5. Measure the landed deterministic tuft capability tiers on representative Adreno/Mali devices and set evidence-based performance thresholds.
6. Finish Phase-3 product activation: choose/wire editor substrate/media session state and execute the real-device Vulkan parity gate.
7. Build the tuned **Phase 7 semantic media-profile catalogue/product controls** on the landed versioned profile boundary and completed Phase-3/4/5 material channels.
8. Expand the versioned material sidecar only if a future feature introduces another canonical per-layer material channel; Phase-5 height + wetness persistence is already landed.
9. Add/record representative artist-reference and physical-device validation for Phase-5 height settling and wet/dry optical response.
10. Keep full spectral/Kubelka-Munk, full individual-bristle PBD/DER, FLIP/PIC/pressure-projected fluids, porous-paper capillary simulation, and Gaussian-splat material research behind explicit evidence that the cheaper model cannot produce the required marks.

Do not start a second wet-mix/pickup backend beside Color Smudge, do not make renderers reinterpret raw telemetry, and do not trade bounded input latency for higher simulation fidelity.

---

# Research tracks — deliberately deferred

### R1 — Full spectral / Kubelka-Munk mixing — 🔬

Only prototype if the bounded pigment mixer cannot meet visual goals.

### R2 — Full PBD/DER individual-bristle simulation — 🔬

Only prototype if the coarse stable bundle system cannot produce the required splay/split/contact marks.

### R3 — FLIP/PIC or pressure-projected fluid solver — 🔬

Only prototype if bounded local wetness transport visibly fails.

### R4 — Porous-paper capillary/evaporation model — 🔬

A future specialized watercolor path, not mandatory overhead for every medium.

### R5 — 3D Gaussian-splat / photogrammetric brush material — 🔬

Experimental rendering/content-generation work only; no current critical-path dependency.

---

# Production-complete gates still open

Even though the Phase-5 implementation and its canonical height/wetness persistence are now landed, the following broader product gates remain explicit before calling the overall system production-complete:

- real Android Vulkan parity for pigment mode;
- real Android Vulkan execution/results for the landed pigment, reservoir/pickup, and substrate parity suites;
- representative physical-device telemetry validation;
- physical tuft population **performance measurements/thresholds** on the already-landed deterministic capability tiers;
- artist tuning/reference strokes for morphology and lifecycle behavior;
- Phase-3 product activation and real-device substrate parity;
- representative-device performance/visual validation of the landed Phase-5 height settling and wet/dry optics;
- tuned semantic media-profile catalogue/product UX after engine behavior is stable (the versioned core profile boundary is already landed);
- persistence/version migration for any **future** canonical material channels beyond the currently persisted Phase-5 height + wetness state.

The target remains a **responsive, deterministic, phone-first material-aware 2.5D paint engine**. Literal physics is optional; convincing marks, stable replay, renderer parity, and bounded latency are not.
