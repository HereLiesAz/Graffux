# Material-Aware Paint Engine — Implementation Status

Companion to `Material-Aware Paint Engine Roadmap.md`.

This file records what has actually landed. The roadmap describes intended architecture; this file is the implementation checkpoint.

## 2026-09-13 — Phase 0 / Phase 1 implementation

### Implemented

- Renderer-independent `MaterialColor` model in `core/engine`.
- `MaterialMixingModel` with:
  - `LEGACY_RGB` — historical compatibility path.
  - `PIGMENT_RYB` — first bounded subtractive/artist-space approximation.
- Deterministic `MaterialColorMixer` CPU reference.
- RYB latent transform is explicitly an approximation, not a claim of full Kubelka-Munk or spectral simulation.
- Phase-0 material scaffolding:
  - `PaintMedium` immutable configuration.
  - `BrushReservoirState` stroke-local live state container.
  - `MaterialChannels` optional layer-channel declaration.
- `ColorSmudgeEngine.Settings` carries `mixingModel`, defaulting to `LEGACY_RGB`.
- CPU Color Smudge uses the material mixer for pickup, deposition, dilution and Dulling/Smear interpolation only when pigment mode is selected.
- The original `lerpArgb` path remains the explicit legacy branch, so existing presets and stroke replay do not opt into new colour behavior accidentally.
- The exact CPU RYB transform is ported to `color_smudge.comp`.
- Native Color Smudge mode values remain ABI-compatible:
  - `0` / `1` = legacy RGB Smear / Dulling;
  - `2` / `3` = pigment RYB Smear / Dulling.
- Pigment selection is carried through Kotlin/native Vulkan without expanding the JNI signature or the Vulkan push-constant layout.
- `DrawingEngine` now allows pigment Color Smudge to use Vulkan; failed or unavailable GPU execution still recomputes from the pristine source through the CPU reference.
- Color Smudge's CPU weighted-average colour packing uses Graffux's platform-independent `ArgbColor` helper, so the same code is testable on the JVM rather than depending on Android framework stubs.

### Tests added

Common-engine pigment tests cover:

- ratio endpoints;
- legacy RGB interpolation;
- yellow + blue producing a green-dominant pigment result;
- cyan + red;
- high-chroma complements;
- black/white tinting;
- repeated mixing;
- complementary-ratio order symmetry;
- alpha preservation;
- material-medium defaults and clamping;
- reservoir-state clamping;
- allocation-free/default color-only material-channel declaration.

Editor integration tests cover:

- default Color Smudge mixing staying byte-identical to explicit `LEGACY_RGB`;
- yellow/blue pigment interaction becoming green-dominant;
- pigment mode producing visibly different output from legacy RGB for the same interaction.

Android instrumentation coverage now includes CPU ↔ Vulkan pigment Color Smudge parity for both Smear and Dulling with a bounded per-channel tolerance, plus a guard proving the pigment fixture does not collapse back to legacy RGB behavior.

### Validation

Safe branch validation run **#2105** (`ci/pigment-vulkan-validation-3`) completed successfully:

- full unit-test job: **success**;
- Android `assembleDebug`: **success**;
- native/CMake/shader build: **success**;
- release/publishing steps: correctly skipped because the validation ref was not `main`.

This proves the Phase 1 code and shader/native integration are build-valid and the hosted JVM suite is green.

The instrumentation CPU ↔ Vulkan pixel-parity test is compiled but has **not** been claimed as executed by hosted CI: it requires a real Android Vulkan device. That physical-device run remains the final hardware parity/product-exposure gate.

### Compatibility contract

The default behavior remains legacy RGB. Existing brushes, imported presets and replayed strokes do not opt into pigment behavior merely because the new mixer exists. Native mode values `0` and `1` retain their historical meaning.

---

## 2026-09-13 — Phase 2: deterministic reservoir and CPU pickup

### Reservoir contract

A renderer-independent `BrushReservoirModel` defines deterministic stroke-local reservoir transitions.

It provides:

