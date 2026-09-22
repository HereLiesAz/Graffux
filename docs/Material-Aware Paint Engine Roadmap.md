# Material-Aware Paint Engine Roadmap

Companion to:

- `Digital Brush Stroke Anatomy Analysis.md` — research survey and physical-media model.
- `Azphalt Engine 2.md` — live-stroke performance contract.
- `Azphalt Engine 2 Convergence.md` — current Engine 2 scheduling/GPU convergence.
- `Krita Brush Engine Adoption.md` — implemented brush-engine primitives and parity work.
- `Native Rendering Engine Design.md` — native/GPU architecture background.

This document turns the physical-paint research into an implementation plan for **the Graffux codebase as it exists now**. It is deliberately not a mandate to reproduce every piece of real-world paint physics. The product target is a responsive, deterministic, phone-first painting engine whose behavior is materially convincing.

The governing idea is:

> **Simulate the perceptually important state first. Escalate to more literal physics only where the approximation visibly fails.**

The target architecture is therefore a **material-aware 2.5D paint engine**, not a general-purpose CFD package.

---

## 1. Current baseline

The research document describes several capabilities as future work that are already substantially present in Graffux. The roadmap starts from the current implementation rather than rebuilding them.

### Input and stroke state already present

`DrawingCanvas.kt` and the common Azphalt input model already provide:

- raw `MotionEvent` observation;
- historical/coalesced position samples;
- pressure;
- stylus tilt;
- stylus orientation/azimuth;
- touch-major/contact size for finger-pressure approximation;
- distance, speed and drawing angle derived once at the input boundary;
- smoothed sensor values;
- disposable prediction infrastructure;
- deterministic `BrushSample` replay semantics.

Prediction remains presentation-only and is **not** authoritative paint. This invariant stays in force unless a separate provisional-paint architecture is explicitly designed and proved safe.

### GPU brush infrastructure already present

Graffux already has a persistent native Vulkan stamp path capable of:

- resolved per-dab colour, flow and hardness;
- masked tips;
- grain;
- dual-brush masks;
- persistent Color Smudge processing;
- AHardwareBuffer-backed zero-copy display on eligible paths;
- native device benchmarking/parity hooks.

This is the compute substrate for the work below. The roadmap must extend it rather than create a parallel painting backend.

### 2.5D/impasto already exists in v1 form

Graffux already has:

- `ImpastoEngine`;
- a per-layer height map;
- persisted in-session height state in the editor model/store;
- brush-controlled `impastoThicknessRate`;
- relief shading;
- Impasto UI exposure in Brush Studio.

The physical-paint roadmap therefore treats height as an existing material channel to evolve, not a feature to introduce from zero.

### Wet-paint interaction already has a home

`ColorSmudgeEngine` and its Vulkan equivalent are the existing read/modify/write wet-paint operation. New reservoir, pickup, pigment-mixing and wetness behavior should converge on this system. Graffux must not grow a second unrelated wet-mix engine beside Color Smudge.

---

## 2. Non-negotiable constraints

Every tranche below inherits the existing Azphalt Engine 2 contracts.

1. **Input capture never waits for rendering.** Every physical input sample is recorded before preview cadence is considered.
2. **Live cost is local.** Per-frame work must scale with newly arrived samples/dabs and dirty material tiles, never total stroke history or total canvas area.
3. **No full readback in the drag hot path.** GPU-resident material state stays on the GPU while painting where the capability path permits it.
4. **No unbounded render queue.** Under overload, preview states may be coalesced/replaced; latency may not grow without bound.
5. **Replay is deterministic.** Gesture telemetry, brush parameters, seeds and any material-state decisions required to reproduce a stroke must be recorded or derivable.
6. **Prediction is disposable.** Predicted geometry does not become canonical paint under this roadmap.
7. **Basic Brush remains the latency baseline.** Material features do not silently migrate or slow the Basic Brush path.
8. **CPU reference before GPU cleverness where practical.** New math gets a deterministic reference implementation or golden-vector test before/alongside native acceleration.
9. **CPU/GPU visible parity is required.** Approximation differences must be bounded and tested, not hand-waved.
10. **Phone-first UI remains a product rule.** Physical realism does not justify a permanent desktop-style parameter wall.
11. **Graceful capability fallback.** Advanced media may reduce fidelity on weaker devices, but must not corrupt state, hang input, or crash.
12. **Research algorithms are replaceable implementations, not public architecture.** Public models describe material behavior; PBD, LUT mixing, diffusion kernels, etc. sit behind them.

