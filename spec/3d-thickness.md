# 3D thickness: a per-pixel thickness channel so a brush stroke on a plane is round, not flat

Status: design only. Nothing here is implemented. Written against `origin/main` at 1.87.44.1 (`CURRENT_FORMAT_VERSION` = 14, `GraffitiProject.kt`; 15 is reserved for the puppetry rig, `spec/3d-puppetry.md` section 4). Every statement about existing code was read in the tree; anything unmeasured is marked "unmeasured".

This document sits beside `spec/3d-space.md`, whose vocabulary (plane, scene camera, orbit pivot, depth pass, view-plane adoption, draw plane depth) it uses without redefining. It also fixes one related drawing rule (section 10) that touches three sections of that spec.

Owner brief, decided: in 3D a brush stroke must not be flat. The brush gets a user-controlled z-depth. The stroke is painted into the plane's 2D bitmap by the existing engine, undo and persistence, and the plane gains **one extra per-pixel channel, thickness**. The brush writes a rounded profile into it; the surface is symmetric about the plane's middle (+/- thickness/2); the renderer extrudes by thickness so the stroke reads round from any angle. z-depth 0 is today's behaviour exactly.

## 1. Goals and non-goals

Goals

- G1. A stroke drawn on a plane has volume: a circular dab is a dome, a stroke a tube, visible as such when the camera orbits.
- G2. Zero regression: a project with no thickness, and any stroke at z-depth 0, take today's code path, produce today's pixels and cost nothing extra in memory, storage or time.
- G3. Reuse the 2D engine, undo (tile deltas, GPU snapshots), persistence and the one-plane-per-layer model. No new layer type.
- G4. The channel is a small, sparse, versioned sidecar; an older build opens a project that uses it read-only and shows it flat.

Non-goals

- N1. A stroke leaving its plane. A loop through depth needs another plane, as the front, side and top views already do (`spec/3d-space.md`, "Group surfaces").
- N2. True sphere or capsule silhouettes at grazing angles (section 12).
- N3. Replacing plane drawing with volumetric strokes (section 11).
- N4. Physical simulation, collision between planes, boolean ops.
- N5. Changing the 3D strokes layer (`SpatialLayerContent`), which already stores tubes in its own units.

### Not the same as existing things

- **Impasto height** (`ImpastoEngine`, `heightMap`, `LayerStore.putHeightBase`) is a per-pixel f32 relief that is *shaded into the layer's pixels* ("an in-place relief shade", `core/wgpu-engine/src/impasto.rs`). It lives inside the plane and never moves geometry. Thickness moves geometry at render time and bakes nothing. They are independent channels; a stroke may write both. Thickness reuses impasto's *plumbing pattern* (a canonical per-layer array beside the bitmap, uploaded before a stroke, read back after, a sparse sidecar), not its data.
- **Relief surfaces** (`spec/3d-space.md`, "Relief surfaces") displace a mesh built once from a photo's depth estimate. Thickness is painted, per layer, per stroke.

## 2. Data model

### 2.1 What is stored

One scalar per bitmap pixel: the **full thickness** `T` of the plane at that pixel, measured along the plane normal, **in layer-bitmap pixels** (the same unit as `L` in `spec/3d-space.md`, "Data model"). The solid occupies `-T/2 .. +T/2` about the plane's middle. `T = 0` means a flat pixel. `T` is meaningful only where the pixel's alpha is above 0; the renderer ignores it elsewhere (section 5.3 keeps the store tidy).

Why layer pixels, not world units and not normalised:

- The plane's `scale` multiplies x, y and thickness together, so a dome stays round when the layer is scaled (`P = C + (ox, oy, z) + R * scale * (L - C)`, `L` including a thickness coordinate).
- A layer scaled or moved needs no rewrite of the channel.
- No per-layer scale factor to store, merge or migrate: two layers merge by comparing numbers in the same unit.

### 2.2 Bit depth and range