- `stateAtDistance(...)` — an analytic exponential load envelope matching Color Smudge's historical Charge decay;
- `effectiveDeposition(...)` — base deposition modulated by available load while preserving the historical final-clamp order;
- bounded deposition that cannot draw the reservoir below zero;
- bounded pickup that cannot fill the reservoir above capacity;
- load-weighted carried-colour contamination;
- load-weighted wetness transfer;
- selection of the Phase 1 material mixer for contamination;
- value-typed deterministic transitions suitable for canonical replay and CPU/GPU parity work.

The model deliberately has no bitmap, editor, tile, JNI or Vulkan dependency. Contact-area and material-field logic can evolve independently around this transition contract.

### Charge integration

`ColorSmudgeEngine` now routes `chargeDecayRate` through `BrushReservoirModel` rather than evaluating a separate exponential formula in the editor layer.

Compatibility is pinned by an editor-level `resolvePlans()` regression that compares every resolved dab against the historical `colorRate * exp(-chargeDecayRate * distance)` equation.

Safe branch validation run **#2109** (`ci/reservoir-charge-integration`) completed successfully:

- full unit-test job: **success**;
- Android `assembleDebug`: **success**;
- native/CMake/shader build: **success**;
- release/publishing steps: correctly skipped.

### CPU reservoir pickup

`ColorSmudgeEngine.Settings` now includes `pickupRate`, defaulting to `0`.

When pickup is enabled on the CPU reference:

- the brush begins with reservoir load `1` and the stroke paint colour as its carried material;
- Charge depletion creates bounded empty reservoir capacity as the stroke travels;
- each dab samples one representative contact colour **before** the dab mutates pixels;
- after that dab renders, a `pickupRate` fraction of available capacity is refilled from the sampled material;
- sampled alpha scales material presence, so fully transparent canvas contributes no pickup mass;
- carried pigment contamination uses the selected material mixer (`LEGACY_RGB` or `PIGMENT_RYB`);
- the contaminated carried pigment is deposited by subsequent dabs, not retroactively by the dab that sampled it;
- Dulling reuses its existing weighted contact sample; Smear takes an equivalent weighted footprint sample for reservoir state while retaining its separate spatial carrier;
- Sample Merged supplies reservoir pickup from the same pre-composited source used by Color Smudge sampling;
- canvas wetness is not fabricated yet: pickup preserves current reservoir wetness until Phase 4 provides a real persistent wetness channel.

### Compatibility and backend gate

- `pickupRate = 0` takes the historical Color Smudge raster path and remains the default for old strokes and imported presets.
- Krita preset mapping leaves `pickupRate` at its Graffux default of `0`.
- Non-zero pickup is deliberately forced through the CPU reference in `DrawingEngine`.
- Vulkan pigment Color Smudge remains enabled when pickup is `0`.
- No native/Vulkan pickup implementation is active yet; the CPU gate prevents devices from silently ignoring pickup state.
- No Pickup UI control is exposed yet.
- No persistent canvas wetness/material channel is allocated by this tranche.

### Pickup tests added

Editor tests cover:

- implicit/default pickup zero being byte-identical to explicit `pickupRate = 0`;
- crossing opaque blue sampled material contaminating later yellow `PIGMENT_RYB` paint toward green;
- reservoir pickup reading the supplied Sample Merged composite;
- deterministic repeated pickup output for identical input/seed;
- the CPU-only backend gate being active only for positive pickup;
- imported Krita Color Smudge presets retaining default-zero pickup.

These pickup commits use `[skip ci]` on `main`; a safe non-release validation branch is used to run the complete test/build gate before this tranche is called complete.

### Next implementation target

1. Complete safe CI validation of the CPU pickup tranche.
2. Port the same stateful reservoir depletion/pickup transition to the native/Vulkan Color Smudge path.
3. Add CPU ↔ Vulkan reservoir/pickup parity instrumentation and run it on real Android Vulkan hardware.
4. Expose the minimal **Load / Pickup / Pigment Mixing** product controls only after CPU/GPU behavior is aligned.
5. Do not begin persistent wetness fields, substrate transport or Impasto-v2 material coupling until the reservoir gate is stable.
