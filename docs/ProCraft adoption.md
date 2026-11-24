# ProCraft adoption: brush compatibility

Source: [ProCraft revision 9e696baf579d733a92af59a3a445639ca3054d1c](https://github.com/artcraft-android/procraft/tree/9e696baf579d733a92af59a3a445639ca3054d1c), MIT OR Apache-2.0. The binary property-list and keyed archive reader design is adapted from `crates/engine/src/plist.rs` and `keyed.rs`; brush field semantics from `brushfile.rs` and `studio.rs`. Copyright (c) 2026 ArtCraft Team and the ProCraft contributors. The adaptation elects Apache-2.0; see `docs/licenses/ProCraft-APACHE.txt` and `docs/licenses/ProCraft-NOTICE.txt`.

## Decision

Keep the existing azphalt/Kotlin brush model, wgpu renderer and extension trust boundary. Originally the custom-brush repository was parameter-only because extension-relative assets cannot be resolved by custom-brush selection. This slice extends the same store with brush-owned PNG files, without bypassing azphalt package verification.

## First slice

The existing Import File action recognizes a single `.brush` by display name before project import. It decodes the root keyed archive on the IO dispatcher, saves a fresh UUID custom brush, and explicitly reports the parameter-only conversion. It does not modify the active project, active tool or selected brush. Supported fields: name, spacing, roundness, size and opacity jitter, count and count jitter. The imported brush is editable and selectable through existing My Brushes. The file size, total inflated bytes, entry count, archive bytes, object count, dictionary and string sizes are bounded; archive entries are never extracted. This is a compatibility subset, not Procreate rendering parity.

## Second slice: embedded shape and grain

Bounded PNG/JPEG archive entries resolve by declared name or default filename, without extracting paths. Embedded images are sampled to a 1024-pixel longest edge and normalized to the existing luminance/alpha coverage convention; archive inversion is respected. Fixed brush-owned PNG sidecars precede atomic metadata publication. Editing the saved brush preserves them; deletion removes them after metadata disappears. Selection loads these images into the existing stroke command, which carries them through live rendering and in-memory replay. Missing declared assets are reported. Selection currently decodes synchronously, and copying a new draft strips assets as before. Orientation, scale, secondary tips, pressure curves and rendering parity remain incomplete. Startup removes unowned interrupted-import sidecars, retaining assets whose metadata is corrupt.

The existing thumbnail renderer produces textured custom-brush stroke previews for My Brushes and the carousel; Brush Studio uses the same renderer with current draft settings, including grain-only brushes. The carousel thumbnail is fixed-size rather than a live Size/Flow preview; textured cards use an import glyph rather than a misleading round-tip icon.

## Color picker adoption

The Android editor's existing adaptive picker hosts ProCraft-style Disc, Classic, Harmony, Value and Palettes pages. Geometry adapts the elliptical square/disc mapping from upstream `crates/core/src/pickers.rs` (Apache-2.0, same attribution above). HSV coordinates are absolute and retained through achromatic choices. Both color changes and history commits use existing editor callbacks; saved swatches use the existing settings repository, not the project paint mixer. Primary/secondary swapping is restored in the digital panel. The selected page persists across panel openings in saveable UI state; the page tabs remain pinned below the scrolling picker body. The disc raster is bounded, initially generated synchronously, and regenerated off the UI thread when hue/value changes. Graffux retains its adaptive sheet/popover host for rail clearance and phone ergonomics. This first slice is sRGB HSV/RGB, with one existing saved palette, not the full ProCraft P3/OKLab/HSY model selector, named palette library, draggable floating picker or ColorDrop. Desktop and background picker are unchanged. Android compilation and visual/gesture verification remain outstanding.

### Named palettes

The Palettes page adds named, thirty-slot collections with stable IDs, compact or card layouts, create/rename/duplicate/delete and active selection. Empty slots save the current opaque RGB colour, filled slots pick it, and long press clears it. The active collection appears below the other picker pages. Legacy `saved_palette` remains an independent, nondeletable collection so no existing swatches are truncated or migrated again; named edits are atomic DataStore read-modify-write operations over `named_palettes_v1`. Strict bounded decoding rejects invalid persisted books rather than overwriting them with empty data. File/image import/export and swatch dragging remain separate follow-up work.

## Brush-set import (initial slice)

The same Android Import File action recognizes `.brushset`. XML and binary `brushset.plist` manifests identify the brush folders in order, following ProCraft's `brushfile.rs` set layout. Only listed folders are decoded, using the existing single-brush settings/texture decoder. Compressed and aggregate inflated bytes, member count, entry size and manifest size are bounded. The standard Apple XML DTD is removed before parsing; other declarations are rejected. No archive entries are extracted. Invalid member brushes and save failures are counted, successful brushes are saved independently, and unavailable textures and rendering differences are reported. Each set import assigns a fresh stable set ID, records its display name and relative order on each saved brush, and displays the grouped brushes under their set name in My Brushes. Standalone brushes remain alphabetic; imported brushes keep their relative source order across edits and reopen. Reimporting the same name creates an independent set. The editor swipe library exposes dedicated imported-set tabs, keyed by stable set ID rather than display name; loose Studio brushes remain in My Brushes. Empty imported sets remain unsupported. Android import and rendering verification remain outstanding.

## Brush-library import

The same import action accepts a ProCraft-compatible `.brushlibrary` ZIP of set folders and nested `.brush` files. Its `.procreate_meta` records determine set and member order (line position plus signed offset), following upstream `brushlibrary.rs`. Unlisted entries follow in ZIP order. Separate aggregate outer and nested inflation limits, entry and brush counts, safe path components and duplicate retained-entry checks bound decoding. Each import assigns a library ID and persists set order alongside brush order. Empty/failed sets are reported and omitted; tool defaults, set icons and unsupported brush fields are not retained. Existing saved brushes are not overwritten. Device verification remains outstanding.

## Single-brush export

My Brushes offers Export .brush through Android's document picker. The exporter writes a binary NSKeyedArchiver with the ProCraft brush class record, supported scalars and opaque grayscale shape/grain coverage PNGs. Procedural round tips and hardness are baked into the shape image. Spacing is converted to the ratio-aware reference; values outside the supported Procreate range clamp. This is a compatibility subset, not a lossless export: pressure routes, advanced rendering, material behavior, grain scale and other unsupported settings may differ. The source brush is untouched. Native Procreate and device validation are outstanding.

## Set and library export

My Brushes exports an imported set as `.brushset` in persisted member order, or all saved brushes as a `.brushlibrary`, grouping loose brushes separately. The set manifest lists UUID folders and each folder contains the same keyed archive and textures as a single brush. Libraries use nested `.brush` files and signed-offset metadata; unsafe or duplicate names receive safe unique folder/file names. Archive size, member count and aggregate inflation are capped before writing to the selected document. Export is all-or-nothing during encoding; a document-provider write failure may leave a partial output. The same compatibility warning applies to every format. Android and native Procreate verification remain outstanding.

## Brush duplication and membership

My Brushes duplicates saved brushes with independent shape and grain files and preserves source set membership, appending copies to the set. A missing declared texture fails the duplicate rather than substituting another tip. Move changes only membership, appending to the destination set or returning the brush to loose Studio brushes. Startup clears exact unowned sidecar names under the repository lock, retaining assets for unreadable metadata. Neither operation changes the active brush or project. Set names and whole-set order can be edited from My Brushes. A single atomic layout file overlays member metadata without rewriting brush files; original order remains until the first manual edit. Invalid layout metadata is not overwritten, and brushes remain accessible using their original metadata. Device verification remains pending.

## Pressure dynamics

Procreate size, opacity and positive pressure-transfer amounts now import into the existing Azphalt pressure bindings. Overall and target curves are composed into a bounded piecewise-linear approximation of ProCraft's spline; signed pressure amounts attenuate at the appropriate end. Single representable pressure routes export as keyed curve objects; flow uses its own transfer-modulation key, and negative transfer is identity in the source engine. The existing dab resolver remains authoritative (including its pressure fade); this is not exact Procreate stroke parity. A Python plistlib fixture exercises positive/negative amounts and composed curves. Procreate taper lengths are relative to the completed stroke, while Graffux explicitly retired end taper to keep live and committed strokes consistent; no end-taper behavior is restored. Device validation is outstanding.

## Texture orientation and fixed grain scale

Shape angle and the shape editor's quarter-turn codes map to the existing brush angle; full stroke-follow rotation maps to the existing Boolean control. Grain quarter-turns are baked into the normalized image before saving, with the legacy canvas-locked orientation/polarity conversion. Canvas-locked grain scale converts file scale to pixels per image pixel (file scale divided by square root of two), clamped to the supported engine range. Export writes the baked images with identity orientation and restores representable fixed scale and offset jitter. Moving-grain zoom, fractional heading follow, arbitrary grain rotation, and preserving original tile size when downsampling remain approximations or unsupported. Device validation is outstanding.

## Moving grain and input stabilization

Moving grain with an explicit file scale uses the cropped tile width (256 × square root of two × file scale). Import computes the engine's pixels-per-texel scale from the actual saved image width; canvas-locked grain also compensates for bounded decode and orientation. Export writes cropped zoom and the inverse tile conversion. Size-following zoom remains an approximation, not a new GPU sampling mode.

Brush-specific StreamLine, moving-average stabilization, motion filtering and pressure smoothing amounts persist and round-trip. The Android input path uses the strongest imported positional filter when the editor-wide level is zero, with independent pressure smoothing; nonzero editor settings take precedence. The existing canonical input collection records filtered points and pressures, not a second replay-time filter. This deliberately approximates the source's stacked filters and leaves ordinary brushes unchanged. Device verification remains outstanding.

## Remaining work

- Secondary tips, size-following moving-grain zoom and rotation, asset-preserving New Brush drafts, asynchronous selection and dynamic textured carousel previews. Verify paint/undo/reopen and deletion on device.
- More detailed round-trip loss reporting, empty-set creation, and reordering individual members within a set.
- Additional pressure targets, multi-stage stabilization parity, rendering, wet mixing and additional dynamics; validate size/opacity/flow pressure curves on device. Length-relative taper requires a separate causal design.
- Desktop import adapter over the same portable format semantics.
- Device rendering and memory benchmarks before any engine replacement.
- Additional malformed archive/fuzz tests, Android build, and independent GLEE review.

Do not mark complete until exercised through the user-facing import path and verified on Android. No stock brush settings or proprietary textures copied.
