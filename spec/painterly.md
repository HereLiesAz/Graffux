# Painterly realism — `spec/painterly.md`

Status: **design only, owner decisions incorporated** (section 10). Nothing here is implemented. This file audits what the engine does today,
specifies the extensions that would make a Graffux brush stroke behave like a loaded paintbrush, a
complete painter's brush set to ship as azphalt asset packs, and a palette that replaces the HSV
colour wheel as the primary way to choose paint. It changes no engine, UI or azphalt code.

Companion documents: [`stroke-program.md`](stroke-program.md) (the contract this spec extends),
[`package-format.md`](package-format.md) (asset packs), `docs/Material-Aware Paint Engine Implementation Status.md`
(what has landed in the material and mechanics work), `docs/Brush Stroke Mechanics Roadmap.md`.

Every claim about the current code cites `file:line` on `origin/main` at the commit this branch was cut
from (`8e422af5`). `E/` abbreviates `core/engine/src/commonMain/kotlin/com/hereliesaz/graffitixr/common/azphalt/`.
Things I could not verify are listed in section 9; do not treat them as fact.

## 0. Direction and rules

The owner's direction, as given: the app should feel like real painting. Familiarity with other
digital painting apps is not a goal; realism in mechanics and effect is. The app should only feel
digital when it lets you do something impossible in real life.

Consequences this spec holds itself to:

1. **Mechanics before menus.** A behaviour is modelled as the physical thing (paint on a brush, a
   mixture on a palette, a wet film on a canvas), not as a slider that imitates its result.
2. **Digital only where physics cannot go** (section 6.4 lists these and why each earns its place).
3. **No parity checklist with other apps.** Where this spec borrows a word (flow, opacity) it
   defines it physically or drops it.
4. **Existing decisions stand** unless section 10 asks the owner to change one. The ones that bind
   this work:
   - "Brushstroke mechanics wins over material work unless material work is required to preserve
     correctness" (status doc, header).
   - "Colour is not medium": selecting a colour never silently assigns viscosity or drying
     (status doc, Phase 7; `PaintMediaProfile` kdoc at `E/PaintMediaProfile.kt:14-22`). Section 6
     keeps this by splitting a tube into a *pigment* (optical) and a *medium* (binder).
   - "Renderers remain dumb consumers of resolved geometry" (`E/BrushTuftDabExpander.kt:3-14`;
     status doc, M6). Geometry and per-dab scalars stay resolved in Kotlin; the GPU now also owns material *transactions* on
     its resident fields (relaxed explicitly in 3.0 and 3.1 principle 3).
   - Full spectral / Kubelka-Munk mixing was "research only" (status doc, Phase 1). **Lifted by the
     owner** (section 10, decision 3): Kubelka-Munk is adopted and applies to the canvas as well.
   - The existing packs keep their ids and licences until the owner-directed removal of superseded
     brushes (section 5.6). New work ships as new package ids (section 5.5).

### 0.1 The reference behaviour, decomposed

The reference is a traditional acrylic petal compared with a digital brush set. I could not view the
Reel; this decomposition is from the owner's description (section 9). One pull of a loaded
flat or round brush makes a petal:

| # | Behaviour | Physical cause |
|---|---|---|
| R1 | Width swells with pressure, then tapers to a point on lift | Bristles splay under load, then close as the brush leaves the surface |
| R2 | Two-tone load (red rim, pink body): colour varies across the stroke width | The brush was dipped or double-loaded, so different bristle groups carry different paint |
| R3 | Bristle streaks run along the stroke | Individual bristles lay individual lines, spaced by the gaps between them |
| R4 | Wet blending / pickup where strokes overlap | Wet film on the surface mixes with, and is lifted by, the incoming brush |
| R5 | Thick-paint ridge with a specular edge highlight | Paint is pushed to the stroke's edges as a raised bead that catches light |

## 1. Audit — engine and stroke-program system

Status key: **supported** (works, shipped), **partial** (works for part of the need, or only by
authoring workaround), **missing**. Effort: S under a day of focused work, M a few days, L a week
or more, XL a multi-week phase. Effort covers engine work plus tests, not pack authoring.

| Behaviour | Status | Evidence | What is and is not there | Effort to complete |
|---|---|---|---|---|
| Pressure to width | supported | `E/BuiltInBrushes.kt:43-48` (Round binds pressure to size); `E/BrushSensorDynamics.kt:150-180` (sensors and targets) | Any sensor can drive size, opacity, flow, spacing, scatter, rotation, hue/sat/value, tip ratio. Curves are supported (loaded-hair Rigger uses a 3-point curve). | none |
| Taper in on touchdown | supported | `E/AzphaltBrush.kt:48-60` (`BrushTaper.startLengthPx/minSize/minOpacity`) | Start taper only. | none |
| Taper out on lift (R1) | partial | `E/AzphaltBrush.kt:41-47` (`endLengthPx`, `liftOffSynthesizesPressure` are retired and ignored: an end taper needs the final stroke length, which a live stroke does not have). `spec/stroke-program.md` "Time ticks and lift-off" (`phase` 3 runs once at lift-off). `E/StrokeLengthPredictor.kt:1-30` predicts length but is used only for pressure-fade approximation. | A real pen's falling pressure tapers a stroke today. A finger (pressure 1.0) cannot. A program can `emit` a tail at `phase == 3`, because `emit` takes arbitrary `x`,`y`, but no shipped pack does and no test pins it (`StrokeProgramTest.kt` has no `phase`/lift reference). | S (spec a causal "lift tail" idiom and test it) |
| Per-bristle simulation | partial | `E/BrushTuftTopology.kt:31-66` (`BrushTuftConfig`); `E/BrushTipTopology.kt:17,34-48` (`BRISTLE` tip kind, `bristleDiameterPx`, `packingFraction`, `mechanicalBundleDiameterPx`); `E/BrushTuftDabExpander.kt:20-60`; status doc "Physical bristle/bundle population law" | Deterministic 2-D population of fixed-diameter bundles with damped bend, lag, hysteretic split/rejoin, reversal, touchdown and lift. Bundles are *coarse* (default 5.5 px) and capped by tier (16/32/96, `E/BrushPerformancePolicy.kt:10-21`). Status doc lists "per-sub-tuft reservoir load" as pending. | M (per-bundle load, see E2) |
| Per-bristle programs | supported | `submissions/com.hereliesaz.azphalt.kinetic.painterly/manifest.json` (Heavy Oil: `n` lanes via `emit` in a `for i in 0..24` loop, each with its own `noise`/`hash` identity) | A program can fake a bristle comb entirely in script, at up to 256 emits per step (`E/StrokeProgram.kt:124-126`). It does not share state with the physical tuft model. | none (reuse) |
| Multi-colour load across width (R2) | partial | `E/AzphaltBrush.kt:28-32` (`BrushColorSource.GRADIENT`), `E/ArgbColor.kt:22-27` (`lerp(base, secondary, dab.colorMix)`); `E/StrokeProgram.kt:149,995` (`emit { mix: }`); `EditorViewModel.kt:8241` (secondary colour) | Exactly two colours (foreground and secondary), blended in **RGB** (`ArgbColor.kt:41-47`), selectable per emitted dab with `mix`. A lane-wise red-rim/pink-body is therefore authorable now, but only with the app's two colours and only with RGB interpolation. No third colour, no per-lane pigment, no load that runs out unevenly. The tuft expander copies the parent colour: `BrushTuftDabExpander.kt:36-57` has no colour term. | M (E1, E2) |
| Bristle streak texture along path (R3) | supported | Same Heavy Oil / Matte Gouache program; `E/AzphaltBrush.kt:261-268` (`grainPath`, `GrainBehavior.CANVAS_LOCKED`); `E/SubstratePreset.kt:18-40` | Lane dabs plus noise give streaks that persist along the path. Canvas-locked grain gives paper tooth. | none |
| Paint-load depletion over stroke length | supported (script) / partial (physics) | Heavy Oil `load -= stepPx / (9*d0 + 150) * ...`; `E/BrushReservoirModel.kt:35-52` (`stateAtDistance`, exponential in distance) ; `E/BrushReservoirModel.kt:68-` (`transfer`) | Two unconnected mechanisms. Program `load` is a scalar the script owns and only changes alpha. The reservoir model is stroke-level, drives Color Smudge only, and has no per-lane or per-colour content (status doc: "Per-sub-tuft reservoir load" pending). Neither carries paint colour. | M (E2) |
| Wet pickup / smear of underlying colour (R4) | partial, split across two systems | **Programs:** `E/StrokeProgram.kt:829-841` (`spray`, `drain`, `wetAt`); `E/PaintLedger.kt:24,48,51` (8 px cells, 45 s dry). **Colour Smudge:** `feature/editor/.../util/ColorSmudgeEngine.kt:61-122` (`Settings`), `core/wgpu-engine/src/shaders/color_smudge.wgsl`, `core/wgpu-engine/src/shaders/wet.wgsl`; `E/PersistentWetnessField.kt:19-72`; `E/WetMaterialTransport.kt:24-31` | Programs see wet *thickness only*, as history of previous *spray* strokes on the layer; they cannot read colour (`spec/stroke-program.md` "Wet paint": "Programs cannot read the canvas"). Colour pickup exists, with reservoir carry and pigment mixing, but only as the separate Color Smudge tool: `AzphaltBrush` has no smudge field (grep of `E/AzphaltBrush.kt` finds none), so a pack brush cannot opt in, and a brush that both lays bristle lanes and picks up colour cannot be authored. | L (E3) |
| Subtractive pigment mixing vs RGB blend | partial | `E/MaterialColor.kt:14-19,59-100` (`LEGACY_RGB`, `PIGMENT_RYB`); WGSL port `core/wgpu-engine/src/shaders/color_smudge.wgsl:108-140`; status doc Phase 1 | RYB interpolation, two colours at a time, opt-in, **Color Smudge only**. `MaterialColor.kt:59-62` states it is "not a Kubelka-Munk/spectral claim". It has no notion of tinting strength, hiding power or white. Stamp compositing and ordinary overlap are plain RGB `composite()` (`stamp.wgsl:62-80,152-170`). | L (E4) |
| Impasto height | supported | `E/ImpastoEngine.kt:40` onward (`deposit`, `depositStroke`, `transferMaterialStroke` :224, `levelWetHeight` :453); `impasto.wgsl:62` (`deposit`); `E/AzphaltBrush.kt:144-170` (`ImpastoMaterialConfig` v2); painterly pack "Heavy Oil" and "Impasto Knife" | Height deposits from load and contact, picks up, levels while wet, persists in the material sidecar. | none |
| Impasto ridge at the stroke edge (R5, shape) | missing | `grep -i "displace\|push\|rim\|ridge\|squeeze"` on `E/ImpastoEngine.kt` and `E/WetMaterialTransport.kt` returns only a doc-comment hit | Height is deposit minus pickup at the dab's own footprint. Nothing moves paint sideways out of the centre of a pass into a rim, which is what makes a flat brush leave two ridges and a trough. A program could fake it by giving edge lanes more height, but `emit` has no height field (`E/StrokeProgram.kt:149`), so it cannot. | M (E5) |
| Specular edge highlight (R5, light) | partial | `E/ImpastoEngine.kt:115` (`shade`, diffuse only), `:591-659` (`shadeMaterialInto`, `specular = wetSpecularStrength * wet * ...`), `impasto.wgsl:95-111` | Diffuse relief shading everywhere. A specular term exists but is gated by *wetness*, so a dried bead loses its highlight. Real dry acrylic stays glossy. No tight highlight tied to the height gradient. | S-M (E5) |
| Dry brush / scumble | supported | `E/SubstrateDeposition.kt:18` (`SubstrateProfile`); status doc Phase 3 ("crest/tooth scumble and dry breakup under light contact"); painterly "Matte Gouache" (`thr`/`on` lane breakup); `AzphaltBrush.substrateResponse` (:334) | Works from tooth against contact depth, and from load in programs. | none |
| Canvas / paper tooth | supported (dry) / partial (wet) | `E/SubstratePreset.kt:18-60` (Smooth, Cold-press, Canvas weave, Brick); `E/SubstrateDeposition.kt:15-21` | Static canvas-locked tooth, deposit gating, valleys fill. `absorbency` is carried but "specialized absorbency-driven wet behavior is intentionally not fabricated" (status doc Phase 3, `SubstrateDeposition.kt:16`). No watercolour wicking into paper. No sizing/ground. | M (E7, watercolour only) |
| Hair splay / flat-brush angle vs stroke direction | supported | `E/BrushContactModel.kt:21-55` (`BrushContactConfig`: stiffness, drag, maxLag, dragSplay, pressureSplay); `E/BrushTuftTopology.kt:20-27,225-346` (ROUND, FLAT, FILBERT, RIGGER, FAN, RAKE identities); `E/AzphaltBrush.kt:269` (`followStroke`); program input `penAngle` (`spec/stroke-program.md` Inputs) | Flat brush keeps a ferrule frame, drags and lags behind a turn, splits and rejoins on reversal. A real flat's *twist* (rolling the handle to turn the edge) maps to device roll where the device reports it. | none (tune) |
| Rotation by pen tilt / azimuth | supported where the device reports it | `E/BrushSensorDynamics.kt:153-154,372` (TILT, ORIENTATION sensors); `E/BrushDeviceAttitude.kt:35,63,186` (confidence-gated); program inputs `tilt`, `azimuth`, `penAngle` | Missing axes are never fabricated (status doc section B). Finger gets touch-ellipse evidence only. | none |
| Determinism and replay | supported | `spec/stroke-program.md` "Where it runs", Limits; `E/StrokeProgram.kt:124-126`; `MaterialGoldenFixtureMatrixTest.kt` | Same samples plus same seed give the same dabs. | none |

**Correction (after review).** The wet pickup and mixing rows above describe *brush-visible* behaviour.
Underneath, the Impasto v2 wet simulation and persistent-wetness Color Smudge already run on the GPU
(`GpuWetSimulation.kt:1-8`, `GpuWetSmudge.kt:1-20`, `core/wgpu-engine/src/wet.rs`), with the Kotlin
classes as reference oracles. The status doc's "persistent-wetness Color Smudge uses the CPU
correctness path" is out of date. See 3.0.

### 1.1 Summary of the audit

- R3 (streaks) and most of R1 (pressure swell) are **already achievable** with the existing
  `emit`-lane technique; the painterly pack proves it.
- R2 (two-tone load) is **partly achievable** with `GRADIENT` and `mix`, but is limited to two RGB
  colours. A convincing version needs the load to be real state, which is E1 plus E2.
