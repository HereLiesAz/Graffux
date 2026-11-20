# Smart stroke: predicting which surface a stroke belongs to when the grid is off

Status: design only. Nothing here is implemented. The owner's answers to the original open questions are recorded in section 19 (Decisions). Written against `origin/main` at 1.87.48.1 (project format 14; this spec adds no format change). Every statement about existing code was read in the tree; anything unmeasured is marked "unmeasured" or "proposed". Code names are real, with their file under `feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/` (abbreviated `editor/`) or `core/common/src/main/java/com/hereliesaz/graffitixr/common/` (abbreviated `common/`).

Related: `spec/3d-space.md` ("Painting on a tilted plane", "Adopting the view plane", "Group surfaces", "Draw plane depth", "Snap to stroked planes", "Paint per view"), `spec/3d-thickness.md` section 10 (rule R1) and the thickness design, `spec/3d-puppetry.md` (posed parts).

## 1. The owner's design (decided)

The "#" button on the rail toggles the grid. Both states already exist in the UI; this spec gives the second one a meaning for strokes.

| Grid | A stroke goes to | Rule |
|---|---|---|
| ON | The plane facing the screen. Absolute, hulls and group surfaces included. | R1 (`spec/3d-thickness.md` section 10), unchanged. |
| OFF | Whatever surface it makes sense to attribute the stroke to: **smart stroke**. | This spec. |

Smart stroke takes four things into account to predict the surface or surfaces a stroke belongs to, even when they are not on one plane (one gesture may be split across several surfaces):

1. the stroke's starting point;
2. the viewing angles of all surfaces;
3. the contents of those surfaces;
4. the ongoing stroke.

Owner priorities, in order: **the stroke is on screen under the finger immediately**; the owner likes the existing undo chunking and forbids changes to undo/history code; the behaviour must be explainable.

## 2. Goals and non-goals

Goals:

- G1. Latency to first pixel is unchanged. Attribution never delays the first dab (section 4).
- G2. Deterministic and explainable: a pure function of the scene, the touch samples and a few session facts; the same input gives the same decision, on a device and in the replay harness. The reason for a decision can be printed in one line (section 13).
- G3. Right surface at touch-down for the common cases (a stroke that starts on a tilted plane, on an overlap, on empty space, near a crease).
- G4. A stroke that runs from one surface onto another is split across them in one gesture and one undo step (section 8).
- G5. Never silently refuse. A stroke always lands somewhere visible or says why not (section 9).
- G6. Grid-on behaviour, strokes in 2D-only projects and the stroke record are unchanged (section 15); the "#" button is added to 2D-only projects as a display toggle (section 3).
- G7. A line drawn from one vertex toward another through empty space ends exactly on the second vertex, on an inferred plane (the prism ridge, section 20), using the stroke predictor the app already has, not a new one.

Non-goals:

- No ML in v1. The scorer is a weighted sum with gates (section 6). Phase 3 may tune its weights from recorded scenarios; it stays a linear, inspectable model.
- No change to undo/history code, the project format, the stroke record, or what peers receive (sections 10.3, 10.4).
- No moving of pixels already committed to a layer. Attribution decides where paint lives from now on; it never relocates committed paint.
- Not a new tool or a new layer type. The attributed layer becomes the selected layer (section 3, decision 10); that alone does not change the camera or the grid.
- Not a replacement for choosing a layer. Selecting a layer is the strongest hint the scorer has (section 6.2). There is no override UI: a wrong guess is undone and drawn again (section 14).

## 3. The toggle and when it is read

State today: `EditorUiState.surfaceGridVisible` (`common/model/EditorModels.kt`, default `true`, session only, never persisted), flipped by `EditorViewModel.onToggleSurfaceGrid()` (`editor/EditorViewModel.kt`), from the rail button `SurfaceMeshButton` (rail id `view.surfaceMesh`, `app/src/main/java/com/hereliesaz/graffux/ToolRail.kt`) and the long-press menu (`meshMenu`, "Wire mesh"). Today it only decides whether the surface wire mesh is drawn (`SurfaceLook.meshArgb`). The button shows today only in a project with a 3D group (`hasThreeDGroup`); decision 5 makes it visible in 2D-only projects too (below).

Proposed: no new flag for the mode. `surfaceGridVisible == true` is "grid on" (R1); `false` is "grid off" (smart stroke), gated by the setting in section 14. The stroke mode is a function

~~~
strokeMode(state, tool) = SMART  when  !surfaceGridVisible
                                  and smartStroke setting is on
                                  and hasThreeDGroup
                                  and tool is an attribution tool (section 10.5)
                                  and no draw-plane depth is set (state.drawPlane == null)
                        = R1     otherwise
~~~

Rules:

- **Read once, at stroke start**, in the stroke-start path (section 11). It is stored for the stroke (as `strokeWrapAroundMode` is: "captured once per stroke"). Toggling "#" mid-stroke affects the next stroke only; it cannot change a stroke already begun, and it never splits or re-homes a stroke.
- A set draw-plane depth (`state.drawPlane`, "Draw plane depth") is an explicit choice of plane and wins over attribution: the stroke takes the existing `adoptDrawDepthPlane` path exactly as with the grid on.
- Grid-off hides the wire mesh, as it does today. No outline of the picked surface is added (decision 12): surfaces only exist where strokes are, and the background grid stays visible.
- **2D-only projects (decision 5).** Strokes do not change: no attribution, no candidates (`hasThreeDGroup` is false), R1 a no-op at the default view. What changes is the button: the "#" grid toggle is visible in 2D-only projects too, where it toggles the grid drawn on the canvas, the 2D reference grid (`InfiniteGrid` in `editor/EditorScreen.kt`), instead of the surface wire mesh. It is the same flag, `surfaceGridVisible`, read for a different drawing: with a 3D group it drives the wire mesh (`SurfaceLook.meshArgb`), without one it gates the `InfiniteGrid` call. The rail condition (`hasThreeDGroup` in `ToolRail.kt`) and the long-press menu change accordingly (the "Wire mesh" and "Smart stroke" rows mean nothing without a 3D group and are not shown there). This is a separate small implementation change, not part of the smart-stroke phases; this spec only records the intent. Any test that asserts the button is absent without a 3D group (for example in `Scene3DRailUiTest`) is updated by that change.
- **Grid default (decision 6).** New projects start with the grid on (`surfaceGridVisible` default `true`): a 3D surface cannot be made without it, because a stroke creates its first plane under R1. Session only, as today.
- **Selection (decision 10).** The attributed layer becomes the selected layer. When: at the lock (end of the provisional window, 8.1), once; a one-dab tap selects at pen-up. Before the lock the selection does not move, so a re-homed stroke selects only its final target. A split moves the selection to the new target when it happens, so after the gesture the selection is the last attributed layer. For a locked surface the selected layer is the receiving layer (5.1), never the locked one. Selecting does not by itself change the camera, the grid state or the running stroke's mapping (`activeLayerId` is not read for it, section 11), and it must not add a history step. The selection is the strong prior `a` for the next stroke, so a user working on one surface keeps landing on it.

## 4. Latency contract: the first dab

The finger is on screen first. Order at touch-down:

1. DrawingCanvas delivers the first sample to the `onStrokeStart` lambda in `editor/EditorScreen.kt` (the one that calls `vm.adoptViewPlaneForStroke()` today).
2. **Synchronous, on the same thread, no hop, no await:** `strokeMode` is evaluated; in SMART mode the touch-down decision (section 7) is computed from geometry that is always fresh (one ray and one plane intersection per candidate, at most K = 8 candidates) and from cached content masks if they are ready.
3. The stroke starts on the decision through the existing start path. The first dab is painted by the existing live engine; nothing in attribution sits between the touch and that dab other than step 2.

Guarantees:

- **Budget (proposed, unmeasured):** step 2 adds at most 0.3 ms at p95 and 1 ms at p99 on the reference mid-range device; no allocation per candidate (arrays sized K, reused). Measured with the existing first-stroke timing (`editor/FirstStrokeTrace.kt`, tag `GraffuxStroke`) plus a new `attr dt=` field (section 13). A release is blocked if the budget is exceeded.
- **Never wait for content.** Content masks (section 6.1, feature `p` and the content part of `o`) are built off the main thread and cached (as `PaintScans` and `StrokedPlanes` are). A mask not ready at touch-down counts as unknown: those features are dropped and the remaining weights renormalised (section 7). The geometry is never cached across camera changes, so a decision is never made on a stale camera.
- **What the finger sees before attribution settles (decision 1):** real paint on the touch-down pick, in the right place on screen. There is no placeholder, no hold, no "pending" look. Because a stroke lands under the finger on every surface (the invariant in "Group surfaces", `SurfaceStrokeGuard`), a change of target moves paint between layers without moving it on screen (section 8.2). This is what makes "paint now, decide better as the stroke grows" possible.
- **No worker thread in v1.** The decision and the per-step re-evaluations are bounded by K and a fixed step (section 12); handing samples to another thread would add latency and nondeterminism for no saving. Revisit only if the budget is missed.

## 5. The candidate set

Built at touch-down from the layer list and the scene camera held by the stroke (the `ActivePlaneMapping` camera: the one the stroke is mapped with). A layer is a candidate when all of:

- It is a raster paint layer the brush can paint: not a 3D strokes layer, model, group, text, vector or adjustment layer (the types `drawDepthRefusal` in `editor/EditorViewModel.kt` refuses, minus surfaces: a paint layer on a group surface is a candidate, and so is a hull's paint layer, whose per-view images are folded into it; the hidden per-view image layers (`surfaceViewOf != null`) are never candidates themselves).
- It and every ancestor group are visible, and its effective opacity is above 0.02 (a layer that is not drawn does not occlude and is not a target; `SurfaceVisibility` makes the same call for group surfaces).
- **Locked layers are candidates (decision 4).** A locked layer's surface (`isImageLocked`) stays a valid attribution candidate and is scored like any other, so a stroke that starts on a locked wall is attributed to that wall. The stroke is never painted on the locked layer: it is applied to a duplicate of that surface (5.1). A locked layer and its pixels are never modified.
- **Not off-screen:** its projected box, intersected with the viewport, has non-zero area. A layer entirely outside the viewport cannot be under a finger.
- **Not edge-on:** `|n . v| >= 0.17` (about 80 degrees off face-on, `StrokeAttribution.EDGE_ON_COS`), with `n` the plane normal from `SceneMath.chainModel(planeChain(...))` and `v` the camera forward, as `facesCamera` computes them. An edge-on plane collapses a stroke to a line and cannot be painted usefully. A group surface is judged per visible face by the existing mapping, not by one normal.
- **Alpha lock** (`alphaLock`) is not an exclusion: it is the content gate in 6.2 (paint only where pixels already exist).
- **Clip** (`clipToLayerBelow`): a clipped layer's paint shows only over its base's coverage; its content features are read from the base's mask at the hit. Where the base is empty the candidate's gate is 0.
- **Selection mask:** with an active selection, a candidate whose hit lies outside the selection (`strokeSelection`, the per-view clip for a hull) has gate 0: its paint would be clipped to nothing.

Capped at K = 8 (`StrokeAttribution.MAX_CANDIDATES`): first every candidate whose projected box contains the touch, nearest first, then the rest by projected on-screen area. The active layer is always kept, even when it is not in the top K. The cap bounds the per-sample cost (section 12).

Not candidates, and the stroke behaves exactly as with the grid on (the existing path, unchanged): a 3D strokes layer or model as the active layer (the paint surface is not composed: `onPaintGateClosed`), tools outside section 10.5, a project with no 3D group.

### 5.1 Locked surfaces: the automatic duplicate (decision 4)

Owner: "If a layer is locked, the user can still draw on the same surface by duplicating that surface into the layer that the stroke is being applied to." Clarified by the owner: this happens automatically, with no prompt, no confirmation, no refusal and no notice; the user just draws. The earlier recommendation (never auto-target, refuse with the locked notice) is rejected.

When the pick is a locked layer's surface `L`:

- The stroke is applied to a **duplicate of `L`'s surface** (same plane, geometry and pose, read from the same plane chain; for a group surface the same outline and views) that lives in the **receiving layer** `R`. `L`, its pixels and its properties are never modified.
- **`R` is the active layer** when it can take paint: a raster paint layer, unlocked, visible, not `L`, and either empty or already carrying `L`'s pose (a previous duplicate, below). A layer has one pose, so a layer with paint on a different plane cannot also hold `L`'s surface.
- **When the active layer cannot take paint** (it is locked, not a paint layer, is `L` itself, or holds paint on another plane), `R` is a **new paint layer** created for the stroke, directly above `L` in `L`'s parent and named after it. This is the recommended default (see "Still open").
- **Reuse.** Repeated strokes on the same locked surface reuse the existing duplicate instead of creating more: before creating anything, attribution looks for a duplicate of `L` (an unlocked paint layer carrying `L`'s pose in the same parent, found by pose and parent, so there is no new project field and no format change) and paints on it. Ten strokes on a locked wall make one layer, not ten.
- **Pixel tools** (Eraser, Smudge, Blur, Sharpen, Dodge, Burn) are not additive: a duplicate has no pixels and `L` may not be modified, so `L`'s gate is 0 for them (6.2); they act on the pixels of an unlocked candidate.

**Timing: the first dab is not delayed (section 4).** The duplicate copies a pose and geometry with an empty bitmap; no pixel is copied. Creating it is synchronous and cheap, the same operation as the fallback plane R1 already creates at touch-down (`adoptViewPlaneForStroke`, `PlaneSwap.created`), and it happens inside step 2 of section 4, within its budget. The first dab paints on the duplicate immediately. If the device check shows the budget is missed, the alternative is: paint at once through `L`'s mapping as a live stroke (nothing committed yet) and attach the duplicate at the lock (8.1) by replaying the buffered samples, as a re-home does; the screen result is identical because the duplicate has `L`'s pose. Either way nothing waits for the duplicate. A stroke cancelled before the lock, or re-homed to another target, removes a duplicate or new layer it created (as `revertPlaneAdoption` removes a fallback plane); a reused duplicate is untouched.

**Undo.** The duplicate (when the stroke created one) and the stroke are one history step by the existing mechanism: the created layer rides the stroke's first undo entry as the fallback plane does (`PlaneSwap.created`), and later segments chain as in 10.3. One undo removes the stroke and the duplicate it created; one redo brings both back. A stroke that reused a duplicate is an ordinary single stroke entry. No undo or history code changes.

**What the user sees.** Paint under the finger as usual. No prompt, dialog or notice. The receiving layer (new the first time, the same layer afterwards) appears in the layer panel and becomes the selected layer (decision 10). `L` stays locked and unchanged. The HUD line of section 13 is a passive live-region announcement of the selected layer, not a notice.

## 6. Features and scoring

### 6.1 Features

All features are in [0, 1], computed per candidate `i`. "At touch-down" features exist from the first sample; "path" features exist after the first evaluation step (section 8.1).

| Symbol | Feature | Definition |
|---|---|---|
| `h` | Hit | The touch ray meets `i`'s drawn extent (box, or group surface outline) and passes the existing guards (`flatOnFinger`, `SurfaceVisibility`): 1. Only the brush footprint reaches it (`toFlatByFootprint`'s ring rule): 0.5. Neither: 0. Footprint rings are cast only for the 2 best candidates by ray result. |
| `o` | Visible at the finger | Candidates with a hit are sorted by camera depth of the ray hit. A candidate is occluded by a nearer one whose content at its own hit has alpha above 0.1 (from the mask; a nearer layer that is empty there does not occlude). `o = 1` with no occluder, `0.15^k` with `k` occluders. Group surfaces use `SurfaceVisibility.visible`. |
| `f` | Foreshortening | `smoothstep(0.17, 0.5, abs(n . v))`. 0.5 is the existing `SURFACE_GRAZING_FACING` (60 degrees off face-on, "collapses a stroke to a line"); 0.17 is the edge-on cull. Used as the multiplicative factor `V = 0.2 + 0.8 f` (6.3), so a grazing surface cannot win on content alone. |
| `p` | Proximity to content | From the layer's occupancy mask (below): `1` when alpha under the touch exceeds 0.1; else `exp(-d / (1.5 r))`, `d` the screen distance (px) from the touch to the nearest painted cell, `r` the on-screen brush radius (`effectiveScreenBrushSize() / 2`). Edges are not a separate term: the distance field already peaks at the boundary of painted regions, which is where a continued shape starts. Unknown when the mask is cold. |
| `c` | Continuity | The newest committed target of this session: `c_i = 1[i is it] * exp(-dt / 4 s) * q`, `dt` since its pen-up, `q = exp(-d_end / (6 r))` with `d_end` the screen distance from the new touch to that stroke's last point when the view is unchanged (`LastStroke.holds`), else a fixed 0.4 (the layer was the last one worked on, the place no longer means anything). Session state kept by the attribution component, not by history. |
| `a` | Active prior | 1 for the selected layer, 0.4 for a layer in the same parent group (the user is working in that group), else 0. The user's intent; the largest single weight after content. |
| `r` | Path on surface | Path feature. Of the last `W = 8` evaluation steps, the fraction whose sample hits `i` (with `h >= 0.5`) and passes the finger guard. Without it a surface that was merely under the first touch keeps winning after the path leaves it. |
| `m` | Motion alignment | Path feature, after at least 3 mm of travel. `max(abs(cos(theta_x)), abs(cos(theta_y)))`, `theta` the angle between the stroke's screen direction and the screen projection of the surface's in-plane axes. Weak by design: strokes along a wall's own edges are slightly favoured. |

Not used as scoring features, with the reason:

- **Stroke length and curvature** so far are not per-candidate features. They modulate the process: curvature raises the hysteresis margin (a scribble in one place is one surface; 8.1) and length drives the lock (8.1).
- **Pressure** is read and found non-predictive of surface; unused in v1.
- **Brush type** acts as a gate (6.2), not a feature.

**Occupancy mask.** Per candidate layer, a 128 x 128 byte grid over the layer's bitmap: maximum alpha per cell, plus a chamfer distance field of the same size (8 bits, in cells). 32 KB per layer, built on the default dispatcher keyed by (layer, bitmap identity, generation) exactly as `editor/PaintScans.kt` does (results for a swapped bitmap, deleted layer or closed project are dropped), and refreshed from a committed stroke's bounds. A layer with a source image takes the image's alpha. Group-surface and hull layers have no mask in v1: their content features are unknown, so the weights renormalise over the rest (a stated limit, section 17).

### 6.2 Gates (hard, 0 or 1)

`G_i = 0` when any of:

- the candidate is excluded in section 5 (edge-on, off-screen, selection-blocked, clip-blocked);
- the touch is a miss with no footprint reach (`h = 0`) at touch-down (a stroke cannot start where it does not land);
- **tool needs pixels:** the tool is the Eraser, Smudge, Blur, Sharpen, Dodge or Burn and the mask has no alpha above 0.05 within the footprint radius of the touch. An eraser or smudge only makes sense where there are pixels. With the mask cold the gate passes (never wait). A locked candidate also has gate 0 for these tools (5.1);
- **alpha lock** is on and the same footprint test fails (paint cannot land).

### 6.3 Score

~~~
S_i = G_i * V_i * ( sum_k w_k * x_ik  /  sum_k w_k )      k over the features available now
V_i = 0.2 + 0.8 * f_i
~~~

Default weights (`StrokeAttribution.Weights`), summing to 1.0. A feature that is unavailable (path features at touch-down, content features with a cold mask) is left out of both sums.

| Feature | `h` | `o` | `p` | `c` | `a` | `r` | `m` |
|---|---|---|---|---|---|---|---|
| Weight | 0.20 | 0.20 | 0.25 | 0.10 | 0.15 | 0.05 | 0.05 |

Constants (all named, all in `StrokeAttribution`; every number here is a proposed default to be tuned by the replay harness, none measured):

| Constant | Default | Meaning |
|---|---|---|
| `MAX_CANDIDATES` | 8 | K, section 5 |
| `EDGE_ON_COS` | 0.17 | candidate cull |
| `S_MIN` | 0.30 | below this the best candidate is not trusted: fallback (section 9) |
| `TIE_EPS` | 0.02 | scores closer than this are a tie |
| `HYSTERESIS` | 0.10 | challenger must beat the incumbent by this (8.1) |
| `HYSTERESIS_CURVE` | x(1 + 2 kappa) | margin scaled by normalised path curvature `kappa` in [0, 1] |
| `DWELL_STEPS` | 3 | challenger must lead on this many consecutive evaluations |
| `EVAL_STEP_MM` | 1.5 | evaluation cadence by travel, not by time |
| `LOCK_MM` / `LOCK_MS` | 6 mm / 250 ms | provisional window ends at whichever comes first, or at pen-up |
| `SPLIT_MARGIN` | 0.10 | challenger must beat the current target by this x (1 + 2 kappa) to split after the lock (8.3); same shape as `HYSTERESIS` |
| `SPLIT_DWELL_STEPS` | 3 | consecutive evaluation steps the challenger must lead before a split (8.3) |
| `SPLIT_MIN_MM` | 8 mm | minimum screen travel since the lock or the previous split before the next split (8.3) |
| `MAX_SPLITS` | 8, tunable | safety bound on splits per gesture, for undo depth (10.3); not the flip-flop control, which is the margin and the minimum travel |

**Ties** (within `TIE_EPS`), in order: the active layer; the continuity target; the nearer hit (smaller camera depth); the higher stack position (drawn later, so the one the user sees on top); finally the layer id, lexically, so a replay is reproducible.

**Worked examples** (touch-down, weights renormalised over `h o p c a`, so divided by 0.90):

- *Non-active tilted wall in front, painted under the finger* (`h 1, o 1, p 1, c 0, a 0`, `f = 0.8` so `V = 0.84`): `(0.20+0.20+0.25)/0.90 * 0.84 = 0.61`. The active layer behind it, facing, hit, occluded by the wall's content (`h 1, o 0.15, p 0, a 1`, `V = 1`): `(0.20+0.03+0+0+0.15)/0.90 = 0.42`. The wall wins by 0.19, above `HYSTERESIS`.
- *Empty tilted plane in front, selected layer behind with paint under the finger*: the empty plane does not occlude (no alpha), so the selected layer is `(0.20+0.20+0.25+0+0.15)/0.90 = 0.89`; the empty plane scores about 0.2. The invisible empty plane is not painted on by surprise; selecting it (`a = 1`) makes it `(0.20+0.20+0+0+0.15)/0.90 * V = 0.61 V`, which wins when it faces the view.

## 7. The decision at touch-down

~~~
decide(start sample, candidates):
    score every candidate (section 6.3); sort by S, then the tie order
    best = first
    if best.S >= S_MIN:   return Pick(best.layer, reason, margin = best.S - second.S)
    else:                 return Fallback            // R1 exactly (section 9)
~~~

`Pick` on a layer that does not face the camera is allowed. This is the one place smart stroke differs from R1: grid off is the deliberate way onto an existing tilted plane (this resolves open question 1 of `spec/3d-thickness.md` section 15, "Painting on an existing tilted plane"; that file is not edited here).

## 8. The life of a stroke

### 8.1 Provisional window

From touch-down until `LOCK_MM` of travel or `LOCK_MS`, or pen-up: the target is **provisional**. The attribution session keeps the raw samples (screen points, as received) since touch-down. Every `EVAL_STEP_MM` of travel (so never per frame) it re-scores with the path features. The incumbent changes only when a challenger beats it by `HYSTERESIS * (1 + 2 kappa)` on `DWELL_STEPS` consecutive evaluations. Curvature `kappa` is the mean absolute turning angle per mm over the window, normalised to [0, 1] by 90 degrees per 3 mm: a scribble stays where it started.

When the incumbent changes inside the window the stroke is **re-homed** (8.2). At the end of the window the target is **locked**: after that only splits (8.3) can move the stroke.

A one-dab tap (a dot) is attributed by its start only and locks at pen-up.

### 8.2 What happens to dabs already drawn (decided, decision 2)

Two regimes, one rule: **never move committed paint; re-home only what has not been committed.**

- **Before lock (re-home).** Nothing has been committed: the stroke is a live overlay, and a command is recorded only at finger-up or segment end (`cancelStroke` in `editor/EditorViewModel.kt`: "commands are recorded on finger-up"). A change of target is a cancel of the live stroke and a restart on the new target, replaying the buffered raw samples in the same frame. Dabs are a pure function of the samples and the stroke seed (`spec/stroke-program.md`: "a program must be, and is, a pure function of the stroke"), so the replay on the new target is exact. On screen nothing moves, because a stroke on any surface lands under the finger; only layer-dependent look (opacity, blend, occlusion by a nearer surface) can change, over at most 6 mm of stroke. A fallback plane created at touch-down is removed by the same cancel (the existing "a cancelled stroke removes it at once": `revertPlaneAdoption`).
- **After lock (split, never re-project).** The part already drawn stays on its layer, committed as one segment; the stroke continues as a new segment on the new target (8.3). Re-projecting committed dabs would mean rolling back and replaying paint on two layers' resident GPU copies, with undo and co-op consequences, to fix something the user sees as already correct under their finger. Leaving it is invisible, cheap, and exactly what a hull-crease stroke does today.

Justification of the cut at the lock: re-home is free while a stroke is short, a mistake is cheapest to fix then, and by the time the finger has moved 6 mm the user has seen where the paint went. Split is the only option that needs no new history behaviour (10.3).

### 8.3 After lock: switching the attributed surface at any point (decided, decision 7)

A stroke may span planes. After the lock the attribution keeps evaluating, every `EVAL_STEP_MM` of travel, and may switch the attributed surface at **any point of the gesture** when the predictor says so. Splits are not limited to loss of the locked target. Let `T` be the current target and `C` the best other candidate by the same score (with `a` and `c` now referring to the locked path; for a locked surface the candidate is the surface and the target is its receiving layer, 5.1). A split happens when all hold:

- `C` has `h >= 0.5`, `G = 1` and `S_C >= S_MIN` at the current sample;
- `S_C - S_T >= SPLIT_MARGIN * (1 + 2 kappa)` on `SPLIT_DWELL_STEPS` consecutive evaluations (hysteresis, as in 8.1). A target that no longer maps (the stroke left its outline, passed behind a nearer surface, or went edge-on) has `h = 0` and loses by a wide margin, so loss of the target is the limiting case of the same rule, not a separate one;
- at least `SPLIT_MIN_MM` of screen travel since the lock or since the previous split.

What bounds flip-flopping: the margin (a split back needs the former target to beat the new one by the same margin, again over the dwell), the minimum travel between splits, and the curvature factor (a scribble raises the margin). `MAX_SPLITS` is only a tunable safety bound on undo depth (10.3). Committed paint is never moved, and every split reuses the existing segment and chained-undo mechanism (10.3), with no history code change.

The split point is in screen space and gapless: when `T` is lost, the segment on `T` ends at the last point where `T` still maps, found by bisection along the straight screen step between the last good and first lost sample (the method of `SurfaceViewStroke.crossing`, `common/scene/SurfaceViewStroke.kt`, used by `ActivePlaneMapping.bridgeViews` in `editor/SceneRender.kt`); the segment on `C` starts at the same screen point mapped on `C` (and continues with its next samples). For two planes meeting at a crease that point is on their common line; for one surface in front of another it is the silhouette where the user's finger passes from one to the other, which is what they see. A split whose bisection fails (a crease another surface hides, the same case `bridgeViews` returns null for) is cut and restarted at the first sample that maps on `C`.

When `T` still maps (a switch the predictor chose), the segment on `T` ends at the current sample and the segment on `C` starts at the same screen point mapped on `C`; nothing already drawn moves. The dwell means the stroke stays on `T` for about `SPLIT_DWELL_STEPS * EVAL_STEP_MM` of travel after `C` first leads; that is the price of no flip-flop.

On a hull (a group with several views) the creases between a candidate's own faces are not splits in this sense: `ViewStrokeFeed` already cuts them by view image and region (see 10.3). Splits are between layers.

## 9. Misses, and what is done instead of refusing

Today a stroke on a layer plane whose ray misses is dropped sample by sample, "a stroke that never hits is cancelled", and `PlaneStrokeFeed` logs only a refusal (`onStrokeRefused`). Smart stroke does the following, in order:

1. **Touch-down on empty space (no candidate at `S_MIN`).** Fallback = R1 verbatim: `adoptViewPlaneForStroke()` as with the grid on (keep the active layer if it faces the camera, else a new plane facing the view riding the stroke's undo entry). The camera-facing plane always hits, so the stroke never misses. A stroke that starts in empty space and moves onto a wall keeps going on the plane it started on while it maps (it always does); 8.1 may re-home it onto the wall inside the window, otherwise it stays.
2. **Another candidate beats the target, or the target is lost and another takes over:** a split (8.3).
3. **Locked target lost, no candidate:** the stroke ends on `T` at the last good point and the rest of the gesture is not painted. This is **not silent**: a once-per-stroke notice ("Stroke left the surface") and a `GraffuxStroke` line. With a start anchor the stroke is not ended by a miss: it is a bridge between anchors (20.5). Creating a camera-facing plane mid-stroke would need the new layer to ride the stroke's undo entry, and a chained entry does not carry a plane (`EditHistory.pushDraw`'s chained branch drops the `plane` argument); changing that is a history change, so it is not a v1 behaviour (decision 9). On a plain paint-layer plane this case is rare: the layer grows to cover what is visible (`growLayerToView`), so the plane is not left at its box edge. It is a real case for a group surface, which ends at its outline.
4. **Never refuse the whole stroke.** There is always a fallback at touch-down, and a locked surface is painted through its duplicate (5.1), so no stroke is refused for a lock.

## 10. Interactions

### 10.1 Thickness (`spec/3d-thickness.md`)

A volumetric brush writes its thickness on the plane of the layer the stroke is committed to; with smart stroke that is the attributed layer. Thickness is per plane (`2.3`), so a stroke split across two planes writes each part on its own plane's channel; there is no continuity of thickness across a split in v1. An existing tilted plane that smart stroke picks keeps its own channel and axis; R1's "new plane per rotation, to build a solid from the front, side and top" is unaffected with the grid on. Per-view images of a group surface have no thickness in v1 (`2.3`), so none is written there either.

### 10.2 Draw plane, snap, R1

A set draw-plane depth overrides attribution (section 3). `strokedPlanes` and the snaps (`PlaneSnap`) are camera-only and unchanged. `adoptViewPlaneForStroke` is called only on Fallback or in R1 mode: on a `Pick`, calling it would swap or create a plane over the chosen layer, which is the opposite of the point.

### 10.3 Undo and chunking (no change)

**Attribution must not change how strokes group into undo steps, and no undo/history code is touched.** The mechanisms used already exist and already serve hull creases:

- A segment end is `EditorViewModel.onStrokeSegmentEnd()` (sets `gestureOpen`, commits, keeps the gesture open); finger-up is `onStrokeEnd()`; a cancel (second finger) `onStrokeCancel()` rolls back every segment (`rollBackGesture`).
- A later segment on the **same** layer joins the newest entry (`continuesGesture` in `EditHistory.pushDraw`). A later segment on a **different** layer becomes its own entry chained to the previous (`chainedToPrevious`, the earlier one marked `chainedNext`), decided in the commit by `gestureCommittedLayerId != layerId` (`editor/EditorViewModel.kt`, the push that calls `history.pushDraw`). That test is about layers, not hulls, so a split between two paint layers is chained exactly like a stroke crossing a crease between two view images; one undo and one redo take the whole gesture.
- A stroke that is never split is one entry, as now. A re-homed stroke (8.2) commits once, on its final target: one entry.
- The fallback plane created at touch-down rides the first entry (`PlaneSwap.created`), as R1 does; so does a locked-surface duplicate or new receiving layer (5.1).

Costs and limits, to be tested (section 16): each chained segment is its own entry in the stack, so a split gesture uses more than one slot of `maxStackSize`; the tunable `MAX_SPLITS` bounds it (the margin and minimum travel keep real strokes well below it). The test must show a split gesture undoes in one step and does not evict more history than a hull-crease stroke of the same segment count.

### 10.4 Co-op

Peers replay the result, never the decision. Each committed segment emits `Op.StrokeComplete(layerId, BrushStroke)` (`editor/EditorViewModel.kt`, the commit path), with points already mapped into that layer's bitmap space, at segment end or finger-up; a fallback plane's, a duplicate's or a new receiving layer's creation is a `LayerAdd` through the existing path. A split gesture is therefore two or more ordinary `StrokeComplete` ops on different layers, the same as a hull-crease stroke. Not carried: scores, the provisional target, the grid state, the setting. Nothing is emitted during the provisional window, so a re-home is invisible to peers. A peer needs no code for this feature.

### 10.5 Tools

| Tool | Attribution |
|---|---|
| Brush | Yes. |
| Eraser | Yes, with the pixel gate (6.2). It also uses `p` and `o` from the mask, so it targets the layer that has the pixels under the finger. |
| Smudge, Blur, Sharpen, Dodge, Burn | Yes, with the pixel gate. |
| Clone, Heal | No. The clone source is a point of the active layer (`cloneSource`, `cloneSourceView`); a different destination layer would make the source meaningless. They behave as with the grid on. |
| Liquify, Fill, Pen, Text, Select, Frame, Color | No (not stamp strokes, or a single tap with its own pick path). Unchanged. |

A Jetpack Ink stroke is shown live in screen space and committed on lift (`commitInkThroughPlane`). Smart stroke decides it once, from the whole finished path, with the same scorer (batch mode, no provisional window), then maps and cuts it through `ActivePlaneMapping.inkSegments` as today. Phase 2.

### 10.6 Locks, selection masks, alpha lock, clip

As in sections 5, 5.1 and 6.2. Locked layers are never modified; a locked surface is painted through a duplicate (5.1, decision 4). The paint surface is not composed while the **active** layer is locked (`onPaintGateClosed`); in v1 (phase 1) that is unchanged, so with a locked active layer grid off paints nothing, the same as grid on, until phase 2 opens the gate for a smart stroke whose receiving layer is a new layer (5.1).

### 10.7 Puppetry (`spec/3d-puppetry.md`)

Candidate planes are read from the same plane chain the renderer reads (`planeChain`, `SceneMath.chainModel`), so a posed part is judged where it is drawn and attribution needs no change when poses exist. A stroke is stored in the attributed layer's flat space, so paint made on a part moves with it. A gesture split between two hinged parts is two segments on two layers. The camera and pose are frozen for a stroke at touch-down (the mapping holds them), and a pose drag is a different gesture.

### 10.8 Group surfaces and hulls

A group surface's paint layer is one candidate; once picked, the existing mapping chooses strip, view, face and region (`SurfaceMath.hit`, `SurfaceFootprint`, `ViewStrokeFeed`), and the "lands under the finger", "visible to the camera" guards apply unchanged. Smart stroke chooses the layer; it never chooses a face.

## 11. Where it plugs in

All names read in the tree.

| Concern | Place | Change |
|---|---|---|
| The scorer | new `common/scene/StrokeAttribution.kt` beside `PlaneSnap.kt` | Pure Kotlin, no Android types: candidate and feature data classes, weights, `decide`, `evaluate`, hysteresis, split rule. |
| Stroke start | `onStrokeStart` lambda in `editor/EditorScreen.kt` (calls `vm.adoptViewPlaneForStroke()`, sets `strokePlane.value`, chooses `strokeFeed` or `viewFeed`) | In SMART mode, call `vm.attributeStroke(sample)` first. On `Pick`: skip `adoptViewPlaneForStroke`, set `strokePlane.value = ActivePlaneMapping.of(state, layerId = target)`. On `Fallback`: call `adoptViewPlaneForStroke()` as today. |
| Mapping | `ActivePlaneMapping.of(state)` in `editor/SceneRender.kt` reads `state.activeLayerId` | Add an optional `layerId` parameter, default the active layer. Nothing else in the mapping changes. |
| The layer the stroke paints | `EditorViewModel.onStrokeStart(startPoint, ...)`: `val layerId = (strokeViewTarget?.let { ... } ?: state.activeLayerId)`; everything downstream reads `strokeLayerId`, captured there | Add one more override beside `strokeViewTarget`, `strokeAttributedLayer`, set for the duration of the call as `onViewStrokeStart` sets `strokeViewTarget`. `activeLayerId` is not changed. |
| Locked surface | `EditorViewModel`, a helper beside `adoptViewPlaneForStroke`; `PlaneSwap.created` | Creates or finds the duplicate (5.1) as the fallback plane is created; no `EditHistory` change. |
| Per-view images | `onViewStrokeStart(primaryId, view, sample, ...)`, `ensureViewImageLayer`, `strokeViewTarget` | Unchanged; `primaryId` is `plane.layerId`, which is now the attributed layer. |
| Feeds | `PlaneStrokeFeed`, `ViewStrokeFeed` in `editor/SceneRender.kt` | Unchanged. A new `AttributedStrokeFeed` wraps them in `editor/EditorScreen.kt`: it owns the raw-sample buffer, the session and the evaluation cadence; it re-homes by cancel and restart, and splits by `segmentEnd` and a restart on the new target's feed. |
| Ink | `commitInkThroughPlane` (`editor/EditorViewModel.kt`) | Phase 2: batch decision before `adoptViewPlaneForStroke()`/`ActivePlaneMapping.of`. |
| Commit and undo | `commitStampStroke`, `endStroke`, `onStrokeSegmentEnd`, `EditHistory.pushDraw` | None. |
| Layer prior and continuity | `LastStroke` (`editor/LastStroke.kt`) holds layer, camera and viewport of recent strokes | Reused for `LastStroke.holds`. End point and time are not in it; the attribution session keeps its own small `LastTarget` record. History is not touched. |
| Content masks | `editor/PaintScans.kt` | A sibling `OccupancyScans` following its keying and drop rules. |
| The toggle | `onToggleSurfaceGrid`, `surfaceGridVisible`, `meshMenu`, `SurfaceMeshButton` | No change to the flag; the long-press menu gains a "Smart stroke" row. The button also shows in 2D-only projects, toggling `InfiniteGrid` (section 3; a separate small change). |
| Diagnostics | `strokeLog` (tag `GraffuxStroke`) in `EditorViewModel` | New `attr` lines (section 13). |

## 12. Performance budget

- Per touch-down: at most K = 8 ray and plane intersections (the chain model per candidate is the cost; it is the same maths `facesCamera` and `ActivePlaneMapping` already do), 8 mask lookups, one sort of 8. Budget in section 4.
- Per evaluation step (every 1.5 mm of travel, so at most about 60 per second for a fast stroke, independent of the touch rate): the same, plus the path features over 8 steps. Proposed: at most 0.2 ms at p95, unmeasured. Between steps a sample costs nothing extra.
- Ray casts per move event: none beyond the existing mapping, except at an evaluation step (K).
- Footprint-ring casts only for the best 2 candidates, and only at an evaluation step.
- Memory: 32 KB mask per candidate layer; the table lives only while the layer exists.
- Main thread: touch-down and steps inline (bounded by K); mask builds on the default dispatcher. Decided; revisit if the budget is missed (section 4).
- Determinism: evaluation is by travel, not time, so the replay harness reproduces a device's decisions. Only `LOCK_MS` and the continuity decay read the clock, and both take an injected clock.

## 13. Transparency and debuggability

- **Log line** (`GraffuxStroke`, via `strokeLog`), one per decision, one per re-home or split, one when a stroke ends with its target. No pixels, no user text; layer ids truncated to 8 characters:

~~~
attr start mode=SMART tool=BRUSH cands=5/9 pick=ab12cd34 S=0.71 next=ef56ab78 S=0.52 margin=0.19 h=1.0 o=1.0 f=0.80 p=0.60 c=0.00 a=1.00 cold=0 dt=0.12ms
attr rehome from=ab12cd34 to=ef56ab78 at=3.0mm S=0.58 vs 0.44 dwell=3
attr split from=ef56ab78 to=11aa22bb at=41.5mm lost=3 S=0.55 splits=1
attr end target=11aa22bb segments=2 locked=1 refused=0
~~~

- **Debug overlay** (debug builds only): each candidate's projected box with its score and gate, the picked one filled, the provisional window and lock distance, the split points. Same layer as the snap highlight (`SnapPlaneHighlight` pattern, fade).
- **Release:** the attributed layer becomes the selected layer (decision 10), which the layer panel shows, and a HUD line "Painting on <layer name>" (a polite live region, as the snap's line is) announces a changed target. No outline of the picked surface is drawn (decision 12): surfaces only exist where strokes are, and the background grid stays visible. Text from resources (`NoHardCodedChromeTextTest`).
- **Replay export (decision 13):** an explicit debug action writes the scene summary (planes, masks, last target) and the raw touch stream to app-private storage on explicit request, for the harness (section 15). Local only, never uploaded.

## 14. Settings and accessibility

- **Opt-out.** A "Smart stroke" check in the "#" long-press menu (`meshMenu`), default on, persisted in app Settings (the grid state itself stays session-only as today). Off: grid-off behaves as grid-on for strokes (R1), so "#" is only a display toggle, as before this feature.
- **No override UI (decision 8).** There is no menu item, gesture or mode to override a guess: a wrong guess is fixed by undo (one step, unchanged) and drawing again. What stays, none of it an override feature: the selected layer is the strongest prior `a` (6.1); a set draw-plane depth is the existing explicit plane choice and wins (section 3); the opt-out above returns grid-off to R1.
- **Explainable:** every pick has a one-line reason (section 13) and the picked layer becomes the selected one.
- **TalkBack:** the "#" button's `stateDescription` ("Shown"/"Hidden") gains a smart-stroke reading from string resources; the HUD line announces a changed target; the menu rows are ordinary 48dp rows. No new gesture.

## 15. Testing

Pure-Kotlin tests with fakes (no Android, no GPU), in `core:common`:

- `StrokeAttributionTest`: table-driven, fake candidates (plane, box, mask, flags), one row per case with the expected pick, the expected order and the margin sign:
  - a stroke that starts on a tilted plane (pick the plane, not the facing active layer);
  - on an overlap (nearer layer with content wins; nearer empty layer does not);
  - on empty space (Fallback; the R1 call is made);
  - across a crease of two planes (start on one, split to the other at the crease, gapless);
  - with the eraser (the layer with pixels under the finger; none within the footprint gives Fallback or the existing refusal, never a clean pick of an empty layer);
  - alpha lock, clip, selection, off-screen, edge-on, cold mask (weights renormalise, no throw), tie order down to the layer id.
- `AttributionHysteresisTest`: no flip-flop on a noisy path; dwell; curvature raises the margin; lock at 6 mm and at 250 ms with an injected clock; no re-home after lock.
- `AttributionSplitTest`: a split on a switch the predictor chooses while the target still maps, and on loss of the target; the margin and `SPLIT_MIN_MM` stop flip-flop on a noisy path across two surfaces; the tunable `MAX_SPLITS`; bisection point on both sides; a hidden crease cuts.
- `LockedSurfaceDuplicateTest`: a stroke on a locked surface picks it and paints a duplicate with the same pose in the active layer; the locked layer's pixels and properties are identical afterwards; a second stroke on the same surface reuses the duplicate (one layer, not two); an active layer that cannot take paint (locked, is the surface itself, holds paint on another plane) gives a new layer above the locked one; the duplicate and the stroke undo and redo as one step through the existing history; a cancelled stroke removes a created duplicate and leaves a reused one; pixel tools never target the locked layer.
- `SmartStrokeSelectionTest`: the selection moves at the lock to the attributed layer (the receiving layer for a locked surface), not on a re-home before it, follows the last target across a split, and changes neither the camera nor the grid state.
- The "#" in 2D-only projects (the separate small change, section 3): visible without a 3D group, toggles `InfiniteGrid`, changes no stroke command.
- Weights: the table sums to 1.0; each weight is non-negative.

In `feature:editor` (JVM, with the existing harness):

- `AttributedStrokeFeedTest`: with fake feeds, re-home is cancel plus restart with the buffered samples (same samples reach the new target); split is `segmentEnd` plus a start; a second finger cancels all.
- `SmartStrokeUndoTest`: a gesture split across two paint layers is **one** undo and **one** redo and its entries are chained as a hull-crease stroke's are; an unsplit stroke is one entry exactly as before; a cancelled gesture removes every segment and any fallback plane.
- `SmartStrokeCoopTest`: a split gesture emits one `StrokeComplete` per segment, nothing during the provisional window.
- `GridGatingTest`: the grid on, a set draw depth, the setting off, a tool outside 10.5, and no 3D group all give R1 with **no call into the attribution component** (a fake that fails the test if invoked).

**Regression guard (grid-on and 2D-only unchanged).** Besides `GridGatingTest`, with the grid on the existing suites run unmodified and must pass: `DrawPlaneDepthTest`, `SnapToPlanesTest`, `StrokeFeedGuardsRequiredTest`, `Scene3DRailUiTest`, `NoHardCodedChromeTextTest`, and the hull and per-view stroke tests. A byte-identical check: the same recorded touch stream in a 2D-only project and in a grid-on 3D project yields the same stroke commands as on `main` before the feature.

**Anchors and the prism ridge** (section 20.7) are tested there, including the headline scenario through the existing predictor.

**Golden scenarios.** A set of small scene files (planes, masks, last target, camera) with a touch stream and the expected decision and score ordering (within 0.02), checked into test resources. Changing a weight means regenerating goldens on purpose, in the same PR.

**Replay harness and acceptance numbers.** A test-sources tool, `AttributionReplay`, reads scenes plus streams from NDJSON, runs the real scorer, and prints a confusion matrix, margin histogram, re-home and split counts, and per-step time. Ground truth is the layer each stroke finally belonged to, taken from owner-recorded grid-off sessions: a stroke undone within 3 seconds and redrawn elsewhere counts as a wrong guess. It also tunes the weights in `Weights` by a constrained search (sum 1, each in [0, 0.5]) maximising locked accuracy minus a re-home penalty. Proposed acceptance targets, to be set by the owner after the first corpus (unmeasured now):

| Measure | Target |
|---|---|
| Table-driven and golden scenarios | 100% |
| First-guess accuracy (touch-down pick equals final layer) | at least 90% |
| Locked accuracy (target at lock equals final layer) | at least 95% |
| Re-home rate (strokes re-homed in the window) | at most 10% |
| False-split rate (splits the user undid and redid unsplit) | at most 1% of strokes |
| Undo-after-stroke within 3 s, per 100 strokes | trending down release over release |
| Touch-down cost / step cost | within section 4 and 12 budgets |

## 16. Phased rollout

- **Phase 0, gating only.** `strokeMode`, `strokeAttributedLayer`, the `ActivePlaneMapping.of(state, layerId)` parameter, the "Smart stroke" row, the log lines, and `GridGatingTest`. Grid off still behaves as R1 (the scorer returns Fallback). Ships dark; proves the regression guard.
- **Phase 1, single-surface attribution at touch-down with hysteresis.** The scorer, masks, the decision at touch-down, the provisional window with re-home, the lock. No splits, no Ink. Brush and Eraser. Selection following (decision 10), the locked-surface duplicate (5.1) when the active layer is unlocked, and the HUD line.
- **Phase 2, mid-stroke re-attribution and cross-plane continuation.** Splits at any point of the gesture through the existing segment and chain mechanism (margin, dwell, minimum travel, tunable `MAX_SPLITS`), the Ink batch decision, the pixel tools, the paint gate opened for a locked active layer (the locked-surface duplicate as a new layer, 5.1).
- **Anchors (section 20):** phase 1 the start snap rule and pin, phase 2 end ranking from the existing predictor's heading, the bridge plane and waypoints, phase 3 edge anchors.
- **Phase 3, tuned weights and optional overtake.** Weights from the replay corpus (linear, same features), tuning of `SPLIT_MARGIN`, `SPLIT_DWELL_STEPS` and `SPLIT_MIN_MM`, mask features for group surfaces, and, only with the owner's approval, the mid-stroke plane carve-out (decision 9).

- **Separate small change, not a phase:** the "#" toggle in 2D-only projects (section 3, decision 5). It touches the rail condition, the menu and the `InfiniteGrid` call, and no stroke code.

Each phase is gated on the previous phase's acceptance numbers and the regression guard.

## 17. Risks

- **A wrong first guess feels worse than a predictable rule.** Mitigation: re-home inside 6 mm, the selected-layer prior, the HUD line, undo and redraw (there is no override UI, decision 8), a conservative `S_MIN`, and the off switch. Measured by the undo-after-stroke rate.
- **Hidden mesh.** Grid off hides the wire mesh; decided that no outline is needed (decision 12).
- **Flip-flop between surfaces after the lock.** Splits are allowed at any point (decision 7); bounded by the margin, dwell and minimum travel (8.3) and measured by the false-split rate (section 15).
- **Duplicate layers on locked surfaces.** Reuse is by pose and parent (5.1); if the user moves the duplicate, the next stroke on the locked surface makes a new one. The panel can gain layers the user did not ask for by name.
- **Re-home flicker:** cancel and replay in one frame must not show a frame of missing paint on the GPU live engine. Needs a device check (not testable in JVM).
- **Undo depth from chained segments** (10.3): bounded by `MAX_SPLITS`; tested.
- **Content features are coarse** (128 cells, about 22 px on a 2816 px layer): proximity to a stroke edge is approximate; group surfaces and hulls have none in v1.
- **Pixel gate with a cold mask passes**, so the first strokes after a layer edit can pick an empty layer with the eraser. The commit then paints nothing (an eraser on empty pixels), which is harmless but could be a surprise.
- **Mid-stroke miss ends the stroke** on a group surface (9.3) until the carve-out is approved (decision 9).
- **Tuning on one owner's corpus overfits.** Keep weights few, linear, and the goldens as the contract.
- **Interaction with the not-yet-implemented R1 and thickness work:** this spec reads R1 as `spec/3d-thickness.md` states it; the code today implements part of it (`wantsViewPlane`, `adoptFacingPlane`, `adoptPlaneBesideSurface` with a 60 degree grazing test for surfaces). Whatever R1 becomes, grid-on is R1 verbatim and Fallback calls it.

## 18. Alternatives considered

- **Decide after the first N mm, paint nothing until then.** Rejected: violates the owner's first priority.
- **Always re-project the whole stroke when the best target changes.** Rejected after lock (8.2): rolls back and replays committed paint on two layers' GPU copies, changes undo and co-op behaviour, for no visible gain.
- **A learned classifier from the start.** Rejected for v1: needs a corpus that does not exist, is not explainable, and is not reproducible on every device. Phase 3 tunes the same linear weights instead.
- **A separate `smartStroke` flag instead of the grid state.** Rejected: the owner decided "#" is the toggle. The setting in section 14 is only an opt-out that returns grid-off to R1.
- **Decide on a worker thread.** Rejected for v1 (section 4).
- **Hold-to-pin touch gesture.** Rejected for v1: it would delay or overload the first touch. Decision 8: no override UI at all.
- **Never auto-target a locked layer; refuse with the locked notice.** The original recommendation, rejected by the owner (decision 4): the stroke is applied to an automatic duplicate of the locked surface (5.1).
- **Splits only when the locked target is lost, at most 3.** The original recommendation, rejected by the owner (decision 7).

## 19. Owner decisions

### Decided (from the owner, original design)

| Item | Decision |
|---|---|
| The toggle | "#" on the rail toggles the grid. |
| Grid on | Strokes go to the plane facing the screen (R1), absolute, hulls and group surfaces included. |
| Grid off | Smart stroke, from the starting point, all surfaces' viewing angles, their contents and the ongoing stroke; one gesture may be split across surfaces and planes. |
| Priority | The stroke is on screen under the finger immediately; attribution never delays the first dab. |
| Undo | The existing chunking stays; no change to undo or history code. |
| Prism ridge | Owner scenario (section 20.1): from apex A toward apex B through empty space, with the grid off, the result is exactly the line from A to B. Solved by reusing the existing stroke predictor (the owner: "we can reuse the stroke-prediction feature"). |

### Decided by this spec (reversible)

| Item | Decision |
|---|---|
| Stroke mode | Read once at stroke start from `surfaceGridVisible`, the setting, the tool and the draw depth; cannot change a stroke begun. |
| Fallback | R1 verbatim at touch-down. |
| Co-op, format, history | Unchanged; peers receive ordinary per-layer strokes. |

### Decisions (owner)

Numbers match the questions the spec first asked. All are decided.

1. **What the finger sees before attribution settles.** DECIDED, yes: real paint at once on the touch-down pick, no placeholder (sections 4, 8.2). A change inside the 6 mm window is invisible apart from layer-dependent look.
2. **Dabs already drawn when attribution changes.** DECIDED, OK: re-home before the lock, split after, never move committed paint (8.2).
3. **Eraser, Smudge and the other pixel tools.** DECIDED, OK: the same attribution with the needs-pixels gate (6.2, 10.5); Clone and Heal bypass it (their source is a point of the active layer), as do Liquify, Fill, Pen, Text, Select and Frame. A locked layer is never a target for them (5.1).
4. **Locked layers.** DECIDED, and the recommendation (never auto-targeted, refuse with the notice) is REJECTED. Owner: "If a layer is locked, the user can still draw on the same surface by duplicating that surface into the layer that the stroke is being applied to." Clarified: this happens automatically. A locked layer's surface stays an attribution candidate; the stroke is applied to a duplicate of that surface (same geometry and pose) in the receiving layer (the active layer when it is unlocked and takes paint, else a new layer); the locked layer and its pixels are never modified; no prompt, confirmation, refusal or notice. The duplicate is made at touch-down without delaying the first dab; repeated strokes reuse it; the duplicate and the stroke are one undo step through the existing mechanism, with no history code change (5.1, 10.3).
5. **2D-only projects.** DECIDED: no change to strokes; the "#" grid toggle is visible in 2D-only projects too and toggles the grid drawn on the canvas (`InfiniteGrid`) instead of the wire mesh (section 3). The implementation is a separate small change.
6. **Grid default for new projects.** DECIDED, yes: on (a 3D surface cannot be made without it), session only (section 3).
7. **A stroke spanning planes.** DECIDED, yes, and not limited to loss of the locked target: the attributed surface may switch at any point of the gesture when the predictor says so, with hysteresis. Flip-flop is bounded by the margin and a minimum travel between splits; the split cap is a tunable safety bound. Committed paint is never moved; splits reuse the existing segment and chained-undo mechanism (8.3, 10.3).
8. **Overriding a wrong guess.** DECIDED: no override UI. The user undoes and tries again (section 14).
9. **A camera-facing plane after the first dab.** DECIDED, OK: not in v1 (section 9).
10. **An attributed layer becomes the selected layer.** DECIDED, yes: at the lock, following the last attributed layer across splits; it does not by itself change the camera or the grid (section 3).
11. **Painting on an existing tilted plane** (the open question of `spec/3d-thickness.md` section 15). DECIDED, correct: it is done with the grid off.
12. **Hiding the wire mesh with the grid off.** DECIDED: no outline of the picked surface is needed, because surfaces only exist where strokes are and the background grid stays visible (sections 3, 13).
13. **Recording scenarios for tuning.** DECIDED, OK: an explicit local-only debug action (section 13).

### Still open

- **The receiving layer when the active layer cannot take paint** (decision 4). Recommended: a new paint layer directly above the locked layer, named after it, selected. Owner to confirm; everything else in decision 4 is decided.

New, from the prism ridge scenario (section 20), each with the recommended default:

14. **Snap tolerance.** Recommended: start 4 mm and end 7 mm for a finger, 3 and 5 for a stylus, dropped at 1.5 times; screen millimetres, with the 3D form at the anchor's depth (20.2).
15. **Straight or pinned.** Recommended: straighten to the exact segment when the hand path stays within 5 mm of the chord, otherwise keep the hand's shape pinned at both anchors (20.5).
16. **AB nearly along the view axis.** Recommended: no bridge below `EDGE_ON_COS` (about 10 degrees); the stroke stays a normal stroke, one log line, no notice. Alternative: the plane through AB and the camera up vector.
17. **Live or at pen-up.** Recommended: the stroke follows the finger, the snap applies at pen-up, a ring on the predicted anchor is the only live cue, and the existing tail stays undrawn. Alternatives: draw the tail bent toward the anchor, or snap live inside the end radius.
18. **Edges as anchors.** Recommended: vertices only in v1; edge-to-edge strokes work through two vertices (20.5); edge anchors in phase 3.
19. **Waypoint chains.** Recommended: yes; a turn of 25 degrees or more, or a 150 ms pause, within the end radius of an anchor ends a leg and starts the next, one undo step.
20. **Reusing the predictor.** Recommended: consume its tail through a new `onStrokeHeading` callback and a pure `heading()` function, with no change to the models or the rankings; whether the Kalman profile should differ for a stylus is left to measurement.

## 20. Anchors and the anchor-to-anchor line (owner scenario: the prism ridge)

Added from the owner's scenario. Everything here extends sections 5 to 9 and reuses their mechanisms; it adds no history, format or co-op change.

### 20.1 The named acceptance scenario: "prism ridge"

Owner's words: "Imagine a long shape with three long rectangle sides and two triangle sides (a triangular prism). Take away two of the long rectangles and their surface planes. It leaves one long rectangle with two triangles perpendicular to it at its ends, both with one point (apex) aimed away from it. With the grid off, if I draw a line from one of those points in the direction of the other point, which is where I would stop drawing that line, because of the smart stroke feature, that's exactly what should happen."

Reading: the scene is one rectangle `R0` and two triangle surfaces `TA` and `TB` standing on its two short edges, each with an apex (`A` on `TA`, `B` on `TB`) pointing away from `R0`. The two removed rectangles leave no surface along the ridge `AB`: it is empty space. With the grid off, the user touches near `A`, drags roughly toward `B` through empty space, and lifts near `B`. The result must be exactly the line from `A` to `B` along the ridge: the stroke begins at `A`, ends at `B`, and lies on a plane that contains the line `AB`. The scenario is the headline acceptance test (20.7) and the design below is what it implies. It is solved by the stroke predictor the app already has (20.4): the heading of the stroke from `A`, ranked against the anchors, picks `B`.

### 20.2 Anchors

An **anchor** is a point of existing geometry that a stroke may start at or end at. Anchors are first-class features of the scorer's scene, beside candidates.

- **Anchor set (v1: vertices only).** For each candidate (section 5, K at most 8, locked layers included) its corner points in world space, read from the same plane chain the renderer reads (`planeChain`, `SceneMath.chainModel`): the four corners of a paint layer's box; the outline vertices of a group surface (a triangle has three); the profile vertices of a hull. A profile with many points keeps only corners, vertices where the outline turns by more than `ANCHOR_CORNER_DEG`. Capped at 16 per candidate and `MAX_ANCHORS` in all. Each anchor is: owner layer, world point, kind (`VERTEX`; `EDGE` is phase 3, 20.9). Hidden per-view image layers are never owners; a vertex shared by two surfaces is one anchor with both owners.
- **Derived, not stored.** Anchors are computed from geometry at touch-down and cached per (layer, geometry generation), like the occupancy masks; nothing is added to the project or the history. Projection to the screen is redone per camera.
- **Visibility.** An anchor is usable when its owner is visible (section 5) and the point is not hidden behind a nearer surface whose content at that point has alpha above 0.1 (the `o` rule). An anchor on a locked surface still snaps (20.8).
- **Tolerance.** Screen distance is primary, in millimetres from the device density, so it is the same on any device: for a finger start snap `ANCHOR_START_MM` = 4 and end snap `ANCHOR_END_MM` = 7 (a lifting finger is less exact); for a stylus 3 and 5 (20.4). The 3D form is the same test: the closest approach of the touch ray to the anchor, converted at the anchor's depth (`tolerance_px * depth / focal`), so a far anchor is not harder to hit. **Hysteresis:** a candidate end anchor is dropped only when the path is more than 1.5 times its radius away, and a changed prediction needs `ANCHOR_DWELL_STEPS` consecutive evaluations and an `ANCHOR_TIE_EPS` margin (20.4). All numbers are proposed defaults, unmeasured.

### 20.3 Start snap at touch-down

In step 2 of section 4, inside the same budget (at most 0.1 ms more, unmeasured; the anchor test is at most `MAX_ANCHORS` distance checks): if the touch is within `ANCHOR_START_MM` of a visible anchor `A`, the stroke has a **start anchor**.

- The first dab is painted at once at the finger, exactly as today. Nothing waits for the anchor and no dab is moved at touch-down.
- The anchor's owner candidates count as hit (`h = 1`) even when the touch lands just outside their outline, so a touch at an apex attributes to the triangle as the footprint rule would, not to a surface behind it. No weight changes.
- The snap itself is applied to the stroke when it is committed (20.5): the start point is pinned to `A`, and the displacement (at most the tolerance) is blended out along the first `SNAP_BLEND_MM` = 12 of path, so a stroke that does not end at an anchor only has its start tidied. Before the lock the target can still be re-homed as in 8.1; a stroke cancelled by a second finger removes everything.

### 20.4 End prediction: reusing the stroke predictor

There is no new predictor. The repository already predicts where the pen is heading; the smart stroke consumes that and ranks anchors against it.

**What exists (read in the tree, `editor/prediction/`).**

- `GesturePredictor` (`GesturePrediction.kt`): `record(GestureSample)` and `predict(targetUptimeMillis): GesturePrediction?`, with `GestureSample(position, uptimeMillis, pressure)` the real samples (predicted ones never enter the stream). Two models run: Google Ink's Kalman predictor (`GoogleInkGesturePredictor`, native, three tuning profiles chosen in Settings, TEMPORARY) and `LinearGesturePredictor` (last velocity), tried in that fixed order; Ink is absent on JVM and linear runs alone.
- `PredictionTournament` (same file) owns them. `predict(targetUptimeMillis, tailLeadMs)` returns the `PredictionTail(model, points)`: the pen's predicted path from the latest real sample, in order. The tail reaches the **measured touch-to-paint lag** (`EditorViewModel.predictionLeadMs`), clamped to 1 to `TAIL_FRAMES` (2) display frames, so about 17 to 33 ms ahead at 120 to 60 Hz. Every model's error roughly doubles per frame and is 100 px or more at frames 3 and 4 (the file's own measurement), which is why the tail is short. `HORIZON_FRAMES` (4) is used only for the session rankings (`rankings()`, the `StrokePrediction` log lines and issue reports, TEMPORARY).
- Where it runs: on the touch path, on the UI thread, once per real sample. `DrawingCanvas.recordRealPoint` calls `predictionTournament.record(...)` then `predict(uptimeMillis + nextFrameMs, predictionLeadMs())`; `InkBrushCanvas.record` does the same for the Jetpack Ink engine. **The returned tail is discarded** ("Predictions are ranked (TEMPORARY reports) but no longer drawn"), and the tournament lives inside the composable (`rememberPredictionSession`), so attribution code cannot reach it today. The tournament times itself (`costLine()`: mean and max microseconds per sample, session long); those figures are not in the repository and must be read from a device log before the budget is signed off.
- Tests: `GesturePredictionTest`, `PredictionRankingReporterTest` (JVM, linear model).

**What the horizon can and cannot do.** A tail 17 to 33 ms long covers 10 to 30 px at finger speeds, not the hundreds of px to an apex. It supplies the **heading and the bend of the stroke's continuation**, smoothed by the Kalman filter, not a far position. Asking Ink for later times is not an option (its error is 100 px or more by frame 3). So the continuation used here is: the tail, then a straight ray along the tail's last tangent, widened by a cone that grows with distance (the same doubling-per-frame uncertainty, stated as an angle). That extension is geometry in the new attribution code, not prediction.

**What the smart stroke consumes (and what must be extended).**

| Need | Today | Change |
|---|---|---|
| The tail per real sample, reachable from attribution | `predict` returns `PredictionTail`, discarded in `DrawingCanvas.recordRealPoint` (`DrawingCanvas.kt`, ~line 137) | New callback `onStrokeHeading: (StrokeHeadingSample) -> Unit` on `DrawingCanvas`, called after `predict`, forwarded in `EditorScreen.kt` to `AttributedStrokeFeed`. Presentation is untouched (the tail is still not drawn). |
| A heading value | none | New pure function `PredictionTail.heading(latest: GestureSample, tool: BrushInputTool): StrokeHeadingSample?` in `prediction/` (JVM-testable): `StrokeHeadingSample(origin, direction (unit), bend, reachPx, model, confidence, tool, uptimeMillis)`. `direction` is the last tail segment's tangent; `bend` the signed turn across the tail in degrees per 10 px; it returns null when `reachPx` is under `HEADING_MIN_PX` (3 px, a near-stationary pen). The adapter maps it to a plain data class in `common/scene/StrokeAttribution.kt` (`Heading`), which has no Android types. |
| A confidence value | none | `confidence` in [0, 1]: 1 for `google-ink`, 0.6 for the `linear` fallback, scaled down by the session's mean error at the tail's frame (`rankings()`, when it has data) relative to the tail's reach, and 0 when the tail is null. Computed in `heading()`, not stored in the tournament. |
| Not depending on TEMPORARY apparatus | rankings, issue filing and `AndroidXMotionGesturePredictor` are TEMPORARY | The smart stroke depends only on `predict`'s tail and `PredictionTail`. If the ranking and reporting are retired, `predict` and the tail stay (or move, unchanged, to a small `StrokeHeadingEstimator`). |
| Ink-engine strokes | live tail not drawn; Ink predicts for itself | No live heading needed: an Ink stroke is decided once from its finished path (10.5), where the lift point and final direction are known. |

**Ranking anchors against the continuation.** At each evaluation step (every `EVAL_STEP_MM` of travel) with a start anchor `A`, the latest `Heading` (origin `P`, direction `d`) and each visible anchor `B` (20.2):

~~~
along = (B - P) . d                    distance of B ahead of the pen along the heading
perp  = |(B - P) - along * d|          distance of B from the continuation ray, screen space
phi   = angle between d and (B - P)
cone  = CONE_DEG * (2 - confidence) + |bend| * along / 10px     widens with distance and bend
g_cont  = exp(-(phi / cone)^2)         when along > 0, else 0
g_chord = exp(-(theta_AB / CONE_DEG)^2)  theta_AB: angle between (P - A) and (B - A)
g_len   = rho (travelled fraction of |AB|) up to 1.05, then exp(-((rho - 1.05) / 0.15)^2)
g_end   = exp(-(|P - B| / (2 * ANCHOR_END_MM))^2)
F_B   = 0.40 g_cont + 0.20 g_chord + 0.15 g_len + 0.25 g_end
~~~

`g_cont` is the predictor's term and the largest. `g_chord` is the stroke's own long-baseline direction from `A`, kept because the 17 to 33 ms heading is noisy at low speed; with no heading (null tail, `confidence` 0) `g_cont` is 0 and the weights renormalise over the rest, as for a cold mask (6.3). `perp` is logged and used for the tie order. `CONE_DEG` = 8, proposed.

The predicted end is the anchor with the highest `F_B` at or above `ANCHOR_F_MIN` (0.55), leading the runner-up by `ANCHOR_TIE_EPS` (0.05) for `ANCHOR_DWELL_STEPS` (3) consecutive steps, after at least `ANCHOR_MIN_TRAVEL_MM` (10) of travel from `A`. Ties inside the margin: the smaller `perp`, then the nearer to `P`, then the nearer to the camera, then the anchor id, lexically (reproducible). On a true tie the recommended behaviour is to pick by this order rather than not snap, since a wrong snap is one undo.

- **Cost.** Nothing is added per sample: `record` and `predict` already run on every real sample. The smart stroke adds building one `StrokeHeadingSample` per sample (a few flops) and the ranking at evaluation steps only (at most `MAX_ANCHORS` anchors, no allocation). The tournament's own per-sample cost (several predictions per model per sample for the TEMPORARY rankings) is pre-existing and counts against sections 4 and 12 when smart stroke is on: the budgets are checked with `costLine()` and a regression is blocked; if the tournament dominates, the ranking horizons are skipped while a smart stroke is in progress (the tail horizon is untouched).
- **Finger and stylus.** The predictor takes no tool type: `GestureSample` is position, time and pressure, and both tools feed the same models. The tool is known at the canvas (`BrushInputTool.FINGER` or `STYLUS`, from `MotionEvent.TOOL_TYPE_*` in `DrawingCanvas`), so it is carried on the heading and the thresholds follow it: a stylus is precise, so `ANCHOR_START_MM` 3 and `ANCHOR_END_MM` 5, and a finger 4 and 7 (20.2); the cone is unchanged. Whether the Kalman profile should differ by tool is unmeasured. A stylus's hover before contact is not used in v1.
- **When the end snaps.** At pen-up, when the predicted anchor `B` is within `ANCHOR_END_MM` of the last sample. The end is not extrapolated in v1: a finger that stops 15 mm short gets no end snap ("stops near B" is the owner's rule). The predictor only decides which anchor is meant, and the last sample decides whether the stroke reached it.
- **What the finger sees while drawing.** The live stroke follows the finger exactly. The existing tail is not drawn today (the Brush engine stopped drawing it as it read as a wrong line, per the file's note on error), so there is no tail to bend. Decision with a recommended default: leave the tail undrawn and show a cue only on the predicted anchor, a thin ring in the guide colour (no fade under reduced motion); optionally a hairline from the stroke head to the ring. The snapped line replaces the live stroke at pen-up, in the same frame as the commit.
- **No anchor fits.** Normal smart stroke: the path is kept as drawn, with at most the start pin of 20.3. A stroke that starts at an apex but heads elsewhere (no anchor within the cone) is exactly this case.
- **Mirror cases (same machinery).** Start in free space and end on a vertex: no start anchor; at lift, the anchor `B` within `ANCHOR_END_MM` that `g_cont` and `g_end` favour is pinned (the heading must point at it for the last `ANCHOR_END_STEPS` steps). Start on a vertex and end in free space: the start pin only. One anchor pins in screen space on the plane the stroke lives on (the point where the view ray through the anchor meets that plane); only two anchors define a plane (20.5).
- **Waypoints and chains.** Where the path passes within `ANCHOR_END_MM` of an anchor `B` and the heading turns by at least 25 degrees (the tail's `bend` and the chord agree) or the pen pauses 150 ms, `B` is a waypoint: the leg `A` to `B` ends as a segment (`onStrokeSegmentEnd`, chained undo as in 10.3) and the next leg starts at `B`. A chain `A, B, C` over several surfaces is one gesture and one undo step, as a split stroke is.

### 20.5 Bridging through empty space

A path that misses every surface between its anchors is **not cancelled or refused** (this replaces the "mid-stroke miss ends the stroke" case of 9.3 when a start anchor exists). With a start anchor `A` and a predicted end anchor `B` it is an **anchor-to-anchor stroke**. The stroke is a bridge when at most `BRIDGE_HIT_FRAC` (0.2) of the path's samples hit any candidate surface away from the two anchors; otherwise the path mostly runs on surfaces and normal attribution and splitting (8.3) apply, with only the endpoints pinned. When both anchors belong to one common candidate surface `S` that is not edge-on (an edge of `S`, or a diagonal), the stroke goes on `S` (through its duplicate if `S` is locked, 5.1) and no plane is made: this is edge snapping, a stroke along the edge between two vertices of one surface.

**Inferring the plane.** The plane contains the line `AB` and is the one nearest to camera-facing that this allows:

~~~
u  = (B - A) / |B - A|                 direction of the ridge, world space
v  = unit view direction (camera forward, the stroke's frozen camera)
n' = v - (v . u) u                     component of v perpendicular to AB
s  = |n'| = sin(angle between AB and the view axis)
n  = -n' / s                           unit normal, facing the camera
plane: through A with normal n         contains B, since n . u = 0
~~~

Among all planes through `AB` the normal is any unit vector perpendicular to `u`; `n . v` is largest in magnitude exactly at `n = n'/|n'|`, with value `s`, so this plane faces the camera as much as the constraint allows. If `AB` is perpendicular to the view axis, `s = 1` and the plane is exactly camera-facing; as `AB` turns into the screen, `s` falls and the plane tilts about `AB`. Both anchors stay exactly on the plane, and on screen the line from `A` to `B` is where the user drew it.

**Degenerate case.** `s < EDGE_ON_COS` (0.17, section 5; `AB` within about 10 degrees of the view axis) leaves a plane that is edge-on to the screen, and `AB` projects to a nearly empty segment. Rule: **no bridge**. The stroke is not refused: it stays a normal smart stroke on the plane it was drawn on (R1's fallback plane, section 9), with no end snap, one `GraffuxStroke` line (`bridge refused=axis`) and no notice. Alternative for the owner: use the plane through `AB` and the camera up vector (normal `u x up'`, `up'` the up vector with its `u` component removed), which is stable but turns the plane to a side view.

**Where the line is stored: a new plane layer, by the existing machinery.** The same operation as the fallback plane: `createPlaneLayer(below, pose)` with the pose set to this plane, recorded as a `PlaneSwap(PlanePose(), pose, made)` that rides the stroke's one undo entry, exactly as `adoptDrawDepthPlane` does for a draw-plane depth (the new layer sits outside any group the anchors belong to, directly above the topmost owner). The pose puts the layer origin at the midpoint of `AB` with its x axis along `u` (signed to point right on screen) and its normal `n`; it is converted to the pose's rotations by the same convention `SceneMath.chainModel` reads. The layer grows to the view as any plane layer does (`growLayerToView`).

**The first dab does not wait.** Before the bridge is known the stroke paints live on the plane it began on (the touch-down pick, or R1's camera-facing fallback at `A`'s depth); nothing is committed. When the path leaves all surfaces inside the provisional window, the stroke is re-homed (8.2) onto a provisional camera-facing bridge layer through `A`, created at the lock and removed by the same cancel as any fallback plane. At pen-up, with `B` known, the pending plane pose is replaced by the final plane above and the stroke is replayed onto it, as a re-home replays its buffered samples, before the commit; nothing committed is moved because nothing is committed before pen-up. The commit is one entry with the created layer (10.3). A stroke with no end anchor keeps the provisional plane. In the prism ridge scene (`s = 1` for a camera facing the ridge side-on) the provisional and final planes are the same.

**Straight or pinned.** Decision with a recommended default: when the hand path stays within a band of `STRAIGHT_BAND_MM` (5) of the chord `AB` (screen distance), the stroke is **straightened to the exact segment `AB`**; otherwise it **keeps its shape, pinned at both anchors**:

~~~
straight:  p'(t) = A + t (B - A),  t = arc-length fraction of the hand path in [0, 1]
pinned:    p'(t) = p(t) + (1 - t) (A - p(0)) + t (B - p(1))
~~~

Both give endpoints exactly `A` and `B`; the pinned form preserves the hand's curve and spreads the endpoint correction along the stroke. Samples keep their pressure and timing by arc-length fraction. The stroke program replays the new samples with the same seed (a pure function of the samples, 8.2). Mapping onto the bridge plane is the ordinary one (the plane contains `A` and `B`, so their projections are the screen points the user sees).

**Brush width and thickness.** Width is in the plane layer's own units, so a ridge whose ends are at different depths tapers in perspective, as paint on any plane does. A volumetric brush writes thickness on the bridge plane's channel and axis (10.1): per plane, no continuity across a chain's legs. The new plane is oblique to the view only as far as `s < 1`.

### 20.6 Where it plugs in

| Concern | Place | Change |
|---|---|---|
| Anchor set and end ranking | `common/scene/StrokeAttribution.kt` (pure Kotlin) | `Anchor`, `Heading`, `anchorsOf(candidate)`, `rankEnd(heading, anchors)`, `bridgePlane(A, B, view)`, `pinPath` (straight or pinned). No Android types. |
| The predictor's output | `editor/prediction/GesturePrediction.kt`; `DrawingCanvas.kt` (`recordRealPoint`); `EditorScreen.kt` | `PredictionTail.heading(...)` and `StrokeHeadingSample`; a new `onStrokeHeading` callback on `DrawingCanvas`, forwarded to `AttributedStrokeFeed`. No change to the models, the tail or the TEMPORARY rankings. |
| Start anchor, waypoints, pen-up replay | `AttributedStrokeFeed` (`editor/EditorScreen.kt`) | Holds the start anchor and raw samples; at pen-up replays the snapped samples. |
| Bridge layer and pose | `EditorViewModel`, beside `adoptDrawDepthPlane`/`createPlaneLayer` | Creates the layer at the lock and swaps its pose at pen-up through `PlaneSwap`; no `EditHistory` change. |
| Ring cue | `SnapPlaneHighlight` pattern | A ring on the predicted anchor while it is predicted. |

### 20.7 Tests

Pure Kotlin in `core:common` unless noted, table-driven, with recorded paths (screen points with times) and fake scenes. Tolerances are named constants; `EPS_WORLD` = 1e-4 world units and `EPS_SCREEN` = 0.01 px.

- `PrismRidgeScenarioTest` (the owner's scenario). Scene: `R0` the base rectangle on the plane `y = 0`, `x` from 0 to 4, `z` from -1 to 1; `TA` at `x = 0` with vertices `(0,0,-1)`, `(0,0,1)`, `A = (0,2,0)`; `TB` at `x = 4` with `(4,0,-1)`, `(4,0,1)`, `B = (4,2,0)`; the two other rectangles and their planes are absent, so the ridge is empty space. Camera: side-on to the ridge (`AB` perpendicular to the view axis). Recorded path: timed samples (position, `uptimeMillis`, pressure) that start 2.5 mm from the projection of `A`, wobble within 2 mm of the chord, and lift 3 mm from the projection of `B`, with grid off, fed through a real `PredictionTournament` (linear model, `includeGoogleInk = false`, as `GesturePredictionTest` does on JVM) and the new `heading()` adapter, and once with a scripted fake `GesturePredictor` for a deterministic tail. Expected: start anchor `A`, predicted end `B`, a bridge (`hit fraction` below 0.2), the plane normal equal to `-v` within `EPS_WORLD`, the committed stroke a straight segment whose first and last points are `A` and `B` within `EPS_WORLD`, on a new plane layer (one `PlaneSwap.created`), one undo step that removes the stroke and the layer, and the locked/unlocked state of `TA` and `TB` irrelevant. Variants: camera turned so `AB` makes 40 degrees with the view axis (normal from the formula, `s = sin 40`, both anchors on the plane within `EPS_WORLD`); wobble of 8 mm (pinned spline, endpoints exact, middle keeps the hand's shape); the same scene with `TA` locked (the anchor still snaps; the bridge layer is new, no duplicate needed).
- `AnchorSnapTest`: start inside and outside `ANCHOR_START_MM` (hysteresis at 1.5 times); an anchor behind a nearer opaque surface does not snap; owner `h = 1` at an outline corner; a hull's profile vertices; capped sets.
- `EndPredictionTest`: given scripted `Heading` values, the continuation, chord and length fits rank the true target first; a null tail (`confidence` 0) falls back to the chord with renormalised weights; the cone widens with `bend` and distance; a stylus and a finger differ only in the tolerances; a path that stops 15 mm short of `B` gives no end snap; **ambiguous**: two anchors symmetric about the heading, the tie order of 20.4 gives one answer, reproducibly; **starts at an apex but heads elsewhere**: no end anchor, only the start pin; a scribble near `A` predicts nothing; overshoot beyond `B` by more than 15 percent lowers `B`'s score.
- `BridgePlaneTest`: `n . u = 0` and `n . v` of magnitude `s` for random `A`, `B`, `v` (property test); `s` below `EDGE_ON_COS` gives no bridge and a normal stroke; `A = B` rejected; both anchors on the plane within `EPS_WORLD`.
- `AnchorChainTest`: waypoint at a turn of 25 degrees or more; legs are chained segments, one undo step; edge snapping: two vertices of one surface go on that surface, no new plane.
- `HeadingTest` (`feature:editor`, JVM): `PredictionTail.heading` gives the last-segment tangent, null under `HEADING_MIN_PX`, confidence 0.6 for linear and 1 for Ink, 0 with no tail; `onStrokeHeading` fires once per real sample with the tail the tournament returned.
- `feature:editor`: `AnchorStrokeUndoTest` (the created bridge layer and the stroke are one step; a cancelled stroke removes the layer; a second finger rolls back all legs) and a no-regression run of `GridGatingTest` (grid on: no anchors, no calls).

Acceptance numbers, all proposed and unmeasured until a corpus exists:

| Measure | Target |
|---|---|
| Prism ridge and the table scenarios | 100% |
| Endpoint error of a bridged stroke | within `EPS_WORLD` and `EPS_SCREEN` |
| Anchor-to-anchor strokes that snap to the anchor the owner meant, per recorded session | at least 95% |
| False end snaps (user undid and redrew without the snap) | at most 2% of anchored strokes |
| Start/end anchor cost per step, with the tournament's `costLine()` included | within the budgets of sections 4 and 12 |

### 20.8 Interactions

- **Locked layers.** Anchors on a locked surface still snap. A bridge goes on its own new layer, so nothing locked is touched and no duplicate is needed; for an edge or diagonal stroke on a locked surface, the stroke goes through the automatic duplicate of 5.1.
- **Undo, co-op, format.** The bridge layer is a `LayerAdd` (co-op) and rides the stroke's entry (`PlaneSwap.created`); the pinned or straightened samples are what is committed, and peers receive an ordinary `StrokeComplete`. Anchors, predictions and the ring are not carried.
- **Grid on.** No anchors, no prediction, R1 verbatim (as `GridGatingTest`).
- **Selection (decision 10).** The bridge layer becomes the selected layer at the lock; after a chain, the last leg's layer.

### 20.9 Phasing and risks

- Phase 1 adds only the start snap's `h = 1` rule and the start pin; phase 2 adds end prediction, bridging, the plane pose swap at pen-up, waypoints and the ring; phase 3 adds edge anchors (`EDGE`: a point snapped along an edge, own tolerance) and tuning from the corpus.
- Risks: the predictor's tail is 17 to 33 ms long, so it gives a heading, not a far position; a stylus or another refresh rate changes the reach (the ranking uses the tangent, so it should tolerate that; unmeasured); the TEMPORARY ranking apparatus costs per sample and is not to be depended on; a wrong end snap costs one undo, bounded by the margin and the 2% target; replaying at pen-up could show a visible jump when the straightened line differs from the live one (bounded by the 5 mm band, device check); `MAX_ANCHORS` with many surfaces hides far anchors; a tilted bridge plane (`s` near 0.17) paints a thin foreshortened stroke.

### 20.10 Constants

All proposed defaults, all unmeasured, all named in `StrokeAttribution`: `MAX_ANCHORS` 64; `ANCHOR_CORNER_DEG` 20; `ANCHOR_START_MM` 4 (stylus 3); `ANCHOR_END_MM` 7 (stylus 5); `SNAP_BLEND_MM` 12; `CONE_DEG` 8; `ANCHOR_F_MIN` 0.55; `ANCHOR_TIE_EPS` 0.05; `ANCHOR_DWELL_STEPS` 3; `ANCHOR_MIN_TRAVEL_MM` 10; `ANCHOR_END_STEPS` 3; `STRAIGHT_BAND_MM` 5; `BRIDGE_HIT_FRAC` 0.2; `HEADING_MIN_PX` 3.