---

## 3. Target architecture

The paint path evolves toward six explicit stages:

```text
BrushSample
    ↓
Input / Sensor Resolution
    ↓
Brush Geometry + Contact State
    ↓
Brush Reservoir State
    ↓
Local Material Interaction
    ↓
Canvas Material State
    ↓
Optical Rendering
```

### 3.1 Input / Sensor Resolution

Already largely implemented by `BrushSample`, `BrushSampleBuilder`, `BrushSensorEngine`, stabilizers and dab placement.

This stage describes **what the hand did**, not what paint did.

### 3.2 Brush Geometry + Contact State

Describes the effective footprint contacting the canvas:

- radius/size;
- aspect ratio;
- rotation;
- contact pressure;
- splay;
- contact fraction;
- optional sub-tuft layout;
- substrate intersection.

The first implementation is heuristic/parametric. A PBD tuft solver is a later replaceable implementation.

### 3.3 Brush Reservoir State

A stateful brush carries material over time rather than emitting an infinite identical colour source.

Minimum state:

- remaining paint load/charge;
- carried pigment colour/material representation;
- carried wetness/vehicle fraction;
- contamination from canvas pickup;
- depletion rate;
- pickup rate.

Reservoir state is stroke-local by default. Persistent dirty-brush behavior across separate strokes is a later product choice, not a prerequisite.

### 3.4 Local Material Interaction

Given contact geometry, reservoir state and the material tiles under the brush, calculate:

- deposition;
- pickup;
- pigment mixing;
- wetness transfer;
- thickness transfer;
- local shear proxy;
- dry-brush skipping over substrate peaks;
- optional short-range spread/leveling.

This is where Color Smudge evolves into the common wet-material operation.

### 3.5 Canvas Material State

The minimum useful material field is deliberately smaller than the research paper's full physical state.

Per material tile/pixel as required by a media profile:

- **display colour / pigment state**;
- **height/thickness** — extends current Impasto;
- **wetness**;
- optional **flow/structure scalar** for thixotropy-like settling;
- optional **substrate height/absorbency** from a lower-resolution/static field.

Not every brush/media type allocates every channel. Plain dry brushes keep the cheap colour-only path.

### 3.6 Optical Rendering

Display is derived from material state:

- colour conversion/mixing;
- height-derived normals;
- relief/self-shadow approximation;
- wetness-dependent roughness/specular response;
- substrate interaction;
- layer compositing.

The material field is authoritative. Lighting is presentation.

---

## 4. Fidelity ladder

The engine must not jump directly from stamp painting to full multi-physics simulation. Each level must be useful on its own.

| Level | Model | Intended use |
|---|---|---|
| 0 | Existing colour stamp / Color Smudge | Compatibility and latency baseline |
| 1 | Pigment-like colour mixing | More natural wet blending without new spatial state |
| 2 | Stateful reservoir: load, depletion, pickup | Brush carries and loses paint |
| 3 | Substrate-aware deposition + dry-brush breakup | Scumble, tooth, intermittent contact |
| 4 | Wetness field + bounded local transport | Wet-in-wet, soft bleed, time evolution |
| 5 | Material height v2 + wet/dry optics | Convincing impasto/oil/acrylic surface |
| 6 | Coarse deformable tuft | Splay, split tips, directional bristle bundles |
| R | Research solvers | Full PBD bristles, FLIP/PIC, spectral transport, etc. |

