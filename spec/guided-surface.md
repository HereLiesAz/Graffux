# Guided surface drawing: front, side and top, one step at a time

Status: design only. Nothing here is implemented. It changes no code, no undo or history code and no project
format (it stays 14; `reach` is the only surface field used, and it already exists). It builds on
`spec/3d-space.md` ("Group surfaces", "The default surface", "Outline assist", "Drawing the other views", "Ready-made
surfaces"); that file links back here from "Guided surface drawing". Where this file and 3d-space.md disagree about
the guided flow, this file wins; everything else in 3d-space.md stands.

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

The entry points are not removed, because a missing button cannot say why. Each one is shown **disabled, with the
reason as its TalkBack state description and as a line in a one-tap notice**:

- the layers rail's 3D axis slot on a 2D layer, and "Draw surface" in the group's layer menu: when the guide would
  be offered they start it (replacing the arming chip on an empty project); when it is not, they keep the
  behaviour in "Existing ways" below, and the disabled guide entry is the extra row "Guided surface" in the layer
  menu and the Surface drawings chooser;
- the "Guided surface" row reads, disabled, with `stateDescription` set to the reason and the row's tap showing
  the same words in the surface chip (not a toast; a chip stays until dismissed, as the armed chip does):
  - not empty: `surface_guide_unavailable_content` "Guided surface drawing is for an empty project. Your project
    has artwork, so draw a single view from the Surface drawings menu instead."
  - no layer: `surface_guide_unavailable_no_layer` "Add a layer first, then draw its surface."
  - locked layer: the existing `LOCKED` wording.
  - checking: `surface_guide_unavailable_checking` "Checking the project. Try again in a moment."
  - unreadable: the existing `NOT_READABLE` wording.
  - 3D unavailable: the existing `UNAVAILABLE` wording.
  Colour is never the only cue: the disabled row also carries the words.

### Existing ways (what stays, what changes)

On a project that is not empty, the existing flows are kept unchanged, because they are the only ways to give
existing artwork a surface and each is already specified:

- "Draw surface" (front, one stroke, `onArmSurfaceDraw`) with "Use a flat sheet" and the art-sized sheet;
- "Draw / Redraw side view" and "Draw / Redraw top view" (`onArmSurfaceView`) from the layer menu and the Surface
  drawings chooser;
- "Insert surface" (ready-made surfaces; a new empty group, so it works on any project);
- relief surfaces built from a photo.

The only conflict is on an **empty** project, where "Draw surface" and the axis slot's 3D tap would arm the single
stroke while the guide is available. There they start the guide instead; the single-stroke arming is not offered
on an empty project from those two entries. The Surface drawings chooser on an empty project's surface group
keeps its per-view entries, but each opens the guide at that view's step (so a step is always offered both
Draw/Edit and Skip). "Insert surface" is unaffected.

**Editing a surface after content exists (decided).** It stays possible, through the existing single-view
"Redraw" entries, not through the guide. Reasons: removing it would strand a user with a surface they drew
badly and paint already on it, with no way to fix the shape; the entries already exist, are specified, undoable
and co-op safe; and rule 1 gates the guided walk-through that *creates* a surface on a blank project, not the
per-view edit of one that exists. The guide itself is never available once content exists, so it is consistent
with rule 1: "guided" requires empty, "single view redraw" does not.

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

A step is in exactly one of four states, shown in words, never auto-resolved:

| State | The step has | Primary | Also offered |
|---|---|---|---|
| Not drawn | no profile for this view | **Draw** | **Skip**; on step 1 also **Use a flat sheet** |
| Drawn (existing) | a profile already on the group | **Edit** | **Keep** (leaves it as it is) |
| Drawn (this session) | a draft the user captured in this guide | **Redraw** | **Keep** |
| Not available | side or top with no front yet | (none) | **Skip**; the reason in words |

Rules:

- Nothing is created or changed implicitly. Arriving on a step does not arm drawing, does not capture, does not
  pre-fill and does not complete the step. Capture begins only when the user chooses Draw, Edit or Redraw.
- Nothing is auto-skipped. A step that cannot be drawn (Not available) is still reached, still shown, and the user
  still presses Skip (or Back) to leave it.
- "Skip" on a not-drawn step and "Keep" on a drawn one both mean: this step is left exactly as it is. They are
  one outcome, `Left as is`, with the wording that fits.
- **Edit** means redraw this view's outline: the current outline is shown as a guide line under the stroke, and the
  new stroke replaces it on Done. There is no point-editing of an existing outline (none exists today).
- **Use a flat sheet** (step 1, only for a group with no front yet) is the existing default, offered as one of
  the choices, not a default taken for the user: it stages the sheet of 3d-space.md "The default surface" (the
  predefined 3/4 by 2/3 sheet, `DefaultSurface.profile`, with `reach`) as the step-1 draft. Because the target
  is an empty layer or group by rule 1, the art-sized variant and its `HAS_ART` refusals can never arise here.
  Sides and top drawn after it make a hull and `reach` is then unused, as today.
- **Not available.** The existing model has no side or top without a front (`onArmSurfaceView` requires a valid
  surface; `onSurfaceProfileDrawn` drops a side or top with no front). So if step 1 ends `Left as is` on a group
  with no front, steps 2 and 3 show "Not available: draw the front, or use a flat sheet, first" with Skip and Back
  only. Going Back and drawing the front makes them available again. Drafts for 2 and 3 captured before the user
  went back and skipped the front are kept as drafts but cannot commit; Done warns in words and leaves them out
  (see 6).

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
`SurfaceView.FRONT`'s first-pose rebase for the first front on a posed group. A stroke below the minimum length is
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

1. the front through the existing first-surface commit (`commitWrappedSurface` for a layer to wrap, else `setSurface`
   with the rebase for a posed group), which makes the wrap and the profile one history entry;
