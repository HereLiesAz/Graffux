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

## 2026-09-13 — Phase 2 start: deterministic reservoir contract

### Implemented

A renderer-independent `BrushReservoirModel` now defines deterministic stroke-local reservoir transitions before those transitions are wired into visible paint behavior.

It currently provides:

- `stateAtDistance(...)` — an analytic exponential load envelope matching Color Smudge's existing Charge decay;
- `effectiveDeposition(...)` — base deposition modulated by available load while preserving the historical final-clamp order;
- bounded deposition that cannot draw the reservoir below zero;
- bounded pickup that cannot fill the reservoir above capacity;
- load-weighted carried-colour contamination;
- load-weighted wetness transfer;
- selection of the Phase 1 material mixer for contamination;
- value-typed deterministic transitions suitable for canonical replay and CPU/GPU parity work.

The model deliberately has no bitmap, editor, tile, JNI or Vulkan dependency. Contact-area and material-field logic can evolve independently around this stable transition contract.

### Reservoir tests

Common-engine tests now cover:

- zero-rate depletion;
- the exponential depletion envelope;
- negative rate/distance bounds;
- final-clamp compatibility with historical Color Smudge rate semantics;
- no reservoir overdraw or overfill;
- load-weighted pickup contamination;
- pigment-space pickup contamination;
- repeated-sequence determinism;
- direct parity between the old Color Smudge Charge equation and the new reservoir load/deposition calculation across multiple stroke distances.

Safe branch validation run **#2108** (`ci/reservoir-model-validation-3`) completed successfully from the frozen current head:

- full unit-test job: **success**;
- Android `assembleDebug`: **success**;
- release/publishing steps: correctly skipped.

### Compatibility state

This first Phase 2 tranche changes **no visible paint behavior** yet.

- `ColorSmudgeEngine` still evaluates its existing Charge decay directly.
- No new Pickup control is exposed.
- No new persistent canvas material channel is allocated.
- No Vulkan pickup implementation is enabled.
- Ordinary brushes remain unchanged.

### Next implementation target

1. Route the existing `chargeDecayRate` calculation through `BrushReservoirModel` and prove replay/output compatibility.
2. Add explicit reservoir pickup to the CPU reference behind a default-zero setting; when pickup is non-zero, force the CPU path until the native equivalent exists rather than silently ignoring the state on Vulkan.
3. Port the same reservoir transition to Vulkan and add hardware CPU/GPU parity coverage.
4. Expose the minimal **Load / Pickup** product controls only after CPU/GPU behavior is aligned.
5. Do not begin persistent wetness fields, substrate transport or height-v2 work until the reservoir gate is stable.