A higher level may use an approximation internally. Level numbers describe visible behavior and state, not a required algorithm.

---

## 5. Phase 0 — Measurement, fixtures and material-state scaffolding

**Goal:** establish testable contracts before changing visible paint behavior.

### Work

1. Add a renderer-independent material model in `core/engine`.
2. Define immutable/configuration data separately from live mutable stroke state.
3. Introduce explicit media profiles rather than inferring physical material solely from selected RGB colour.
4. Extend latency instrumentation to identify material stages separately from dab generation/submission where useful.
5. Add deterministic golden fixtures for representative strokes:
   - slow pressure ramp;
   - fast flick;
   - repeated crossing strokes;
   - wet-over-wet crossing;
   - low-load dry drag;
   - impasto stab;
   - long stress stroke.
6. Record baseline pixel output and latency from the current engine.

### Proposed model boundary

Names are provisional; the separation is the important part.

```kotlin
data class PaintMedium(
    val mixingModel: MixingModel,
    val viscosity: Float,
    val yieldLikeStrength: Float,
    val dryingRate: Float,
    val pickupRate: Float,
    val depositionRate: Float,
    val heightResponse: Float,
    val substrateResponse: Float,
)

data class BrushReservoirState(
    val load: Float,
    val wetness: Float,
    val carriedColor: MaterialColor,
)

data class MaterialChannels(
    val hasHeight: Boolean,
    val hasWetness: Boolean,
    val hasStructure: Boolean,
)
```

Do not freeze these exact fields until the first two prototype phases expose what is actually needed.

### Exit gate

- No visible behavior change required.
- Existing brushes serialize/replay identically.
- Basic Brush latency unchanged within measurement noise.
- New material state can be disabled at zero allocation/cost for ordinary dry brushes except trivial branch/config overhead.

---

## 6. Phase 1 — Pigment-like colour mixing

**Goal:** achieve the first large perceptual win with the smallest new state surface.

### Strategy

Start with a fast latent/LUT pigment mixer rather than full spectral Kubelka-Munk evaluation per pixel.

The public contract should be something like:

```text
mix(materialA, materialB, ratio) -> material/display colour
```

The implementation may initially use:

- a Mixbox-style latent mapping;
- a compact custom LUT calibrated from artist-selected primaries;
- another bounded subtractive approximation.

The architecture must allow later spectral/K-M experiments without changing stroke commands or UI models.

### Integration

1. Add pigment mixing as an optional Color Smudge/material interaction mode.
2. Implement a pure CPU reference.
3. Implement the Vulkan shader equivalent.
4. Keep standard RGB/legacy blending available for existing brushes and imported presets.
5. Do **not** automatically map arbitrary RGB colours to fixed historical pigments with hidden mechanical properties.

A media profile may explicitly say `Titanium White`, `Ultramarine`, etc. later; plain colour selection remains plain colour selection.

### Validation

Golden tests should include at least:

- yellow + blue;
- cyan + red;
- high-chroma complements;
- black/white tinting;
- repeated mixing order;
- ratio endpoints 0/1;
- CPU/Vulkan error bounds.

### Exit gate

- Pigment mode visibly avoids the worst RGB interpolation artefacts.
- GPU result stays within agreed tolerance of CPU reference.
- No full-canvas pass is introduced; only touched regions participate.
- Existing brushes remain pixel-compatible when pigment mode is off.

---

## 7. Phase 2 — Stateful brush reservoir, depletion and pickup

**Goal:** make a stroke evolve because the brush carries finite material.

This is the first phase where the paint engine stops behaving like an infinite stamp source.

### Model

Per active stroke maintain:

- initial load/charge;
- remaining load;
- carried material colour;
- dilution/wetness;
- pickup contamination;
- optional per-sub-tuft load later.

Per dab/contact step:

1. determine contact area;
2. deposit a bounded fraction of reservoir material;
3. sample existing wet material under the footprint;
4. pick up a bounded fraction into the reservoir;
5. mix carried material using the Phase 1 mixing model;
6. update reservoir load and wetness;
7. emit changed material only into dirty tiles.