**Unsigned 16-bit fixed point, 1/16 px per step**: `stored = round(T * 16)`, saturating at 65535, so the range is 0 to 4095.94 px. A 100 px dome has 1600 steps; 8 bits would give 0 to 16 px at the same step, or a 4 px step over 0 to 1020 px (visible terracing on a dome, where the rim slope is steepest). 16 bits keeps the rim smooth and the format simple. 32-bit float (impasto's choice) doubles the cost for precision nobody can see.

### 2.3 Per plane, not per layer type

The channel belongs to a **paint layer** (the layer that owns a bitmap), is exactly the bitmap's size, and shares its coordinates. It does not exist on groups, 3D strokes layers, model layers, text, vector or adjustment layers. A per-view image on a surface group (`spec/3d-space.md`, per-view paint) is out of scope for the first release; see open question 9.

### 2.4 Memory budget, 1536 x 2816 class (4,325,376 px)

| Copy | Bytes per pixel | Size | When allocated |
|---|---|---|---|
| ARGB bitmap (today) | 4 | 17.3 MB | always |
| Thickness, CPU canonical (`ShortArray`) | 2 | 8.65 MB | first stroke with z-depth above 0 on that layer, or on load of a layer that has the sidecar |
| Thickness, GPU resident | 4 (one `u32` per pixel) | 17.3 MB | when a stroke with thickness binds the layer |
| Thickness, per-stroke best (GPU) | 4 | 17.3 MB | while a z-depth stroke is live (see 6.1) |
| Undo, per stroke | tile deltas only | the touched tiles | per stroke that changed thickness |
| Disk | sparse tiles, gzip | proportional to painted area | only when non-empty |

Worst case adds about 8.65 MB CPU and about 35 MB GPU to a layer that is being drawn on with thickness. Tiles that never held thickness cost nothing on disk. The GPU copies count against the tier's resident budget (`GpuBudget`, `docs/Native Rendering Engine Design.md`); when the budget or `maxStorageBufferRange` is exceeded the stroke falls back to the CPU reference, exactly as the existing `strokeMax` path does ("Above `maxStorageBufferRange`, the call returns false and the stroke falls back to the CPU"). A GPU copy of 2 px per word (8.65 MB each) is possible if the kernel processes pixel pairs; the budget above assumes the simpler one pixel per word.

The GPU copy is a storage buffer of `u32`, not an R16 texture: 16-bit normalised storage textures are optional wgpu features on some adapters, and a buffer needs nothing optional.

### 2.5 Storage, compression, empty-channel omission

- Held per layer in `LayerStore` beside the impasto height base, as `thicknessBase: ShortArray?`. `null` is "no channel", the state of every existing project.
- Persisted as a **sidecar file per layer**, sparse and tiled the way `MaterialStateCodec` is (tile size, a presence flag per tile, GZIP, `material_<hex>.bin.gz` naming; the thickness file follows the same scheme with its own prefix). A tile whose values are all 0 is omitted; a layer with no non-zero tile writes **no file at all**. The codec is versioned from 1 and fails closed (a reader that does not know a version reads "no thickness" and logs it), the rule the material sidecar already follows.
- It is a separate file, not a new tile flag in the material sidecar: thickness must work without any painterly state, and sharing a version number would tie two independent formats together.
- Existing projects: no file, no field, no allocation. G2 holds by construction.

## 3. Brush model

### 3.1 The stamp writes a rounded profile

For a dab centred at `c` with radius `r` (bitmap pixels), z-depth ratio `k` and a pixel at distance `d` from `c`, `d <= r`:

~~~
profile(u) = sqrt(1 - u^2)            u = d / r, ROUND (default)
T_dab      = 2 * k * r * profile(u)    full thickness; half (the +z surface) is k * r * profile(u)
~~~

So `k = 1` is a true sphere (centre thickness `2r`, equal to the diameter), `k = 0.5` an oblate dome, `k = 2` a prolate one. This is the owner's "height = sqrt(r^2 - d^2) scaled by the z-depth setting", with the owner's symmetry made explicit: the *height above the middle* is `k * sqrt(r^2 - d^2)`, the *stored thickness* is twice that.

Outside `d <= r` the dab writes nothing. The profile reaches 0 at the rim, so a lone stroke has no side wall and its silhouette meets the plane continuously.

### 3.2 Parameters

| Parameter | Range | Default | Notes |
|---|---|---|---|
| `zDepth` (`k`) | 0 to 2, step 0.05 | 0 | Dimensionless ratio to the brush radius. Shown as a percentage, 0 to 200 %, 100 % = round. A ratio, not a pixel count, so it scales with brush size and pressure. |
| `thicknessProfile` | `ROUND`, `SOFT`, `BEVEL` | `ROUND` | Phase 1 ships `ROUND` only; the enum is persisted so later shapes add no format change. |

Profile options: `ROUND` as above; `SOFT`, `0.5 * (1 + cos(pi * u))` (a gentle pillow with a zero-slope crest and rim, no sharp ridge); `BEVEL`, `min(1, sqrt(1 - u^2) / e)` with `e` = 0.5, a plateau with a rounded edge (a fat marker).

### 3.3 Pressure, size, opacity, flow

- Pressure already drives size (and opacity) through the existing mapping. Because thickness is proportional to `r`, a pressure-tapered stroke tapers in thickness with no extra mapping. No separate pressure-to-depth curve in the first release (open question 5).
- Stroke opacity (the whole-stroke ceiling) and flow do **not** scale thickness: opacity is translucency, not mass. A 30 % stroke is a translucent tube of full thickness (open question 6).
- Feathering (hardness) shapes alpha only. The profile is geometric, over the brush's full radius.
- Spacing: the max across dabs gives scalloping along the stroke, of depth `k * r * (1 - sqrt(1 - (s / 2r)^2))` for dab spacing `s`. When `k > 0` the engine caps spacing at 0.1 of the diameter (`s <= 0.2 r`), keeping ripple under 0.5 % of `k * r`. (Evaluating the distance to the segment between consecutive dabs would remove it at no extra dabs; deferred, section 14, risk 8.)

### 3.4 Width: 3D diameter versus the 2D screen size (ask the owner)

Today the brush size (`brushSize`, default 50) is a footprint in document space, or on-screen when "brush size locked to screen" is on (`brushSizeFixedOnScreen`, `EditorUiState`). On a tilted or perspective plane the existing path casts each finger point onto the plane (`SceneMath.worldToLayerFlat`) and the 2D engine stamps in **layer pixels**. This spec keeps that: `r` is the radius the engine already uses, in layer pixels, so the dome is round *in the plane's own space*. Seen head-on it is as wide as it always was; seen obliquely it foreshortens like any mark on that plane.

Ambiguity for the owner (open question 1): with "locked to screen" on, on a foreshortened plane the layer-pixel radius differs from the on-screen radius. Recommendation: thickness follows the layer-pixel radius (consistent with the stored paint, and the only choice that stays round when the camera moves).

## 4. Combine rule for overlapping strokes

Options considered, for a paint dab landing on existing thickness `T0`:

| Rule | Behaviour | Verdict |
|---|---|---|
| `max(T0, T_new)` | union of volumes, like clay pressed together; idempotent; order independent | **Recommended default** |
| `T0 + T_new` | builds up thickness where strokes cross | Rejected as default: a stroke that doubles back on itself, or a held dab, grows without bound; dabs within one stroke would need special handling anyway. Possible later as a brush option ("build up"). |
| per-stroke replace (`T_new` where the stroke has coverage) | newest stroke wins, flat or not | Rejected: erases a dome when a thin stroke crosses it, which reads wrong in 3D. |

**Within a stroke:** max across dabs, held in a per-pixel stroke-best value, the same mechanism as the engine's existing `strokeMax` alpha (`docs/Native Rendering Engine Design.md`, section 2: binding 4 holds "pre-stroke base, full-precision stroke-best alpha"). That keeps a stroke from stacking on itself.

**Across strokes:** `T' = max(T0, T_stroke)`, over the pixels the stroke has coverage on.

**A z-depth 0 stroke** leaves `T` untouched: it is paint on the existing relief (colour on a bump). It never flattens what it crosses. (To flatten, erase, 5.3, or the future "flatten thickness" brush; out of scope.)

**Non-normal brush blend modes** (multiply, erase and so on) do not write thickness; the z-depth control is disabled for them.

### 4.1 Eraser

The eraser removes coverage `e` (0 to 1, stroke-max, as alpha already is). Alpha becomes `alpha * (1 - e)`; thickness becomes `T0 * (1 - e)`, computed from the pre-stroke `T0` (so the eraser needs the pre-stroke thickness, section 6.3). A full erase zeroes `T`; a partial erase thins the stroke in step with its opacity; a soft eraser edge leaves a soft thinning. Where the result's alpha is 0, `T` is written 0 (tidy storage, and the invariant that `T > 0` implies alpha above 0 holds after every commit). A hard eraser edge across a thick region leaves a cut face: the renderer shows the cut as a wall once ray-marched (phase 2, section 7); the phase 1 preview shows none (section 12).

### 4.2 Undo and redo

Thickness is part of the stroke's undo. The tile delta for a stroke carries the thickness tiles it changed (6.3); undo restores `T` and the pixels together, redo likewise. A stroke that changed no thickness carries none, so a flat stroke's undo entry is byte-identical to today's.

## 5. What else touches the channel

| Operation | Effect on thickness |
|---|---|
| Colour adjust, filters, LUT, curves, blur, sharpen, hue, brightness | **Untouched.** They remap colour, not shape. |
| Geometric edits to the bitmap: flip, rotate-in-bitmap, resize/crop canvas, selection move, clone, paste, "move selected pixels" | The channel is remapped with the same map as the pixels (nearest-neighbour for the integer cases, bilinear then re-quantised for a resample, never a colour-weighted blend). A clone or paste copies the source's `T` with its pixels. |
| Smudge, wet transport, Color Smudge | Colour moves; `T` stays where it is. A smudge does not drag volume (open question 7). |
| Impasto / painterly | Independent (section 1). A painterly stroke writes thickness exactly as any other, from its dabs. |
| Fill | Writes no thickness. |
| Layer opacity, blend mode, clip | Not part of the channel. A layer whose blend mode, clip or adjustment keeps it out of the depth pass draws flat (7.5). |
| Merge down | Both layers must already be on the same plane, as merge down requires today; the result's `T = max(T_lower where lower alpha > 0, T_upper where upper alpha > 0)` per pixel. A channel on one layer only carries over unchanged. If the planes differ, the existing refusal or flatten rules apply; nothing about thickness relaxes them. |
| Flatten all, export PNG / PDF / share / thumbnail | The scene export renders what the canvas shows at the camera (`spec/3d-space.md`, "Export"), including extrusion. The flattened layer has **no** channel: the extrusion is baked into the colour as seen, and the result is on the view plane. At the default camera with shading off this adds nothing but perspective displacement (section 7.1). |
| glTF export | Phase 1 and 2: unchanged (the quad, flat). Phase 3: the thickness becomes real geometry (section 13). |
| Duplicate layer | Copies the channel with the bitmap. |
| Co-op | Peers replay the stroke program, so the channel is derived from `zDepth` carried in the stroke (a new field, default 0, omitted when 0). A peer build without thickness ignores the unknown key and shows the stroke flat; colour does not diverge. No channel bytes cross the wire. |

## 6. Engine implications (`core/wgpu-engine`, `:core:nativebridge`, `:feature:editor`)

### 6.1 Stamp pass

`shaders/stamp.wgsl` is a compute kernel, so there is no second render target and no multiple-render-target support to depend on: thickness is **an extra storage binding** the same dispatch writes. Per pixel the kernel already loops its dabs; for a dab with coverage it also evaluates `T_dab` (3.1) and keeps the max in a per-stroke-best word, then writes `max(T0, best)` to the resident channel. While the kernel stays pixel-owned (each thread writes only its pixel, as it does for `strokeMax` today), the max is a plain read-max-write with no atomics.

- `k`, the profile id and a "thickness enabled" bit go in the per-stroke `StampUniform` ("stamp params" uniform in `engine.rs`), not per dab, so the `GpuDab` layout and the JNI layout parity stay unchanged. With the bit off the shader's thickness branch is not taken; a z-depth 0 stroke binds no thickness buffer and runs today's pipeline (G2).
- Dabs from the GPU path are in layer pixels already, so `r` is the dab radius the shader has.
- The CPU reference (`DrawingEngine` stamp path) gets the same function; it is what the GPU is tested against (13).

### 6.2 Readback and residency

- The canonical copy is the CPU `ShortArray`, as the height map is today. Before a z-depth stroke the layer's channel is uploaded (or allocated zeroed); after it, only the stroke's dirty rectangle is read back and merged. The existing readback timeout and recovery (`recover_from_stall`) cover it: a timed-out thickness readback invalidates the layer's GPU residency exactly as a colour one does.
- `GpuStampEngine` residency entries gain a thickness generation tag alongside the colour one; a bind that finds the tags differ re-uploads both.

### 6.3 Undo: tile deltas and GPU snapshots

- `TileDelta.TileSnapshot` carries `before`/`after: IntArray` for colour. It gains `thicknessBefore`/`thicknessAfter: ShortArray?`, both `null` when the tile's thickness did not change. Equality, merge (`TileDelta.merge`: before from the earliest, after from the latest) and apply follow the colour arrays. Tile fast-path undo (`EditorViewModel.applyTileDeltaFastPath`) patches both.
- `GpuUndoSnapshots.keep` copies the rectangle painted, *before* the stroke, into a GPU snapshot, and `swap` exchanges it with the layer. Thickness adds a second snapshot buffer for the same rectangle, kept and swapped in the same call so the pair cannot desynchronise; `Ref` carries both ids. A swap that finds only one half fails whole (the existing "swap fails: re-upload" path) and the CPU tile deltas redo the work.
- The eraser (4.1) needs the pre-stroke `T0`, which this snapshot provides; the paint max needs no pre-stroke copy (it is monotone).

### 6.4 Wet, blend and the painterly transaction

The painterly GPU transaction and wet simulation own raw pigment, height, wetness and per-tile media. They **do not** own thickness: it is not checkpointed with the wet state, and a wet "levelling" or "transport" step never changes it. A stroke on a layer that is still wet writes thickness at touchdown from its dabs, like any other. Brush blend modes other than normal write none (section 4).

## 7. Rendering

### 7.1 Where it runs

A layer with a non-empty thickness channel that composites normally (no blend mode, clip, adjustment or frame clip: the `DepthComposite.plan` membership rule) joins the **depth pass** (`DepthCompositor`, GLES 3), always, including at the default camera, because the extrusion has perspective even head-on: a point on the front surface at `T/2` projects away from the centre by `F / (F - T/2)`, so a 100 px dome 600 px off-centre shifts about 9 px at the default camera (`F` is about 3400 px for a 2816 px canvas). A document without a channel never enters the pass for this reason (the pass does not exist for a flat document without 3D ink, `spec/3d-space.md`, "Per-pixel depth"; unchanged).

Why not HWUI: the homography draw moves a whole texture rigidly; extrusion needs per-fragment depth, which the depth pass already has (depth test and writes make crossings exact).

### 7.2 Displacement approach

Both faces are the plane's bitmap: the front surface at `+T/2` toward the viewer, the back at `-T/2`, the same texels (the back reads mirrored, as a plane seen from behind does today).

- **Level 1, mesh displacement (phase 1 preview).** The layer's quad is a tessellated grid (a fixed 128 x 256 cells for a 1536 x 2816 layer, about 33 k vertices, under the 64 k cap relief surfaces use); the vertex shader samples `T` and moves each vertex along the normal by `+/- T/2`. No ray march, no per-fragment cost beyond today's, exact depth. Resolution is the mesh's: fine detail and a thin stroke narrower than a cell soften, and a cut face is not drawn.
- **Level 2, per-pixel relief ray march (phase 2).** The fragment shader marches the view ray through the thickness field in layer space: a linear search of up to 16 steps for the first sample below the front surface, then 4 binary refinement steps, writing `gl_FragDepth`. It gets thin strokes, exact silhouettes within the extruded edge and cut faces. Tile masks (a 16 x 16 coarse map of the max `T` per tile, built when the channel changes) bound the step count and skip empty tiles entirely.
- Level 0 is the flat layer (today).

### 7.3 Cost and Mali-G68-class devices

Phase 2 worst case is one full-screen thick layer: about 20 texture fetches per fragment, about 52 M fetches a frame at 1080 x 2400, which at 60 Hz would be too heavy for a Mali-G68-class GPU (not listed in `spec/painterly-device-matrix.md`, which lists Mali-G52 as the CONSTRAINED floor and G76/G77 as BALANCED; treat G68 as BALANCED-low). Mitigations are part of the design, not afterthoughts: tile masks; steps scaled to the tile's max `T`; level 1 *while the camera or a stroke is in motion* and level 2 when it settles (the same debounce the inactive-layer cache uses); half-resolution march with a depth-aware upsample as a later option. **Targets, unmeasured**: the thick-layer addition to the depth pass at most 4 ms on BALANCED and 8 ms on CONSTRAINED for one full-screen layer; over budget, drop a level. These numbers must be measured on device before phase 2 ships (the matrix's measurement process, `spec/painterly-device-matrix.md`).