2. the side, then the top, each through `setSurface(groupId, current.withView(view, draft))`, one history entry
   each (`pushHistory` is inside `setSurface`, so nothing about history is touched).

The commit runs as one synchronous pass on the view-model's main path (the steps do not interleave with a
user gesture or another commit), the camera returns to `surfaceReturnCamera`, the guide closes, and the "surface
set" chip (`DONE`) says what was made, with the existing "Front: done. Side: not drawn yet. Top: ..." status.
`Left as is` views change nothing. Drafts that cannot commit (a side or top with no front, section 4) are left
out, and the chip says "Side was not saved: it needs a front." in words.

A view left `Drawn` but identical to what the group already has changes nothing and adds no history entry.

**Combining into one surface (the hull).** The three outlines are not blended; each is the silhouette of the solid
as seen along its axis, and the surface is their intersection: the front outline swept along Z, the side along X,
the top along Y, carved as `SurfaceHull` already does, with creases where faces of two views meet and the hull's
existing crease handling for paint and fills. Closed outlines make solids, open lines thin walls; an outline that
does not agree with the others (a side taller than the front) is not rejected: the intersection is whatever the
three produce, exactly as drawing them one at a time today.

**Partial completion** (all legal; nothing is completed for the user):

| Front | Side | Top | Result |
|---|---|---|---|
| drawn | left | left | a front surface swept along Z without bound (today's "Draw surface") |
| flat sheet | left | left | the default sheet (`reach`) |
| drawn | drawn | left | a hull of two views |
| drawn | left | drawn | a hull of two views |
| left, no existing front | any | any | nothing is created; steps 2 and 3 are Not available; Done changes nothing |
| existing | edited | left | the side replaced, the others as they were |

## 7. Re-entering and editing

- On an empty project the guide can be opened again at any time, from the same entries, on the same group: each
  step then reads its state from the group's profile (`Drawn (existing)` shows Edit and Keep). Re-entry is how a
  user adds the side or top later, or fixes one.
- Once the project has content the guide is not offered (rule 1). A surface that exists is edited through the
  single-view "Redraw" entries (section 2, decided), each of which is the existing flow and one undo step.
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

No project-format change; it stays 14. A guided surface is a `SurfaceProfile` with `side`, `top` and (flat sheet)
`reach`, all existing, written by the existing save. The guide's state is not persisted: a project saved or the app
killed mid-guide reopens with the guide closed and the document as it was before Done (a draft is lost; the
panel says nothing, the user starts again). Co-op: drafts are not sent; Done's commits go out as the existing
per-step layer-property operations (`emitLayerProps`), in order. A peer that adds content during the guide closes
it as a Cancel for this user, as in section 2.

## 10. Special projects and platforms

- **2D-only project** (no group has a surface; the camera never moved): the guide is the way to make the first
  surface on an empty one; on a non-empty one it is unavailable and says why, and nothing about 2D rendering,
  gestures or export changes. A project that never uses the guide behaves exactly as before.
- **A project with no 3D group** and with layers all empty: the guide is offered on the active layer. One with
  content but no 3D group: the guide is unavailable (the content reason) and the existing "Draw surface" /
  "Use a flat sheet" entries are the way in.
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
   surface, camera and history byte-identical; Done with front only, front and top, side without a front (not
   committed, reported), flat sheet then side; each committed view adds exactly one history entry and undo
   removes them one at a time; Edit replaces only its own view; unchanged redraw adds no entry; content arriving
   mid-guide cancels it; the first front on a posed group bakes the pose as today.
3. **Panel and strings.** `SurfaceGuidePanel` with the unavailable rows. Compose tests: the indicator text, the
   four step states and their buttons, 48dp minimum size on every control, the live-region announcement, the
   disabled rows' `stateDescription`, the Discard confirm, chrome hidden while a stroke is down, and no control
   reachable only by drag (the Shapes and flat-sheet paths). A lint test that every guide string resource exists
   in every locale and that no prompt is a literal.
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
   stroke, even erased, is content; a posed or surface-bearing but art-free layer is empty. If PR #882 lands a
   different rule, that rule replaces this one.
4. Not offered means the guide entry is disabled with a spoken and written reason, never hidden.
5. On non-empty projects the existing single-view flows (Draw surface, flat sheet, art-sized sheet, Redraw side and
   top, Insert surface, relief) stay. Product call in one sentence: the owner said the Draw surface feature is
   offered only on an empty project, but the art-sized sheet and the redraw of a surface that already exists need
   it; I kept them, and removing them is a one-line gate if the owner wants it.
6. On an empty project "Draw surface" and the 3D axis slot start the guide instead of the single-stroke arming.
7. Editing a surface after content exists stays possible through the existing single-view Redraw, not the guide.
8. Drafts are held in memory and committed on Done; Cancel is therefore truly "nothing changed". The cost is that a
   step is not in the document until Done.
9. One undo step per drawn view, through the existing `setSurface`; a guided step commits one cleaned outline, so
   it does not have outline assist's raw-then-clean pair.
10. Side and top need a front (the existing model); without one they are Not available, still shown, still skipped
    by the user.
11. Skip on a not-drawn step and Keep on a drawn one are the same outcome (the view is left as it is), with
    different words; Done is available on every step.
12. "Use a flat sheet" is a choice on step 1 only, the predefined sheet (the art-sized sheet cannot occur because
    the project is empty).
13. A "Shapes" list (rectangle, ellipse, triangle, same as front) is the non-drag alternative to drawing, and
    stages a draft like a drawn stroke.
14. The guide's state is never saved, never in history and never in co-op; an app kill mid-guide loses the drafts.
15. No project-format change (stays 14), no change to undo or history code, nothing on desktop.