### Convergence rule

This must extend the existing Color Smudge/wet-mix pipeline. Do not create a second paint-pickup system on `AzphaltBrush` that competes with Color Smudge semantics.

### Replay decision

Reservoir evolution should be deterministic from:

- stroke samples;
- brush/media settings;
- stroke seed;
- canvas material state at stroke start/interaction.

For undo/redo, tile deltas remain the preferred fast path where available. Canonical replay must still produce the same material result.

### User-facing controls

Start minimal:

- **Load** / Charge;
- **Pickup**;
- **Dilution** or Wetness.

Advanced derived controls stay hidden until needed.

### Exit gate

- A long stroke visibly depletes.
- Crossing a wet differently coloured stroke visibly contaminates subsequent output.
- Same stroke + same starting state reproduces identically.
- Latency remains bounded by incremental dirty work, not stroke duration.

---

## 8. Phase 3 — Substrate-aware deposition and dry-brush breakup

**Goal:** produce convincing scumble/dry-drag behavior without simulating individual fibres.

### Reuse existing systems

Graffux already has tip grain/texture concepts. Extend these into a **substrate field** rather than inventing an unrelated canvas-noise feature.

A substrate profile may provide:

- height/tooth texture;
- absorbency scalar/texture;
- optional anisotropy/fibre direction later.

### Deposition rule

A contact footprint receives a local contact depth derived from pressure, brush geometry and optional splay.

Pigment deposits only where:

```text
contactDepth >= substrateHeight - localPaintHeightContribution
```

The exact normalized equation can evolve, but the result should naturally create:

- crest-only scumble under light contact;
- fuller coverage under pressure;
- intermittent parallel marks for split/coarse tips;
- paint filling valleys as material height builds.

### Exit gate

- The same brush produces measurably different marks on smooth vs rough substrate profiles.
- Pressure changes substrate penetration rather than merely opacity/size.
- Dry-brush behavior arises from load + contact + substrate, not only a random alpha mask.
- Substrate sampling remains GPU-local/static and does not create per-frame CPU texture work.

---

## 9. Phase 4 — Wetness field and bounded local transport

**Goal:** introduce wet-in-wet behavior and post-deposition evolution without adopting full Navier-Stokes/FLIP.

### State

Add an optional wetness channel to material tiles.

Wetness controls:

- pickup eligibility;
- pigment mobility;
- local spread radius/rate;
- optical gloss later;
- drying transition.

### First solver

Use a bounded local approximation:

- dirty-tile compute only;
- short-range diffusion/advection proxy;
- conservation-aware transfer where practical;
- a fixed small iteration budget;
- activity stops below a wetness threshold;
- drying decays wetness over time or on discrete simulation ticks.

Avoid a full pressure-projected fluid solver initially.

Possible first-order update:

```text
mobility = wetness * medium.flowResponse * f(localShear)
colour/material transfer = neighbourGradient * mobility * dt
wetness -= dryingRate * dt
```

This is intentionally phenomenological. It earns replacement by a more literal solver only if artists can identify a specific failure the approximation cannot fix.

### Scheduling

Time-dependent material simulation must not become an always-running whole-canvas process.

Maintain an **active material tile set**:

- only recently touched/wet tiles tick;
- tiles leave the active set when wetness/activity falls below thresholds;
- simulation cadence may be lower than display refresh;
- multiple elapsed ticks may be analytically/coarsely integrated after inactivity rather than replayed one-by-one.

### Exit gate

- wet strokes soften/mix locally after contact;
- dry strokes remain static;
- cost scales with active wet tiles, not canvas dimensions;
- an idle dry document incurs effectively zero material simulation work;
- deterministic tests can advance material time explicitly rather than depending on wall-clock scheduling.

---

## 10. Phase 5 — Impasto v2: height + rheology-like settling + material optics

**Goal:** evolve the existing Impasto height map into a first-class material channel.

This is an extension of current `ImpastoEngine`, not a rewrite from zero.