### 7.4 Fallback when unsupported

Order: level 2, then level 1 (vertex texture fetch is core in GLES 3), then flat. Where GLES cannot start (unit tests, no pbuffer) or the layer is not a depth-pass member, the layer draws flat through its homography and thickness is ignored, as the glTF fallback already omits what it cannot draw. The editor shows the layer's menu line "Thickness shows flat" with the reason (blend mode, clip, adjustment, no GPU).

### 7.5 Silhouette, lighting, normals

- **Silhouette.** The profile is 0 at the rim (section 3.1), so a single stroke's outline lies on the plane and the extrusion gives the visible edge: at 45 degrees a dome is a lens, near edge-on a thin lens. This is not a sphere's silhouette (section 12). The cut face of an erased region is a wall (level 2 only).
- **Normals.** From the gradient of `T` (central differences over the 1 px grid, in layer pixels, so they scale with the layer): the front normal is `normalize(-dT/2dx, -dT/2dy, 1)` in the layer frame, flipped for the back. A pixel with `T = 0` has the plane's normal.
- **Lighting.** Off by default, so a document's head-on look does not change beyond the perspective shift above. A "Thickness shading" toggle in the view menu turns on one fixed headlight-plus-ambient term (`ambient + diffuse * max(0, n . l)`, `l` fixed in view space, strength default 0.35, multiplies colour and never changes alpha). The shading is render-time only, never baked, never exported unless the export is the rendered scene (PNG/PDF), and independent of impasto's baked shade.