- R4 (wet blending) is **split across two systems that cannot be combined**: colour pickup lives in
  the Color Smudge tool, wet thickness lives in programs. This is the largest structural gap.
- R5 (ridge and highlight) is **missing**, but small: one new dab field and one shader term.
- Taper on lift is **blocked by design** for finger input, and **authorable** for the lift tail.
- Pigment-accurate mixing is **missing**; RYB is a placeholder, and nothing models white or tinting.

### 1.2 Existing packs (inventory, for coordination)

Source: `/home/user/azphalt/submissions/` (read-only for this task). All are `kind: "asset"`,
`targetApps: ["com.hereliesaz.graffux"]`, version 1.0.0.

| Pack id (suffix) | Licence | Brushes | Relevance |
|---|---|---|---|
| `brushes.loaded-hair` | CC0-1.0 | Square Flat, Rigger, Fan Softener, Scrub, Glaze Mop, Trowel | Physical bristle morphologies; no programs |
| `kinetic.painterly` | LicenseRef-Proprietary | Heavy Oil, Matte Gouache, Compressed Charcoal, Cauliflower Bloom, Impasto Knife | Closest to this work; programs plus `impastoMaterial` v2 |
| `brushes.dry-goods` | CC0-1.0 | Graphite, Vine Charcoal, Sidewalk Chalk, Oil Pastel | Dry media |
| `brushes.line-and-nib` | CC0-1.0 | Brush Pen, Fineliner, Italic Nib, Dry Pen, Ballpoint | Line |
| `brushes.street-kit` | CC0-1.0 | Needle Cap, Spit Cap, Overspray, Nap Roller, Mop Marker | Street |
| `kinetic.{calligraphy,depletion,engraver,gravity,noir-ink,print-shop,spray-caps,street-and-mural,voltage,weave,wild-growth}` | LicenseRef-Proprietary | 5 to 18 each | Programs; `depletion` and `gravity` are relevant to load and flow |

No manifest in the repository carries a price field (a scan of those manifests found none), and the
paid-package mechanism exists in the repository API (`/home/user/azphalt/spec/repository-api.md`,
`priceStatus: "paid"`, entitlement tokens, Play purchase exchange) and in this host's purchase flow
(`spec/store-app.md` "PurchaseCoordinator"). Whether the existing proprietary-licence sets are
priced is not recorded anywhere I could read; the owner has said they are free (section 9).

## 2. Audit — current palette and colour picker

| Aspect | Finding | Evidence |
|---|---|---|
| Primary tool | An HSV disc plus brightness slider, an opacity slider and a hex field | `feature/editor/.../SketchToolsDialog.kt:70-130` (decomposes the working colour to HSV with `android.graphics.Color.RGBToHSV`), `:189-231` (`ColorWheel`), `:269` (`color.alpha`), `:277-313` (`HexField`) |
| Modes | Disc, Harmony, Palettes, "mirroring Procreate's Disc / Harmony / Palettes tabs" | `SketchToolsDialog.kt:51-53`; harmony partners from hue (`:131`) |
| What a colour is | One ARGB int. No pigment identity, no opacity-vs-tint distinction, no mixture | `core/common/.../model/EditorModels.kt:726-728` (`activeColor: Color`, `secondaryColor: Color`) |
| Palette storage | Ordered list of ARGB ints, max 120, comma-joined hex in one preference string | `core/common/.../util/PaletteCodec.kt:16-48`; `RecentColors.kt` (12 recents) |
| Palette semantics | A swatch is a colour to select. Nothing is mixed, nothing depletes, nothing is named | `PalettesTab`, `SketchToolsDialog.kt:360` |
| Eyedropper | Present in the editor | `EditorViewModel.kt`, `DrawingCanvas.kt` (an eyedropper intent exists; touch-and-hold opens it per `spec/stroke-program.md` "Time ticks") |
| Mixing | None in the picker. Material mixing exists only inside Color Smudge (`PIGMENT_RYB`) | `E/MaterialColor.kt` |
| Accessibility present | Swatch content descriptions with hex (`SketchToolsDialog.kt:161,450`); 48 dp swatch targets (`:145,442`); opacity slider has a description (`:267`) | as cited |
| Accessibility gaps | Hue is the only primary axis (no non-colour cue); no colour-name readout beyond hex; no colour-vision-safe affordance; wheel is 240 dp fixed and drag-only | `:195` |
| Colour spaces | Everything is sRGB ARGB 8-bit. Dab colour is resolved by RGB lerp then an HSV shift (`E/ArgbColor.kt:22-38`) | as cited |
| Persistence of "paint on the brush" | None. A new stroke always starts from `activeColor` at full load | `E/BrushReservoirModel.kt:35` (load restarts at 1 per stroke) |

In short: the picker models a **selected colour**. It does not model paint.

## 3. Engine capabilities (extensions to the stroke-program contract)

### 3.0 Where each computation runs (CPU/GPU split)

Amended after the owner asked "why is it using CPU to do wetness?". The rule is:

> **Per-dab scalars are Kotlin. Per-pixel fields that are read, written or evolved over time are
> GPU (wgpu) compute passes, each with a Kotlin reference that is the test oracle.**

What the repository already does (read from source): the Impasto v2 wet material simulation is a
wgpu simulation, `GpuWetSimulation` ("there is no CPU path: the wet stage then fails",
`core/nativebridge/.../GpuWetSimulation.kt:1-8`), with passes TRANSFER, DEPOSIT, PIGMENT, ADVANCE,
LEVEL, SHADE and GPU checkpoints (`:56-70`, `core/wgpu-engine/src/wet.rs`, `shaders/wet.wgsl`).
Persistent-wetness Color Smudge also runs on that simulation (`feature/editor/.../GpuWetSmudge.kt:1-20`).
The Kotlin `WetMaterialTransport`, `PersistentWetnessField` and `ImpastoEngine.transferMaterialStroke` are the
reference implementations the Rust tests compare against (`core/wgpu-engine/tests/wet.rs`,
`cpu_transfer`, `cpu_level`, `cpu_advance`). The status doc's sentence that persistent-wetness smudge
"intentionally uses the CPU correctness path" (Phase 4) predates `GpuWetSmudge` and is out of date.

This spec's first draft contradicted that by putting wet sampling and pickup in a Kotlin "probe". That
is withdrawn. The audit of every place it assigned wetness, pickup or mixing to the CPU:

| Where (first draft) | What it did on the CPU | Verdict | Now |
|---|---|---|---|
| 1 audit rows (wet pickup, mixing) | Describes today's code | Accurate, but must say Impasto v2 wet is already GPU | Corrected in 1 |
| 3.3 E2 brush load | Load columns evolved and read in Kotlin | Per-dab scalar bookkeeping is fine in Kotlin; the *truth* of how much paint reached the canvas is a field effect | Split: Kotlin load **budget** (prediction for authoring), GPU load **buffer** (truth) |
| 3.4 E3 `underWet`/`underMix`/`lift` | Footprint reads of a CPU copy of the layer and the wetness field, from Kotlin | **Wrong.** A footprint sample is a per-pixel field read; it forces a CPU copy or a readback of a GPU-resident layer, and the CPU copy goes stale as the field evolves | Moved to a GPU **paint transaction** pass (3.4) |
| 3.5 E4 `PigmentMixer` | Pure Kotlin | Right for per-dab mixing (brush, palette recipe); wrong for canvas-wide mixing (decision 3), which is per pixel | Kotlin reference + WGSL port + a 3-D lookup texture |
| 3.8 E7 wicking, granulation, edge darkening | "extend `WetMaterialTransport`" (CPU class) | **Wrong.** It is a time-evolving field | GPU ADVANCE-family pass; `WetMaterialTransport` stays the oracle |
| 6.3 palette board | "same engine" | Right in intent; the board is a small surface on the same GPU machinery | Stated explicitly |
| 3.1 principle 3 "renderers stay dumb" | Everything resolved in Kotlin | Too strong | Rewritten below |
| 4 rule 4 replay purity | Pure function of samples, layer state, load | Missing the GPU state that now evolves | Extended in 4 |
| 6.7 budgets | CPU-side budgets only | Missing GPU dispatch and memory | Added |

What stays on the CPU, and why each is right:

- **Program execution and `emit`.** Per-dab scalars, bounded by the existing 40,000-op budget; it must
  precede the GPU (the GPU consumes its dabs). Pure function of samples, so replay is exact.