### Height evolution

Add optional behavior for:

- deposition from reservoir volume/contact pressure;
- pickup/removal by brush contact;
- limited leveling while wet;
- yield-like freeze below a movement threshold;
- substrate interaction;
- dirty-region/tile updates.

A single `structure` or `mobility` scalar may approximate thixotropic recovery:

```text
under shear/contact: structure decreases → easier leveling
at rest: structure recovers → material freezes
```

Do not introduce a volumetric voxel field unless the height representation demonstrably fails.

### Optical rendering

Extend relief shading so material appearance responds to:

- height-derived normal;
- wetness-driven roughness/specular strength;
- optional media profile base roughness;
- existing layer colour/pigment result.

Wet paint should read glossier; drying should transition toward the medium's matte/rough state.

Lighting remains presentation state and must not alter canonical material data.

### Persistence requirement

Implemented Phase-5 persistence keeps `Layer.heightMap` as a transient runtime mirror while storing canonical height + wetness in a versioned sparse/tiled per-layer sidecar. Empty/color-only layers write no sidecar. The sidecar is restored with the layer, participates in normal save/flush behavior, and is included by project archive export/import. Yield/structure recovery is derived from persisted wetness, so no additional structure image is required.

### Exit gate

Current implementation status (2026-09-19): **code-side exit gate met**.

- ✅ Existing Impasto brushes retain their current visual contract; v2 is enabled only by an explicit versioned material configuration.
- ✅ Height deposition/pickup is deterministic and reservoir-bounded.
- ✅ Wetness changes surface roughness/specular presentation without changing stored pigment colour.
- ✅ Save/reload preserves canonical height + wetness through the versioned material sidecar.
- ✅ Live material presentation reshades only the touched region; time-based leveling runs in deterministic commit/replay rather than the display-batch hot path.
- ✅ Canonical material undo/replay includes height, wetness, and spatial medium ownership rather than relying on pixel-only deltas.
- ✅ Material-response ownership is spatial and versioned in the sidecar; ownership changes only on real deposition and is restored/duplicated with the layer.
- ✅ Soft selection weights material deposition with compact alpha coverage, so feathered visible paint and canonical height/wetness agree without large duplicate full-canvas float buffers.
- ✅ Impasto never reconstructs pigment by inverting relief/specular presentation. Recorded-time evolution advances height/wetness directly; Color Smudge remains the pigment-transport owner.
- ✅ Layer-content replacement clears stale canonical material state, failed material writes remain retryable, and persisted monotonic uptime starts a fresh replay epoch after restore.
- ✅ Live preview consumes the same spatial material-owner state as authoritative commit/replay.

Representative Adreno/Mali performance and artist-reference validation remain part of the cross-phase production validation matrix described below; they do not require another Impasto backend.

---

## 11. Phase 6 — Coarse deformable tuft

**Goal:** make brush morphology respond to pressure, angle, load and movement without simulating thousands of hairs.

### First model

Represent a brush as a small number of **bristle bundles/sub-tufts** (for example 8–32 depending on capability and brush type), each with:

- root position in ferrule-local space;
- rest direction;
- stiffness;
- tip/contact point;
- local load fraction;
- cohesion to neighbouring bundles.

A lightweight solver or even an analytic spring/contact approximation produces:

- radial splay under pressure;
- directional bending with motion;
- flat/round/filbert footprint differences;
- load-dependent cohesion;
- progressive splitting as the reservoir dries;
- snap-back on lift.

### Algorithm policy

Start with the cheapest model that produces the required footprints.

Possible progression:

1. analytic footprint deformation;
2. spring/bundle constraints;
3. PBD bundle solver;
4. only then evaluate denser bristle simulation.

The rest of the engine sees only `BrushContactState`; it does not care which solver produced it.

### Exit gate

- flat, round and filbert brushes have recognizably different pressure/angle behavior;
- wet loaded brushes cohere more than dry depleted brushes;
- dry splitting creates structured striation rather than pure random noise;
- solver cost is bounded per dab/frame and has device capability tiers;
- disabling deformable tuft returns to the existing static footprint path.