## 8. UI

- **Z-depth control.** A slider, 0 to 200 %, labelled "Depth", in the brush settings panel directly under the size and feathering controls, with a tick at 100 % ("round"), beside a profile chooser (one entry in phase 1). Same 48 dp rows and from-resources strings as the rest of the panel (`NoHardCodedChromeTextTest`); TalkBack exposes it as a slider (`progressBarRangeInfo`).
- **Visibility.** Shown only in a project with a 3D group or a placed layer (`hasThreeDGroup` or any `isPlacedIn3D` layer) and only for tools that can write it (paint brushes, not eraser, fill or clone; the eraser has no depth control, it acts on whatever is there). A project that never touched 3D never shows it: the 2D UI is byte-identical.
- **Persistence.** The setting is per brush, saved with the brush's other settings. Default 0 for every existing brush and for new brushes everywhere (open question 2 asks whether 3D projects should start at 100 %).
- **Feedback.** While drawing, the live stroke shows through the preview (7.2 level 1). A layer that carries thickness shows a small badge on its rail tile; the layer menu offers "Clear thickness" (one undo step, drops the channel and the sidecar) and "Thickness shading".

## 9. Project format

- `CURRENT_FORMAT_VERSION` is 14. 15 stays reserved for the rig (`spec/3d-puppetry.md` section 4). **Proposed: 16** for thickness, written only when a project holds a non-empty channel ("write the lowest format that can hold the document", the narrowing `spec/3d-puppetry.md` 4.1 proposes and flags as its open question R5; if the repository keeps its global bump, a project file would be 16 once this build saved it, and the cost is the one stated there). If thickness lands before the rig, nothing renumbers: format numbers are just the highest the writer needs, and a build whose constant is 16 reads 15 and 14.
- Fields: `OverlayLayer.thickness: LayerThickness? = null` (JSON key `thickness`): `{ "schema": 1, "unit": 16, "file": "<sidecar name>" }`, plus the sidecar. Absent means no channel. `schema` versions the sub-format so a profile or quantisation change needs no project-format bump.
- `ProjectManager.migrateFormat` 14 (or 15) -> 16 only records the version (no data to transform), as 10 to 14 did.
- **Backward compatibility.** Everything in a 14 file reads as before; a 14 file has no `thickness`, which is "no channel".
- **Old builds reading a 16 file.** `isNewerFormat(16)` is true, so the project opens **read-only** and cannot save, so it cannot discard the sidecar by saving over it. It renders flat (`Json { ignoreUnknownKeys = true }` skips `thickness`; the sidecar is never read). This is why the bump is needed: a build that reads a file *without* a version bump would ignore the sidecar and then drop it on the next save. A sidecar version this build does not know (`schema` above 1) opens the project normally with that layer's thickness absent and a one-time notice, and the layer is marked so a save does not rewrite or delete the unread file.
- **New builds reading old files.** Nothing to do.
- Co-op: stroke ops carry `zDepth` (default 0; omitted when 0); older peers drop the unknown key and render flat.
- `SurfaceGroupTest` asserts `CURRENT_FORMAT_VERSION == 14`; that test changes in the PR that bumps it (a code change, outside this spec).