- **The brush-load budget.** A few dozen floats per step; depletion by distance and pressure is a
  closed form (`BrushReservoirModel.stateAtDistance`). It is a *prediction* used for authoring
  decisions (a script's dry-out threshold). It never decides a pixel (3.3).
- **Pigment recipes, the eyedropper solve and lookup-table generation.** Per-colour or offline work, not
  per pixel, not per frame.
- **Reference implementations** of every GPU stage, as test oracles and for desktop tests.

What moves to, or stays on, the GPU: everything that samples or changes pixels of colour, wetness or
height: wet pickup, lift and draw sampling across a footprint, K-M mixing against the surface, glazing,
wicking, bleed, diffusion, wet-edge behaviour, film spread, drying, levelling, shading.

### 3.1 Principles for every extension

1. **Reuse the contract.** The program language, `emit`, outputs, limits and the "pure function of
   the stroke" rule (`spec/stroke-program.md` "Where it runs", "Limits") are unchanged. New
   capabilities are new *inputs*, *emit fields*, *functions* and *declared state*; never a second
   scripting mechanism.
2. **Backward compatible by omission.** Every new field defaults to the value that reproduces
   today's behaviour. A brush that declares none of the new blocks must produce byte-identical dabs
   to the current build; this is a required test (section 8.2). New `Dab` fields are **appended at
   the end of the data class with defaults**, as `contactDepth` was (`E/BrushStamps.kt:58-66`), so
   positional callers keep their meaning.
3. **Kotlin decides intent; the GPU executes material transactions.** The Kotlin generator
   (`IncrementalDynamicDabGenerator`, `E/IncrementalDynamicDabGenerator.kt:59-118`) stays the single
   place programs run, for live strokes, commits and undo replay. It resolves **geometry and per-dab
   scalars**: where, how big, how hard, which lane, how much it *intends* to draw or lift. It does not
   read or modify per-pixel fields. The wgpu engine executes the **transaction** each dab implies
   against the resident fields (sample the surface, lift, draw, mix, update wetness and height), in
   recorded dab order. This relaxes "renderers remain dumb consumers of resolved geometry"
   (`E/BrushTuftDabExpander.kt:3-14`; status doc M6) in exactly one place: the renderer now owns the
   *material* state (colour, wetness, height under the footprint) and the transaction on it. It still
   owns no *decisions*: it never chooses geometry, timing or amounts beyond clamping an intent to what the
   field can supply. Everything geometry-shaped stays as dumb as before.
4. **One law, two implementations, one oracle.** Each GPU stage has a Kotlin reference
   (`commonMain`, pure, desktop-testable) that is the specification. The WGSL is a statement-for-statement
   port (the convention already used for the RYB transform, `color_smudge.wgsl:108-140`, the stamp
   port, `stamp.wgsl:1-10`, and the wet stage, `wet.wgsl` against `wet.rs` tests). There is no CPU
   *runtime* path for a painterly layer, matching the existing wet stage; a device that cannot run wgpu
   already cannot draw (`ARCHITECTURE.md` "Retired" section). Parity tests run the real WGSL on lavapipe
   (section 8) with stated tolerances. A stage with no parity test does not ship.
5. **Determinism (extended in section 4).** No wall clock, no unseeded randomness, no unordered float
   atomics. `rand()` stays a stream seeded by the stroke; new noise is `hash`/`noise`. **Material time is
   the recorded stroke clock, quantised to a fixed timestep** (rule 4a). Material time advances from recorded
   sample uptime and is never persisted as portable time (status doc, Phase 5).
6. **Bounded cost.** Every extension counts against the existing operation budget (40,000 per step,
   `E/StrokeProgram.kt:124`) in proportion to the work done, and has a hard cap stated below. GPU stages
   have dispatch and memory caps (6.7). Budgets that exceed the cap behave as today: roll back program
   state, paint the brush's own dabs.
7. **Versioned.** Brush `params` gain `"painterly": 1` (section 3.9). A host that predates it ignores
   the field and the rest of `params` still parses, so a new pack degrades to a plain brush on an old
   build rather than failing to install.

### 3.2 E1 — Pigment recipes and per-dab intents

**Gap:** colour on a dab is an RGB base plus an HSV shift (`E/ArgbColor.kt:22-38`).

**Extension (amended for the GPU split, 3.0):** pigment colour is **recipes plus intents**, not a
colour the generator resolves.

- A **recipe** is a vector of concentrations over named pigments (`PigmentMix`, Kotlin, section 6.2).
  Recipes live where colour is chosen: the palette, the brush's `load.fill`, the eyedropper solve. The
  Kotlin `PigmentMixer` turns a recipe into a displayable colour for UI and for the brush-colour fallback.
- New `emit` **intent** fields (all optional, defaults copy the brush's dab as today): `lane` (-1..1
  coordinate across the brush width, E2), `draw` (load units to take), `lift` (amount to lift into the
  lane), `dispose` (lift and discard, 3.4), `mixWet` (0..1 upper bound on mixing with the wet surface,
  E3), `h` (height deposit, E5; implemented in P2 as `Dab.heightMul`, clamped at 0 or above).
- The generator does **not** compute the dab's pigment colour. A dab that names a `lane` carries the
  intent to the GPU paint transaction, which draws the column's actual mixture (3.4). A dab with no
  `lane` takes the old path: `resolveDabColor` over the brush colour, unchanged.
- `Dab` gains appended intent fields (`lane`, `draw`, `lift`, `dispose`, `mixWet`), all defaulting to
  "no intent". The wgpu dab layout **does** gain appended slots for them (they are real GPU inputs now);
  `layout_parity.rs` extends in the same change as the Kotlin layout (rule 4.2).

**Compatibility:** no intent fields set is an exact identity. Existing packs never set them.
**Cost:** a few floats per dab; capped at the existing 256 emits per step.
**Effort:** M.

### 3.3 E2 — Brush load as state

**Gap:** paint on the brush is a scalar surrogate inside each script (`load` in Heavy Oil) or a
stroke-level float in the Color Smudge reservoir. Neither knows what colour the paint is, or where
on the brush it sits.

**Extension:** a declared `BrushLoad` with two coordinated representations.

~~~json
"load": {
  "lanes": 16,
  "depth": 3,
  "capacity": 1.0,
  "fill": [
    { "pigment": "cadmium-red-medium", "lanes": [0, 3],   "amount": 0.9 },
    { "pigment": "cadmium-red-medium:1+titanium-white:1", "lanes": [4, 11], "amount": 1.0 },
    { "pigment": "cadmium-red-medium", "lanes": [12, 15], "amount": 0.9 }
  ]
}
~~~

- `lanes` is the resolution across the brush width, `depth` the number of layers along bristle
  length (tip layer is spent first, as on a real brush). Bounded (lanes at most 32, depth at most 4):
  at most 128 cells, each a `(12 tube concentrations + 4 canvas-origin basis concentrations, amount)` (6.2 item 4).
- **Truth (GPU).** The load lives in a small GPU storage buffer for the stroke. The paint transaction
  (3.4) draws from it and lifts into it, in dab order. At stroke end it is read back (a few hundred
  bytes) and stored with the editor session and the project, so the brush starts the next stroke with
  what it still holds (this is the same pattern as the existing reservoir slot carried between transfer
  batches, `wet.wgsl` `ctl[p.flags_off - 1]`, `transfer_batches_carry_the_reservoir` in
  `core/wgpu-engine/tests/wet.rs`). A fresh tube-load is an explicit user action (6.3).
- **Budget (Kotlin).** The generator keeps a *prediction* of each column's amount using depletion by
  distance and pressure only (no pickup), so a program can branch on "this lane is nearly dry". It
  is a pure function of the samples, so program output is deterministic. It can differ from the GPU
  truth when a lift refilled a column; that is an **authoring inexactness, not non-determinism**: the
  program's inputs are identical on every replay, and the GPU result is a deterministic function of
  the resulting dabs. Packs are written against the budget and tolerate the difference; the spec does
  not ask for the program to see GPU state (that would need a per-dab readback).
- Program functions (all deterministic, all cost-counted):
  - `loadAmount(lane)` returns 0..1, the budget's predicted amount for the column serving that -1..1 coordinate.
  - `loadMix(lane)` returns 0..1, the fraction of the column's colour that is its second pigment
    (a scalar, because the language has no vectors).
  - An `emit` naming `lane` and `draw` is an **intent**: draw up to `draw` from that column.
    The GPU colours the dab from the column's actual mixture and scales its alpha by what the
    column can supply.
- Depletion keeps the existing exponential envelope as its default law
  (`BrushReservoirModel.stateAtDistance`, `E/BrushReservoirModel.kt:35`) and exposes the rate to
  the pack, so existing packs' feel can be matched. The Kotlin reference of the GPU transaction
  uses the same law; one definition, two implementations.

**Compatibility:** a brush without a `load` block has none of this state and behaves as today.
Programs that keep their own scalar `load` variable keep working; the name does not collide
because engine load is read through functions, not a variable.
**Cost:** O(lanes) per dab on the GPU; cell count capped.
**Effort:** M.

### 3.4 E3 — Paint transactions on the GPU: wet pickup, lift and draw

**Gap:** programs cannot read colour; colour pickup exists only as the Color Smudge tool
(section 1). This is the structural gap behind R4. The first draft put it in a Kotlin surface probe;
that is replaced (3.0).

**Design.** A new GPU pass, `paint_transaction`, extends the existing wet stage
(`core/wgpu-engine/src/wet.rs`, `shaders/wet.wgsl`, `GpuWetSimulation`) and runs once per painterly
dab, in recorded order, with the same deterministic reduction pattern the transfer pass already uses
(workgroup partial sums, no float atomics; `wet.wgsl` pass 1 / pass 2).

Per dab, the transaction does, over the dab's footprint:

1. **Sample** the surface: colour (as K-M latent through the lookup texture, 6.2), wetness and height.
2. **Lift** (`lift` intent): move paint from the surface into the named load column, limited by the
   remaining *source quantity* (below), the dab's footprint weight and the column's free capacity.
3. **Draw** (`draw` intent): deposit the column's mixture onto the surface; where the surface is wet
   (`mixWet`), mix the deposited colour with the surface colour in latent space by wetness; add
   height (`heightMul`, E5) and wetness.
4. **Write** colour, wetness, height and the load column back, so the next dab in the stroke sees the
   consequences of this one.

**Source quantities (fixes the refill-forever hazard).** A lift draws from a stroke-local
*remaining liftable amount* per pixel, `avail`, initialised at touchdown from the committed surface's
wet film (wetness and paint height). Every lift **consumes** `avail` and applies the matching removal to
the committed surface field (wetness and height decrease where paint was lifted). Repeated lifts from
one patch therefore deplete it; a stroke cannot refill from a patch indefinitely. The same applies to a
palette blob: a blob is a region of the board surface's paint field (6.3), so a lift from a blob
consumes that blob, and finite paint on the board follows from the same rule with no separate ledger.
Colour for a lift is sampled from `snap_color`, a GPU copy of the surface colour taken at touchdown
(the existing `SAVE_RAW` op, `GpuWetSimulation.kt:56-58`), **for colour sampling only**; quantities
come from `avail`, not from the snapshot.

**No self-pickup (owner decision 5, v1).** The stroke's own deposits are written to the surface but not
added to `avail` or to `snap_color`, so a stroke never lifts its own wet pass. The door stays open: a
later `transaction: 2` could add own deposits to `avail`; it would be flagged on the brush and never a
silent change to existing strokes.

**What this replaces in the program ledger.** `spray`/`drain`/`wetAt` (`E/PaintLedger.kt:24-132`,
`E/StrokeProgram.kt:829-841`) stay as they are for existing packs. They are a spray-only,
thickness-only, in-memory ledger: `drain` only subtracts thickness, clamps at zero, reports nothing and
never touches the persistent surface. It must **not** be used for pickup bookkeeping on painterly brushes;
`lift` supersedes it (a brush uses one or the other, never both, enforced at compile time by the
`painterly` flag).

**Program surface.** No new surface-reading functions. A program says what it intends, on an `emit`:
`lane`, `draw`, `lift` (amount to lift into the lane at this dab's footprint) and `mixWet` (0..1 upper
bound on mixing with the wet surface) and `dispose` (0 or 1: with `lift` and no `lane`, remove the
paint and discard it instead of loading it; this is how scrapers, sgraffito and the rag clean a surface).
Disposal consumes `avail` and the surface exactly as a lift into a lane does (3.4, source quantities); only
the destination differs (a discard sink that holds nothing). It reads only the Kotlin budget (3.3) and
its own inputs. Coordinates: `lane` is a **-1..1 coordinate across the brush width** (the nearest load
column serves it), never a column index; `loadAmount(lane)` and `loadMix(lane)` take the same coordinate.

**Why GPU (justification against the owner's criteria).**

| Criterion | Kotlin probe (withdrawn) | GPU transaction (adopted) |
|---|---|---|
| Mid-range Android perf | Footprint reads of a CPU copy: 48 lanes x 16x16 samples per step, plus a CPU copy of a GPU-resident layer (`ARCHITECTURE.md`: layers stay resident; readback only of the dirty rect) | Runs where the layer already is; no readback in the live path |
| Determinism and replay | Exact on the CPU, but the CPU copy goes stale as wetness evolves | Deterministic within the contract in rule 4a; same device identical, cross-device within stated tolerance |
| One implementation | One, but a second is still needed on the GPU to display it | Kotlin reference + WGSL port; parity-tested |
| Existing GPU wet shading | A CPU wetness read would not agree with the GPU field `shade_material` lights (`wet.wgsl:584-627`) | Same field the specular-while-wet shading already reads |

**Compatibility:** none of this runs unless the brush declares `"painterly": 1` with a `load` block.
**Cost:** one extra dispatch per dab batch; footprint capped at the existing 320 px clamp.
**Effort:** L.

### 3.5 E4 — Subtractive pigment mixing

Specified in section 6.2. Two implementations of one law:

- **Kotlin (`:core:engine` `commonMain`, pure, no Android dependency):** `PigmentMixer` is the reference
  and the oracle, and runs at runtime for the **per-colour** work: palette recipes, the eyedropper
  solve, the brush's budget, and tube/blob colour on the board UI.
- **WGSL (`wet.wgsl` family):** the same mixer applied **per pixel** for canvas mixing (decision 3):
  wet blends, glazes, overlap. The RGB-to-latent lookup is a 3-D texture generated by the Kotlin
  reference and uploaded once; the WGSL samples it and does the K-M algebra in the paint transaction.

The existing `MaterialMixingModel` enum gains a third value `PIGMENT_KM` (appended; the two existing
values keep their ABI meaning, as the status doc requires, "Native mode values preserve historical ABI
meaning").

**Effort:** L.

### 3.6 E5 — Impasto ridge and highlight (implemented in P2, PR #764)

**Gap:** no sideways displacement; specular only while wet (section 1).

- `emit { h: }` (and output `heightMul` for the brush's own dabs) set per-dab height deposit,
  independent of colour opacity. `h` is clamped to **0 or above**; carving paint away is a removal
  (`lift` with `dispose`, 3.4), never a negative deposit. A flat brush gives its edge lanes a larger `h`
  than its centre. Under the v2 material transfer a thicker bead also draws more paint off the brush
  (the deposit spends load), so the rim is a consequence of the load, not a texture. A brush-level
  `heightProfile` curve across `lane` is a pack-authoring convenience that expands to per-lane `h`; it is
  **not implemented** yet.
- `Dab.heightMul` (appended, default 1) is honoured by `ImpastoEngine.deposit` (v1), `transferMaterialStroke`
  (v2), GPU v1 (folded into the per-dab `strength`, no shader change) and GPU v2 (`wet.wgsl`, carried in the
  `MaterialDab` pad as `heightMul - 1` so a zeroed pad is unchanged).
- Dry sheen: `PaintMedium.sheen` (`impastoSheen` in params) adds `sheen * lobe` to the existing Blinn
  specular term in `ImpastoEngine.shadeMaterialInto`, `ImpastoRegionShader.shadeMaterial` and `wet.wgsl`
  (`M_SHEEN`, media slot 6), so a dried bead keeps a highlight. `wetSpecularStrength` keeps its meaning and
  adds while wet. Acrylic and oil are glossy when dry, so `sheen` is separate from wetness by design.
- Persistence: the material sidecar is `VERSION` 3 and stores sheen; a v2 sidecar reads sheen as 0.

**Compatibility:** `sheen = 0` and `heightMul = 1` are exact identities (tested). `ImpastoMaterialConfig`
keeps `version: 2`; sheen is an optional field, not a version bump.
**Parity:** Rust `transfer_honours_the_per_dab_height_multiplier` compares the GPU to the CPU reference on
lavapipe. The sheen WGSL is naga-validated but has no GPU pixel test yet (no `shade_material` test exists to extend).
**Effort:** done.

### 3.7 E6 — Lift tail (taper to a point on release)

No new engine mechanism is needed; this is a **contract clarification plus a test**.

- A program may `emit` at `phase == 3`. That emit may lie ahead of the last sample along the final
  heading. This is causal: lift-off is a recorded sample, so replay produces the same tail
  (`spec/stroke-program.md` "Time ticks and lift-off").
- Tail length is a function of the speed at lift (`speed`, `layerSpeed`), the load and the
  pressure; the tail's width falls to zero and its lanes close together. A finger therefore
  tapers like a brush that is lifted while moving.
- Add `StrokeProgramTest` cases: a phase-3 emit is painted; it survives undo/redo replay; it is
  absent when the stroke ended at rest (`speed` near 0).
- The live preview shows the tail at lift-off. That small "pop" is the cost of causality and is
  the honest behaviour of a brush leaving the surface.
- `BrushTaper.endLengthPx` stays retired.

**Effort:** S.

### 3.8 E7 — Watercolour (GPU field passes)

Required: watercolour is one of the four media (decision 1). It is a set of **time-evolving per-pixel
fields**, so it is GPU work, not CPU (3.0). The pieces, all as additions to the existing wet stage's
ADVANCE family (`GpuWetSimulation.ADVANCE/LEVEL/PIGMENT`):

- **Wicking and bleed.** Paper `absorbency` (from `SubstrateSample`, `E/SubstrateDeposition.kt:15-35`,
  already carried but unused for wet behaviour) drives lateral wetness flux. The existing pass
  exchanges wetness and pigment between neighbours with no paper term; the new term scales the
  exchange by a per-pixel absorbency texture (the substrate tile already uploads as a static R8 texture).
- **Granulation.** A per-pigment property (6.1) applied at deposit: pigment settles into tooth valleys
  (the substrate height texture), a per-pixel modulation of deposited amount.
- **Wet-edge behaviour and edge darkening.** A drying boundary pulls pigment outward: a conserved-volume
  flux toward pixels that dry first, using the same fixed-timestep pass.
- **Wet film spread and drying.** Exponential drying per tick already exists (`ADVANCE`); the film
  spread is the existing wetness flux with the absorbency term.

The Kotlin `WetMaterialTransport` (`E/WetMaterialTransport.kt:24-31`) gains the matching reference
(absorbency, granulation, edge flux) and the Rust tests extend `cpu_advance` accordingly. The first
draft's wording "extend `WetMaterialTransport`" meant the runtime; it is the oracle only.

**Effort:** L.

### 3.9 E8 — Declaring and versioning

A brush using any of the above declares it:

~~~json
"painterly": 1,
"load": { ... },
"transaction": 1,
"heightProfile": [[-1, 1.0], [-0.6, 0.35], [0, 0.2], [0.6, 0.35], [1, 1.0]]
~~~

`AzphaltBrush.fromParams` (the parser at `E/AzphaltBrush.kt:387-511`) ignores unknown keys today;
that is what makes older hosts degrade safely, and must stay true. New keys parse with the same
`finiteOr` / `coerceIn` sanitisation the existing ones use.

### 3.10 Authoring and tooling contract

- `StrokeProgram.compile` already returns line and message for tooling (`spec/stroke-program.md`
  "Declaring one"). It gains the new names so authors get errors, not silent no-ops.
- `PackRenderHarness` (`core/engine/src/desktopTest/.../PackRenderHarness.kt`) renders every brush
  against six test strokes. Add a seventh, **the petal pull** (swell, hold, lift with tail), and
  render the demo scene of section 5.4. Output includes `peakOps` as today.

### 3.11 E9 — Draw the brush itself at the contact point

Owner's idea, three goals: (1) **realism** — show what the bristles are doing (splay, flattening under
pressure, rotation and direction, tilt and azimuth on a pen); (2) **load** — show how much paint is on
the brush, at the bristle tips, depleting, with no numeric slider (it is the visible half of the
finite-paint design, 3.3 and 6.3); (3) **latency** — let the bristles cover the area where the stroke
lags.

**What exists today (read from source).**

- A **hover** brush already draws: `DrawingCanvas.kt:436-500` renders the tip hull and the bristle
  cells from `BrushTipTopology.preview(...)` posed by `BrushDevicePresentationModel` (device attitude),
  as a dark under-stroke plus a bright hairline "without hiding the art beneath it". It is shown while
  hovering and cleared when contact starts (`brushCursorPosition = null`, `DrawingCanvas.kt:~242`). It is
  the *static* tip shape; it does not use the live contact state.
- The live contact state exists but is private: `IncrementalDynamicDabGenerator` keeps
  `mechanicalState` and a per-step `contact` (`BrushContactState`) (`E/IncrementalDynamicDabGenerator.kt:53,200-202`) and
  uses it for the dab. Nothing outside the generator can read it.
- **Latency work that exists:** a stroke-prediction tournament with a drawn predicted tail whose reach
  is the *measured touch-to-paint latency* (`feature/editor/.../prediction/GesturePrediction.kt:92,205-233`,
  `tailLeadMs`), and a direct-display path where the wgpu engine presents the in-progress stroke into a
  `SurfaceView` above the window (`LiveStrokeOverlayHost.kt`; the design is in
  `docs/Native Rendering Engine Design.md` section 3, front-buffer presentation). Predicted samples never
  enter stroke history (`BrushSample.predicted`).

**Data per frame (verified).** The model already computes, for every sample, everything a drawn brush
needs: `BrushContactState` (`E/BrushContactModel.kt:125-149`): `widthMultiplier`, `tipRatioMultiplier`,
`angleOffsetDeg` (lag of the drag angle), `offsetXFraction`/`offsetYFraction` (contact-centre drag),
`bend`, `compression`, `lean`, `splay`, `contactDepth`, `tipLeanX/Y`, `tipTwistDeg`, and `tufts`: a list
of `BrushTuftContact` (`E/BrushTuftTopology.kt:161-181`) with `id`, `rootLateralFraction`, offsets,
`radiusScale`, `angleOffsetDeg`, `splitAmount`, `touchdownAmount`, `liftAmount`, `reversalImpulse`,
`physicalRadiusPx`, `contactWeight`. Tilt and azimuth arrive on `BrushSample`
(`tiltRadians`, `orientationRadians`) with confidence in `BrushDeviceAttitude`, and
`BrushTelemetryMetadata` says which axes are real. **Not available today:** the contact state outside
the generator; per-bristle *load* (the load design, 3.3, supplies a Kotlin budget per lane, which is
what the drawn brush shows).

**Design.**

1. **Single source of truth.** The drawn brush is a *view of the same numbers that make the paint*. The
   generator, in the step that emits a dab, also publishes an immutable `BrushPresentationFrame`
   (contact state, tuft contacts, the sample's tilt/azimuth/pressure/time, the lane load budget) as a
   read-only accessor. The overlay **never re-simulates and never recomputes mechanics**; it only
   draws, and interpolates between the last two frames. So the drawn brush cannot disagree with the
   paint: a bristle drawn splayed is a tuft the dab expander used. (Same rule as 3.0: Kotlin decides,
   the renderer draws.) For hover, before any contact, the existing `BrushTipTopology.preview` is used
   unchanged.
2. **Anchoring: the tip is exactly at the contact point.** The contact patch is drawn at the **dab
   centre** (`sample + offset * contactDiameter`, `E/IncrementalDynamicDabGenerator.kt:249-250`), mapped through the *same*
   function that maps a stroke sample to the layer and the screen as the dabs use, including layer
   scale, offset, rotation, zoom, pan and the 3D-surface mapping (`OverlayGeometry`). No second
   transform, no per-overlay offset constant. The body of the brush is drawn trailing from that point;
   the patch never moves relative to the dab centre. A recent fix addressed strokes landing away from
   the finger; this is the same class of bug, so the invariant is a required test (anchor within 0.5 px
   of the dab centre across zoom, pan, rotation and layer transforms, section 8.2).
3. **Latency: what it does and does not do.** *It does not reduce real latency.* Paint still reaches
   the screen L milliseconds after the touch (the measured touch-to-paint latency that
   `tailLeadMs` already carries); drawing a brush adds work and cannot make paint arrive sooner. *It can
   reduce perceived lag*, because the brush is drawn at the latest touch position at display rate and
   its bristles cover the gap between the paint's leading edge and the finger **only while the lag
   distance, speed x L, is smaller than the brush's own extent**. A large flat brush hides the lag; a
   rigger or a fine tip does not (lag distance exceeds the brush, the gap shows). So the claim is bounded:
   "less visible lag for brushes larger than the lag distance", never "zero lag". Relationship to the
   existing prediction tail: the tail draws *speculative paint* ahead of the real stroke; the drawn brush
   draws *no paint*. They compose: the brush sits at the latest real sample (with extrapolation of at most
   one frame, clamped by the same prediction confidence), the tail stays as is, and neither writes
   history. The brush must be **in the same presentation layer as the paint**: if the stroke is shown in the
   direct-display `SurfaceView` and the brush is a Compose overlay drawn a frame later, the brush would lag
   the paint and defeat goal 3. So: v1 draws in Compose and makes **no latency claim** (goals 1 and 2
   only); v2 draws the brush shape in the direct-display layer (a small extra pass in the wgpu
   swapchain) and is measured against the existing touch-to-paint figure before the claim is made.
4. **The brush covers the work, and that is the point (owner direction).** A real brush, and a
   stylus in a digital app, covers what the painter is making; the painter adapts as in real life: lift,
   look, move the hand. The drawn brush therefore is **opaque and physically plausible by default**: the
   ferrule and handle are drawn solid, at a realistic angle, with the contact patch and bristles at the
   tip. There is **no translucency, no offset and no fading by default**, and this spec does not treat the
   occlusion as a problem to mitigate. Held angle: for a pen, the real **tilt and azimuth where the device
   reports them**; for a finger, or a pen with no tilt or azimuth, a **sensible held-brush angle**
   (a fixed, handedness-aware pose, as a painter holds a brush) rather than a fabricated one (item 5).
   The only presentation setting is **"Hide drawn brush"**, under accessibility and power saving. It is
   **not the default and not promoted in the UI flow** (no first-run prompt, no toolbar button). It exists
   for reasons that are not a taste preference: a user for whom a large moving object over the canvas is a
   barrier (vestibular or low-vision accessibility, or a need to see the whole canvas at all times because
   of a visual condition), and a low-power or low-end mode where the overlay's cost is unwanted. Hiding it
   loses no function (item 8).
5. **Honesty about telemetry.** The drawn brush never fabricates an axis a device does not report
   (status doc section B). A finger or pressure-only pen is drawn at the sensible held-brush pose of item 4,
   not with invented tilt; splay, flattening and drag still show because they come from pressure and
   motion.
6. **Load display (goal 2).** Each tuft's tip is coloured from its lane's *budget* colour (the Kotlin
   prediction of the load, 3.3; colour from the lane's tube concentrations by `PigmentMixer`, which is
   per-colour work and fits the CPU side of 3.0) and its drawn length of coloured tip shrinks as the
   budget falls; a dry lane shows bare hair. Because the budget is a *prediction*, the picture can differ a
   little from the GPU truth; it is corrected at stroke end when the true load is read back (3.3). No
   number is shown; the cue is the paint itself (decision 6). When the GPU transaction (P5) is not active
   (a non-painterly brush), the tip is drawn in the brush colour at full length, as hover does today.
7. **Cost and budget (mid-range Android; targets, not measurements).** The overlay draws at most the
   tier's tuft count (16 / 32 / 96, `E/BrushPerformancePolicy.kt:10-21`) as short strokes, not every hair,
   and at most the tier's `maxPreviewCells` (96 / 192 / 1024) for the hair texture; target under 0.5 ms
   of draw per frame on BALANCED. Frame building is allocation-free in the step loop (reuse one mutable
   frame), and interpolation is two lerps per tuft. The presentation frame adds nothing to the dab path
   beyond copying a handful of floats and the tuft list. If a frame misses budget the overlay draws fewer
   hair cells for that frame (the tuft count and silhouette stay) and never delays paint.
8. **Accessibility and power.** One setting, **"Hide drawn brush"** (item 4), off by default and not
   promoted; **hiding it loses no function**, since the overlay is purely presentational and no input, paint
   or load behaviour depends on it. The
   load cue has non-visual and non-colour equivalents: the coloured length of the tip, not hue alone;
   a polite screen-reader announcement when a lane runs dry ("brush running low"); optional haptic
   tick; colour-vision safe (length and pattern carry the information). Contrast follows the hover rule
   (dark under-stroke and bright hairline, readable on light and dark art). Reduced motion: the overlay
   adds no easing of its own; it only interpolates real frames.
9. **Phase placement.** **P4b**, after brush load (P4): needs the load budget for goal 2. The
   geometry-only part (goal 1) has no dependency and could ship earlier if wanted. The latency layer
   (goal 3, direct-display pass) is a separate task in P9 with its own measurement gate.

**Effort:** M for the frame accessor and the Compose overlay; M-L for the direct-display pass.

## 4. Backward-compatibility and determinism rules (normative)

1. A brush with no `painterly` key renders byte-identically to the previous build. Pinned by a
   golden test over every shipped built-in brush and every pack in `submissions/` that targets
   Graffux (section 8.2).
2. New `Dab` fields are appended with defaults. `WgpuDabs` slot indices (`WgpuDabs.kt:32-37`) are not
   reordered; new slots are appended and the layout-parity test (`core/wgpu-engine/tests/layout_parity.rs`)
   is extended in the same change as the Kotlin layout.
3. Persisted state stays versioned. Brush load persistence (section 6.5) is a new optional project
   field; a project written by this build and opened by an older one loses only the load, never
   the paint. The material sidecar's fail-closed rule (status doc Phase 5) is the model: absent or
   corrupt load state means "start empty", never an error.
4. Replay: the *Kotlin* side (program output, dabs, load budget) is a pure function of
   (samples, stroke seed, recorded load at stroke start). The *GPU* side (the paint transaction and the
   wet passes) is a deterministic function of (those dabs, the canonical surface state at stroke
   start, the recorded stroke clock). A stroke records its starting load.
4a. **GPU determinism contract.** (i) Passes run in recorded dab order; (ii) reductions use the fixed
   workgroup-order pattern of `wet.wgsl` (no float atomics, no unordered accumulation); (iii) no
   wall-clock input; (iv) **fixed timestep:** material time advances in whole ticks of `TICK_MS`
   derived from the recorded stroke clock (`WetnessReplayState.advanceMaterialTo`), with the existing
   iteration caps (2 default, 4 hard cap for colour transport, status doc Phase 4), so the number of ticks
   is a function of recorded times only; (v) drying uses the per-tick constant, never a measured delta;
   (vi) the same device and driver reproduces a replay exactly; across devices results agree within the
   tolerances in section 8 (height and wetness 2e-4 absolute as `wet.rs` already uses; colour within 2
   levels per channel, the `tools/stamp-engine-diff` figure). (vii) **Concentrations are canonical state:** the board's 12 channels and the canvas latent tiles are read and written by the transaction under the quantisation rule in 6.2 (deterministic largest-remainder, identical in Kotlin and WGSL), stored in the sidecar at commit, and included in GPU checkpoints, so a replay starts from exact concentrations and never re-derives them from RGB. (viii) **Replay windows are short:**
   a commit stores the canonical state in the material sidecar (`MaterialStateCodec`), and undo replays at
   most the undo depth (20 entries, `EditHistory`) from a GPU checkpoint or the canonical CPU state, so
   cross-device drift cannot accumulate over a document's history. Undo past a material-changing stroke
   already bypasses the pixel-only fast path and reconstructs canonical state (status doc Phase 5).
5. Float determinism: pigment math (Kotlin and WGSL) uses `f32` with a fixed evaluation order, no `fastMath`, no
   `kotlin.math` functions whose results differ between JVM and Android ART beyond the existing
   test tolerance. Where an exponential or power is needed it goes through a table or a documented
   polynomial, so the Kotlin oracle and the WGSL port agree to the stated tolerance.
6. Kotlin is authoritative. If the Kotlin and Rust results disagree beyond tolerance, Rust is
   wrong.

## 5. The painter's brush set

### 5.1 How brushes are described here

For each brush: **real behaviour** (what the tool does in hand), **medium and surface** it assumes,
**parameters** (existing keys where they exist, new ones marked `[new]`), and a **program sketch**.
Existing capability is used wherever it exists; sketches are not full scripts. Names in `monospace`
are keys in `params`. Every brush is a `type: "brush"` asset in a `kind: "asset"` package.

Colour is not medium: a brush asset names a *medium* (`impastoMaterial`, load behaviour), never a
colour. Colour comes from the palette (section 6). Brushes marked **existing** are in the packs of
section 1.2; the new brush supersedes them and they are removed under section 5.6.

Surface codes: P = cold-press paper, C = canvas weave, G = gessoed panel (smooth, low tooth),
W = wall (brick/concrete preset). These map to `SubstratePreset` (`E/SubstratePreset.kt:18-60`)
except gessoed panel, which is `SMOOTH` with a small `baseHeight` (a new preset is not required).

### 5.2 Bristle brushes

**1. Flat (bright/long flat).** Chisel edge for lines, full width for fill, side for a petal's broad
swell. Loads along the edge; the corners hold more paint. Medium: acrylic or oil. Surface: C, G.
Parameters: `contact.tufts.morphology: "flat"`; `tipGeometry.kind: "bristle"`,
`population.bristleDiameterPx ~1.1`; `substrateResponse 0.4`; `heightProfile` rim-heavy `[new]`;
`load.lanes 24 [new]`. Program: lanes across width from `penAngle`; `draw` from the tip layer;
edge lanes get `h` high, centre low (E5); lane colour from the column (E2).
Existing: **Square Flat** (loaded-hair) is the physical morphology without load or ridge.

**2. Filbert.** Oval-ended flat; soft edges, rounded petal ends, blends well. Medium: oil, acrylic.
Surface: C. Parameters: `morphology: "filbert"`; as Flat with softer `hardness`, lower `splitThreshold`.
Program: as Flat, lane width falls off with `sqrt(1-u^2)` toward the corners.

**3. Round.** Belly and point: swells with pressure, tapers to a point on lift; the petal-and-leaf
brush. Medium: acrylic, oil, watercolour. Surface: C, P. Parameters: `morphology: "round"`;
pressure curve on `size` with a long toe (`outputMin 0.05`); `load.lanes 12`. Program: lane count
tracks pressure (fewer bristles touch when light); tail at lift-off (E6).

**4. Rigger / liner.** Very long, thin, soft hairs hold a lot of thin paint; one flowing line, tapers
at both ends. Medium: ink-thin acrylic, watercolour, thinned oil. Surface: any. Parameters:
`morphology: "rigger"`; `taper.startLengthPx 30`; pressure curve with a toe. Program: load
depletes slowly; a long tail on lift. Existing: **Rigger** (loaded-hair), which has the start taper
only.

**5. Fan.** Splayed thin row; soft blending of wet edges, foliage, grass. Barely loaded.
Medium: oil, acrylic. Surface: C. Parameters: `morphology: "fan"`; `count` of bundles high.
Program: mostly `lift` (picks up and re-lays wet paint, E3); little `draw`. Existing:
**Fan Softener** (loaded-hair), a stamp rather than a pickup tool.

**6. Mop / wash brush.** Large soft belly holds a lot of fluid; broad even washes and wet-in-wet.
Medium: watercolour, thin acrylic, ink. Surface: P (cold press), and a damp surface.
Parameters: `load.capacity` high; large `mechanicalBundleDiameterPx`; low `viscosity`. Program:
wetness deposit (existing `depositWetnessStroke`); pigment `granulation` (6.1). Existing:
**Glaze Mop** (loaded-hair, opacity-limited glaze) and **Cauliflower Bloom** (painterly).

**7. Stiff hog-bristle (bristle flat/bright).** Stiff, springy, leaves pronounced bristle tracks
and ridges in thick paint; moves paint rather than lays it. Medium: oil, heavy acrylic. Surface: C.
Parameters: high `stiffness`, low `cohesion`, high `bendDifferential` is wrong for hog (low); large
`heightProfile` ridges `[new]`. Program: heavy `h`, strong ridge, `lift` takes paint from the
centre to the rim (displacement, E5). Existing: **Scrub** (loaded-hair).

**8. Dry-brush.** Nearly empty, stiff brush skimmed over a toothed surface; paint catches only the
high points. Medium: oil, acrylic. Surface: C, P (needs tooth). Parameters: `substrateResponse` high;
`load.fill amount 0.15`. Program: `draw` thresholds against `tooth` (as Matte Gouache's `thr`/`on`).
Existing: partly **Dry Rake** (kinetic.depletion) and Matte Gouache.

**9. Scumble brush (old stiff round).** Worn, short hairs scrubbed in circles to lay a broken,
semi-opaque veil over a darker layer. Medium: oil, acrylic. Surface: C. Parameters: round, short
hairs, high `scatter`; `pickupRate` low. Program: mix of new over old at lane level using `lift`/`mixWet` intents
(E3), so veil colours optically mix.

**10. Wash (flat wash brush, 1 to 3 inch).** Wide flat with a thin ferrule and soft fine hair; one
even, overlapping pass. Medium: watercolour, thin acrylic. Surface: P. Parameters: wide flat,
`load.capacity` large, wetness deposit high. Program: edge pooling (E7).

**11. Dagger / sword striper.** Angled long hair for one-stroke lines that start thick and end thin;
leaves, vines, lettering. Medium: acrylic, enamel. Surface: any. Parameters: a `rigger`-like
morphology with an angled root layout `[new]` (population layout parameter). Program: width from
roll (`tilt`, `penAngle`) as well as pressure; tail at lift.

### 5.3 Non-bristle tools

**12. Palette knife (flat, painting).** Flexible steel blade; lays slabs with sharp edges, scrapes,
and drags colour. No bristle streaks; clean edge ridge. Medium: oil, thick acrylic. Surface: C, G.
Parameters: `tipGeometry` a thin rectangle; `heightProfile` flat with raised edge; very high
`yieldLikeStrength`. Program: `lift` at the trailing edge, `draw` at the leading edge (E2, E3).
Existing: **Impasto Knife** (painterly) and **Trowel** (loaded-hair).

**13. Palette knife (pear / trowel, for mixing).** The tool used on the palette (section 6.3). It
exists as a *palette tool*, not a canvas brush; listed so the set is complete.

**14. Sponge.** Natural sponge dabbed: irregular stamp, broken texture, picks up and re-lays.
Medium: acrylic, watercolour. Surface: any. Parameters: `shapePath` from a sponge mask, `scatter`,
`rotationPerPx`; random angle per touch. Program: one `emit` per touch with `hash`-driven pore holes;
pickup on lift. Existing: **Sponge Charge** (kinetic.depletion) is stamp-like.

**15. Rag / cloth.** Wipes and lifts paint to reveal the ground, or blots a wet area. Mostly `lift`.
Medium: oil, acrylic (while wet), watercolour (lift). Surface: any. Parameters: large soft tip,
`pickupRate` high, `load` starts empty. Program: `lift` only; the load becomes the picked-up colour
and is **wiped to nothing at the end of the stroke** (section 6.3, cleaning).

**16. Stipple / stencil brush.** Stubby round bristle, pounced vertically. Even speckle, no streak.
Medium: any. Parameters: round, short, `spacing` large, jitter. Program: tap per touch.

**17. Sgraffito tool / scraper.** Scratches through wet paint to reveal what is under. Medium: oil,
acrylic (wet). Parameters: hard 1 px tip, `load` with no fill. Program: each dab `emit`s a **disposal lift**
(`lift` with `dispose: 1`, no `lane`, no `draw`): the paint is removed from the surface and sent to a
discard sink instead of a load column. This is the surface-removal transaction (3.4); it consumes the
liftable amount and applies the matching removal to the committed surface (wetness and height drop where
it scratched, so the ground shows). It does **not** use `drain` (the spray-only ledger, prohibited on
painterly brushes) and it does **not** use a negative `h` (`h` is clamped to 0 or above; carving is a
removal, not a negative deposit).

**18. Airbrush / spatter (toothbrush).** Out of scope for realism-first painter's set; the
street-kit and spray-caps packs own these. Listed only to say they are excluded.

### 5.4 Realism-first brushes matching the reference

**19. Loaded Petal Flat (the reference brush).** A flat brush double-loaded: rim colour on the
edge lanes, body colour in the middle. One pull makes a petal: width swells with pressure, the edge
colour stays at the rim, bristle streaks run lengthwise, a bead of paint ridges at the rim, and it
tapers to a point on lift.

Behaviour map to the reference:

| Reference | How |
|---|---|
| R1 | Pressure curve on size (swell), then lift tail (E6) closes the lanes to a point |
| R2 | `load.fill`: red on edge lanes, pink mix on centre lanes (E2); mixed in latent space (E4) |
| R3 | Lane `emit`s with per-lane `noise` (existing technique) |
| R4 | `lift`/`mixWet` against wet paint under the pass (E3) |
| R5 | `heightProfile` rim bead (E5) plus `sheen` (E5) |

Program sketch (new keys marked, rest existing syntax):

~~~
// lanes across the flat's edge; u in -1..1
let n = clamp(floor(diameter / 1.6), 8, 32);
let sw = 0.55 + 0.45 * pressure;                 // swell: edge opens with pressure
hide = 1;
for i in 0..32 {
  if (i < n) {
    let u = (i + 0.5) / n * 2 - 1;
    let off = u * diameter * 0.5 * sw;
    let avail = loadAmount(u);                    // [new] E2
    let streak = 0.75 + 0.25 * noise(i * 9.1 + dist / (diameter * 1.5));
    if (avail > 0.02) {
      emit { x: px - ny * off, y: py + nx * off,
             size: len, tip: cw / len, angle: ang,
             lane: u, draw: stepPx * 0.004 * streak, // [new] E2
             lift: stepPx * 0.002, mixWet: 0.6,                  // [new] E3 intents; the GPU samples the surface
             h: 0.6 + 1.8 * smoothstep(0.55, 0.95, abs(u)) };                       // [new] E5
    }
  }
}
// lift-off tail: close the lanes toward a point
if (phase == 3) {
  let tail = clamp(layerSpeed * 14, 0, diameter * 2);
  for k in 0..16 { /* emit shrinking dabs ahead along (nx, ny) */ }
}
~~~

(`nx`, `ny`, `ang`, `len`, `cw` are the direction and size locals defined exactly as in the Heavy
Oil program.) Medium: heavy-body acrylic. Surface: C. **This is the demo brush.**

**20. Loaded Petal Round.** The round-brush counterpart: a comma stroke, a leaf, a teardrop. Pressure
swells the belly; the load pools at the belly; the point forms on lift. Same program as 19 with
concentric lanes (radial layout) instead of linear.

**21. Comma / Leaf Stroke.** One-twist stroke: rolls the brush (`penAngle` or heading change over the
stroke) so the edge lane switches from one side to the other, leaving a leaf with a vein. Needs
the roll input; on a finger, a heading change stands in.

**22. Double-Load Flat (blend-on-brush).** The flat is loaded with two colours side by side and the
load mixes **on the brush** as it moves (a gradient develops across the width). Uses the lateral
diffusion between neighbouring load columns, a small deterministic smoothing step per dab in E2.

**23. Glaze Flat.** Thin transparent film of a transparent pigment over a dry layer; colour mixes
optically (K-M with alpha as film thickness). Tests E4 more than any other brush.

**24. Impasto Bead.** Rim-heavy ridge with strong `sheen`, dried and glossy: the "thick paint" look
from the reference in isolation.

### 5.5 Distribution as azphalt asset packs

Constraints from the code and spec read: `kind: "asset"`, `type: "brush"` assets, shipped with
`targetApps: ["com.hereliesaz.graffux"]`, CI computes `files` digests, the folder name equals `id`
(`/home/user/azphalt/submissions/README.md`, "Layout"). A brush's `params.program` is data, so these
packs stay `kind: "asset"` (`package-format.md` §2.6). `AssetType` `palette` and `material` already
parse but have no consumer (`package-format.md` §2.6.1).

Packs (decision 1): **four media packs, all free, Apache-2.0, bundled with the app**, and each also
gets an azphalt store listing so the same files can be installed, updated and shared through the
store. Bundled means the app ships the `.azp` content in its assets and installs it on first run
through the same `AzpInstaller` path as a store install (no second loader); the store listing is a
normal `kind: "asset"` submission. All new ids; none touches an existing pack.

| Pack id (suffix) | Medium | Contents | Needs |
|---|---|---|---|
| `painterly.acrylic` | acrylic | Flat, Filbert, Round, Rigger/Liner, Fan, Hog, Dry-brush, Scumble, Dagger, Palette Knife, Sponge, Rag, Stipple, Sgraffito, Loaded Petal Flat, Loaded Petal Round, Comma/Leaf, Double-Load Flat, Glaze Flat, Impasto Bead | E1-E6 |
| `painterly.oil` | oil | The same tool shapes tuned for long open time, soft blending and heavy body, plus Blender (fan, mop) | E1-E6 |
| `painterly.water` | watercolour | Mop, Wash Flat, Wet-in-Wet Round, Granulating Wash, Rigger, Sponge lift, Rag lift | E1-E7 (E7 is no longer optional) |
| `painterly.ink` | ink | Brush Pen, Sumi Round, Dip-Nib line, Ruling line, Dry-Brush Ink, Ink Wash | E1-E3, E6 |
| `painterly.pigments` | (data) | The named pigment set, a `palette` asset (6.1), installed alongside whichever packs are present | host `palette` consumer |

Full ids are `com.hereliesaz.azphalt.painterly.<name>`, matching the existing
`com.hereliesaz.azphalt.brushes.*` / `kinetic.*` convention. Manifests follow the existing brush
submissions: `azphalt: "0.1"`, `kind: "asset"`, `license: "Apache-2.0"`, `compat: ">=0.1"`,
`targetApps: ["com.hereliesaz.graffux"]`, one `assets[]` entry per brush with `type: "brush"`,
`path` to a tip image, `tags`, and `params`. A `LICENSE` file is required by the submission layout.
The submission folders are prepared in `spec/painterly-packs/` as manifests-only drafts when the pack
phase (P7) lands, ready to copy into `azphalt/submissions/`; nothing in azphalt is edited from here.

A demo is **sharable** (decision 9) because it is a brush pack plus a stroke list: the shareable unit is the pack
(the brushes) and a project file or a recorded stroke replay (the pull), which the editor already
serialises as samples (`spec/stroke-program.md` "Where it runs": replay feeds the recorded samples).
Video export is a later, separate feature. **This does not exist yet and is a dependency, not an assumption.** The first draft said the editor
"already serialises" strokes as samples; that was wrong, as P0 found. The serialisable project stroke,
`BrushStroke` (`core/common/.../model/EditorModels.kt:444-460`), has points, pressures, colour, size,
opacity, a tool ordinal and an Ink utensil id, but **no stamp brush, no stroke seed, no samples with
tilt/time/phase, and no wet-ledger input**. Stamp strokes exist only as the in-memory `StrokeCommand`
(`EditorViewModel.kt:146`) in the undo history. So a pack plus a project cannot replay a demo after
reopen or share. Phase **P7b** (section 7) adds persistent stamp-stroke capture and a replay consumer;
until it lands, demo sharing is not available and nothing in the packs depends on it. The shareable
unit then is the pack (brush references by id and version) plus a `StrokeRecording` file: see P7b.

**Coordination with HereLiesAz/azphalt (do not change it):**

1. The `palette` asset type needs a documented `params` schema for a pigment (section 6.1). That is
   a spec addition in azphalt's `spec/package-format.md` / `extension-manifest.md`; this repository
   only records the proposal.
2. `packages/azp` validates assets only by path safety (`/home/user/azphalt/packages/azp/src/index.ts:121-153`),
   so a new `params` shape needs no change there. Unknown `params` keys on a brush pass.
3. All four packs are free (`priceStatus` free); no commerce code is involved.
4. A `preview` block (`{image, clip}`) already exists for store cards (`package-format.md` §2.7); the
   petal pack should use `clip` for its pull.

Sizing: kinetic packs run 5 to 18 brushes. `painters-bristles` at 11 and `petal-demo` at 6 fit that
pattern.

### 5.6 Superseded brushes are removed: migration and replay

Decision 10: a brush a new one supersedes is **removed**, not kept. The candidates are the
loaded-hair and painterly brushes the new packs replace (Square Flat, Rigger, Fan Softener, Scrub,
Glaze Mop, Trowel, Heavy Oil, Matte Gouache, Cauliflower Bloom, Impasto Knife).

**P0 finding (read from source, not run): saved projects do not depend on the brush that drew them.**

- A stamp stroke's replay record is `StrokeCommand` (`EditorViewModel.kt:146`), a plain in-memory data
  class (no `@Serializable`; no reference to it in `:core:data` or `:core:common`). It embeds the whole
  `AzphaltBrush` snapshot in `stampBrush` (`EditorViewModel.kt:~175`), the samples, the colour and the
  wet-ledger snapshot. It lives only in the undo/redo history (`EditHistory`, 20 entries,
  `EditHistory.kt:~14`) and is never written to the project file.
- A project persists **bitmaps** per layer (plus the material sidecar). The brush that painted them
  is not stored. The project-level `BrushStroke` model (`EditorModels.kt:444`) stores colour, size,
  points and pressures for the legacy round brush; it has no stamp-brush field.
- Therefore removing a brush from the store or from the app cannot change any saved pixel, and an
  in-session undo of a stroke drawn with a now-removed brush replays from the embedded snapshot, not
  from a lookup by name.

Consequently the migration the first draft proposed (alias-then-bake of strokes) is **not needed for
stroke replay**. What does reference a brush by name, and must be handled:

1. **Favorites and the active brush.** `activeBrushName` (`EditorModels.kt:607`) and the favorites list
   (read by `BottomCarousel`/`RailPackWheel`) hold names. A `BrushAliasTable` (removed name -> successor
   name) resolves them once at load; a name with no alias and no installed pack falls back to `Round`,
   the existing default (`BuiltInBrushes.DEFAULT_NAME`).
2. **Installed old packs.** Users who installed the old store packs keep the files; the host stops
   listing them as brush sets after the alias table ships. The submissions in `azphalt/submissions/` are
   not edited from this repo; withdrawing them is an azphalt-side action for the owner.
3. **Appearance of new strokes** on an old layer is the new brush's, as with any new stroke.
4. **Tests:** an alias-resolution unit test (every removed name resolves; unknown falls back to Round),
   and a fixture asserting that opening a project and undoing a pre-removal stroke in the same session
   still replays from the embedded snapshot.

Not verified: the exact favorites persistence key (I did not trace it) and the `EditHistory` entry size.

## 6. Palette and colour — a physical model

### 6.1 Pigments and tubes

A **tube** is a named pigment plus a binder. The optical half is the pigment; the binder is the
medium and stays with the brush (preserving "colour is not medium").

~~~json
{
  "type": "palette",
  "path": "assets/pigments.json",
  "params": {
    "pigments": [
      { "id": "titanium-white",      "name": "Titanium White",      "pigment": "PW6",
        "hiding": 0.98, "tinting": 1.0,  "granulation": 0.05, "dataQuality": "placeholder", "dataVersion": 1, "wavelengths": [450,480,510,540,570,600,630], "K": [ ... ], "S": [ ... ] },
      { "id": "cadmium-red-medium",  "name": "Cadmium Red Medium",  "pigment": "PR108",
        "hiding": 0.95, "tinting": 0.8,  "granulation": 0.10, "dataQuality": "placeholder", "dataVersion": 1, "K": [ ... ], "S": [ ... ] },
      { "id": "ultramarine-blue",    "name": "Ultramarine Blue",    "pigment": "PB29",
        "hiding": 0.45, "tinting": 0.55, "granulation": 0.55, "dataQuality": "placeholder", "dataVersion": 1, "K": [ ... ], "S": [ ... ] },
      { "id": "phthalo-blue",        "name": "Phthalo Blue (GS)",   "pigment": "PB15:3",
        "hiding": 0.30, "tinting": 1.0,  "granulation": 0.0,  "dataQuality": "placeholder", "dataVersion": 1, "K": [ ... ], "S": [ ... ] }
    ]
  }
}
~~~

- **`pigment`** is the Colour Index generic name (PW6, PR108, PB29 …) so a tube is unambiguous.
- **`bands`** is the pigment's absorption `K` and scattering `S` at the model's wavelength samples
  (6.2), each array the same length as `wavelengths`. **Data format (decision 4):** the format
  accepts calibrated numbers later, so each pigment carries `"dataQuality": "placeholder" | "calibrated"`
  and a `"source"` string. v1 ships **hand-tuned, believable K/S marked `"placeholder"`**; the host
  shows no claim of accuracy for a placeholder pigment, `PackRenderHarness` and the golden tests mark
  its vectors as placeholder-pinned (they pin *current* output, not truth), and replacing a pigment's
  numbers with calibrated ones bumps that pigment's `dataVersion` so recorded strokes keep resolving the
  numbers they used (the `PaintMediaProfile.version` rule, `E/PaintMediaProfile.kt:14-22`).
  Hand-tuned values are derived by fitting K/S so that a pigment's masstone, tint-with-white and a
  few complementary mixes land near the artist-intended look; the fitting script is committed with the
  data so the numbers are reproducible, not magic.
- **`hiding`** (0..1) is opacity: how much of the surface below shows through at working
  thickness. It drives the dab's alpha ceiling for a given load.
- **`tinting`** (0..1, relative to 1.0 for the strongest tinter) is tinting strength: how far the
  pigment overpowers white. Phthalo blue is a strong tinter; ultramarine is weaker. This one number
  is what makes mixed paint feel real and is exactly what RYB lacks.
- **`granulation`** (0..1) is the tendency to settle into tooth; used by E7 and by dry-brush.
- **Naming (decision 7):** generic pigment names only (cadmium red, ultramarine, titanium white);
  no manufacturer or product-line names anywhere in code, data, UI or docs. If a specific product ever
  has to be referenced, use an off-brand wink in the Mr. Pibb / Dr Thunder spirit rather than the
  real name.
- **Baseline hand-pigment set (a selection, not data):** titanium white, zinc white, ivory black,
  cadmium yellow light, cadmium yellow medium, yellow ochre, raw sienna, burnt sienna, cadmium red
  medium, alizarin crimson (or a lightfast quinacridone substitute), quinacridone magenta, burnt
  umber, raw umber, ultramarine blue, cobalt blue, cerulean blue, phthalo blue, phthalo green,
  viridian, sap-green equivalent. About twenty tubes, a classic limited-but-complete palette.
  The exact list is an authoring task for P4, not an open owner question.

**Naming rule.** The UI shows the tube name and a one-line physical note ("opaque, warm, granulating"),
never only a swatch. This is also an accessibility requirement (6.6).

### 6.2 The mixing model — Kubelka-Munk, and why

**Decision (owner, 3): a Kubelka-Munk (K-M) model of the mixture, sampled at a small fixed set of
wavelength bands, applied to the canvas as well as the palette and brush, with a baked RGB-to-latent
lookup so the 8-bit canvas storage does not change.** Concretely:

1. **Forward model.** Each pigment has absorption `K(λ)` and scattering `S(λ)` at `B` bands
   (proposed `B = 7`; revisited when calibrated data arrives). A mixture
   with concentrations `c_i` has `K_mix = Σ c_i K_i`, `S_mix = Σ c_i S_i` (K-M's linearity in
   concentration, which is the whole point). Reflectance of an infinitely thick film is
   `R∞(λ) = 1 + K/S − sqrt((K/S)² + 2 K/S)`. Converted through a fixed observer to linear sRGB,
   then to the canvas's 8-bit sRGB.
2. **Tinting strength comes for free.** It is the magnitude of `K_i, S_i`; a white's high `S` and
   near-zero `K` is why a little phthalo blue dominates a lot of titanium white while a little white
   barely moves phthalo.
3. **Opacity is separate and cheap.** True two-constant K-M handles film thickness over a ground
   with hyperbolic functions per band. That cost per pixel is not justified. Hiding is therefore
   modelled as **alpha** (from `hiding` and load), and K-M decides only the *colour* of the paint
   that the alpha then composites. A glaze is a low alpha of a transparent pigment, composited
   in **latent** space against the underlying colour rather than in RGB, which is what makes
   glazes shift colour correctly.
4. **Two representations, because the palette and the canvas have different jobs** (amended after
   review: the first draft said the board was an RGBA8 surface and also that it kept exact recipes;
   those contradict, because `MaterialStateCodec.Snapshot` stores only ARGB, height, wetness and medium
   (`feature/editor/.../MaterialStateCodec.kt:23-35`), so a recipe reconstructed from RGB cannot tell
   two recipes with the same colour apart, and they would mix identically afterwards).
   - **Palette board: a persisted concentration field.** Each board texel stores the *recipe*:
     concentrations over **16 channels**: **12 board tubes** (`BOARD_TUBES = 12`) plus **4 basis
     channels** (`BASIS_CHANNELS = 4`, the same four fixed basis pigments the canvas latent uses),
     as **16 x u8**, plus paint amount in the existing height channel. Board tubes are slots
     assigned when the palette is set up; the slot table (slot -> pigment id + `dataVersion`) is stored
     with the board. **Why 16, not 12:** a brush that has lifted wet canvas paint carries basis-origin
     concentrations (3.3, below); dabbing it back into a blob must have a representation, or the board
     would silently discard that paint or reconstruct a different recipe. Real palettes get contaminated
     by a dirty brush, so the honest model keeps the contamination rather than forbidding it. (The
     alternative, requiring the brush to be wiped before it can touch the board, was rejected: it is an
     invented rule, it breaks "dip, load, mix" flow, and it saves only 4 B per texel.) **The UI names only
     the tube part** of a texel's recipe and shows any basis part as unnamed "mixed (from canvas)" paint,
     as it does for a brush.
     **Palette pickup and board mixing read and write this field; they never use the canvas
     RGB-to-latent lookup.** Two recipes that share an RGB stay distinguishable and mix as what they
     are. The visible colour of a texel is computed from its 16 concentrations by the K-M mixer (a basis
     channel contributes as its basis pigment), not stored.
     *Quantisation:* the 16 concentrations are normalised **jointly** to sum 255 by a deterministic
     largest-remainder rule (tie to the lower channel index, tubes before basis), so every texel sums to
     exactly 255 and the rule is identical in Kotlin and WGSL. A step of 1/255 of one channel changes
     colour by at most about one 8-bit level for the placeholder set; P3 pins this with a test.
     *Budget:* 16 B per board texel (plus 4 B height and 4 B wetness). Board size **512 x 512 by
     default (6 MB total), at most 1024 x 1024 (24 MB)**; this replaces the earlier 12 B figure (6.7).
   - **Canvas: a sparse basis-latent field plus the colour it already has.** Painterly canvas layers
     keep their RGBA8 colour (and the existing sparse raw-pigment tiles) **and** a **4 x u8 basis
     concentration** per pixel, allocated lazily per 64 x 64 tile only where a painterly stroke has
     deposited (the way wetness and raw pigment already allocate, status doc Phase 4/5). The four
     basis pigments are fixed (the lookup header records their ids and `dataVersion`). Elsewhere, and
     for paint that predates the field, a pixel's latent comes from the **baked RGB-to-latent lookup**
     (a 32-cube, 4 channels, about 128 KB). The reconstruction error is carried as an RGB residual
     (the stored colour minus the mixer's output for the stored concentrations) re-added after a mix, so
     an unmixed pixel round-trips exactly. This is the approach Mixbox popularised; we do **not** use
     Mixbox's code or data (section 9, licensing).
   - **Consequences, stated plainly.** (i) On the **canvas, recipes are exact only up to projection onto
     the four basis pigments.** Two canvas paints that differ in tube (say ultramarine versus a phthalo
     blue and white metamer) but project to the same basis concentrations mix identically afterwards.
     The board keeps tube identity; the canvas does not. (ii) Paint **lifted from the canvas into a
     brush** carries basis-origin concentrations, so it can no longer be named as tubes. A brush load
     cell therefore holds 12 tube concentrations plus 4 canvas-origin basis concentrations (3.3), and
     the UI names only the tube part ("about 40% cadmium red, the rest picked up from the canvas").
     (iii) Memory: canvas latent is 4 B per *touched* pixel, about 16 KB per 64 x 64 tile; a layer
     where 30% of a 2048 x 2048 canvas is painted costs about 5 MB, never the full 16 MB.
     (iv) A brush carrying canvas-origin paint can be returned to the board without loss: the board's 4 basis channels hold it (above). (v) Canvas mixing is approximate where the lookup is used (unpainted-by-latent or legacy paint),
     exact to the basis where the field exists.
   - **Sidecar format.** `MaterialStateCodec` goes from `VERSION` 3 (sheen, phase P2) to **4**: two new
     per-tile flags, `FLAG_CONCENTRATION` (board: 16 B per texel, plus the slot table in the header) and
     `FLAG_LATENT` (canvas: 4 B per texel). A v3 reader of a v4 file fails closed to "no material"
     for that layer (the existing rule); a v4 reader of v3 treats both fields as absent. This lands
     with the board in P6 (the field is meaningless before the transaction in P5 writes it).
5. **Brush load keeps the exact thing it can.** The brush load (E2) holds tube concentrations, exact,
   plus canvas-origin basis concentrations when it has lifted canvas paint.

**Why K-M and not the alternatives**

| Model | Verdict |
|---|---|
| RGB lerp (today's default) | Wrong for pigment: yellow + blue gives grey. Already identified as the problem (`E/MaterialColor.kt:59-65`). |
| RYB (today's `PIGMENT_RYB`) | Fixes yellow + blue only. No white, no tinting strength, no named pigments, hue-only. Keep for back-compat. |
| Weighted geometric mean / Beer-Lambert in RGB | Cheap and decent for transparent glazes, bad for white, cannot represent tinting strength. |
| Full spectral (30+ bands, two-constant K-M) | Most accurate. Per-pixel cost and the 8-bit canvas make it poor value on mid-range Android. |
| Mixbox | Does what is wanted, but its licence is non-commercial by default (from memory, to verify; section 9). A commercial app needs a licence or an own implementation. |
| **K-M, few bands, LUT readback (chosen)** | Correct white and tinting behaviour, linear concentration algebra, exact on the palette, bounded on the canvas, and ours to ship under our own licence. |

The status doc's earlier rule (K-M only if the bounded mixer visibly fails) is superseded by the
owner's decision; it is also satisfied independently, because tinting strength cannot be expressed in
RYB. Canvas-wide K-M means every overlap and every wet blend on a painterly layer uses the lookup,
which makes the lookup table the performance-critical piece (6.7) and means the CPU oracle and the
WGSL port must both implement it (section 3.1, rule 4). `PIGMENT_RYB` and `LEGACY_RGB` remain for old
projects and non-painterly brushes.

Mixed in practice: a dab drawn from a load column asks `PigmentMixer` for the column's current
concentration vector and receives one ARGB; wet mixing with the canvas is one LUT lookup plus one mix.
Both are bounded per dab.

### 6.3 The physical palette

The palette is a surface you work on, not a list of swatches.

- **A board with blobs.** The artist squeezes a tube onto the board: a blob of that pigment (with
  its binder's body), finite in quantity. The board is a layer-like surface with its own wet film.
- **Dab and load.** Touching the brush to a blob loads the brush (E2): the brush's tip layer takes
  paint; the blob loses it. The amount loaded depends on pressure and dwell.
- **Mix by working it.** Dragging a palette knife or the brush through blobs mixes them by the K-M
  rule, with the same lift/draw physics as the canvas (it *is* the same engine: the palette is a
  surface the paint transaction (E3) acts on). Mixing is incremental and spatial, not a slider: more passes,
  more homogeneous.
- **Finite paint (decision 6).** Paint in the mixer is finite: a blob is a region of the board
  surface's paint field, and every lift from it consumes that field (3.4, source quantities), so a blob
  runs out by the same rule as wet canvas paint and needs no separate ledger. A brush holds a limited
  load. Wetness and the amount loaded are **felt through use, never shown as numeric
  sliders**: you dip, the brush takes paint and is visibly fuller; you paint and it depletes, strokes
  thin and break up; a dry brush drags. The only readouts are physical ones (the brush's load strip,
  6.6, and the look of the blob). Section 6.4 lists what is infinite on purpose (refills, cleaning).
- **Cleaning the brush.** Three physical actions, each cheap and deterministic:
  - **Wipe on a rag:** removes the tip layer (depth 0) of the load, partially.
  - **Rinse (water / solvent):** removes more, depends on the medium; for water-based media it is
    nearly total, for oil it needs solvent (a UI choice).
  - **Dip in clean medium:** replaces load with a thin film of medium (a thinner, for watercolour).
- **Brush state is visible.** The brush shows what it holds (a strip of load columns, section 6.6),
  so colour is never hidden in the model.
- **Pigment identity persists.** The colour on the brush is a mixture of named tubes
  (`cadmium-red-medium 0.62, titanium-white 0.38`). The UI can say so.

Palette board data: a small dedicated surface (at most 1024 x 1024, 8-bit, plus the same material
sidecar the canvas layers use) stored in the project. It costs one more layer's worth of memory and
reuses the existing wet/height/pigment machinery, so no second engine (consistent with the status
doc's "no second wet-mix engine" rule, Phase 4). **The board is a GPU surface**: knife and brush
mixing on it run through the same paint transaction as canvas strokes (3.4), on a small resident
layer, so the palette and the canvas share one implementation of the transaction. They differ in
what a texel holds: the board stores recipes (12 concentration channels), the canvas stores colour plus
a lazily allocated 4-channel basis latent (6.2 item 4), and the transaction reads whichever the surface
has. Board pickup never goes through the RGB-to-latent lookup.

### 6.4 What stays digital, and why

The owner's rule: feel digital only when it lets you do something impossible in real life. The list
below is the complete set worth keeping, each with the reason it earns its place. Everything else
should be physical.

| Affordance | Why it stays |
|---|---|
| **Undo / redo** | Physically impossible; the central digital gift. Material-aware undo already exists (status doc Phase 5). |
| **Infinite, instant palette cleaning** (a "clean board" action) | Real boards need scraping; here it is a single tap. Using a tube still spends paint (6.3). |
| **Unlimited tube refills** | A tube is a mark of a physical limit, but running out of paint mid-stroke is not the experience the app should enforce at scale. Refill is free. |
| **Eyedropper from the canvas and from a reference photo** | A painter matches colour by eye and mixing; sampling is impossible and useful. The sampled colour (or typed hex) becomes a **target** shown beside the brush, plus the engine's best **recipe** of tubes (6.5), rather than a magic colour. It does not paint the picked colour directly. |
| **Layers, blend modes, masks, selections** | Not paint. Not the painterly scope. Retained as-is. |
| **Reference overlays, tracing** | The app's core purpose in the wider product. Retained. |
| **Symmetry, transforms** | Impossible with a brush; keep. |
| **Dry time control** (fast-forward drying) | A real glaze waits hours; here the artist can say "dry now". Affects the wet field only. |
| **Alpha lock, clipping** | Standard masking, not paint behaviour. |
| **Cloud / pack sharing, replaying a stroke list** | Inherently digital; the demo-sharing goal depends on it. |
| **Brush tool choice and size** | Real artists pick a tool; picking and sizing remain. Wetness and load are deliberately **not** sliders (decision 6): they are felt through dipping and use. |

**The physical palette/mixer replaces the current colour picker entirely (decision 2).** The HSV
disc, Harmony tab, free-floating opacity slider and the saved-ARGB swatch list are removed. The only
survivors are small, clearly labelled **"Digital"** affordances: the eyedropper (from the reference
or canvas, returning a recipe, 6.5) and **hex entry** (trivially cheap; it also returns a recipe, and
says when the nearest tube mixture is off). Existing saved palettes (`PaletteCodec`) are migrated by
treating each saved ARGB as an eyedropper target and storing its recipe, so no user's swatches are
lost (the original ARGB is kept next to the recipe for exactness). A project whose strokes record
`colorArgb` (`EditorModels.kt:446`) keeps replaying it as before; only *choosing* colour changes.

### 6.5 The eyedropper as recipe

Because the palette is physical, a sampled colour from a photo cannot be *loaded* directly. The
engine instead inverts the model: given a target RGB, find the nearest concentration vector over the
tubes on the palette (a small bounded search, deterministic, using the same lookup table), and
present it as a recipe ("about 55% titanium white, 30% cadmium red medium, 15% yellow ochre") plus an
**auto-mix** button that squeezes and mixes those blobs on the board. This keeps the physical model
and still delivers the digital superpower. If no recipe lands within a tolerance the UI says so and
shows the closest, never silently quantising.

Persistence: the brush's load and the palette board persist in the project (`Project` gains an
optional `painterly` block, absent = empty), with the sidecar rule of fail-closed (4.3).

### 6.6 Accessibility requirements

Requirements, testable where marked:

1. **Never colour alone.** Every tube has a name and a physical note; every load column has a
   non-colour cue (a pattern or letter code for the dominant pigment, shown on request). Mixed
   blobs show their recipe text. Test: no screen conveys information only by hue.
2. **Colour-vision deficiency.** An optional "check" mode simulates protanopia, deuteranopia and
   tritanopia on the palette and on the canvas preview; a tube's name never depends on being able
   to see it. Test: render the 20-tube set under each simulation and assert distinct patterns remain.
3. **Contrast.** All text and icons meet 4.5:1 (3:1 for large text and graphical objects) against the
   palette board and against the surrounding chrome in both themes. The board itself is paint, so
   nothing is placed over it without a backing scrim.
4. **Touch targets.** At least 48 x 48 dp (the existing swatch size, `SketchToolsDialog.kt:145,442`),
   with at least 8 dp between targets; blobs on the board are larger than that or have a minimum
   hit area while smaller than 48 dp visually.
5. **Gestures have alternatives.** Dab-and-mix by drag has a tap-and-step alternative ("mix once",
   "mix more") for users who cannot drag; every board action is available as a button.
6. **Screen reader.** Each blob, column and tube announces its name, quantity and mixture;
   state changes are announced ("brush loaded: 62% cadmium red medium"); mirrors the existing
   `contentDescription` use at `SketchToolsDialog.kt:161,450`.
7. **Reduced motion.** Mix animation and blob settling respect reduced-motion; mixing still completes.
8. **Haptics optional** and never the only feedback.
9. **Text size** follows system font scale up to 200% without clipping the board controls.

### 6.7 Performance budgets (mid-range Android)

All numbers are **targets to be measured**, not measurements. I did not run anything on a device
(section 9). They use the repository's own tiers (`E/BrushPerformancePolicy.kt:10-21`) and its
hardware floor (Android 10, Vulkan 1.1, `ARCHITECTURE.md` "Graffux's hardware floor").

| Budget | Target on the BALANCED tier |
|---|---|
| Live stroke latency (input to pixel) | No worse than today's stamp brush; the Kotlin work is on the generator thread; the material transaction runs on the existing GPU render thread (`GpuRenderThread`) and must not add readback to the live path |
| Generator cost per dab step | Painterly programs at most 8,000 ops p95 (the limit is 40,000; the current healthy range is "low thousands", `spec/stroke-program.md` authoring notes) |
| Emits per step | At most 48 lanes per step on BALANCED, 24 on CONSTRAINED, 96 on FULL; extends the existing tier cap |
| Pigment mix | At most 12 latent mixes per dab step; table-driven exp/pow |
| Paint transaction (GPU) | At most 48 dabs per batch, footprint at most 64 x 64 px each at 1x; one extra dispatch per batch; target under 2 ms per batch on a mid-range Mali/Adreno (to measure) |
| GPU memory | Latent lookup texture 32^3 x 4 B (about 128 KB), `avail` and `snap_color` buffers sized to the dirty rect, load buffer under 8 KB (128 cells x 16 floats) |
| Load state | At most 128 cells, copied per step as a small array, no allocation in the step loop |
| Palette board | 512 x 512 default (6 MB), at most 1024 x 1024 (24 MB); 16 B per texel of concentration plus 4 B height and 4 B wetness; knife mixing uses the same budgets |
| Memory added by a painterly document | Default board 6 MB, canvas latent about 4 B per touched pixel (about 5 MB at 30% coverage of 2048 x 2048), lookup 128 KB: about 12 MB typical; at most about 35 MB beyond today at the board cap |
| Drawn brush (E9) | At most the tier's tuft count as short strokes and `maxPreviewCells` hair cells; under 0.5 ms draw per frame on BALANCED; allocation-free frame build |
| Shading (E5) | One extra dot product and one `pow` approximation per shaded pixel; at most +10% on the existing impasto shade pass |

CONSTRAINED devices degrade deterministically by capping lane count and disabling `transaction` (the brush then draws without lift or wet mixing), in the
same way the tuft cap is frozen into the per-stroke brush snapshot, so live and replay agree.

## 7. Phased build plan

Each phase ends in something usable. Ordering follows the standing rule that mechanics win over
material work. P1 and P2 need no new pigment model; P3 supplies it.

| Phase | Deliver | Depends on | Exit gate |
|---|---|---|---|
| **P0 — Read-only groundwork** (done) | Read the stamp-stroke persistence path; read how a live stamp stroke reaches the wet simulation and `SAVE_RAW` (E3, now P5's first task); write the K/S fitting script and placeholder data format | none | Findings written into this spec; no code behaviour change |
| **P1 — Lift tail and the petal harness** (E6) | Phase-3 emit tests; "petal pull" test stroke in `PackRenderHarness`; proof-of-concept Loaded Petal Flat from today's features | P0 | Harness renders a recognisable petal; tests pin tail determinism and replay |
| **P2 — Edge ridge and highlight** (E5) | `emit h`, `heightMul`, `sheen`, shading port, stamp-engine-diff scenario | P1 | Rim bead and dry highlight visible; CPU and wgpu parity within tolerance; `sheen = 0` byte-identical |
| **P3 — Pigment model** (E4) | Kotlin `PigmentMixer` (reference/oracle), K-M, pigment data format (`dataQuality`/`dataVersion`), ~12 placeholder tubes, **concentration quantiser (sum-255 over 16 channels, largest remainder) with an error-bound test**, basis-latent lookup generator (4 fixed basis pigments, residual); **WGSL port of the per-pixel mixer** (`pigment.wgsl`) with a Rust parity test; the lookup's 3-D texture upload and sampling come with P5, where the transaction first uses them | P0 | Golden fixtures pass; white-tint behaviour pinned; lookup round-trips; Kotlin vs WGSL (lavapipe) within 2 levels |
| **P4 — Brush load** (E2) | `load` block; Kotlin budget; **GPU load buffer** carried between batches and read back at stroke end; finite depletion | P3 | A double-loaded flat depletes unevenly and replays identically (same device) |
| **P4b — Live brush rendering** (E9) | `BrushPresentationFrame` accessor on the generator (read-only, allocation-free), opaque Compose overlay (default on; a single non-promoted "Hide drawn brush" accessibility/power setting), anchor invariant, load-coloured tips from the Kotlin budget, golden frames; **no latency claim** | P4 (load) | Opaque brush drawn at the contact point with splay, pressure flattening, tilt/azimuth where reported or a held-brush pose otherwise, depleting load; hiding it changes nothing in strokes |
| **P5 — Paint transaction** (E3) | **GPU `paint_transaction` pass**: sample, lift (consuming `avail` and the surface), draw, K-M wet mix; `snap_color`/`avail` buffers; Kotlin reference; Rust tests including the repeated-lift-cannot-refill case and own-deposit-not-lifted case | P3, P4 | Petal pull over wet paint picks up and blends; lifts deplete the source; parity within tolerance; replay-equality across an undo cycle |
| **P6 — Palette/mixer replaces picker** | Board with **16-channel (12 tube + 4 basis) persisted concentration field and slot table**, **material sidecar v4 (`FLAG_CONCENTRATION`, `FLAG_LATENT`)** and codec tests (v3 fail-closed, v4 round trip), canvas basis-latent tiles written by the transaction, Board, blobs, knife/brush mixing, dip/load/clean, recipe eyedropper and hex, saved-palette migration, removal of the old picker | P3-P5 | Paint a petal using only the mixer; accessibility checks pass; old palettes migrate |
| **P6b — Integration** | **Wire the pieces so the packs can actually paint.** (1) The GPU paint transaction runs on the **resident canvas/board surface** inside the wgpu engine (concentration/amount/avail/snap buffers beside the resident layer, `SAVE_RAW`-style touchdown snapshot, the appended `Dab` intent slots in the wgpu layout, `layout_parity.rs`); (2) **brush load persists across strokes** (GPU load buffer read back at stroke end into an editor-session `BrushLoadStore`, restored at the next touchdown; persisted with the project); (3) **finite paint on the brush drives the stroke path** (a painterly stroke's dabs go through the transaction; load depletes; a dry brush paints dry); (4) **board persistence**: the palette board saved and loaded through the sidecar v4 in the project; (5) **the board's mixed paint feeds a painterly brush load** (the board brush's recipe becomes the stroke's starting load, not just an ARGB colour); (6) **live brush rendering** (E9: `BrushPresentationFrame` accessor, opaque overlay, anchor invariant); (7) **removal of the CPU 96 x 96 stand-in**: the mixer drives the GPU board at the spec size. | P4, P4b, P5, P6 | A painterly brush loaded from the board paints with finite load and wet pickup on a real canvas; load and board survive reopen; replay equal on one device; the CPU board is gone |
| **P7 — Four packs** | `painterly.{acrylic,oil,water,ink}` + `painterly.pigments`, bundled, store-listing manifests prepared; brush alias table (5.6); removal of superseded brushes | P2-P6b, P8 | Packs install on first run; every brush has a rendered preview; old projects open and replay |
| **P7b — Stroke capture and demo replay** | **Persistent stamp-stroke capture** (`StampStrokeRecord`: brush ref by pack id and version plus a params hash, stroke seed, full `BrushSample`s with tilt/time/phase, colour or recipe, size, opacity, canvas transform, substrate preset, wet-ledger prior film if the brush uses it) in a new per-layer project sidecar; a **replay consumer** that feeds recorded strokes through the editor's stroke pipeline at recorded timing; a `StrokeRecording` export/import; project-format version bump with a fail-closed reader | P7 | A recorded petal replays pixel-identically on the same device after reopen, and within tolerance on another; a pack plus a recording shares a demo |
| **P8 — Watercolour engine** (E7) | GPU wicking/bleed (absorbency texture), granulation, edge flux in the ADVANCE family; Kotlin reference extended; Rust `cpu_advance` extended | P3, P5 | Needed by `painterly.water`; can run in parallel with P6 |
| **P9 — Hardening** | Device matrix, GPU dispatch and memory tuning, accessibility audit, **brush drawn in the direct-display layer and its latency measured against touch-to-paint** (E9 item 3) | all | Budgets in 6.7 met on at least one mid-range device |

P7b exists because the project does not persist stamp strokes today (5.5); demo sharing (decision 9) depends on it and is not claimed before it lands. Order of implementation PRs follows the owner's instruction: engine gaps (P1-P5) first, then the
palette/mixer UI (P6), then the packs (P7), with P8 slotted before P7's water pack. Each phase is its
own PR.

Phase P1 is deliberately useful on its own: it shows what the owner's reference needs *without any
engine risk*, and its result informs how much of P3 to P5 is worth the cost.

## 8. Test strategy

### 8.1 What exists (and what does not)

- Pure Kotlin, `commonTest`: numeric golden fixtures, e.g. `MaterialGoldenFixtureMatrixTest.kt`,
  `MaterialColorMixerTest.kt`, `StrokeProgramTest.kt` (21 tests), `StrokeProgramLedgerTest.kt` (16),
  live-vs-replay parity tests (`BrushReservoirChargeParityTest`, `BrushContactPhaseDabParityTest`, ...).
- Rust `core/wgpu-engine/tests/` (`layout_parity.rs`, `stamp_modes.rs`, `impasto.rs`, `wet.rs`,
  `smudge_wet.rs`, ...) plus `tools/stamp-engine-diff` byte comparison.
- `PackRenderHarness` renders packs through the real generator against six strokes.
- **No property-testing library** appears in `core/engine/build.gradle.kts` or
  `gradle/libs.versions.toml` (a grep for property/kotest/checkAll found nothing). Property-style
  tests below are written as seeded sweeps in plain `kotlin.test`, which is how the existing
  stress test (`AzphaltEngine2StressTest`) works.
- CI has no emulator or physical GPU; `connectedAndroidTest` is not in the pipeline (status doc header). It does install Mesa's software Vulkan driver (lavapipe) and sets `GRAFFUX_REQUIRE_GPU=vulkan` so the Rust GPU tests run instead of skipping (`.github/workflows/merged-build.yml:69-75`); WGSL parity tests added by this spec run there.
  Real-device parity is manual.

### 8.2 What to add

| Layer | Test |
|---|---|
| Back-compat | Golden dab lists for every built-in brush and every Graffux-targeted pack in `submissions/`, generated from current main and committed; the new build must reproduce them exactly (rule 4.1) |
| Determinism | Replay-equality: random-seeded stroke, run live-incrementally, run full replay, assert identical dabs including load state, for every new function |
| Seeded sweeps (property-style) | For 1,000 seeded strokes: load never goes negative or above capacity; total pigment is conserved by mixing (within tolerance); `draw` never exceeds `available`; `lift` never over-fills a column; emitted alpha is in 0..1; no non-finite values |
| Pigment golden vectors | A fixed table of mixtures (white + each tube at 5 ratios; complementary pairs; 3-way) with expected sRGB to within 2 levels, in a `PigmentMixerGoldenTest` extending the existing matrix. Includes white-tinting monotonicity and order symmetry (as `MaterialColorMixerTest` already pins for RYB) |
| Lookup round-trip | For a grid of RGB values, `toLatent -> toArgb` returns within 1 level on the stored set |
| Ridge conservation | Deposited height volume over a pass is bounded by load drawn, within tolerance (E5) |
| Golden images | Render the petal pull, the Loaded Petal Flat, and a six-brush strip through the harness and compare to committed PNGs with a per-pixel tolerance; images are regenerated only by an explicit script and reviewed by eye. Stay small (256 px) to keep the repository light |
| CPU/GPU parity | New `tools/stamp-engine-diff` scenarios: dab with explicit pigment colour, `heightMul`, `sheen`; assert within the existing 1 to 2 level tolerance |
| Layout parity | Extend `layout_parity.rs` for any appended dab slot |
| Drawn brush | Golden frames (desktop CPU rasteriser of the overlay, committed PNGs, per-pixel tolerance) for brush x pose x pressure x load; **anchor invariant** (contact patch within 0.5 px of the dab centre across zoom, pan, rotation, layer transform and the 3D mapping); single-source test (overlay inputs are the generator's published frame, not recomputed); determinism (same samples give the same frame); hiding the brush leaves dabs byte-identical; frame-build cost relative guard |
| Perf | A harness `peakOps` and wall-time guard over the demo pack; fail above the 6.7 budgets on the desktop test machine (a relative, not absolute, check) |
| Accessibility | Compose semantics tests for names/descriptions/targets (min 48 dp); a colour-vision simulation unit test on the palette |
| Manual device | Matrix on a low-end, a mid-range and a high-end device for budgets and for the felt quality of the petal pull; recorded, not CI |

## 9. Things I could not verify

- **The Reel.** I did not view it. Section 0.1 is the owner's description only.
- **No code was run.** No Gradle build, no test, no harness render, no device. All findings are from
  reading source and docs; status claims ("supported") reflect the code's own docs and tests, not a
  fresh run. `StrokeProgramTest` lacking a lift-off test is from a text search, not a run.
- **Pigment data.** I have no reflectance or K/S data for any pigment and have not invented any.
  `bands: [ ... ]` in 6.1 is a placeholder; the baseline tube list is a selection to source.
- **Band count and lookup size** (7 bands, 32-cube, 130 KB) are design estimates, not tuned values.
- **Licences from memory.** I believe Mixbox is licensed for non-commercial use by default with a
  commercial licence available, and that other open spectral-mixing libraries exist under permissive
  licences. I did not verify either from a source; do not rely on this section for a legal decision.
- **Performance numbers** in 6.7 are targets, not measurements. The existing tier caps
  (16/32/96) are real; everything else is proposed.
- **Whether the proprietary kinetic sets are priced.** The owner says they are free; no manifest says
  so either way (no price field exists in them), and I did not read the live registry.
- **Whether the GPU `snap_color` copy at touchdown (E3) is available on every painterly path.** The
  `SAVE_RAW` op exists on the wet simulation (`GpuWetSimulation.kt:56-58`) and `commitStampStroke`
  receives `startBitmap` (`EditorViewModel.kt:~9208`), but I did not trace how a live stamp stroke on a
  resident layer reaches the wet simulation, which is the P5 starting task.
- **`E/IncrementalDynamicDabGenerator.kt` internals beyond lines 59-118 and 355** (where
  `StrokeProgramFrame` is built) were not read in full.
- The `Project` format change for persisting load (6.5) was not designed against the current project
  version; it is proposed as an optional block only.

- **Rust and GPU verification in this environment (checked, P2):** `cargo` builds `core/wgpu-engine`
  here and the Rust tests run on Mesa lavapipe (Vulkan); the GL backend has no adapter and skips.
  With `GRAFFUX_REQUIRE_GPU=vulkan` the GPU tests run rather than skip. So the WGSL stages this
  spec adds can be parity-tested against their Kotlin references here and in CI, but **only on a
  software Vulkan driver**: nothing here runs on Mali/Adreno, so device-specific float behaviour and
  real performance remain unverified until the P9 device matrix.
- **Drawn brush (E9), not measured:** the real touch-to-paint latency on a device and whether a Compose overlay lags the direct-display paint; whether `BrushPresentationFrame` can be published without measurable cost in the live path (`EditorViewModel` is very large and I did not trace the live generator call site). The hover code and the contact state were read, not run.
- **Whether a K-M latent lookup in a compute pass meets the 2 ms target** is a design estimate.

## 10. Owner decisions

The ten questions in the first draft are answered. Recorded here so the spec and the code agree.

| # | Decision |
|---|---|
| 1 | Four separate packs: acrylic, oil, water(colour), ink. All free, Apache-2.0, bundled with the app, each also listed in the azphalt store as `kind: "asset"`. azphalt itself is not changed; submission manifests are prepared in the style of the existing brush submissions (5.5). |
| 2 | The physical palette/mixer replaces the colour picker entirely. Only small, clearly labelled "Digital" affordances remain: eyedropper, and hex entry (6.4). |
| 3 | Kubelka-Munk applies to the canvas as well as the palette and brush (6.2). |
| 4 | Calibrated pigment data is the goal. v1 ships believable hand-tuned K/S behind a data format that accepts calibrated numbers later; placeholder data is marked (`dataQuality`, 6.1). |
| 5 | v1: a stroke does not pick up its own wet pass (touchdown snapshot). Documented in E3; the door stays open. |
| 6 | Paint is finite in the mixer. Wetness and loaded amount are felt through use, not shown as numeric sliders (6.3). |
| 7 | No real manufacturer names. Generic pigment names are fine; if a product must be referenced, an off-brand wink in the Mr. Pibb / Dr Thunder spirit (6.1). |
| 8 | Pricing is free (decision 1). |
| 9 | Demo sharing: a pack plus a replayable stroke list. Video export later (5.5). In plain terms: the app plays your recorded pen movements back through the brushes, so someone else sees the same petal being painted; a video file is a separate, later feature. |
| 10 | Superseded old brushes are removed. Saved projects hold bitmaps, not brush references, so they are unaffected; favorites and the active brush resolve through an alias table with a Round fallback (5.6). |

### 10.1 What is still open

Not owner questions; implementation unknowns that the groundwork phase (P0) resolves:

- ~~How stamp-brush strokes persist their brush in the project~~ Resolved in P0 (5.6): they do not.
- Touchdown snapshot (E3): P0 found `commitStampStroke` receives `startBitmap`, "the layer bitmap the stroke started on (and its live GPU layer was seeded from)" (`EditorViewModel.kt:~9208`), so a CPU copy exists at stroke start. Still to confirm: that it is the pre-stroke content for a stroke on a resident GPU layer, and how a live stamp stroke reaches the wet simulation so the GPU can take `snap_color` with `SAVE_RAW` (the generator never reads it). Settled at the start of P5.
- The final wavelength band count once the lookup cost is measured on a device.
- Whether canvas K-M meets the budget on the CONSTRAINED tier. The lookup runs as a GPU texture on every tier (there is no CPU runtime path, 3.0); the open question is the cost, not the placement.

## 11. Risks

| Risk | Effect | Mitigation |
|---|---|---|
| Replay divergence from GPU material state/load | Undo shows different paint | Persist load per stroke, restore canonical state or a GPU checkpoint, fixed timestep (4a); replay-equality test is a merge gate (4.4) |
| Canvas cannot tell metamers apart | Two recipes with the same basis projection mix alike on the canvas | Stated in 6.2; tube identity lives on the board and in the brush's tube part; accept for v1 |
| Concentration quantisation drift | Repeated mixes round to u8 each time | Largest-remainder rule; error-bound test; limit mixes per texel per stroke; P3 test of 50 repeated mixes |
| Sidecar v4 | Older builds drop board/latent | Fail-closed per layer; board is regenerable from slot table only if the field survives, so back up is the project file |
| The drawn brush covers the work | A user who cannot cope with a large moving object over the canvas | Intended behaviour, not a defect (owner direction); the non-default, non-promoted "Hide drawn brush" setting serves accessibility and low-power only (E9 item 4) |
| Brush overlay lags the paint | Perceived lag gets worse | Overlay in a later layer than direct display: v1 makes no latency claim; v2 draws in the direct-display layer and is measured (E9 item 3) |
| Drawn brush disagrees with the paint | Mistrust | One source: a read-only frame published by the same generator step; never recomputed (E9 item 1); the load picture is a budget corrected at stroke end |
| Overlay cost on mid-range | Frame drops | Tuft cap by tier, under 0.5 ms target, drops to fewer hair cells for a frame, never delays paint |
| Dab-level mixing looks banded against the canvas | Visible lane edges | Lane count tuned by tier; sub-pixel overlap; `mixWet` is a gradient, not a step |
| K-M data quality | Colours look wrong or muddy | Source real data first (P0); golden vectors; allow tube tuning in packs |
| 8-bit canvas quantisation of repeated mixes | Muddy drift after many mixes | Residual in the lookup keeps unmixed pixels exact; limit mixes per pixel per stroke; test with 50 repeated mixes |
| Mid-range cost | Lag in the live path | Tier caps; ops budgets; `surface` disabled on CONSTRAINED |
| Watercolour engine scope | Delays the water pack | P8 is bounded to wicking, granulation, edge darkening; the rest of the water pack works without it |
| Removing old brushes breaks saved state | Favorites/active brush dangling | Alias table with Round fallback (5.6); saved projects are unaffected |
| Canvas-wide K-M cost | Slow painterly layers | Lookup as a GPU texture; dispatch caps in 6.7; non-painterly layers keep the old path |
| GPU float differences across devices | A replay differs slightly on another device | Short replay windows from stored canonical state (4a); stated tolerances; sidecar snapshots at commit |
| No real Android GPU in CI | Mali/Adreno-specific bugs unseen | Parity runs on lavapipe in CI; manual device matrix in P9; `GRAFFUX_REQUIRE_GPU` so a missing adapter fails rather than skips on a GPU host |
| Kotlin budget vs GPU truth disagree | A script's dry-out threshold fires a little early/late | Documented authoring inexactness (3.3); packs tolerate it; deterministic either way |
| Project format change in P7b | Old builds cannot read new projects | Optional sidecar; old builds ignore it; fail-closed reader |
| Existing users lose the familiar picker | Churn | Digital eyedropper and hex remain; saved palettes migrate to recipes (6.4) |
| The two engines disagree | Pixels differ live vs committed | Kotlin is the oracle; only shading is ported; parity scenarios in `tools/stamp-engine-diff` |
| Authoring difficulty | Few good packs | The petal pack is the template; `PackRenderHarness` previews; the idioms (lane, tail, ridge) are documented in section 5 |
