# Guided surface drawing: first, second and third surface, one step at a time

Status: design only. Nothing here is implemented. It changes no undo or history code. It does change the surface
model, additively (section 6a, "Any perspective first"), which needs one project-format bump to the next free number
(15 is the puppetry rig's and 16 the thickness channel's, both design only, so 17 if both land first). A project that
never uses the new fields loads, renders and saves exactly as before. It builds on `spec/3d-space.md` ("Group
surfaces", "The default surface", "Outline assist", "Drawing the other views", "Ready-made surfaces", "Adopting the
view plane", "Draw plane depth") and `spec/shape-tool.md`; 3d-space.md links back here from "Guided surface
drawing". Where this file and 3d-space.md disagree about the guided flow or about which surface tools are offered,
this file wins; everything else in 3d-space.md stands.

Three owner rules frame it. (1) The guided walk-through is for an **empty** project only; on a project with content
a surface is the plane where the strokes are, found by the nearest-edge rule already on main (#882: `NearestEdge`,
`hasPlaneContent`, `adoptViewPlaneForStroke`), and the profile-drawing tools are not offered there. (2) The user
draws a **first, second and third surface**, each from a different one of the three perpendicular perspectives; the
second and third bound the first. (3) While drawing, the **vector tools** are the default and the earlier surfaces
are shown in a reference colour.

The Android app only. The desktop app does not use `:core:common` or `:feature:editor`, has no 3D and no surfaces,
and gets nothing (see "Special projects and platforms").

## 1. What it is

Today "Draw surface" arms one stroke; the side and top views are separate, later, optional arms from a chooser.
The guided flow is a walk through up to three surfaces, in a fixed order, on a project that has nothing in it yet.
It adds a step sequence, a place to hold drawings until the user confirms, vector drawing tools, a reference
overlay and the words. Capture (`SurfaceMath.capture`), outline assist (`OutlineAssist`), the merge
(`SurfaceProfile.withView`) and the hull with its creases (`SurfaceHull`) are reused.

How the three surfaces relate:

- The **first surface** is any outline the user draws in the first perspective. If it is **closed** (a circle, a
  rectangle, any enclosed shape) it is extruded through the axis perpendicular to its drawing plane and becomes a
  solid: a circle becomes a cylinder. If it is **open** (a line, an arc, a pen path) it stays what an open profile
  is today: a swept surface, the outline carried along the perpendicular axis (a wall), not a solid.
- The **second and third surfaces** are drawn in the other two perspectives. They **bound** the first, like the
  other two orthographic views of an object: the result is the first surface limited by what the second and third
  allow. Section 6 gives the combination for every case.
- The steps are **strictly dependent**: the second is presented only after a first exists, the third only after a
  second. Skipping the first ends the flow with nothing created; skipping the second ends it with just the first;
  skipping the third gives the second **mirrored** (section 4).

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
| Surface drawings chooser (slot on a surface group's layer; "Draw / Redraw side view", "... top view") | **absorbed**: each entry opens the guide at that perspective's step, or at the first step still lacking a surface | **removed**: the slot is not shown on a surface group's layers; the group stays editable through its planes (below) |
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

## 3. Three surfaces, three perspectives (rule 2)

The three perspectives are the existing `SurfaceView` cameras (`SurfaceView.camera`). They are labels for where the
camera stands, not the names of steps:

| Perspective | `SurfaceView` | The camera looks along | The canvas shows | The outline is carried along | Camera used |
|---|---|---|---|---|---|
| Z-view (the front) | `FRONT` | Z (into the page) | X across, Y down | Z | `yaw 0, pitch 0` |
| X-view (the side) | `SIDE` | X (from the right) | Z across (depth), Y down | X | `yaw 90 degrees, pitch 0` |
| Y-view (the top) | `TOP` | Y (down from above) | X across, Z down the screen (depth) | Y | `yaw 0, pitch` at the camera's limit |

Axes are the group's box-local axes of 3d-space.md: x right, y down, z away from the viewer at the front view. Each
step's prompt names the perspective in words and by axis ("You are looking along the X axis. Up and down is Y, left
and right is depth, Z"), so X, Y and Z are never bare letters.

**The first perspective** is **the view the camera currently shows**, by default: the one of the three whose axis
the camera looks most nearly along (largest absolute component of the view direction; ties go to the Z-view). On
entering step 1 the camera is set exactly to that perspective, so the outline is axis-exact. The user may choose
another with three radio buttons on step 1, "Looks along Z / X / Y" (each 48dp, with the words, not colour or
position alone), until a first surface has been drafted; after that the first perspective is fixed for this
session (Cancel and reopen to change it). For a group that already has views the first perspective is its base
(section 6a) and is not offered for change.

**The second and third perspectives are the other two, in a fixed cyclic order** (Z, then X, then Y, then Z):

| First perspective | Second | Third |
|---|---|---|
| Z-view | X-view | Y-view |
| X-view | Y-view | Z-view |
| Y-view | Z-view | X-view |

The steps are therefore **Step 1, Step 2, Step 3 = the first, second and third perspective of that table**, always
in that order, and they are **strictly dependent**: n surfaces need the n-1 before them (the count rule is only that
dependency). The second step is presented only once a first surface exists (drawn this session, or kept from the
group); the third only once a second exists. A step that is not yet presented is shown in the indicator as "later"
and has no controls. The step number is also the surface's role: step 1 is always the first surface, step 2 the
second, step 3 the third. There is no renumbering.

**Target.** The guide works on exactly one group. If a group is active (or the active layer is in one), that group.
Otherwise the active layer is the target and is wrapped in a new group on the first committed surface, exactly as
"Draw surface" wraps a layer today (`commitWrappedSurface`; an empty layer, so the default sheet rules of 3d-space.md
"Empty layers and groups" apply to the flat sheet). With no layers the guide is not offered. An empty group that
already has a surface is a valid target; its existing surfaces are shown as existing (section 4).

## 4. Each step: create, edit, skip (rule 3)

A presented step is in exactly one of three states, shown in words, never auto-resolved:

| State | The step has | Primary | Also offered |
|---|---|---|---|
| Not drawn | no profile for this perspective | **Draw** | **Skip** (what it does depends on the step, below); on step 1 also **Use a flat sheet** |
| Drawn (existing) | a profile already on the group | **Edit** | **Keep** (leaves it and moves on) |
| Drawn (this session) | a draft the user captured in this guide | **Redraw** | **Keep** (accepts it and moves on) |

There is no "Not available" state and no "must be closed" rule: the first surface may be open or closed, in any
perspective. Nothing is created or changed implicitly: arriving on a step does not arm drawing, capture, pre-fill or
complete it, and nothing is auto-skipped; capture begins only when the user chooses Draw, Edit or Redraw. **Edit**
means redraw this surface: the current outline is shown as a guide line under the stroke, and the new stroke replaces
it on Done; there is no point-editing of an outline.

**What Skip does (the dependency rules).**

- **Skip on step 1 (not drawn) ends the guided flow.** A second or third surface has nothing to bound, so neither is
  presented. Nothing is created and nothing changes; the panel closes with the camera restored, and the chip says
  "No surface was made. You skipped the first one." (an explicit user action, so there is no extra confirm). This
  is the same end state as Cancel with no drafts.
- **Skip on step 2 (not drawn) ends the flow with just the first surface.** The first (drawn or kept) is committed
  as Done would, the chip says what was made, and no third is offered. The button reads "Skip: finish with the first
  surface".
- **Skip on step 3 (not drawn), after a first and a second exist, gives the second mirrored**, and then the flow
  ends and commits as Done would. The button reads "Skip: use the second surface, mirrored". The mirror, precisely:
  let L1 be the axis the first perspective looks along, and L2 and L3 the axes the second and third look along. It is
  the reflection through the plane that contains L1, passes through the centre of the first outline's bounding box,
  and swaps L2 and L3 (normal e_L2 minus e_L3), applied to every point of the second surface's profile and to its
  sweep (e_L2 becomes e_L3); the result is the profile in the third perspective, committed as a real third
  surface. Example: a circle drawn in the Z-view, a rectangle in the X-view (it shows Z and Y); the mirrored third
  is the same rectangle in the Y-view (it shows Z and X): a cylinder cut to the same depth on both sides, bounded
  alike in X and Y. It is a pure function (`SurfaceGuide.mirrored(second, first)`), the same whether the first or
  the second is open or closed (when the first is open the plane still passes through its bounding box centre).
  Until Done the mirrored draft is shown in the reference style marked "mirrored" and follows edits of the second;
  once committed it is ordinary data and does not follow. If a third already exists, Keep leaves it and no mirror
  is made.
- **Keep or Next on a step that has a surface** moves to the next step (or, on step 3, to Done). **Next on a step
  with nothing drawn is disabled**, with its reason spoken ("Draw this surface, or Skip"), because the next surface
  would have nothing to bound.
- **Done** is available whenever at least one surface is drawn or kept and commits everything so far; with none it
  is Cancel's twin ("Nothing was drawn").
- **Back** returns to an earlier step, keeping every draft. If the user then Redraws an earlier surface, later
  drafts stay (they were drawn against the old one and are shown with it as the reference); if the user instead
  removes nothing, there is no remove: a drawn surface is replaced, never deleted, by the guide.

**Step state diagram.** `S1`..`S3` are the steps, each Not drawn (N), Drawn (D) or Kept; `End(x)` is how the flow
ends:

~~~text
S1 N --Draw/flat sheet--> S1 D --Keep/Next--> S2 N --Draw--> S2 D --Keep/Next--> S3 N --Draw--> S3 D --Done--> End(commit all)
S1 N --Skip----------------------------------> End(nothing created)
S2 N --Skip----------------------------------> End(commit first only)
S3 N --Skip----------------------------------> End(commit first, second, mirrored third)
any step --Cancel----------------------------> End(nothing changed)
any step with a surface --Done---------------> End(commit what exists)
S2, S3 --Back--> previous step (state kept)
~~~

**Use a flat sheet** (step 1 only, for a group with no surfaces, and only when the first perspective is the Z-view,
because the sheet lies in the z = 0 plane) is the existing default, offered as one of the choices, not taken for the
user: it stages the sheet of 3d-space.md "The default surface" (the predefined 3/4 by 2/3 sheet,
`DefaultSurface.profile`, with `reach`) as an open first surface. The project is empty, so the art-sized variant and
its `HAS_ART` refusals never arise. Surfaces drawn after it bound it as any open first surface (section 6).

**An existing group.** Its surfaces start as Drawn (existing) in step order: the base is step 1, then the others in the
table's order; a group with only a second-perspective surface treats it as the base. A step beyond the last
existing surface is Not drawn and is presented after the earlier ones are kept.

## 5. The guidance UI (rule 4)

One panel, `SurfaceGuidePanel`, in the app's message-card chrome (the same container as `SurfacePromptChip`:
`RailFill`, 12dp corners, `widthIn(max = 420.dp)`), at the top of the canvas, non-modal so the canvas is live for
the stroke. It replaces `SurfacePromptChip` while the guide is open.

**Content, top to bottom:**

1. **Step indicator** as text and as a row of three segments: "Step 2 of 3: second surface, looking along X". The
   segments carry the state in a symbol and a word (drawn / left as is / this step), not colour alone. It is one
   TalkBack node: "Step 2 of 3. Step 1: drawn. Step 2: not drawn, this step. Step 3: later."
2. **Prompt**, per role and per state, all string resources (below), never built from parts in code.
3. **First-perspective choice** (step 1 before a first draft exists): three radio buttons.
4. **Drawing tools** (section 5a) and, when earlier surfaces exist, the **reference legend** (section 5b).
5. **Preview** (section 6): the captured outline on the canvas and a small thumbnail of the solid so far.
6. **Step actions** (the state's choices, section 4), then **navigation**.

**Navigation:** `Back`, `Next`, `Done`, `Cancel`.

- **Back** goes to the previous step without changing or discarding anything (drafts are kept). Disabled with its
  reason on step 1 ("This is the first step").
- **Next** (the same as Keep on a step that has a surface) goes to the next step; it is disabled on a step with
  nothing drawn, with its reason spoken, and on step 3 (Done takes its place). Later steps are not presented until the
  earlier one has a surface (section 4).
- **Done** is available once at least one surface is drawn or kept; it ends the guide and commits what exists
  (section 6). With none it changes nothing and says "Nothing was drawn" (a Cancel with a different word).
- **Cancel** abandons the whole flow, discarding every draft; the document is exactly as before the guide opened
  (same layers, surface, camera, view, active tool, shape mode). The Back key is Cancel while a stroke is not in
  progress; while one is, it cancels the stroke first. Cancel with drafts asks once: "Discard your drawings?" with
  Discard and Keep drawing (48dp each). Cancel with no drafts does not ask.

**Accessibility:**

- Every control is at least 48dp square (the `SurfacePromptChip` button rule), with a visible label (no icon-only
  controls) and a `Role.Button` (radio buttons: `Role.RadioButton`, selected state spoken) with a
  `contentDescription` that repeats the step ("Skip, second surface").
- The panel is one live region (`LiveRegionMode.Polite`, as the chip is), so a step change is announced once.
- **No drag-only path.** Drawing is a drag, as drawing a profile always has been; every other part is a tap. For a
  user who cannot drag, **Shapes** (below) stages an outline by taps alone, and Skip, Keep, Use a flat sheet and the
  perspective buttons need no drag. The camera is moved by the step, not by the user.
- **Shapes (non-drag alternative).** In every Draw, Edit and Redraw state a **Shapes** button opens a short list
  (rectangle, rounded rectangle, ellipse, triangle, and for the second and third "Fit the first surface": a
  rectangle the size of the first outline's extent in the axes this perspective shows) that stages a draft outline
  sized to the view's current frame. It stages a draft only; the user confirms with Done.
- Reduced motion: the camera turns to a step's view by setting the camera, not by an animation, unless the app's
  existing camera-move animation setting is on.
- **Chrome hidden during strokes.** While a stroke is down the rails, panel and chip are hidden, as for every other
  stroke (3d-space.md open point 5), and return when it lifts. A stroke that lifts as a tap (under 4 px) changes
  nothing and the panel stays, saying so (the existing `TOO_SHORT`).

**Strings (all in `ChromeStrings` / `SurfaceStrings`, English first, other locales per the project rule, none
hard-coded):** `surface_guide_title` "Draw this surface in up to three views"; `surface_guide_step_indicator` "Step
%1$d of 3: %2$s"; role prompts `surface_guide_first_prompt`, `_second_prompt`, `_third_prompt` (each with a Draw
and an Edit variant, a closed and open note, and the perspective sentence `surface_guide_look_x`, `_look_y`,
`_look_z`); `surface_guide_draw`, `_edit`, `_redraw`, `_skip_first` "Skip: no surface", `_skip_finish_first` "Skip: finish with the first surface", `_skip_mirror` "Skip: use the second surface, mirrored", `_keep`, `_flat_sheet`, `_shapes`, `_back`, `_next`, `_done`, `_cancel`, `_discard_confirm`, `_discard`,
`_keep_drawing`; tool names reuse `shape_mode_*` and the Pen and Freehand labels; legend `surface_guide_ref_first`,
`_ref_second`, `_ref_mirrored`; the unavailable reasons of section 2; and state words `surface_guide_state_drawn`,
`_not_drawn`, `_left_as_is`, `_this_step`, `_mirrored`, `_not_used`. The prompts follow the 3d-space.md chip
wording: plain, one sentence of what to do, one of what it shows, "Your artwork is not changed."

### 5a. Vector tools are the default

While a step is capturing, the drawing tools are, in the order offered: **Rectangle, Rounded rectangle, Ellipse,
Line, Pen**, and, last, **Freehand** (the existing raw stroke with outline assist). These are the Shape tool's modes
(`ShapeToolMode`, spec/shape-tool.md, merged in #879) and the Pen (`Tool.PEN`), so they are the vector tools; the
freehand brush is reachable but is never the default.

- **How the tool is selected.** The guide keeps its own tool, `SurfaceGuideState.tool`, and shows it as a row of
  radio chips in the panel (48dp, glyph from `RailIcons.shapeGlyph` plus the name, the selected one spoken). On
  entering step 1 it is the Shape tool's current mode when that is Rectangle, Rounded rectangle, Ellipse or Line,
  otherwise **Ellipse**; on entering step 2 and 3 it is the tool last used in this guide (sticky across steps), so
  it is never reset to Freehand. The user changes it with one tap on a chip.
- **How it is restored.** The guide never writes `EditorUiState.activeTool`, `shapeToolMode` or their preferences
  (`shape_tool_mode`): the capture surface already lies over every tool, so the app's tool in hand is untouched for
  the whole flow. Exit by Done or Cancel (or the guide closing because content appeared) therefore has nothing to
  restore: the tool in hand and the shape mode are exactly what they were, and the guide's own choice is dropped
  with its state (it is not remembered for the next time). A test pins that they are equal before and after.
- **What a tool captures.** The same gestures as spec/shape-tool.md section 4 (drag from one corner to the
  opposite, the Keep proportions chip, the snap to square and 15 degrees, one Compose rubber band while dragging), but
  the result is a **draft outline, not a layer**: no vector layer is made. Rectangle: four corners and the closing
  point. Rounded rectangle: the corners sampled (8 segments each, radius from the Shape tool's corner-radius
  option). Ellipse: 64 vertices on the ellipse. Line: two points (open). Pen: the traced path, simplified as a
  capture is (open unless it ends on its start). Freehand: as today. Each outline then goes through
  `SurfaceMath.capture` with the step's camera and chain. The shape tools' results are exact, so outline assist is
  not applied to them; it runs on Pen and Freehand when the setting is on.
- **Open or closed.** Nothing is forced. A closed outline (a rectangle, rounded rectangle, ellipse, or a stroke that
  ends on its start by the profile's own `isClosedOutline` rule) is closed; Line and an unclosed Pen or Freehand
  stroke are open. The panel says which in words ("Closed shape" or "Open line") so the user knows whether the first
  surface will be a solid or a wall (section 6).

### 5b. Reference colour for earlier surfaces

On the second step, the first surface is shown; on the third, the first and the second (a mirrored third shown on
the third step is the draft being drawn, not a reference). Each reference is an overlay on the canvas:

- **What it is.** The earlier surface's outline (its drafted or existing profile), projected through the current
  step's camera with the same projection the canvas uses (`SceneMath`, the one the mesh overlay uses), so in a
  perpendicular perspective a surface drawn in another plane appears edge-on, as a line of its true extent, and the
  extruded solid so far appears as its silhouette in this view: the outline of the combined prisms' silhouette is
  drawn thin and dotted under the references, so the user can see what the new outline will bound.
- **How it differs from what is being drawn.** The current stroke and its draft are drawn solid, 3dp, in the
  selection ink (`colors.primary`). The first surface's reference is a **dashed** 2dp line (6dp on, 4dp off) in the
  theme token `SurfaceGuideReferenceFirst`; the second's is a **dotted** 2dp line (2dp on, 4dp off) in
  `SurfaceGuideReferenceSecond`; a mirrored third is dotted with a small "mirrored" tag. Each also carries a
  small text tag at its first vertex ("1", "2") and has a row in the legend ("Dashed line: first surface"). So the
  difference is never colour alone: dash pattern, tag and legend carry it, and the tints are chosen so each is at least
  3:1 in luminance against the canvas background and against the selection ink, and they stay distinct when the app
  is rendered monochrome. The tokens are in `core/design` colour resources with a light and a dark value, no
  literal colours in the composable.
- **Not editable, not part of the result.** A reference takes no touch (it is drawn in the canvas overlay layer under
  the capture surface, which receives every pointer; nothing hit-tests it), is not selectable, is a graphic with no
  semantics of its own (the legend and the panel's words describe it for TalkBack), is never exported, and is
  not in the document: it draws from the guide's drafts and from the group's existing profiles, which Done then
  commits. Cancel removes it with the rest of the draft.

## 6. Capture, preview, commit

**Capture.** Choosing Draw, Edit or Redraw sets the camera to the step's perspective (`SurfaceView.camera`, keeping
the first camera in `surfaceReturnCamera` exactly as `onArmSurfaceView` does) and arms one capture with the guide's
tool (5a) on the capture surface (over every tool, painting nothing). The outline is made into a profile by the
existing `SurfaceMath.capture` with the existing chain (`planeChain`), with the first-pose rebase for the first
surface drawn on a posed group. A stroke below the minimum length is `TOO_SHORT` and the step stays armed. When
the finger lifts, outline assist runs once if the setting is on and the tool is Pen or Freehand
(`surfaceOutlineAssist`), and its result **replaces** the raw stroke in the draft (the guide commits one cleaned
outline per surface; see Undo). The step then shows `Drawn (this session)`.

**Draft.** The drawn outlines live in guide state, not in the document: `EditorUiState.surfaceGuide:
SurfaceGuideState?` (null when closed; in-memory only, never saved, never sent to co-op, not in history). It holds
the target (a group id, or the layer to wrap), the first perspective, the current step, the tool, and per
perspective one `SurfaceProfile?` draft with its mark (`Drawn`, `Left as is`, or `Mirrored`). While it is non-null
`surfaceDrawArmed` is true only during a step's stroke, so the existing capture code runs unchanged, and
`onSurfaceProfileDrawn` routes the result to the draft instead of `setSurface`.

**Preview.** Nothing is committed to the layers, so the document, the saved file, co-op and undo do not see a
draft. The preview is the outline on the canvas, the references (5b), and a thumbnail in the panel: the drafts and
existing profiles merged by `SurfaceProfile.withView` in a pure copy and meshed by `SurfaceHull` (the same code that
renders a hull), from a fixed three-quarter camera, with its words ("First surface: closed, a solid. Second: from
this session. Third: mirrored.") for TalkBack.

**Commit (Done).** In order of the steps, for each perspective whose mark is `Drawn` or `Mirrored`:

1. the first surface (the earliest drawn in step order, or the group's existing base) through the existing
   first-surface commit (`commitWrappedSurface` for a layer to wrap, else `setSurface` with the rebase for a posed
   group), which makes the wrap and the profile one history entry; it sets `base` and, for a closed outline,
   `solid` (section 6a);
2. each later surface through `setSurface(groupId, current.withView(view, draft))`, one history entry each
   (`pushHistory` is inside `setSurface`, so history code is untouched). A mirrored third is committed like a drawn
   one.

The commit runs as one synchronous pass on the view-model's main path, the camera returns to
`surfaceReturnCamera`, the guide closes, and the "surface set" chip (`DONE`) says what was made. `Left as is`
perspectives change nothing. A surface that is identical to what the group already has adds no history entry.

**Combining the surfaces (what the result is).** Each surface is the silhouette of the object seen along its
perspective's axis. The result is `SurfaceHull`'s: one prism per drawn surface, each carried along its own axis
through the padded box of everything drawn, with the closed prisms intersected and creases where faces of two
prisms meet (the hull's existing crease handling for paint and fills):

| First surface | With | Result |
|---|---|---|
| closed | nothing else | a **solid**: the outline extruded both ways through the axis perpendicular to its plane, as far as its own widest side on each side of the plane (a circle: a cylinder as long as it is wide). `solid` is set so it is a hull of one prism, not an unbounded tube |
| closed | a closed second (and third) | the extrusion **bounded** by the second and third: the intersection of the prisms. Sides that do not agree are not rejected; the intersection is whatever they produce |
| closed | an open second or third | the open outline is a wall under the existing one-wall rule: it is kept as the wall of the hull (the first open one only) |
| open | nothing else | a **swept surface**: the outline carried one-sided along the perpendicular axis, without bound (or to `reach` for the flat sheet), exactly today's open profile (`solid` false, no hull) |
| open | a closed second (and third) | the swept surface is the wall; it is **shaped by** the closed prisms: only the part of the swept surface inside the intersection of the closed prisms exists, as `SurfaceHull.of` already clips a wall |
| open | an open second | the existing one-wall rule keeps only the first open outline, so the second does not shape anything. The draft is marked "Not used" in words, Done leaves it out and says so |
| any | a mirrored third | the second's profile reflected as in section 4, then as above (a closed second mirrored is closed, an open one open) |

**Partial completion** (all legal; nothing is completed for the user, except the third's mirror when asked for):
the first alone (Skip on step 2, or Done after step 1; closed: a solid, open: a swept surface); first and second
(Done after step 2); all three (Done after step 3); first, second and a mirrored third (Skip on step 3); an existing
perspective edited with the others kept; none (Skip on step 1, or Cancel: nothing is created). There is no
second-without-first and no third-without-second.

## 6a. Any perspective first: how the surface model changes

Today the model is front-first. `SurfaceProfile` IS the front outline (its own `points`, swept along its `sweep`),
and `side` and `top` are optional nested outlines on it; there is no slot for a front when the root is something
else, and a closed single outline is a sheet, not a solid. The guide needs any perspective to be the first one
drawn, and a closed first surface to be a solid. The change is small and additive.

**The base.** The root profile is the **base**: the first surface drawn, in whichever perspective. Its points and
`sweep` are exactly what a capture of that perspective already produces (`SurfaceMath.capture` stores the view
direction as the sweep, so an X-view outline's sweep is already +X and a Y-view's +Y). Three new optional fields on
`SurfaceProfile`:

~~~kotlin
val base: SurfaceView? = null     // which perspective the root profile is; null means FRONT (every existing project)
val front: SurfaceProfile? = null // the Z-view outline when the base is not the Z-view; unused when base is FRONT
val solid: Boolean = false        // a closed outline is a solid (a hull of one prism), not an unbounded sheet
~~~

`side` and `top` keep their meaning for a base that is the front. For another base the slots are the other two
perspectives, by name (base SIDE uses `front` and `top`; base TOP uses `front` and `side`); the slot for the base's
own perspective is always null (it is the root). `SurfaceProfile.baseView` is `base ?: FRONT`. `view(v)` returns the
root (stripped of its slots) when `v == baseView`, else the matching slot. `withView(v, drawn)` replaces the root's
points when `v == baseView`, else sets the matching slot; it never changes `baseView`. The base is set once, when
the first surface is committed, and later drawing never moves a surface between the root and a slot. `hasHull`
becomes "two or more perspectives are drawn, or `solid`" (`viewCount > 1 || solid`), not `side != null || top !=
null`. A guided closed first surface commits `solid = true`; an open one commits `solid = false`, so it is exactly
today's open profile. A legacy closed front-only surface has `solid = false` and keeps drawing as the sheet it is.

**Roles are not stored.** First, second and third are the order of drawing in the guide; the data records only the
perspective of each profile and which is the base. A mirrored third is stored as an ordinary profile in its slot,
with no marker. Re-entering the guide reads the base as the first surface and the others in step order. The guide is strictly dependent, so it never makes a third without a second or a second without a first (an older or imported group may have them; the guide shows what exists and presents steps in order).

**Format.** All three fields are optional with the front-first defaults and are written only when set. A project in
which every surface is front-first and not `solid` serialises as before and loads unchanged. One bump (to the next
free number, section header) is needed because an older build would otherwise read a side-first group's root as a
front and draw it in the wrong plane, so an older build sees the newer format and opens the project read-only, as
for every earlier optional field. No existing project is migrated or rewritten.

**What each subset constrains, and what the missing axis does.** A surface's outline is a silhouette in the two
axes it shows; the axis it looks along is unconstrained by it alone.

| Surfaces | Constrained axes | The missing axis |
|---|---|---|
| one closed (`solid`) | the two axes it shows | the axis it looks along: extruded both ways, as far as the outline's widest side |
| one open | the two axes it shows | the axis it looks along: swept one-sided without bound (or to `reach`), as today |
| two | the union of their axes | none: the intersection of the prisms, clipped to the padded box of everything drawn |
| three | all three, each by two surfaces | none: the intersection of the three prisms |

The padded box is `SurfaceHull.of`'s: the bounds of all drawn points, widened to the widest side where an axis is
thinner than 1%. So a second surface that reaches further than the first's extent along the axis the first was
extruded through extends the box, and the extrusion is then limited by the second's outline.

**Crease and hull combination for any subset.** `SurfaceHull.of`, `meshByView`, `capMeshByView` and `creaseLinks`
are built from prisms tagged with their view, not from "the front plus extras", so any 1-, 2- or 3-view subset
already combines; the work is the tagging (the root prism is tagged `baseView`, not hard-coded FRONT), the open-wall
rule (at most one open outline is kept: the root if it is open, else the first open one in step order), and
admitting a single closed prism when `solid` is set.

**Code paths that assume a front-first model** (the implementation PR must change each; names are current main):

- `SurfaceProfile` (`:core:common`): the root-is-front documentation and `view()`, `withView()`, `hasHull`,
  `sweepLimit`, `isFlat`, the `@Serializable` shape and its kdoc ("Only read on a group's own profile").
- `SurfaceHull.of`: labels the root `SurfaceView.FRONT`; must label it `baseView`, read the `front` slot, order the
  wall rule by base, and build a one-prism hull for `solid`. `SurfaceHull.meshByView`, `capMeshByView`,
  `creaseLinks`, `HullHit.view`, `SurfaceHullMesh.textured` and the per-view images (`SurfaceViewImages`,
  `carryPaintOntoFront`, `DepthScene.hullPlanes`): the "front image", and the carrying of the sheet's paint onto it
  when a second view first appears, become the base's image.
- `SurfaceView.camera` and `EditorViewModel.onArmSurfaceView`: `view == FRONT` is routed to `onArmSurfaceDraw`, and
  the group must "already have its surface" (`surface?.isValid == true`); both go. Arming any perspective works
  with or without a surface.
- `EditorViewModel.onSurfaceProfileDrawn`: `if (view != FRONT && existing == null) disarm` drops a side or top drawn
  first; the rebase test `first = newGroup == null && view == FRONT` is "no surface yet", not "front"; and
  `existing?.withView(view, profile) ?: profile` must make the new profile the root with `base = view` (and `solid`
  from its closure).
- `EditorViewModel.onSkipSurfaceDraw` and the `FlatSheet` outcomes: `surfaceDrawView != FRONT -> return` and the
  front-only checks. The flat sheet stays a Z-view open sheet, offered only on step 1.
- `surfaceReturnCamera`: set today only when a non-front view is armed (`onArmSurfaceView`), while arming the front
  leaves the camera as it is. The guide turns the camera for every presented step, so the first camera is stored on the
  first armed step and restored by `disarmedSurface()` on every exit.
- `SurfacePromptChip` and `surfacePromptContent` (`:app`): `SurfaceViewsStatus`, `onDrawView`, `onSkip` and the
  per-view titles and bodies; the guide replaces them with its panel, the chip keeping only notices.
- Consumers of `hasHull`: `NearestEdge.surfaceOutline` (a hull's clip box corners; a `solid` one now takes that
  branch), `OrbitPivot`, `SurfaceMath.localBounds` and `ActivePlaneMapping.onHull`: they follow the new definition.
  `SurfacePayload.toProfile` (ready-made surfaces put the front as the root: unchanged, base FRONT).
- Surface drawings chooser state (`surfaceViewsMenuOpen`) and the layer menu's Redraw items: removed (section 2).
- Shape tools: `ShapeOps` gains a pure outline-points function per mode (no layer), used by the guide.

## 7. Re-entering and editing

- On an empty project the guide can be opened again at any time, on the same group: each presented step reads its
  state from the group's profile (`Drawn (existing)` shows Edit and Keep). Re-entry is how a user adds the second or
  third surface later, or fixes one. The Surface drawings chooser, where an empty project shows it, opens the guide at
  the step of the entry chosen, or at the first step that still lacks a surface if an earlier one is missing (the
  steps are dependent).
- Once the project has content the guide is not offered (rule 1) and no profile redraw exists (section 2). A
  surface that exists is then edited through its planes (section 2, "What stays reachable"), which is the owner's
  rule: a surface is the plane where strokes are, not a profile redrawn over them.
- Opening the guide on a group whose paint layers are empty but whose perspectives have image layers (a hull's hidden
  images are empty paint) is allowed; committing an edited surface does what redrawing it does today (3d-space.md
  "Paint per view"). Because the project is empty, no paint is lost.

## 8. Undo and redo

No undo or history code changes. Each committed surface is exactly one `pushHistory` through `setSurface` (the first
that wraps a layer is one step with its wrap, as today). So a Done that commits three surfaces is three undo
steps, one per step, redone in the same order; nothing is grouped and nothing is skipped. The guide's own state
(the open flow, the drafts, the current step) is never history: Back, Next, Skip (before it commits), Keep, Cancel and Redraw of a
draft change no document and push no entry, and the camera is never history. Undo while the guide is open is the
editor's normal undo, which finds nothing from the guide to undo (the drafts are not in history) and so affects only
what was done before it opened; the panel stays open.

Outline assist's two-step commit (raw, then cleaned, 3d-space.md "Outline assist") is not used by the guide: a
guided step commits the one cleaned outline, so one undo returns to before that step. Undo therefore does not give
back the raw stroke inside the guide; the user redraws it (Redraw), which the step offers. With outline assist off
there is no difference. The single-view flows keep their two-step behaviour.

## 9. Persistence, co-op, format

One format bump, only for the new optional `base`, `front` and `solid` fields (section 6a); a front-first, non-solid
surface writes none of them. A guided surface is otherwise a `SurfaceProfile` with `side`, `top` and (flat sheet)
`reach`, all existing, written by the existing save. The guide's state is not persisted: a project saved or the app
killed mid-guide reopens with the guide closed and the document as it was before Done (a draft is lost; the user
starts again). Co-op: drafts are not sent; Done's commits go out as the existing per-step layer-property operations
(`emitLayerProps`), in order. A peer that adds content during the guide closes it as a Cancel for this user, as in
section 2.

## 10. Special projects and platforms

- **2D-only project** (no group has a surface; the camera never moved): the guide is the way to make the first
  surface on an empty one; on a non-empty one it is unavailable and says why, a plane is made by drawing on it,
  and nothing about 2D rendering, gestures or export changes. A project that never uses the guide behaves exactly as
  before.
- **A project with no 3D group** and with layers all empty: the guide is offered on the active layer. One with
  content but no 3D group: the guide is unavailable (the content reason); drawing under an orbited camera makes a
  plane at the nearest edge of the existing planes ("Draw plane depth"), which is the way in.
- **A project whose only layers are an empty group that has a surface:** empty, so the guide is offered and
  opens with the surfaces it has as Drawn (existing).
- **Desktop.** The desktop app has no 3D, no `:core:common` and no surfaces; nothing is added there, and a
  project that has a surface opened on desktop is read the way it already is. The guide is Android only. A project
  with the new fields opened by a build that does not know them is read-only, as every newer-format project is.

## 11. Phases and tests

1. **Pure rule (no UI).** `SurfaceGuide.available`, `projectIsEmpty(layers)` and the reasons enum, in
   `:core:common`. Tests: every content kind counts (image, text, shapes, stroke, erased stroke, 3D ink, model,
   adjustment, one faint pixel, relief group); empty plane, empty group, nested empty groups, group with a surface
   and no art, posed empty layer, view image, hidden and locked layers; no layers; a bitmap that cannot be read
   is not empty; stale-answer guard. `SurfaceGuide.firstPerspective(camera)` (nearest of three, ties to Z) and the
   cyclic order table; `SurfaceGuide.mirrored(second, first)`: the reflection and sweep swap for each of the three
   first perspectives, an involution for a plane through the centre, open and closed profiles.
2. **State and commit.** `SurfaceGuideState`, the reducer, and the commit order. Tests (the existing view-model test
   harness):
   - no implicit create or skip on entering a step; later steps are not presented until the earlier one has a
     surface;
   - the dependency rules: Skip on step 1 ends the flow with layers, surface, camera, history, active tool and shape
     mode unchanged; Skip on step 2 commits the first only and offers no third; Skip on step 3 commits the first,
     second and the mirrored third; Next is disabled on a not-drawn step; Done with no surface changes nothing; Done
     and Cancel from every state;
   - Cancel leaves layers, surface, camera, history, `activeTool` and `shapeToolMode` byte-identical;
   - the first surface closed (`solid`, base, one history entry with the wrap) and open (today's profile, `solid`
     false); every first perspective (Z, X, Y) and its cyclic second and third; the flat sheet then a second;
   - each committed surface adds exactly one history entry and undo removes them one at a time; Edit replaces only
     its own perspective; an unchanged redraw adds no entry; content arriving mid-guide cancels it; the first
     surface on a posed group bakes the pose as today.
3. **Model (section 6a).** `SurfaceProfile` `base`/`front`/`solid` round-trip and old-file load (no field written for a
   front-first non-solid surface; a file without them reads as before); `view`/`withView`/`hasHull` for every base;
   `SurfaceHull.of` for every subset and for open versus closed firsts (volume, creases, wall order) equal to the
   front-first result for the same outlines rotated; a closed solid's extent equals its widest side both ways; an
   open first clipped by a closed second; a second open outline not used when a wall exists; a side-only and a
   top-only surface paint, hit and render like a front-only one; an old build opens a new-format project read-only.
   Removed entries: tests that each tool in section 2's table is absent on a non-empty project and present as
   stated on an empty one; a legacy surface group still loads, renders and paints.
4. **Tools and overlay.** The default tool on entering step 1 (the Shape tool's mode if Rectangle, Rounded rectangle,
   Ellipse or Line, else Ellipse) and sticky on steps 2 and 3, never Freehand by default; each tool yields a draft
   and no layer; the tool in hand and the shape mode are equal before and after; outline assist runs on Pen and
   Freehand only. The reference overlay: projection of the first surface edge-on in a perpendicular view,
   dashed and dotted styles, the legend row, tokens exist with light and dark values and meet 3:1 against the
   background, the overlay takes no touch and is absent from export and from the saved file.
5. **Panel and strings.** `SurfaceGuidePanel` with the unavailable rows. Compose tests: the indicator text including
   "later", the three states and their buttons, the Skip wording on each step, 48dp minimum size on every
   control, the live-region announcement, the disabled rows' `stateDescription`, the Discard confirm, chrome hidden
   while a stroke is down, and no control reachable only by drag (Shapes, flat sheet, perspective radios). A lint test
   that every guide string resource exists in every locale and that no prompt is a literal.
6. **Preview.** The thumbnail of the solid so far. Tests: its solid equals the committed hull (same meshing), and
   its words match the marks.
7. **Device.** Not verifiable off a device: the camera turn between steps with the real depth pass, the capture on each
   perspective with each tool, TalkBack end to end, and Done's commits with outline assist on a large canvas. To be
   listed as untested in the PR that builds it, as 3d-space.md does.

## 12. DECISIONS (my defaults where the owner did not say)

1. The steps are the first, second and third surface, each in a different perspective (Z-view, X-view, Y-view,
   named by what the camera looks along and what it shows). The first perspective is the one the camera currently
   shows (nearest of the three, ties to Z), changeable on step 1 by three radio buttons until a draft exists; the
   second and third follow in the cyclic order Z, X, Y.
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
   migrated. "Insert surface" stays only on an empty project, where it adds an art-free group.
6. On an empty project "Draw surface", the axis slot's 3D tap and the chooser's entries start (or open) the guide.
7. (Owner) There is no profile redraw once content exists; the way to change a surface's shape then is to flatten it
   or undo. Product call in one sentence: a user who wants to reshape a surface that already holds paint has no
   redraw, only flatten or undo; my default is to keep that strict, as the rule says.
8. Drafts are held in memory and committed on Done (or by the Skip that ends the flow with surfaces); Cancel is
   therefore truly "nothing changed".
9. One undo step per committed surface, through the existing `setSurface`; a guided surface commits one cleaned
   outline, so it does not have outline assist's raw-then-clean pair.
10. (Owner) The first surface may be open or closed. Closed: extruded through the axis perpendicular to its plane into
    a solid (a circle makes a cylinder), bounded by the second and third. Open: today's swept surface, shaped by the
    closed second and third (only the part inside them exists). There is no required-closed, auto-close or refusal
    rule. A single closed first surface is a solid as long as it is wide (a new `solid` flag); an open one is
    unbounded one-sided as today.
11. (Owner) The steps are strictly dependent: n surfaces need n-1 before them. Skip on the first ends the flow with
    nothing created (said in words); the second is presented only after a first exists and the third only after a
    second; Skip on the second ends the flow with just the first. There is no renumbering and no "Not available" state.
    Next is disabled on a step with nothing drawn.
12. (Owner) Skip on the third, after a first and second exist, gives the second mirrored. My mirror: the reflection
    through the plane that contains the first perspective's axis, passes through the centre of the first outline's
    bounding box, and swaps the second's and third's look-along axes. It is committed as an ordinary third surface
    with no marker and does not follow later edits of the second.
13. When the guide ends by Skip on step 2 or 3 it commits what exists, as Done does, because Skip is an explicit
    action whose button says so.
14. Vector tools are the default: Rectangle, Rounded rectangle, Ellipse, Line, Pen, then Freehand. The default on
    step 1 is the Shape tool's mode if it is one of the first four, else Ellipse; later steps keep the tool last used.
    The guide holds its own tool and never writes `activeTool`, `shapeToolMode` or preferences, so nothing needs
    restoring on exit or Cancel. Shape tool drags make draft outlines, not layers.
15. Earlier surfaces are shown in the step's camera as references: the first dashed in `SurfaceGuideReferenceFirst`,
    the second dotted in `SurfaceGuideReferenceSecond`, each tagged and in a legend, plus a thin dotted silhouette of
    the solid so far. They take no touch, are never exported or saved, and exist only from drafts.
16. "Use a flat sheet" is a choice on step 1 only, only for the Z-view, and is an open first surface (the
    predefined sheet).
17. A "Shapes" list (rectangle, rounded rectangle, ellipse, triangle, "Fit the first surface") is the non-drag
    alternative to drawing, and stages a draft like a drawn stroke.
18. The guide's state is never saved, never in history and never in co-op; an app kill mid-guide loses the drafts.
19. One project-format bump to the next free number, for `base`, `front` and `solid`; the existing fields do not
    suffice for a non-front first surface or a single solid. Legacy projects load unchanged. No change to undo or
    history code, nothing on desktop.