## 10. Related rule: after rotating, the next stroke goes on the plane that now faces the camera

### 10.1 Requirement

**R1.** After the view is rotated, the next brush stroke is drawn on a **new plane that faces the camera**. The earlier plane keeps its pose unchanged. Drawing is never refused because something was rotated.

Precisely:

- **Rotation** means any change of the camera's direction or of the active layer's own orientation: the two-finger orbit, the rotation triangle, the step buttons, the Move tool's twist, and a twist about X or Y. Pure pan, zoom, and a canvas twist about Z do not rotate a plane relative to the view axis and do not trigger it.
- **Faces the camera** means the angle between the plane's normal and the view axis is at most `PlaneSnap.ORBIT_CAPTURE_DEGREES` (5 degrees; the same constant the orbit snap uses, so "face-on" is one idea everywhere).
- **New plane** is the view plane through the orbit pivot at the automatic depth, with the pose `SceneMath.viewPlanePose` gives (the "Adopting the view plane" pose), created as `adoptDrawDepthPlane` already creates one: an empty paint layer directly above the active layer, in the same group, active from then on, riding the stroke's undo entry (`PlaneSwap.created`) so one undo removes stroke and plane, and a cancelled stroke removes it at once.
- **One plane per rotation, not per stroke.** Once the new plane is active and faces the camera, further strokes land on it until the view is rotated again.
- **Armed by a rotation, cleared by a deliberate choice.** The rule is armed when a rotation leaves the active paint layer not facing the camera. It is cleared when a stroke consumes it, when the user selects a layer on the rail (including the already-active one), or when the view returns to face the active layer. A user who rotates and then *chooses* a layer paints on it where it stands, as "Painting on a tilted plane" describes today.
- **Never refused.** Because the new plane faces the camera, its ray always hits, so the edge-on case (no hit, nothing painted) cannot occur for a plain paint layer.