---

## 12. Phase 7 — Media profiles and artistic controls

**Goal:** make the physics useful to artists rather than merely technically impressive.

### Profiles

Ship semantic starting points such as:

- Heavy Oil;
- Soft Oil;
- Acrylic;
- Gouache;
- Watercolour;
- Ink;
- Dry Bristle;
- Marker-like legacy/dry digital mode.

Profiles configure the material engine but remain editable.

### Important UX rule

**Colour is not medium.** Choosing white does not secretly force titanium-white viscosity. Choosing magenta does not secretly make paint runny.

Optional named pigment libraries may later bundle optical pigment data with physical defaults, but the user must enter that semantic mode deliberately.

### Exposed controls

Prefer a small artistic vocabulary:

- Load;
- Wetness/Dilution;
- Flow/Body;
- Pickup;
- Drying;
- Thickness;
- Texture interaction;
- Brush stiffness/splay.

Internally these may map to many engine coefficients.

### Exit gate

- a useful media preset can be selected without opening advanced controls;
- advanced behavior remains editable without permanent screen occupation;
- default existing brushes remain familiar.

---

## 13. Research tracks — explicitly not on the critical path

These are valid experiments only after the fidelity ladder exposes a concrete limitation.

### R1. Full spectral / Kubelka-Munk mixing

Prototype only if latent/LUT mixing cannot meet visual goals.

Questions to answer before adoption:

- how many spectral bands are actually needed;
- mobile bandwidth/storage cost;
- conversion cost to display colour;
- how layer compositing interacts with spectral state;
- whether artists can perceive the improvement over the fast mixer.

### R2. Full PBD/DER individual-bristle simulation

Prototype only if the coarse tuft cannot reproduce desired splay/splitting/contact marks.

Success must be judged against visible strokes and frame budget, not physical elegance.

### R3. FLIP/PIC or pressure-projected fluid solver

Prototype only if local wetness transport cannot reproduce required wet-in-wet behavior.

A full solver must prove:

- a clear perceptual improvement;
- bounded active-region cost;
- acceptable mobile GPU occupancy;
- deterministic/replay strategy;
- integration with undo/save state.

### R4. Porous-paper capillary/evaporation model

A dedicated watercolor research path may later add:

- directional fibre transport;
- edge darkening;
- granulation;
- backruns/blooms;
- absorbency/sizing profiles.

This should remain a specialized aqueous-media solver, not mandatory overhead for oil/acrylic brushes.

### R5. 3D Gaussian-splat / photogrammetric brush material

Treat as an experimental rendering/content-generation technique, not part of the core paint architecture. It has no current critical-path dependency.

---

## 14. Data, undo and persistence

Physical-looking paint is useless if undo/replay/save cannot reproduce it.

### Stroke commands

Stroke history should continue to record **intent + deterministic configuration**, not giant per-stroke simulation dumps, wherever replay remains practical.

A material-aware stroke command may need to snapshot:

- media profile/version;
- material coefficients actually used;
- initial reservoir state;
- deterministic random seed;
- brush/contact solver version if behavior changes across app versions;
- explicit simulation-time parameters where relevant.

### Tile deltas

Material channels extend the existing tile-delta idea:

- colour delta;
- height delta;
- wetness/structure delta when those states are undo-visible.

Undo should restore a coherent material tile atomically rather than colour first and height later.

### Persistence

Project persistence needs a versioned material-channel representation before time-evolving media is production-ready.

Prefer sparse/tiled storage so a mostly dry/flat layer does not pay for full extra float images when unused.

Compatibility principle:

> Old colour-only documents remain colour-only documents until an explicit material feature is used.

---

## 15. Performance budgets and capability tiers

No phase is complete merely because it works on one high-end phone.

### Required device classes

At minimum validate representative:

- Adreno high tier;
- Adreno mid tier;
- Mali high/mid tier;
- fallback/no-supported-native-feature path where relevant.

