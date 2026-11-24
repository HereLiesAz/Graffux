# ProCraft adoption: brush compatibility

Source: [ProCraft revision 9e696baf579d733a92af59a3a445639ca3054d1c](https://github.com/artcraft-android/procraft/tree/9e696baf579d733a92af59a3a445639ca3054d1c), MIT OR Apache-2.0. The binary property-list and keyed archive reader design is adapted from `crates/engine/src/plist.rs` and `keyed.rs`; brush field semantics from `brushfile.rs` and `studio.rs`. Copyright (c) 2026 ArtCraft Team and the ProCraft contributors. The adaptation elects Apache-2.0; see `docs/licenses/ProCraft-APACHE.txt` and `docs/licenses/ProCraft-NOTICE.txt`.

## Decision

Keep the existing azphalt/Kotlin brush model, wgpu renderer and extension trust boundary. Originally the custom-brush repository was parameter-only because extension-relative assets cannot be resolved by custom-brush selection. This slice extends the same store with brush-owned PNG files, without bypassing azphalt package verification.

## First slice

The existing Import File action recognizes a single `.brush` by display name before project import. It decodes the root keyed archive on the IO dispatcher, saves a fresh UUID custom brush, and explicitly reports the parameter-only conversion. It does not modify the active project, active tool or selected brush. Supported fields: name, spacing, roundness, size and opacity jitter, count and count jitter. The imported brush is editable and selectable through existing My Brushes. The file size, total inflated bytes, entry count, archive bytes, object count, dictionary and string sizes are bounded; archive entries are never extracted. This is a compatibility subset, not Procreate rendering parity.

## Second slice: embedded shape and grain

Bounded PNG/JPEG archive entries resolve by declared name or default filename, without extracting paths. Embedded images are sampled to a 1024-pixel longest edge and normalized to the existing luminance/alpha coverage convention; archive inversion is respected. Fixed brush-owned PNG sidecars precede atomic metadata publication. Editing the saved brush preserves them; deletion removes them after metadata disappears. Selection loads these images into the existing stroke command, which carries them through live rendering and in-memory replay. Missing declared assets are reported. Selection currently decodes synchronously, and copying a new draft strips assets as before. Orientation, scale, secondary tips, pressure curves and rendering parity remain incomplete. Process death before metadata publication can leave invisible orphan sidecars.

The existing thumbnail renderer produces textured custom-brush stroke previews for My Brushes and the carousel; Brush Studio uses the same renderer with current draft settings, including grain-only brushes. The carousel thumbnail is fixed-size rather than a live Size/Flow preview; textured cards use an import glyph rather than a misleading round-tip icon.

## Remaining work

- Secondary tips, texture orientation/scale, imported-brush copying with assets, orphan cleanup, asynchronous selection and dynamic textured carousel previews. Verify paint/undo/reopen and deletion on device.
- Brush-set/library ordering, import and export with loss reporting.
- Pressure curves, stabilization, taper, rendering, wet mixing and other dynamics; compare to known independently generated fixtures.
- Desktop import adapter over the same portable format semantics.
- Device rendering and memory benchmarks before any engine replacement.
- Additional malformed archive/fuzz tests, Android build, and independent GLEE review.

Do not mark complete until exercised through the user-facing import path and verified on Android. No stock brush settings or proprietary textures copied.