### 10.2 Existing spec text it must reconcile with

Each of these is edited in the implementing PR; none is changed by this one.

1. `spec/3d-space.md`, "Painting on a tilted plane": "a layer seen edge-on takes no paint" and "a stroke that never hits is cancelled". True only for a layer the user chose after rotating, or for non-paint layers; for a paint layer the rotation arms R1 and the stroke lands on the new plane. `PlaneStrokeFeed` stays for the chosen-tilted-layer and surface cases.
2. "Adopting the view plane": "A layer with paint keeps its plane. So does a layer already placed in 3D by hand." Now: a layer with paint keeps its plane **and** is where a stroke goes only while it faces the camera, or when chosen after the rotation (10.1). The adoption of an *empty* layer is unchanged and is the same pose.
3. "Draw plane depth", "The next stroke": the new-layer-per-stroke mechanism exists and creates the layer when a draw depth is set. R1 reuses it with a second trigger (a rotation). A camera change already lapses a set depth (`DrawPlaneDepth.holdsIn`), so a rotation always yields the automatic depth for the new plane. "Where it does not apply" (surface layers, 3D strokes layers, group, text, vector, adjustment) applies to R1 too.
4. "Snap to stroked planes": unchanged; a snap changes the camera only. If the orbit snap lands face-on to an existing stroked plane, that plane then faces the camera and R1 does not arm.
5. "View control" gestures and the rotation triangle: nothing changes in the gestures; the effect is on the next stroke, not the turn. A turn is still never history.
6. Surface group layers keep to the surface (see open question 10 for the case where the surface is edge-on).

### 10.3 Interaction with thickness

Each rotation yields its own plane, so thickness is naturally per plane: a stroke after a 90 degree turn is a new plane at right angles to the first, which is how a user builds a solid from the front, side and top (N1).

## 11. Alternatives considered

