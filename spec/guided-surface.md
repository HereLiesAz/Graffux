# Guided surface drawing: front, side and top, one step at a time

Status: design only. Nothing here is implemented. It changes no undo or history code. It does change the surface
model, in one small way (section 6a, "Any view first"), which needs one project-format bump to the next free number
(15 is the puppetry rig's and 16 the thickness channel's, both design only, so 17 if both land first; a project
that never draws a side or top first stays byte-identical in meaning and loads and renders unchanged). It builds on
`spec/3d-space.md` ("Group surfaces", "The default surface", "Outline assist", "Drawing the other views", "Ready-made
surfaces", "Adopting the view plane", "Draw plane depth"); that file links back here from "Guided surface drawing".
Where this file and 3d-space.md disagree about the guided flow or about which surface tools are offered, this file
wins; everything else in 3d-space.md stands.

Two owner rules frame it: the guided front/side/top walk-through is for an **empty** project only; on a project with
content a surface is the plane where the strokes are, found by the nearest-edge rule already on main (#882:
`NearestEdge`, `hasPlaneContent`, `adoptViewPlaneForStroke`), and the profile-drawing tools are not offered there.


The Android app only. The desktop app does not use `:core:common` or `:feature:editor`, has no 3D and no surfaces,
and gets nothing (see "Platforms").

## 1. What it is

Today "Draw surface" arms one stroke; the side and top views are separate, later, optional arms from a chooser.
The guided flow is a walk through all three views, in a fixed order, on a project that has nothing in it yet.
It does not draw anything new: each step reuses the existing capture (`SurfaceMath.capture`), the existing
outline assist (`OutlineAssist`), the existing merge (`SurfaceProfile.withView`) and the existing hull
(`SurfaceHull`, creases included). It adds a step sequence, a place to hold drawings until the user confirms, and
the words.

## 2. When it is offered (rule 1)

The guide is offered only when both hold:

1. **3D is available.** There is no 3D mode and no user setting (see 3d-space.md, "What the user sees"), so this
   is a capability test, `SurfaceGuide.available`: the Android editor, with a usable canvas size (`canvasSize`
   positive and finite, the existing `UNAVAILABLE` test) and the depth path that surfaces already need. Today it is
   true wherever the editor runs. It is one named predicate so a future 3D setting is one more clause.
2. **Every layer in the project is empty** (`SurfaceGuide.projectIsEmpty(layers)`, pure, over the whole stack
   including hidden, locked and nested layers). The content rule is the one the rest of the surface code already
   uses, `Layer.isEmptyPaintLayer` ("Adopting the view plane": no source image, text, shapes, adjustment or 3D
   ink, no recorded stroke, no non-transparent pixel in the bitmap; `ArtScan` is the one alpha test). The
   NearestEdge change (PR #882) is not on main at the time of writing; if it lands first with its own content
   rule, this file adopts that one verbatim and the two never differ. Applied to a stack:
   - a **plane layer** counts as content unless `isEmptyPaintLayer` is true. So an imported image, text, a shape,
     a stroke (even one later erased to nothing: a recorded stroke is content, as the rule says), a 3D strokes
     layer, a model layer, an adjustment layer and a layer with any non-zero alpha pixel all count;
   - a **group or frame** is not content in itself: it is empty exactly when every layer under it is empty (an
     empty group, a group of empty layers, and a group that already has a surface profile but no art all count
     as empty). A relief group holds the photo, so it is content;
   - a **view image** (the hidden per-view paint images of a hull) is a paint layer: empty or not by the same
     rule;
   - a layer with a pose (offset, scale, tilt, depth) but no art is empty: pose is not content;
   - a project with no layers at all is empty, but see "Target" below (nothing to draw on, so the entry is
     disabled with its own reason).
   The scan is the existing off-main-thread `ArtScan` with the existing stale-answer guard (it applies only if
   layers, canvas size and arming are unchanged when it answers). While it runs, or if a bitmap cannot be read
   just now (the existing `NOT_READABLE`), the guide is not offered and says so (the "checking" and "unreadable"
   reasons below). A cannot-tell answer never counts as empty.

The rule is re-evaluated whenever the layer list changes. If content appears while the guide is open (a co-op peer
adds a layer, say), the guide closes as a Cancel (nothing changed) and the chip says why.

### What the user sees when it is not offered

The one guided entry is shown **disabled, with the reason as its TalkBack state description and as a line in the
surface chip** (a chip stays until dismissed, as the armed chip does; no toast). It is the "Guided surface" row in
the layer menu. The reasons:

- not empty: `surface_guide_unavailable_content` "Guided surface drawing is for an empty project. In a project with
  artwork, draw on a layer and it becomes a plane where you draw; move or turn it with the Move tool."
- no layer: `surface_guide_unavailable_no_layer` "Add a layer first, then draw its surface."
- locked layer: the existing `LOCKED` wording.
- checking: `surface_guide_unavailable_checking` "Checking the project. Try again in a moment."
- unreadable: the existing `NOT_READABLE` wording.
- 3D unavailable: the existing `UNAVAILABLE` wording.

Colour is never the only cue: the disabled row also carries the words.

### What happens to each existing surface tool

On a **non-empty** project a surface is the plane that already holds the strokes. A paint plane is already a flat
sheet at its own pose; it is placed by view-plane adoption and the nearest-edge rule ("Adopting the view plane",
"Draw plane depth"), and moved, turned and re-depthed by the planes' own tools. So the tools that draw or size a
profile for a plane are not offered there. Nothing is migrated: groups that already carry a surface, drawn,
flat, art-sized, ready-made or relief, load, render, paint, export and undo exactly as now.

| Existing tool or entry | Empty project | Non-empty project |
|---|---|---|
| "Draw surface" in the group layer menu (`onArmSurfaceDraw`) | **absorbed**: starts the guide | **removed** (no entry) |
| 3D axis slot tap on a 2D layer (arms Draw surface) | **absorbed**: starts the guide | **removed**: the slot is not shown on a 2D layer in a non-empty project (a plane is made by drawing, not armed) |
| Surface drawings chooser (slot on a surface group's layer; "Draw / Redraw side view", "... top view") | **absorbed**: each entry opens the guide at that step | **removed**: the slot is not shown on a surface group's layers; the group stays editable through its planes (below) |
| "Use a flat sheet" on the arming chip and the art-sized sheet (`onSkipSurfaceDraw`, `ArtSheet`, `HAS_ART`) | **absorbed**: "Use a flat sheet" is a choice on step 1 (the predefined sheet) | **removed**: a plane with content is its own flat sheet at its pose; the art-sized sheet has no entry |
| "Insert surface" (3D toolbox, `SurfacePicker`, `onInsertSurface`) | **kept**, offered only here (it makes a new group with no art, so the project stays empty and the guide can then edit its views) | **removed** (hidden); installed packs keep loading and `AssetType.SURFACE` stays supported |
| Relief from a photo ("Build surface from image") | not applicable (needs a photo, so the project is not empty) | unchanged: it is the photo's own action, not a profile tool; the structure of the in-flight relief PR (the duplicated photo as the first layer of the depthed surface, the original tied to a hidden flat surface) is its own and this file does not touch it |
| "Flatten surface", delete, hide, group opacity | unchanged | unchanged |

The implementation PR deletes what no entry reaches any more (the art-sized sheet and its notices, the chooser
state `surfaceViewsMenuOpen`, the Redraw menu items), keeping the pure maths and capture code the guide reuses.

**What stays reachable for a plane or surface that has content (no profile redraw).** Editing is done on the
planes themselves, all existing: the Move / Transform tool on a 3D layer (slide, depth by pinch, turn about the
axis), the rotation triangle and view steps, the "#" button's draw-plane-depth menu, "Snap to stroked planes",
`resetActivePlane`, tap-select of tilted layers, and layer delete, hide and order. A surface group's shape is not
redrawn once content exists. To change it, flatten the surface (the group's layers become plain planes again) or
undo; a surface that is wrong before any content exists is fixed in the guide, which is offered then.

The guide itself is never offered once content exists, so rule 1 holds without exception: nothing profile-drawing
is reachable on a non-empty project.

## 3. The three steps (rule 2)

The order is fixed and the same for every user: **1 Front, 2 Side, 3 Top.** The steps map to the existing
`SurfaceView` values and their cameras (`SurfaceView.camera`), which is what each step shows:

| Step | `SurfaceView` | The camera looks along | The canvas shows | Outline is swept along | Camera used |
|---|---|---|---|---|---|
| 1 Front | `FRONT` | Z (into the page) | X across, Y down | Z | `yaw 0, pitch 0` |
| 2 Side | `SIDE` | X (from the right) | Z across (depth), Y down | X | `yaw 90 degrees, pitch 0` |
| 3 Top | `TOP` | Y (down from above) | X across, Z down the screen (depth) | Y | `yaw 0, pitch` at the camera's limit |

Axes are the group's box-local axes of 3d-space.md: x right, y down, z away from the viewer at the front view.
Each step's prompt names both what it shows and what it looks along, so "X, Y and Z" are never bare letters: for
example "Step 2 of 3, side view. You are looking along the X axis. Draw the shape as seen from the side: up and
down is Y, left and right is depth (Z)."

The owner's X/Y/Z wording does not say whether a step is named by the axis it looks along or by the axes it shows;
the guide names both on every step and the order is front, side, top (Z-axis view, X-axis view, Y-axis view).

**Target.** The guide works on exactly one group. If a group is active (or the active layer is in one), that
group. Otherwise the active layer is the target and is wrapped in a new group on the first committed step,
exactly as "Draw surface" wraps a layer today (`commitWrappedSurface`; an empty layer, so the default sheet rules
of 3d-space.md "Empty layers and groups" apply to the flat sheet). With no layers the guide is not offered. An
empty group that already has a surface is a valid target: its existing views are shown as existing (step state
below).

## 4. Each step: create, edit, skip (rule 3)

Every step is always offered, in every state. A step is in exactly one of three states, shown in words, never
auto-resolved:

| State | The step has | Primary | Also offered |
|---|---|---|---|
| Not drawn | no profile for this view | **Draw** | **Skip**; on step 1 also **Use a flat sheet** |
| Drawn (existing) | a profile already on the group | **Edit** | **Keep** (leaves it as it is) |
| Drawn (this session) | a draft the user captured in this guide | **Redraw** | **Keep** |

The surface needs views in a count, not in an order: the **first** view drawn may be any of front, side or top; a
**second** needs at least one already drawn (existing or drafted); a **third** needs two. In the guided order (front,
side, top) that is always satisfied, because a view is only ever drawn after the earlier steps have been offered.
There is therefore no "Not available" state. A group with no front can have a side, or a top, or both, as its
views; the first one drawn becomes the group's base (section 6a).

Rules:

- Nothing is created or changed implicitly. Arriving on a step does not arm drawing, does not capture, does not
  pre-fill and does not complete the step. Capture begins only when the user chooses Draw, Edit or Redraw.
- Nothing is auto-skipped. A step is still reached and the user still presses Skip, Keep or Next.
- "Skip" on a not-drawn step and "Keep" on a drawn one both mean: this step is left exactly as it is. They are
  one outcome, `Left as is`, with the wording that fits.
- **Edit** means redraw this view's outline: the current outline is shown as a guide line under the stroke, and the
  new stroke replaces it on Done. There is no point-editing of an existing outline.
- **Use a flat sheet** (step 1, only for a group with no views yet) is the existing default, offered as one of the
  choices, not taken for the user: it stages the sheet of 3d-space.md "The default surface" (the predefined 3/4 by
  2/3 sheet, `DefaultSurface.profile`, with `reach`) as the front draft. The project is empty, so the art-sized
  variant and its `HAS_ART` refusals never arise. Views drawn after it make a hull and `reach` is then unused.

## 5. The guidance UI (rule 4)

One panel, `SurfaceGuidePanel`, in the app's message-card chrome (the same container as `SurfacePromptChip`:
`RailFill`, 12dp corners, `widthIn(max = 420.dp)`), at the top of the canvas, non-modal so the canvas is live for
the stroke. It replaces `SurfacePromptChip` while the guide is open.

**Content, top to bottom:**

1. **Step indicator** as text and as a row of three segments: "Step 2 of 3: Side view". The segments carry the
   state in a symbol and a word (drawn / left as is / this step), not colour alone. It is one TalkBack node:
   "Step 2 of 3, side view. Front: drawn. Side: not drawn. Top: not drawn."
2. **Prompt**, per step and per state, all string resources (below), never built from parts in code.
3. **Preview** (see 6): the captured outline on the canvas and a small hull thumbnail.
4. **Step actions** (the state's choices, section 4), then **navigation**.

**Navigation:** `Back`, `Next`, `Done`, `Cancel`.

- **Back** goes to the previous step without changing or discarding anything (drafts are kept). Disabled with its
  reason on step 1 ("This is the first step").
- **Next** goes to the next step. Next never completes or skips the step it leaves: leaving a step that has
  nothing drawn this session is not a choice for it, it stays `Not drawn`. Next is disabled on step 3 (Done takes
  its place).
- **Done** is available on every step, because every step can be skipped; it ends the guide and commits (6). If
  no draft exists, Done changes nothing and says "Nothing was drawn" (it is a Cancel with a different word, and
  the chip says so).
- **Cancel** abandons the whole flow, discarding every draft; the document is exactly as before the guide opened
  (same layers, surface, camera, view). The Back key is Cancel while a stroke is not in progress; while one is,
  it cancels the stroke first. Cancel with drafts asks once: "Discard your drawings?" with Discard and Keep
  drawing (48dp each). Cancel with no drafts does not ask.

**Accessibility:**

- Every control is at least 48dp square (the `SurfacePromptChip` button rule), with a visible label (no icon-only
  controls) and a `Role.Button` with a `contentDescription` that repeats the step ("Skip side view").
- The panel is one live region (`LiveRegionMode.Polite`, as the chip is) so a step change is announced once:
  "Step 3 of 3, top view".
- **No drag-only path.** Drawing a view is a drag, as drawing a profile always has been, so every other part is a
  tap. For a user who cannot drag, Draw offers **Use a preset outline** (below) as an equivalent choice, and
  Skip, Keep and Use a flat sheet need no drag. The camera is moved by the step, not by the user, so no
  two-finger gesture is needed.
- **Preset outlines (non-drag alternative, also useful to everyone).** In each Draw/Edit state a **Shapes** button
  opens a short list (rectangle, ellipse, triangle, and, for steps 2 and 3, "Same as front") that stages a draft
  outline from the pure `OutlineAssist` shape output sized to the view's current frame (the same unit-box
  placement as "Ready-made surfaces"). It stages a draft only; the user still confirms with Done.
- Reduced motion: the camera turns to a step's view by setting the camera (as `onArmSurfaceView` does), not by an
  animation, unless the app's existing camera-move animation setting is on.
- **Chrome hidden during strokes.** While a stroke is down the rails, panel and chip are hidden, as for every other
  stroke (the layers rail is hidden while a stroke is down, 3d-space.md open point 5), and return when it lifts. A
  stroke that lifts as a tap (under 4 px) changes nothing and the panel stays, saying so (the existing
  `TOO_SHORT`).

**Strings (all in `ChromeStrings` / `SurfaceStrings`, English first, other locales per the project rule, none
hard-coded):** `surface_guide_title` "Draw this surface in three views"; `surface_guide_step_indicator` "Step %1$d
of 3: %2$s"; `surface_guide_front_prompt`, `_side_prompt`, `_top_prompt` (each with a Draw, an Edit and a Not
available variant, e.g. `surface_guide_side_prompt_draw`, `_edit`, `_unavailable`); `surface_guide_draw`,
`_edit`, `_redraw`, `_skip`, `_keep`, `_flat_sheet`, `_shapes`, `_back`, `_next`, `_done`, `_cancel`,
`_discard_confirm`, `_discard`, `_keep_drawing`; the unavailable reasons in section 2; and the state words
`surface_guide_state_drawn`, `_not_drawn`, `_left_as_is`, `_this_step`. The prompts follow the 3d-space.md chip
wording: plain, one sentence of what to do, one of what it shows, "Your artwork is not changed."

## 6. Capture, preview, commit

**Capture.** Choosing Draw, Edit or Redraw sets the camera to the step's view (`SurfaceView.camera`, keeping the
first camera in `surfaceReturnCamera` exactly as `onArmSurfaceView` does) and arms one stroke on the capture
surface (the same surface "Draw surface" uses, over every tool, painting nothing). The stroke is cast, simplified
and made a profile by the existing `SurfaceMath.capture` with the existing chain (`planeChain`), with
the first-pose rebase for the first view drawn on a posed group (it is the base view, whichever view that is). A stroke below the minimum length is
`TOO_SHORT` and the step stays armed. When the finger lifts, outline assist runs once if the setting is on
(`surfaceOutlineAssist`), and its result **replaces** the raw stroke in the draft (the guide commits one cleaned
outline per step; see Undo). The step then leaves the armed state and shows `Drawn (this session)`.

**Draft.** The drawn outlines live in guide state, not in the document: `EditorUiState.surfaceGuide:
SurfaceGuideState?` (null when closed; in-memory only, never saved, never sent to co-op, not in history). It holds
the target (a group id, or the layer to wrap), the current step, and one `SurfaceProfile?` draft per view plus the
view's `Left as is` / `Drawn` mark. While it is non-null `surfaceDrawArmed` is true only during a step's stroke,
so the existing capture code runs unchanged and `onSurfaceProfileDrawn` routes the result to the draft instead of
`setSurface` when a guide is open.

**Preview.** Nothing is committed to the layers, so the document, the saved file, the co-op peers and the undo
stack do not see a draft. The preview is:

- the captured outline drawn as a line over the canvas in its own view, with the existing outline of the view
  (Edit) as a lighter guide line under it; and
- a hull thumbnail in the panel: the three drafts and existing profiles merged by `SurfaceProfile.withView` in a
  pure copy and meshed by `SurfaceHull` (the same code that renders a hull), shown from a fixed three-quarter
  camera. The thumbnail also has the words ("Front drawn. Side from this session. Top left as is.") for
  TalkBack. A shape that does not close, or whose combination has no solid, is shown with the same wall rule as
  a committed one (an open line is a thin wall).

**Commit (Done).** In order, front, side, top, for each view whose mark is `Drawn`:

1. the first drawn view (the base: the earliest of front, side, top that is `Drawn`, or the group's existing base)
   through the existing first-surface commit (`commitWrappedSurface` for a layer to wrap, else `setSurface` with the
   rebase for a posed group), which makes the wrap and the profile one history entry;
2. each later drawn view through `setSurface(groupId, current.withView(view, draft))`, one history entry each
   (`pushHistory` is inside `setSurface`, so nothing about history is touched).

The commit runs as one synchronous pass on the view-model's main path (the steps do not interleave with a
user gesture or another commit), the camera returns to `surfaceReturnCamera`, the guide closes, and the "surface
set" chip (`DONE`) says what was made, with the existing "Front: done. Side: not drawn yet. Top: ..." status.
`Left as is` views change nothing.

A view left `Drawn` but identical to what the group already has changes nothing and adds no history entry.

**Combining into one surface (the hull).** The three outlines are not blended; each is the silhouette of the solid
as seen along its axis, and the surface is their intersection: the front outline swept along Z, the side along X,
the top along Y (whichever of them exist), carved as `SurfaceHull` already does, with creases where faces of two views meet and the hull's
existing crease handling for paint and fills. Closed outlines make solids, open lines thin walls; an outline that
does not agree with the others (a side taller than the front) is not rejected: the intersection is whatever the
three produce, exactly as drawing them one at a time today.

**Partial completion** (all legal; nothing is completed for the user). Any subset of the three views is a surface:

| Drawn views | Result |
|---|---|
| front only | the front outline swept along Z (today's "Draw surface"); flat sheet if "Use a flat sheet" |
| side only | the side outline swept along X, one-sided from its plane, as a front is along Z |
| top only | the top outline swept along Y, likewise |
| any two | the hull of two prisms |
| all three | the hull of three prisms |
| existing view edited, others left | that view replaced, the others as they were |
| none | nothing is created; Done changes nothing |

## 6a. Any view first: how the surface model changes

Today the model is front-first. `SurfaceProfile` IS the front outline (its own `points`, swept along its `sweep`),
and `side` and `top` are optional nested outlines on it; there is no slot for a front when the root is something
else. The guide needs any view to be the first one drawn. The change is small and additive.

**The base view.** The root profile is the **base**: the first view drawn. Its points and `sweep` are exactly what a
capture of that view already produces (`SurfaceMath.capture` stores the view direction as the sweep, so a side
outline's sweep is already +X and a top outline's +Y; nothing new is needed to represent a side-only or top-only
curve). Two new optional fields on `SurfaceProfile`:

~~~kotlin
val base: SurfaceView? = null   // which view the root profile is; null means FRONT (every existing project)
val front: SurfaceProfile? = null // the front outline when the base is not the front; unused when base is FRONT
~~~

`side` and `top` keep their meaning for a base that is the front. For another base the slots are the **other two
views**, by name: base SIDE uses `front` and `top`; base TOP uses `front` and `side`; the slot for the base's own
view is always null (it is the root). `SurfaceProfile.baseView` is `base ?: FRONT`. `view(v)` returns the root
(stripped of its slots) when `v == baseView`, else the matching slot. `withView(v, drawn)` replaces the root's
points when `v == baseView`, else sets the matching slot; it never changes `baseView`. The base is set once, when
the first view is committed, and is not changed by later drawing (so the first drawn view stays the root and no
view is ever moved between a slot and the root). `hasHull` becomes "two or more views are drawn" (`viewCount > 1`),
not `side != null || top != null`.

**Format.** Both fields are optional with the front-first defaults, written only when set. A project in which every
surface is front-based serialises as before. One bump (to the next free number, section header) is needed
because an older build would otherwise read a side-first group's root as a front and silently draw it in the wrong
plane, so an older build sees the newer format and opens the project read-only, as it does for every earlier
optional field. No existing project is migrated or rewritten.

**What each subset constrains, and what the missing axis does.** A view's outline is a silhouette in the two axes
it shows; the third axis, the one it looks along, is unconstrained by that view alone.

| Views | Constrained axes | The missing axis |
|---|---|---|
| front only | X, Y | Z: swept one-sided from the profile plane, without bound (or to `reach` for a flat sheet), as today |
| side only | Z, Y | X: swept one-sided from the profile plane along +X, without bound |
| top only | X, Z | Y: swept one-sided along +Y, without bound |
| two views | the union of their axes (all three, each axis possibly by two views) | none: the hull is the intersection of the two prisms, clipped to the box of the drawn points (`SurfaceHull.of`'s padded box) |
| three views | all three, each by two views | none: the intersection of the three prisms |

The one-sided sweep for a single view is the existing sheet rule applied to the view's own sweep vector, so a
single side or top is exactly as unbounded and as one-sided as a single front. As soon as a second view exists the
surface is a hull: the unconstrained axis now takes its extent from the other view's points, the box being the
padded bounds of everything drawn, as `SurfaceHull.of` computes it. A view drawn first is the base only for
storage; the hull itself does not care which view is the root, because `SurfaceHull.of` already builds one prism
per drawn outline from that outline's own sweep and takes their intersection.

**Crease and hull combination for any subset.** Creases are where faces of two prisms meet, and caps are the
faces of the box. `SurfaceHull.of`, `meshByView`, `capMeshByView` and `creaseLinks` are built from prisms tagged
with their view, not from "the front plus extras", so any 1-, 2- or 3-view subset already combines; the work is the
tagging (the root prism is tagged `baseView`, not hard-coded FRONT) and the open-outline rule ("at most one open
outline is kept as the wall, the first of front, side, top"), which stays in that fixed order whatever the base is.
A single closed outline of any view is a prism through the padded box both ways, as a lone front is today; a single
open one is a thin wall.

**Code paths that assume a front-first model** (the implementation PR must change each; names are current main):

- `SurfaceProfile` (`:core:common`): the root-is-front documentation and `view()`, `withView()`, `hasHull`,
  `sweepLimit`, `isFlat`, the `@Serializable` shape and its kdoc ("Only read on a group's own profile").
- `SurfaceHull.of`: labels the root `SurfaceView.FRONT`; must label it `baseView` and read the `front` slot.
  `SurfaceHull.meshByView`, `capMeshByView`, `creaseLinks`, `HullHit.view`, `SurfaceHullMesh.textured` and the
  per-view images (`SurfaceViewImages`, `carryPaintOntoFront`, `DepthScene.hullPlanes`): the "front image", and the
  carrying of the sheet's paint onto it when a second view first appears, become the base view's image.
- `SurfaceView.camera` and `EditorViewModel.onArmSurfaceView`: `view == FRONT` is routed to `onArmSurfaceDraw`, and
  the group must "already have its surface" (`surface?.isValid == true`); both go. Arming any view works with or
  without a surface.
- `EditorViewModel.onSurfaceProfileDrawn`: `if (view != FRONT && existing == null) disarm` drops a side or top drawn
  first; the rebase test `first = newGroup == null && view == FRONT` is "no surface yet", not "front"; and
  `existing?.withView(view, profile) ?: profile` must make the new profile the root with `base = view`.
- `EditorViewModel.onSkipSurfaceDraw` and the `FlatSheet` outcomes: `surfaceDrawView != FRONT -> return` and the
  front-only checks. The flat sheet stays a front-based sheet (a base of FRONT), offered only on step 1.
- `surfaceReturnCamera`: set today only when a non-front view is armed (`onArmSurfaceView`), while arming the front
  leaves the camera as it is. The guide turns the camera for every step, front included, so the first camera is
  stored on the first armed step and restored by `disarmedSurface()` on every exit; front-step camera handling
  becomes the same as the others.
- `SurfacePromptChip` and `surfacePromptContent` (`:app`): `SurfaceViewsStatus`, `onDrawView` offered only after
  "set", `onSkip` only for a group's first front, and the per-view titles and bodies; the guide replaces them with
  its panel, the chip keeping only notices.
- The "front is drawn first" assumptions in `SurfacePayload.toProfile` (ready-made surfaces put the front as the
  root: unchanged, base FRONT) and `NearestEdge.surfaceOutline` (uses `hasHull`, so it follows the new
  definition), `OrbitPivot`, `SurfaceMath.localBounds` and `ActivePlaneMapping.onHull` (all read `hasHull`).
- Surface drawings chooser state (`surfaceViewsMenuOpen`) and the layer menu's Redraw items: removed (section 2).

## 7. Re-entering and editing

- On an empty project the guide can be opened again at any time, on the same group: each step reads its state
  from the group's profile (`Drawn (existing)` shows Edit and Keep). Re-entry is how a user adds the side or top
  later, or fixes one. The Surface drawings chooser, where an empty project shows it, opens the guide at the view
  chosen.
- Once the project has content the guide is not offered (rule 1) and no profile redraw exists (section 2). A
  surface that exists is then edited through its planes (section 2, "What stays reachable"), which is the owner's
  rule: a surface is the plane where strokes are, not a profile redrawn over them.
- Opening the guide on a group whose paint layers are empty but whose views have image layers (a hull's hidden
  images are empty paint) is allowed; committing an edited view does what redrawing it does today (3d-space.md
  "Paint per view"). Because the project is empty, no paint is lost.

## 8. Undo and redo

No undo or history code changes. Each committed view is exactly one `pushHistory` through `setSurface` (the front
that wraps a layer is one step with its wrap, as today). So a Done that commits front, side and top is three undo
steps, one per step, redone in the same order; nothing is grouped and nothing is skipped. The guide's own state
(the open flow, the drafts, the current step) is never history: Back, Next, Skip, Keep, Cancel and Redraw of a
draft change no document and push no entry, and the camera is never history. Undo while the guide is open is the
editor's normal undo, which finds nothing from the guide to undo (the drafts are not in history) and so affects only
what was done before it opened; the panel stays open.

Outline assist's two-step commit (raw, then cleaned, 3d-space.md "Outline assist") is not used by the guide: a
guided step commits the one cleaned outline, so one undo returns to before that step. Undo therefore does not give
back the raw stroke inside the guide; the user redraws it (Redraw), which the step offers. With outline assist off
there is no difference. The single-view flows keep their two-step behaviour.

## 9. Persistence, co-op, format

One format bump, only for the new `base` and `front` fields (section 6a); a front-first surface writes neither. A
guided surface is otherwise a `SurfaceProfile` with `side`, `top` and (flat sheet) `reach`, all existing, written by
the existing save. The guide's state is not persisted: a project saved or the app
killed mid-guide reopens with the guide closed and the document as it was before Done (a draft is lost; the
panel says nothing, the user starts again). Co-op: drafts are not sent; Done's commits go out as the existing
per-step layer-property operations (`emitLayerProps`), in order. A peer that adds content during the guide closes
it as a Cancel for this user, as in section 2.

## 10. Special projects and platforms

- **2D-only project** (no group has a surface; the camera never moved): the guide is the way to make the first
  surface on an empty one; on a non-empty one it is unavailable and says why, a plane is made by drawing on it,
  and nothing about 2D rendering, gestures or export changes. A project that never uses the guide behaves exactly as before.
- **A project with no 3D group** and with layers all empty: the guide is offered on the active layer. One with
  content but no 3D group: the guide is unavailable (the content reason); drawing under an orbited
  camera makes a plane at the nearest edge of the existing planes ("Draw plane depth"), which is the way in.
- **A project whose only layers are an empty group that has a surface:** empty, so the guide is offered and
  opens in the Edit/Keep states for the views it has.
- **Desktop.** The desktop app has no 3D, no `:core:common` and no surfaces; nothing is added there, and a
  project that has a surface opened on desktop is read the way it already is. The guide is Android only.

## 11. Phases and tests

1. **Pure rule (no UI).** `SurfaceGuide.available`, `projectIsEmpty(layers)` and the reasons enum, in
   `:core:common`. Tests: every content kind counts (image, text, shapes, stroke, erased stroke, 3D ink, model,
   adjustment, one faint pixel, relief group); empty plane, empty group, nested empty groups, group with a surface
   and no art, posed empty layer, view image, hidden and locked layers; no layers; a bitmap that cannot be read
   is not empty; stale-answer guard.
2. **State and commit.** `SurfaceGuideState` (steps, marks, drafts), the reducer for Back, Next, Skip, Keep,
   Draw, Redraw, Done, Cancel, and the commit order. Tests (the existing view-model test harness): no implicit
   create or skip on entering a step; every step reachable by Next and Back with state kept; Cancel leaves layers,
   surface, camera and history byte-identical; Done with front only, front and top, side only, top only,
   side then front (front in the `front` slot, base stays side), every one-, two- and three-view subset; each committed view adds exactly one history entry and undo
   removes them one at a time; Edit replaces only its own view; unchanged redraw adds no entry; content arriving
   mid-guide cancels it; the first front on a posed group bakes the pose as today.
3. **Panel and strings.** `SurfaceGuidePanel` with the unavailable rows. Compose tests: the indicator text, the
   four step states and their buttons, 48dp minimum size on every control, the live-region announcement, the
   disabled rows' `stateDescription`, the Discard confirm, chrome hidden while a stroke is down, and no control
   reachable only by drag (the Shapes and flat-sheet paths). A lint test that every guide string resource exists
   in every locale and that no prompt is a literal.
3a. **Model (section 6a).** `SurfaceProfile` `base`/`front` round-trip and old-file load (no field written for a
   front-first surface; a file without them reads as before); `view`/`withView`/`hasHull` for every base;
   `SurfaceHull.of` for every subset (volume, creases, wall order) equal to the front-first result for the same
   outlines rotated; a side-only and a top-only surface paint, hit and render like a front-only one; an old build
   opens a side-first project read-only. Removed entries: tests that each tool in section 2's table is absent on a
   non-empty project and present as stated on an empty one; a legacy surface group still loads, renders and paints.
4. **Preview.** The hull thumbnail and the lighter existing-outline guide. Tests: the thumbnail's solid equals
   the committed hull (same meshing), and its words match the marks.
5. **Device.** Not verifiable off a device: the camera turn between steps with the real depth pass, the stroke
   capture on each view, TalkBack end to end, and Done's three commits with outline assist on a large canvas. To
   be listed as untested in the PR that builds it, as 3d-space.md does.

## 12. DECISIONS (my defaults where the owner did not say)

1. Three steps in the fixed order front, side, top, each named by what it looks along and what it shows
   (section 3). The owner's X/Y/Z is taken as the label of the axes, not an ordering of the views.
2. "3D is enabled" has no switch to read (there is no 3D mode), so it is a capability predicate that is true on
   Android today. If the owner means a new user setting, that is one added clause, not a redesign.
3. "Every layer is empty" uses `isEmptyPaintLayer` for plane layers and "all children empty" for groups; a recorded
   stroke, even erased, is content; a posed or surface-bearing but art-free layer is empty. Relief groups hold a
   photo, so they are content, hidden layers included, whatever structure the in-flight relief PR gives them.
4. Not offered means the one guided entry is disabled with a spoken and written reason; the removed tools below
   are not shown at all.
5. (Owner) On a non-empty project the single-view surface tools are removed: Draw surface, the axis slot's arming
   tap, the Surface drawings chooser and its Redraw side and top, "Use a flat sheet" and the art-sized sheet, and
   "Insert surface". A surface there is the plane that holds the strokes, placed by the nearest-edge rule (#882) and
   edited through its planes (Move / Transform, depth menu, snap, reset, delete, flatten). Saved projects are not
   migrated: existing surfaces keep loading, rendering and painting. "Insert surface" stays only on an empty
   project, where it adds an art-free group; installed packs keep loading.
6. On an empty project "Draw surface", the axis slot's 3D tap and the chooser's entries start (or open) the guide.
7. (Owner, replaces the earlier "edit after content") There is no profile redraw once content exists; the way to
   change a surface's shape then is to flatten it or undo. Product call in one sentence: a user who wants to
   reshape a surface that already holds paint has no redraw, only flatten or undo, which follows from your rule;
   my default is to keep it that strict.
8. Drafts are held in memory and committed on Done; Cancel is therefore truly "nothing changed". The cost is that a
   step is not in the document until Done.
9. One undo step per drawn view, through the existing `setSurface`; a guided step commits one cleaned outline, so
   it does not have outline assist's raw-then-clean pair.
10. (Owner, replaces "side and top need a front") The rule is by count: the first drawn view may be any of front,
    side or top, a second needs one drawn, a third needs two. Every step is always offered; there is no "Not
    available". The surface model gains `base` and `front` (section 6a); the first view drawn is the root, a single
    view is swept one-sided along its own sweep without bound, two or three views are the hull of their prisms.
11. Skip on a not-drawn step and Keep on a drawn one are the same outcome (the view is left as it is), with
    different words; Done is available on every step.
12. "Use a flat sheet" is a choice on step 1 only, the predefined sheet (the art-sized sheet cannot occur because
    the project is empty).
13. A "Shapes" list (rectangle, ellipse, triangle, same as front) is the non-drag alternative to drawing, and
    stages a draft like a drawn stroke.
14. The guide's state is never saved, never in history and never in co-op; an app kill mid-guide loses the drafts.
15. One project-format bump to the next free number, solely for `base` and `front`; the existing fields do not
    suffice for a side-first or top-first surface. No change to undo or history code, nothing on desktop.