### Budget policy

Do not hard-code one universal millisecond number in this document before physical-device baselines are collected. Instead preserve these relative requirements:

- input processing remains comfortably below frame budget;
- material simulation cannot create an ever-growing queue;
- active material work is bounded by dirty/active tiles;
- material features may lower preview simulation cadence before they increase latency;
- expensive fidelity may degrade by capability tier (fewer tuft bundles, fewer diffusion iterations, lower material-tile resolution);
- canonical pointer-up reconciliation may do more work than preview, but must not silently change the visible committed result.

Each phase must add physical Adreno/Mali benchmark data to the validation record before being called production-complete.

---

## 16. Recommended implementation order

The dependency order is intentional:

```text
Phase 0  Material scaffolding + fixtures
   ↓
Phase 1  Pigment-like mixer
   ↓
Phase 2  Reservoir / depletion / pickup
   ↓
Phase 3  Substrate contact / dry brush
   ↓
Phase 4  Wetness / bounded transport
   ↓
Phase 5  Impasto v2 / wet-dry optics
   ↓
Phase 6  Coarse deformable tuft
   ↓
Phase 7  Media profiles / product polish
```

Why this order:

- pigment mixing gives immediate visible value with little state;
- reservoir state makes strokes evolve before adding a canvas fluid model;
- substrate interaction creates convincing dry media cheaply;
- wetness then adds spatial/temporal state on top of already-useful deposition/pickup;
- current Impasto can then consume the same reservoir/wetness semantics rather than inventing its own;
- tuft deformation arrives after the material system can actually react to the contact geometry it produces.

Do **not** begin with FLIP/PIC or thousands of PBD bristles. Those are possible later implementations, not foundation work.

---

## 17. Historical first development tranches — completed

The roadmap began with the two intentionally small tranches below. Both are now landed; this section is retained as design history and as the compatibility rationale for building persistent material state incrementally rather than starting with the expensive simulation phases.

### Tranche A — material colour kernel

1. Define `MaterialColor`/mixing interface in `core/engine`.
2. Implement a CPU pigment-like mixer with deterministic tests.
3. Add legacy RGB and pigment modes as explicit alternatives.
4. Add the equivalent Vulkan mixing helper/kernel.
5. Wire pigment mixing into Color Smudge only behind an experimental/media flag.
6. Add CPU/Vulkan parity instrumentation.
7. Add 5–10 artist-relevant golden mixing cases.

**Historical scope rule:** at this tranche, nothing else changed yet — no wetness field, PBD, or project-format migration. Later phases have since implemented the bounded wetness and material-persistence work described above.

### Tranche B — reservoir prototype

After Tranche A, the roadmap proceeded with:

1. add stroke-local reservoir state;
2. add load/depletion only;
3. verify deterministic long-stroke behavior;
4. then add pickup/contamination from Color Smudge samples;
5. expose only Load/Pickup in experimental controls.

These two tranches were the original test of the roadmap's core thesis: **does persistent material state make Graffux feel materially different from a conventional stamp engine?** They passed that architectural checkpoint and the implementation subsequently progressed through substrate, persistent wetness, and Impasto v2.

---

## 18. Definition of success

The roadmap succeeds when an artist can identify media behavior from the stroke without needing to know which simulation algorithm produced it.

A successful material-aware Graffux stroke should be able to demonstrate all of the following while remaining responsive:

- it begins loaded and can run dry;
- it can pick up and carry existing colour;
- blue/yellow wet mixing behaves more like pigment than RGB interpolation;
- light dry contact catches substrate peaks;
- pressure changes contact/deposition, not only diameter;
- wet regions remain mobile while dry regions stop costing compute;
- thick paint creates persistent height and believable surface lighting;
- the same recorded stroke reproduces the same committed material state;
- low-end/fallback devices remain usable;
- none of this requires turning the Graffux phone UI into a desktop paint laboratory.

That is the target. Full physical accuracy is not.