- **Jetpack Ink.** Stroke geometry is 2D (a mesh in the stroke's plane); it carries no depth, and the Ink tool already commits through the plane (`commitInkThroughPlane`). Rejected for this purpose.
- **True sphere or capsule stroke storage.** Store each stroke as 3D primitives (as the 3D strokes layer's tubes do). A larger format and engine change: the 2D engine, brushes, wet and painterly would all need 3D equivalents, and undo, selection, fill and filters have no meaning on primitives. Revisit only if silhouettes at grazing angles prove unacceptable (phase 3).
- **Replace plane drawing with volumetric strokes everywhere.** Breaks the front/side/top workflow and every 2D tool; rejected.
- **Bake thickness into impasto height.** The relief shade is in-plane and moves no geometry; it would still read flat from the side. Rejected as the answer, kept as a complement.
- **Store thickness as a separate layer.** Doubles layers, breaks merge and move, no gain. Rejected.

## 12. Known limits

- A stroke cannot leave its plane; looping through depth needs a new plane (R1 makes that natural).
- At grazing angles the extruded edge shows, not a true sphere's silhouette.
- Overlapping strokes use the max rule; there is no additive build-up in the first release.
- Smudge and wet transport do not move volume.
- Level 1 (the preview and the fallback) draws no cut face for an erased edge.
- What is drawn is not exactly under the finger off-axis when `T` is large: the finger casts to the plane's middle while the front surface sits `k * r` nearer, so the paint appears displaced by about `k * r * tan(theta)` (`theta` the angle of the ray from the plane's normal; about 5 px for a 50 px dome 12 degrees off-axis). Phase 2 fixes this by casting the finger ray to the plane offset by the brush's crest height while `k > 0` (the finger rides the crest); phase 1 accepts it.
- A layer outside the depth pass (blend mode, clip, adjustment) draws flat.
- Surface groups' per-view images take no thickness in the first release.

## 13. Phased rollout and acceptance tests

Each phase is its own PR sequence; no phase changes the output of a project with no thickness.

### Phase 1: channel, slider, preview

Builds: `ThicknessProfile` (pure Kotlin, `:core:common`: `height(d, r, k, shape)`, the combine and eraser rules, quantisation); `thicknessBase` in `LayerStore`; the stamp pass's storage binding and uniform; CPU readback; the tile-delta and GPU snapshot carrying; the sidecar codec; format 16 and `LayerThickness`; the Depth slider; level 1 mesh displacement in the depth pass; "Clear thickness".

Acceptance:

- Unit tests with fakes (no GPU): `ThicknessProfileTest` (`T(centre) = 2kr`; `T(rim) = 0`; monotone decreasing in `d`; `k = 0` writes nothing; 1/16 px quantisation and saturation at 65535); `ThicknessCombineTest` (max across dabs, max across strokes, an order-swapped pair gives the same result, z-depth 0 leaves `T` untouched, eraser `T0 * (1 - e)`, full erase zeroes, non-normal blend writes nothing); `ThicknessCodecTest` (round trip; empty channel writes no file; an all-zero tile omitted; unknown schema reads as none and keeps the file); `TileDeltaThicknessTest` (capture/apply/merge carry both; an unchanged channel stores `null`; colour-only delta is byte-identical to today's); `GpuUndoThicknessSwapTest` (using `GpuUndoSnapshots.refForTest`, as `GpuUndoSwapAsyncTest` does: a half-missing pair fails whole); `ProjectManagerThicknessMigrationTest` (14 -> 16 records version only; a project with no channel is still written as 14; a 16 file is `isNewerFormat` for a 14 reader's constant); `NoThicknessRegressionTest` (a recorded stroke program at `zDepth = 0` produces today's golden bitmap and a bit-identical undo stack).
- CPU reference for the profile maths: the Kotlin function is the reference; `core/wgpu-engine/src/reference.rs` mirrors it, and a `cargo test` compares the stamp kernel's output to it on a grid of radii and `k` values, **at most 1 step (1/16 px) off** (a float sqrt may round differently across backends; undo is exact regardless because it uses stored deltas, not replay).
- Golden images (JVM, CPU render of the level 1 mesh, 256 x 256): a `k = 1` dome of radius 40 at yaw 0 equals the flat render within 1 / 255 per channel; at yaw 45 the lens width is within 2 px of the analytic `2 * 40 * cos(45)` plus the extrusion bound; a `k = 0` render at any camera is **bit-identical** to the flat golden.
- Not verifiable in JVM: GL path, real device timing; stated plainly in the PR as in `spec/3d-space.md`'s "Not tested" lists.

### Phase 2: full render

Builds: level 2 relief ray march with tile masks, cut faces, normals and the shading toggle, the crest-riding finger cast (section 12), the quality ladder, device measurement, R1 (section 10) if not shipped earlier.

Acceptance: a CPU ray-caster reference of the same field; golden render of the phase 1 scenes at yaw 0, 30, 60, 80: SSIM at least 0.98 against the reference, silhouette error at most 1.5 px up to yaw 60; the cut-face scene shows a wall; device: the 4 ms / 8 ms targets measured on a BALANCED and a CONSTRAINED device, with the numbers recorded in `spec/painterly-device-matrix.md`'s way, or the targets revised; the fallback ladder tested with a fake that refuses level 2 and one that refuses all; shading off is pixel-identical to phase 1 head-on up to the stated perspective shift.

R1 acceptance (whichever phase ships it): `FreshPlaneAfterRotationTest` (orbit 90 degrees then stroke: a new plane faces the camera, the old plane's pose is unchanged, one undo removes both; two strokes without a rotation share the plane; a triangle turn, a step button and a Move-tool X twist each arm it; pan, zoom and Z twist do not; selecting a layer on the rail disarms it and a stroke then lands on the tilted layer; the orbit snap onto an existing stroked plane does not arm it; an edge-on layer never refuses; a locked layer still refuses with its notice; a surface layer keeps to the surface; redo; a cancelled stroke removes the plane).

### Phase 3 (optional): true geometry

Real extruded meshes with side walls from the channel (marching squares over `T > 0`, front and back displaced grids joined at the contour), used for glTF export (`SceneGltfExporter`) and for exact silhouettes; optionally capsule/sphere silhouette treatment if grazing angles prove unacceptable (an owner call, see open question 12). Acceptance: the exported mesh is manifold (every edge shared by two triangles) on the golden scenes; vertex count capped (a stroke-heavy layer decimated to the 64 k cap used for relief surfaces); the glTF validates; round trip into the flat render matches the level 2 render within the phase 2 tolerance.

## 14. Risks

1. **Memory on a 17 MB layer** (about 35 MB GPU while a thick stroke is live): contained by allocate-on-first-use and the existing CPU fallback; a device near its budget gets slow thick strokes, not a failure.
2. **Depth-pass cost on a Mali-G68-class GPU**: unmeasured; the ladder and tile masks exist because of it (7.3).
3. **Undo desync of colour and thickness**: the single-ref pair and "fail whole" rule (6.3); tested with the existing `GpuUndoSwapAsyncTest` pattern.
4. **Older builds**: read-only on a 16 file (9), which is the intended protection; the cost is that a mixed-version device or co-op peer cannot edit a project that uses thickness.
5. **Perspective shift at the default camera** makes a thick stroke look slightly different from a flat one head-on (7.1, section 12): a deliberate consequence of real extrusion.
6. **Finger offset** off-axis (section 12): accepted in phase 1, fixed in phase 2.
7. **R1 creates layers**: one per rotation could proliferate planes. Mitigated by one-per-rotation, the rail's existing grouping, and an undo that removes the plane with the stroke.
8. **Scalloping along a stroke** from dab spacing: the 0.1-diameter cap costs more dabs when `k > 0` (up to roughly 2.5x the dabs of a typical 0.25 spacing); the segment-distance evaluation removes both the ripple and the cap later.
9. **Float parity**: CPU and GPU `sqrt` can differ by one quantisation step; tolerance stated; undo exact.
10. **Thickness and a flatten/merge that goes through the view plane** loses the channel (baked into colour). Documented, not a bug.

## 15. Open questions for the owner

Each has a recommended default; the implementing PR takes the default unless told otherwise.

1. **Width in 3D versus on screen.** With "brush size locked to screen" on, on a foreshortened plane, is the dome's radius the layer-pixel radius (the tube keeps its width when the camera moves) or the on-screen radius (it looks the same width as the cursor, but is distorted in the plane)? *Recommended: layer pixels.*
2. **Default z-depth.** 0 everywhere, or 100 % (round) for brushes in a project that has a 3D group? *Recommended: 0 everywhere; a one-tap "round" tick on the slider.*
3. **Format number.** 16 as proposed, or take 15 and move the rig to 16 (the rig's PR has not landed)? *Recommended: 16; leave 15 reserved as `spec/3d-puppetry.md` says.*
4. **Content-dependent format version** (write 14 when the project has no thickness). This repository bumps globally today; `spec/3d-puppetry.md` R5 asks the same. *Recommended: write the lowest format that can hold the document.*
5. **Pressure to depth.** A separate depth-versus-pressure curve? *Recommended: none; thickness follows radius, which pressure already drives.*
6. **Opacity and flow scale thickness?** *Recommended: no (translucent but full-thickness).*
7. **Smudge and wet transport move volume?** *Recommended: no in v1; revisit with real use.*
8. **Overlap rule**: max (union). Additive "build up" as an optional brush mode later? *Recommended: max now, build-up later if asked for.*
9. **Per-view images on a surface group** take no thickness in v1. Acceptable? *Recommended: yes; a surface already has shape.*
10. **A surface group's edge-on case.** A stroke on a surface layer whose surface is edge-on today skips samples (`PlaneStrokeFeed`). Should R1 send that stroke to a new free plane outside the group (with a notice), so drawing is never refused there either? *Recommended: yes, with a once-per-stroke notice; the surface layer itself is untouched.*
11. **Facing tolerance and reuse.** 5 degrees (the orbit snap's constant) as "faces the camera", and a new plane every time the active layer does not face, even if another existing plane already faces the camera. Or should a stroke prefer such an existing plane? *Recommended: 5 degrees, always a new plane (the owner's stated behaviour); the orbit snap is the way back onto an old plane.*
12. **Phase 3 and silhouettes.** Is a lens-shaped silhouette at grazing angles acceptable for the first release, with true geometry gated on seeing it in practice? *Recommended: yes.*
13. **Shading default.** Thickness shading off by default (head-on documents look as drawn), or on when a layer has thickness? *Recommended: off, with a view-menu toggle, so exports and head-on views do not shift in tone.*
14. **Finger rides the crest** (cast the finger to the crest plane, section 12) in phase 2, or never (accept the offset)? *Recommended: phase 2.*
