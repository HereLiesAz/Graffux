# Material-Aware Paint Engine — Implementation Status

Companion to `Material-Aware Paint Engine Roadmap.md`.

This file records what has actually landed. The roadmap describes intended architecture; this file is the implementation checkpoint.

## 2026-09-13 — Phase 0 / Phase 1 start

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
- `ColorSmudgeEngine.Settings` now carries `mixingModel`, defaulting to `LEGACY_RGB`.
- CPU Color Smudge uses the material mixer for pickup, deposition, dilution and Dulling/Smear interpolation only when pigment mode is selected.
- The original `lerpArgb` path remains the explicit legacy branch, so existing presets and stroke replay do not opt into new colour behavior accidentally.
- `DrawingEngine` deliberately disables the current Vulkan Color Smudge path for non-legacy mixing until shader parity is implemented. Pigment mode therefore uses the deterministic CPU reference rather than producing different pixels depending on device capability.

### Tests added

Common-engine tests cover:

- ratio endpoints;
- legacy RGB interpolation;
- yellow + blue producing a green-dominant pigment result;
- complementary-ratio order symmetry;
- alpha preservation;
- material-medium defaults and clamping;
- reservoir-state clamping;
- allocation-free/default color-only material-channel declaration.

Editor integration tests cover:

- default Color Smudge mixing staying byte-identical to explicit `LEGACY_RGB`;
- yellow/blue pigment interaction becoming green-dominant;
- pigment mode producing visibly different output from legacy RGB for the same interaction.

### Compatibility contract

The default behavior remains legacy RGB. No existing brush, imported preset, replayed stroke or standard Color Smudge configuration opts into pigment behavior merely because this code exists.

### Validation limitation

These implementation commits use `[skip ci]` intentionally. The repository's current `release-aab.yml` publishes on every push to `main`, so firing ordinary push CI for this development checkpoint would also initiate a Google Play release. The added tests are therefore committed but have not been claimed as executed by GitHub Actions in this checkpoint.

### Phase 1 still open

Before Phase 1 can be called complete:

1. Port the exact RYB material-mix transform to `color_smudge.comp`.
2. Carry mixing-model selection through Kotlin → JNI → native Vulkan push constants.
3. Add CPU/Vulkan parity vectors and physical-device validation.
4. Expand pigment fixtures beyond yellow/blue to cyan/red, high-chroma complements, black/white tinting and repeated mixing.
5. Decide the product exposure surface only after CPU/GPU parity exists; do not ship a UI switch that silently changes execution backends.

### Next implementation target

**Vulkan pigment-mixing parity for Color Smudge.**

Do not begin wetness fields, reservoir depletion/pickup behavior, substrate transport or new persistent layer channels until Phase 1's CPU/GPU visible-parity gate is satisfied.
