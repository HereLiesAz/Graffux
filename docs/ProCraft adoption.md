# ProCraft adoption: brush compatibility

Source: [ProCraft revision 9e696baf579d733a92af59a3a445639ca3054d1c](https://github.com/artcraft-android/procraft/tree/9e696baf579d733a92af59a3a445639ca3054d1c), MIT OR Apache-2.0. The binary property-list and keyed archive reader design is adapted from `crates/engine/src/plist.rs` and `keyed.rs`; brush field semantics from `brushfile.rs` and `studio.rs`. Copyright (c) 2026 ArtCraft Team and the ProCraft contributors. The adaptation elects Apache-2.0; see `docs/licenses/ProCraft-APACHE.txt` and `docs/licenses/ProCraft-NOTICE.txt`.

## Decision

Keep the existing azphalt/Kotlin brush model, wgpu renderer and extension trust boundary. The existing custom-brush repository deliberately stores parameters only: extension-relative tip files are not resolved by custom-brush selection or Brush Studio. Import therefore uses that existing store without bypassing azphalt package verification or pretending to import texture assets.

## First slice

The existing Import File action recognizes a single `.brush` by display name before project import. It decodes the root keyed archive on the IO dispatcher, saves a fresh UUID custom brush, and explicitly reports the parameter-only conversion. It does not modify the active project, active tool or selected brush. Supported fields: name, spacing, roundness, size and opacity jitter, count and count jitter. The imported brush is editable and selectable through existing My Brushes. The file size, total inflated bytes, entry count, archive bytes, object count, dictionary and string sizes are bounded; archive entries are never extracted. This is a compatibility subset, not Procreate rendering parity.

## Remaining work

- Asset ownership and persistence for imported shape, grain, secondary tips; resolve the same assets in live preview, replay, Brush Studio and thumbnail generation.
- Brush-set/library ordering, import and export with loss reporting.
- Pressure curves, stabilization, taper, rendering, wet mixing and other dynamics; compare to known independently generated fixtures.
- Desktop import adapter over the same portable format semantics.
- Device rendering and memory benchmarks before any engine replacement.
- Additional malformed archive/fuzz tests, Android build, and independent GLEE review.

Do not mark complete until exercised through the user-facing import path and verified on Android. No stock brush settings or proprietary textures copied.
