# One 3D space: layers as planes, a scene camera, 3D strokes as a layer

Status: implemented on Android (project format 12; the multi-view surface below has its data model, maths, persistence, drawing UI and per-view paint; rendering and glTF export of the hull). The desktop app does not use `:core:common` or
`:feature:editor`, so it is unaffected and has no 3D.

Posing groups like puppets (joints, rigidity, soft bodies, keyframes) is designed in `spec/3d-puppetry.md`; nothing there is implemented.

## What the user sees

The canvas is one 3D space. Every layer is a flat plane. A layer that was never moved in 3D sits
where it always has: at depth 0, facing the camera. A document never touched in 3D looks and
behaves exactly as before, through exactly the same render path.

There is no 3D mode. "2D" is simply the identity camera. What two fingers and a double-tap do
depends on whether the **active layer** is a 2D or a 3D layer (below):

| Gesture | 2D layer | 3D layer, no Move tool | 3D layer, Move / Transform tool (Transform panel open) |
|---|---|---|---|
| One finger | the tool in hand (unchanged) | the tool in hand, on the layer's plane | drag the active layer (as before) |
| Two-finger drag | pan the view | pan the view (orbit, in a project with a 3D group: see below) | slide the layer so it follows the fingers |
| Pinch | zoom the view | zoom the view | move the layer in depth (out = nearer) |
| Twist | rotate the canvas (the 2D view rotation) | axis Z: rotate the canvas; X / Y: orbit the camera (pitch / turn); in a project with a 3D group the camera is never orbited by a twist (retired, see below) | rotate the layer about the current axis |
| Double-tap | nothing | step the axis X -> Y -> Z (reset the view, in a project with a 3D group) | step the axis X -> Y -> Z (reset the view, in a project with a 3D group) |
| Brush / Eraser on a 3D strokes layer | | draw a tube on its guide / erase the curve under the finger | |
| Brush / Eraser on a 3D model layer | | paint / erase the model's surface under the finger | |

A double-tap never changes the active layer; a buried layer is picked from the layers rail. (The
old double-tap that cycled through the layers under the finger is gone.)

## View control in a project with 3D groups (gestures and surface appearance built)

Status: the view gestures below are built (not yet device-verified), and so is the default surface
("The default surface", not yet device-verified); the open points are still open. Where this section and the table above disagree,
this section governs in a project with a 3D group. Nothing here changes a project with no 3D group.
Test coverage: the double-tap tests run without a GPU, so pixel-level "no mark" and cancelling an in-flight commit are not covered by them.

Built in `:core:common` `scene/ViewControl.kt`: `hasThreeDGroup` (the one predicate: a group with a
surface profile), `twoFingerViewMode`, the drag-to-angle mapping (`ORBIT_FULL_WIDTH_RADIANS`: one
canvas width of drag is a half turn, same rate on both axes, pitch clamped by `SceneCamera.MAX_PITCH`),
and `ViewTapTracker`. Orbit holds the pivot for the gesture and saves the camera at its end; it is
never history. Double-tap timing and slop are the platform's (`ViewConfiguration`).

Cancelling the first tap's dot: in a 3D-group project a brush tap's dot is pushed as its own
*tentative* history entry (`EditHistory.pushDraw(tentative = true)`): never chunk-joined; the stack may
hold at most one entry over its cap for it (so the cap still bounds a run of spaced taps); and the redo
stack is set aside rather than discarded. A second tap in the window, when that dot is still the newest
entry, cancels it by identity (`EditHistory.cancelTentative` via `EditorViewModel.onBrushTap`): the dot is
undone, the redo stack comes back, no earlier stroke is touched. If the dot is no longer the newest entry
(a drag between the taps, or the first tap pushed nothing, e.g. a locked layer) the second tap is an
ordinary tap. A side effect: rapid dots in these projects are one undo step each instead of chunking.
Known gaps: touchdown-spray brushes (they start painting on touch-down), a fill tap and the Ink tool (its own surface) do not take part in the
double-tap, so a double-tap there does not reset the view; and if the first dot's commit has not
landed within the window (not seen: it is pushed synchronously on finger-up) it would stay.

**The idea.** Once a project has a 3D group, the surface is the canvas. The rectangle that bounded
the art while it was 2D-only is no longer something the user works inside; it matters again only at
export. So the view is manipulated the way a 3D sketching app does it (Feather's one-finger turntable
was the reference), and the one finger stays free for the tool in hand.

### Gestures, in a project with at least one 3D group

| Gesture | Now |
|---|---|
| One finger | Always the tool in hand. There is no on-canvas / off-canvas split: a touch that never hits the surface does nothing, by the rule in "Painting on a tilted plane". |
| Two-finger drag | **Orbit** the view about the orbit point (below). It replaces pan, in these projects. |
| Pinch | Zoom the view. To reach another part of a large surface, zoom out and pinch back in there. |
| Twist | Rotate the canvas (what the Z axis did). The twist no longer orbits the camera. |
| Double-tap | **Reset the view** (the identity camera). The first tap's dot is cancelled, so a double-tap leaves no mark and no history entry. |

- **Unchanged.** With the Move / Transform tool in hand on a 3D layer, two fingers still slide the
  layer, pinch moves it in depth and a twist turns it, one undo step per completed gesture, exactly as
  the table above says. The rail's axis slot, the rotation triangle and the
  wire-mesh ("#") button (the grid toggle and, see "Draw plane depth", the draw distance) are unchanged. In a project with no 3D group, nothing changes at all:
  two fingers pan, and a double-tap does nothing.
- **Retired in these projects.** The double-tap that stepped the axis X -> Y -> Z, the twist that
  orbited the camera about X or Y, and two-finger pan.
- **The orbit point does not change.** Orbit turns about the one rotation point of "The orbit point"
  (derived from the layers), held for the length of the gesture, with the pivot fixed on screen.
- **A surface larger than the screen.** Orbiting never brings a far part of the surface into view
  (it only tilts it). Zoom out, then pinch in at the part wanted. This needs the pinch to zoom about
  the midpoint between the fingers; it does (`onViewportPanZoom` keeps the point under the midpoint fixed).

### Non-gesture alternatives (built)

Both gesture-only actions have button twins on the tool rail, in a project with a 3D group only (a
project with no 3D group is unchanged: it keeps the "Fit" button, shown only while the view is moved).
They take ONE rail slot: a single 48dp "View" rail button (`rail_view`, `rail.view`) that opens a small
popup beside it. The popup is a 4-way orbit pad (up over left / right over down) with Reset view in the
centre; every control in it is a 48dp focusable button labelled from string resources (`rail_reset_view`,
`rail_orbit_left/right/up/down`). The popup is a full-window transparent layer with the pad placed
beside the button and clamped inside the window (`viewPadPosition`), so an outside tap dismisses it and is
consumed, never reaching the canvas; Back (a key event in the popup's own window) dismisses it too. An
orbit step leaves it open so a step can be repeated; Reset view closes it. Focus moves to the first pad
button when it opens and returns to the View button when it closes; the pad is a pane titled "View
controls" (`rail_view_controls`) for TalkBack. The open state is keyed on the project id (a different
project starts closed), and losing the 3D group removes the button and the pad.

Placement follows the rail's most-used-first order: the View button is the LAST item of the scrolling
tool pill, after the drawing tools, the axis slot and the mesh toggle (gestures are the primary way to
move the view, so it is the rarest item); Undo / Redo stay in their own pill, never scrolled away. No
existing item moved. The pill is given the screen height less the top inset (16dp) and the bottom strip
(72dp), about 86% on a 640dp-high screen, and scrolls with no clipped bottom padding, so the last item is
always reachable (`ViewControlShortScreenUiTest`, 360x640dp, scrolls to the View button, opens the pad and
clicks its last orbit button).

- **Reset view** runs the double-tap's own path (`EditorViewModel.resetViewport`): identity camera,
  zoom 1, no canvas rotation.
- **Orbit view left / right / up / down** turn the view one fixed step of 15 degrees
  (`ORBIT_STEP_RADIANS`, `orbitAnglesForStep`) with the signs of the matching drag (RIGHT is a drag to the right,
  DOWN a drag down), through `EditorViewModel.onOrbitStep`: the same `onSceneOrbit`, so the same pivot
  and pitch clamp as the gesture. Saves are coalesced (500 ms after the last tap) and never history. Each step moves the content's near side the way its name says (pinned in `ViewControlTest`). The buttons are plain actions: they do not announce a selected state.

Not built: TalkBack custom actions on the canvas itself (the buttons cover the same ground).
Not device-verified (TalkBack or keyboard); covered by `ViewControlTest`, `EditorViewModelTest`, `ViewPadPositionTest` and the Robolectric `ViewControlButtonsUiTest` only.

### The default surface (built)

The first thing done with a new 3D group is to draw its surface. If the user chooses not to, the group
gets a flat rectangular surface in the plane z = 0, sized from the screen:

- portrait: 3/4 of the short side wide and 2/3 of the long side high;
- landscape: the same fractions on the same sides, so 2/3 of the long side wide and 3/4 of the short
  side high.

In both orientations that is 3/4 of the short side by 2/3 of the long side.

Built. What is the owner's rule and what is my default:

- **The action (built).** The armed "Draw surface" chip has a second button, **Use a flat sheet**, in a row
  with Cancel (`SurfacePromptChip`, `surface.prompt.skip`, at least 48dp square, string
  `surface_prompt_flat_sheet`). The wording is mine, not owner-confirmed. It is offered only for a group's
  first, front surface: not when redrawing a group that already has one, and not for the side or top views.
  Cancel keeps its meaning (nothing changes). `EditorViewModel.onSkipSurfaceDraw` makes the surface; wrapping
  a layer in a new group and setting the profile is one undo step, as for a captured line.
- **A real surface (built).** The sheet is an ordinary group surface profile (`DefaultSurface.profile`,
  `:core:common`; the sizing is the pure `DefaultSurface.rect`), so painting, the rotation triangle, export,
  persistence and undo are those of a drawn surface. It is a flat profile: the top edge of the rectangle
  across z = 0, swept down the screen for exactly the sheet's height (`SurfaceProfile.reach`, a new optional
  field: with it `SurfaceMath.gridExtent` and `localBounds` report the rectangle itself, so the orbit pivot and
  rotation triangle use the sheet's true box (the orbit pivot is then n behind its centre, see "The orbit point"); without it a profile is unbounded, as drawn ones are).
  Every consumer of the sweep clamps to it (`SurfaceProfile.sweepLimit`): the mesh, the ray hit (so a touch
  below the sheet misses and paint cannot land there), the rendered reach and the visibility box. So with a
  full-canvas layer painted on it the surface is exactly width x height, and a layer's art past the sheet is
  cropped, not drawn. It is project format 14 (a 13 file has none and loads unchanged; an older build treats a
  14 file as newer and opens it read-only, like every earlier optional field). The puppetry plan names 14 for
  its `rig`; whichever lands second takes 15. A side or top view drawn later makes the surface a hull and
  `reach` is then not used. Only the guide grid reaches the usual 32-unit margin past the sheet; the mesh does
  not. "Ready-made
  surfaces" has no flat plane (those are outline packs), so nothing was reused.
- **From what the user sees (my default).** "The screen" is the canvas view's size (`canvasSize`) and the
  sheet is sized and centred from what is visible: its centre is the middle of the screen mapped back to the
  canvas (`CanvasHitTest.screenToWorld`) and its size is the screen fraction divided by the view's zoom. A
  square counts as portrait. The view's twist is not applied: the sheet stays axis-aligned in canvas units.
  A size or zoom that is not positive and finite gives no sheet (the chip says the surface is unavailable).
- **Centred: assumed, not owner-confirmed** (open point 7 stays open).
- **No recompute on rotation: my default, not owner-confirmed.** The surface is data once created: if the
  device is rotated afterwards, the sheet is not resized or moved.
- **Empty layers and groups keep the predefined sheet (decided with the coordinator).** For an empty paint layer (the
  definition "Adopting the view plane" uses: no source image, text, shapes, adjustment or 3D ink, no recorded stroke,
  no set pixel), one whose bitmap has no non-transparent pixel, or an armed group that holds no layer with art, "Use a
  flat sheet" builds the sheet above, exactly as before.
- **A layer WITH art gets an art-sized sheet (built; see "The art-sized sheet" below).** This replaces the earlier
  rule that offered Skip only when there was no art to place.

#### The art-sized sheet

Built, not device-verified. "Use a flat sheet" on a top-level layer that has art (a raster layer whose art is its
bitmap) makes the same kind of flat surface, wrapped in a new group in one undo step (`commitWrappedSurface`), but sized
to the art instead of the screen:

- **The art box.** The bounding box of the layer's non-transparent pixels (alpha byte not 0), found by `ArtScan.bounds`
  one bitmap row at a time off the main thread (no copy of the bitmap), with the same stale-answer guard as the
  empty-layer scan (it commits only if the layers, canvas size and arming are the same when it answers) and the same
  `IllegalStateException`/`IllegalArgumentException` "cannot tell" handling for a bitmap recycled or resized under it.
  A bitmap with no such pixel is an empty layer: the predefined sheet. Any non-zero alpha counts, however faint: a
  stray faint pixel stretches the sheet. That is deliberate (the owner sized the sheet to "the painted art" and a
  threshold would be a second rule they did not give); for the owner to confirm or change. Each side of the sheet
  also gets `ArtSheet.EDGE_PAD` (0.005 unit), so float rounding in the transform can never leave the art's first or last
  pixel a ulp outside the sheet or `sweepLimit`.
- **How the box maps to layer space.** Bitmap pixels become the layer's box coordinates (canvas units) through the frame
  (`ArtSheet.boxOf`: pixel `p` is frame pixel `p + origin`, and the frame fits the canvas uniformly, centred), then
  through the layer's OWN transform (offset, scale, Z turn: `SceneMath.model`), which is where the art is seen. The sheet
  is the axis-aligned box of those four corners (`ArtSheet.plan`). A layer that is only offset and scaled gets
  its art box, grown by `EDGE_PAD` on each side. A layer turned about Z by anything but a quarter turn gets the smallest axis-aligned sheet that holds the
  art, so it is larger than the art's own box in that case (the surface's unrolled axes are the group's, not the
  layer's). A side under `ArtSheet.MIN_SIZE` (16 units) grows about the art's centre, so a dot of paint still has a
  sheet. The sheet is in the plane z = 0, as the predefined one.
- **Nothing displaced, nothing cropped.** The group's pose is the identity and the sheet's first vertex is its top-left
  corner, so the layer is moved by minus that corner (its `offset`, in the same undo step) and the art then starts within
  `ArtSheet.EDGE_PAD` of the sheet's first vertex. The layer's art lands where it was seen (`SurfaceMath.flatSurfaceModel` maps each art corner
  back to the corner it had), and spans `[0, length] x [0, reach]` (within the pad), so `reach`/`sweepLimit` (= the sheet's
  height) never cuts it. Undo puts the layer, its offset and its place in the stack back as they were.
- **The orbit point (derived, not stored).** `n = ((w + h) / 2) / 2`, with w and h the sheet's width and height (the
  median of two values is their average; half of it). The point is the sheet's centre, moved `n` along +z (away from the
  viewer, BEHIND the sheet): the art swings about it like a card on a turntable. It comes from `OrbitPivot.ofSurfaces`
  (see "The orbit point"): a flat, bounded sheet counts as a slab `2n` deep behind its face, so the centre of the box that
  holds the surfaces is `n` behind the sheet's centre. The surface cannot tell an art sheet from the predefined one, so
  the same rule applies to EVERY flat bounded sheet, the predefined one included (its n comes from its own width and
  height: for a 1000 x 1600 screen, `(750 + 1066.7) / 4`, about 454, behind its centre). No field was added and nothing
  is stored (format 14; 15 is the puppetry rig's), so the point follows the layers: moving, resizing, adding or removing
  a sheet moves it, and undo and redo need no pivot handling. The same applies to every consumer (two-finger orbit, step
  buttons, Reset view, a newly adopted view plane): all read `OrbitPivot.of`. A project that holds a flat sheet saved
  by an earlier 14 build now orbits about the point `n` behind it instead of its centre.
- **Where Skip is still refused (the notice `HAS_ART`, "A flat sheet cannot be sized to this layer...").** Only where a
  displacing or cropping outcome would remain: a layer with text or shapes (the art is not its bitmap), a warp (the
  bitmap is not where it is drawn), a layer already placed in 3D (the sheet is flat at z = 0, so it would be flattened;
  the 3D slot does not arm such a layer for wrapping at all, so this is a guard), a surface-view image, an adjustment,
  3D-ink or model layer; a bitmap that cannot be read, or a canvas with no size (its own neutral notice, `NOT_READABLE`: "could not be read just now", not the refusal wording); and an armed GROUP that holds any layer with art (a group's art is
  not sized by this path). A layer inside a non-group parent keeps its existing refusal.
- **Not done.** No windowed profile was built (open point 8): the sheet starts where the art starts and the layer moves
  to meet it. The pivot is not recomputed when the layer or sheet changes.
- **Untested.** On a device: that the depth pass, painting on the sheet, the export frame and a co-op peer show an
  art-sized sheet exactly as the maths says (the maths and the view-model flow are unit tests; none ran against the
  GPU path); the scan time on a very large bitmap; a layer whose frame has grown past its bitmap; a Z-turned layer's
  larger sheet; and a layer whose bitmap is not what is drawn (instances that inherit a base).

### How the surface looks

Built (the appearance only; the document boundary is a later slice).

- **Fill:** the canvas background colour the user has set. That is `SettingsRepository.backgroundColor`,
  mirrored into `EditorUiState.canvasBackground` (the page fill `ArtboardPage` paints); the screen
  background is the separate fixed `Workspace` colour (`SurfaceLook.fill(canvas, workspace)`; if the two
  are equal the edge is outlined in the grid colour). The canvas and the screen background were already
  different colours in 2D, so the surface's edge stays visible without an outline.
  - *Where it sits:* a top-level surface group's fill (`SurfaceFill`) is emitted in one of two places.
    If the GPU depth pass paints that group's surface layers (`SurfaceMath.passFillGroups`: paint on a
    surface joins the pass even with the identity camera), the fill is emitted immediately before the pass
    at its anchor slot, in stack order with the other such fills, so it is under the paint the pass draws and
    above whatever is stacked below the anchor. A group with no visible painted layer in the pass (no paint, or only hidden or empty paint) is filled by `LayerStackNode` just before its own box, at its own place in the stack. Either
    way the page never overpaints it. The split inactive-layer render only applies when there is no pass, so
    only the second kind of fill occurs there. Known limits: a fill emitted at the anchor slot is under layers
    stacked between the anchor and its group, the same way that group's paint is; and with two surface groups
    both painted, the later group's opaque fill is emitted before the pass, so it is under the earlier group's
    paint where they overlap. `fillKey` copies a few small arrays per recomposition (cheap, unmeasured).
  - *Opacity:* the group's own opacity (times its animation frame alpha); the enclosing groups' opacity
    applies because the fill is inside their box. A hidden or zero-opacity group draws nothing (the mesh's gate:
    a valid surface, visible, not at zero opacity).
  - *Geometry:* `SurfaceMath.fillTriangles`, the sheet's triangles over the mesh's extent (a relief
    surface by its displaced mesh), clipped to the near plane exactly as the mesh lines are, and oriented
    alike so folds in perspective fill as a union. The triangles are recomputed only when
    `SurfaceMath.fillKey` (profile, model, camera frame) changes.
  - *Centre tile only,* like the mesh, in wrap-around mode.
  - *Not filled:* a multi-view group (a solid); a group nested inside another group (its canvas-space
    triangles would need the parent's placement; it keeps its mesh).
  - **Assumption for the owner to confirm:** a canvas colour that is not fully opaque gives no surface fill
    (a translucent colour would blend twice over the page; a transparent canvas has nothing to fill with). The
    owner said only "the canvas background colour".
- **Mesh:** the grid colour, drawn only if a grid is on. It replaces the cyan wire mesh (no dark
  under-line either). There is no user-chosen grid colour and no grid on/off setting today: the 2D
  reference grid (`InfiniteGrid`) is always on in one fixed colour, `SurfaceLook.GRID_ARGB`, the one source for both. So the mesh
  uses that colour, and "a grid is on" is the wire-mesh flag (`surfaceGridVisible`). The mesh is a 1.5 px line at that colour's low alpha with no under-line; its legibility over artwork is unverified. Adding a grid colour
  and grid toggle to settings is a gap, listed in the open points.
- **The wire-mesh button** ("#" on the rail, and the "Surface grid" menu check) is unchanged in
  place and wording, and now toggles the surface's grid overlay (the mesh) on and off; the fill is not
  affected by it.
- **Export:** neither the fill nor the mesh is exported. They are canvas overlay; PNG, PDF, share, the
  thumbnail and glTF still composite over the document's own background (`exportBackgroundArgb`) inside
  the document rectangle, and nothing in `SceneGltfExporter`, `DepthCompositor` or the wgpu path drew the
  cyan mesh, so none changed.
- **Document boundary:** not drawn in these projects (built, not device-verified): neither the page
  rectangle (`ArtboardPage`, the canvas colour's fill of the artboard) nor the frame outline
  (`ArtboardFrame`); the workspace colour shows to the edges and the surface fill is the only canvas colour.
  The boundary appears at export, as the export frame below. Projects with no 3D group still draw both.

  **Export frame** (`ExportFramePreview.kt`, `EditorViewModel.requestExport*`): in a project with a 3D group, a
  document export first shows the rectangle it keeps, with everything outside it dimmed, and Export / Cancel.
  Nothing is exported until Export.
  - *The rectangle* is `exportFrameRect`: the artboard, or, with a non-identity Crop-tab crop, the crop's
    rectangle of it (the one `DocumentCropRender.render` and `VectorScene.documentPage` cut). It is computed in
    the exporter's world (the display's pixel size, which is what the exporters composite in), not from the
    on-screen box, and has no viewport input: the exporter ignores pan, zoom and rotation. Assumption: the
    editor canvas and the display share an origin (the canvas fills the window).
  - *Where it is drawn:* the dimming and outline (`ExportFrameOutline`) are inside the camera container, in the
    same transformed space as the layers, so after zoom, pan, rotate or Reset view it still lands on the
    part of the artwork the exporter keeps. The Export / Cancel and message (`ExportFrameControls`) are a
    dialog window: modal to touch, the two-finger camera, the tap gestures and TalkBack, and Back cancels. They
    sit under the safe-drawing insets on a dark backing. The two-finger camera and the tap-undo/redo gestures
    are also switched off in code while it is up.
  - *Through it:* the menu's PNG; the Export panel's PNG / SVG / PDF with no frame selected; the poster PDF; a
    PDF of all frames when the project has no top-level visible frame (it falls back to the document page).
  - *Not through it:* a selected frame's export (crops to the frame's box); a PDF of all frames when there is
    at least one (a page per frame); Share (hands a Uri to the host); the animation export; glTF (whole layer
    quads and camera, uncropped, so no rectangle). A project with no 3D group exports at once.
  - *Content inside the rectangle may differ from the canvas view for SVG and PDF.* PNG and the poster PDF composite
    with the scene camera (`compositeToDocument(..., camera = sceneCamera)`), so they match the orbited view.
    SVG and PDF go through `designExportPage` -> `VectorScene.documentPage` (and `DesignRasterizer` for
    what vectors can't express), which take no camera: they draw the layers flat. The rectangle is right for
    every format; what is inside it is the canvas view only for PNG and poster. Not fixed here.
  - *Lifecycle:* the held export is dropped on a project switch and when the project stops having a 3D
    group (undo, delete). A request while another export runs is ignored; a second request while the frame
    is up replaces the held one; a second Export tap runs nothing.
  - *Same-frame taps:* the running-export guard reads `isLoading`, which the export sets a frame after Confirm,
    so it does not stop two requests in one frame; the second replaces the first and the held export runs once. A
    project with no 3D group is never held up by a running job (it exports at once, as before the preview).
  - *Dialog insets:* the controls use `safeDrawing` insets inside a dialog window with `decorFitsSystemWindows =
    false`; how that reports insets below API 30 and with a cutout is not checked.
  - *Not covered by tests:* the scrim's pixels; the exported bitmap matching the rectangle on a device; the
    dialog's inset handling on a device with a cutout; TalkBack's focus being contained by the dialog window
    (platform behaviour, not exercised); a touch on a node beneath the dialog (Compose's test injection reaches each root directly, so a window-level
    block cannot be exercised on the JVM); the call sites passing `uiState.exportPreview` to the gate modifiers
    (the gates themselves are tested); the real `EditorScreen` placement of the outline (the shared
    `viewportCamera` modifier and `ExportFrameLayer` are tested in a container of the test's own);
    the host's rails and view buttons are in the activity window, so the dialog blocks them by touch, but this is
    not device-checked; Compose rendering of the hidden page and
    outline in 3D projects (only the predicate `showsDocumentBoundary` is tested). Not device-verified.

### Open points

1. Does "3D project" include a project whose only 3D content is a 3D strokes layer or a model layer,
   or only one with a 3D group (a group with a surface)? The owner's wording was "3D groups".
2. How the user chooses not to draw a surface: built as "Use a flat sheet" on the arming chip; the wording is not owner-confirmed.
3. Orbit and reset-view need button or accessibility equivalents, as the sheet stages got; not yet
   designed.
4. (Settled: the pinch zooms about the fingers' midpoint.)
5. Resolved above: canvas colour is `canvasBackground`; the button stays and toggles the mesh. Still
   open: a user-chosen grid colour and a grid on/off setting do not exist.
6. Export framing: built (hidden while editing, shown as the export frame; see "Document boundary" above), unverified on a device and in the gaps listed there. Treat as open until it is.
7. Whether the default surface is centred in the viewport (assumed; built that way).
8. (Settled: built as an art-sized flat surface, not a windowed profile; see "The art-sized sheet" under "The default surface". Untested parts are listed there and in the PR.)

## 2D and 3D layers

The two stay distinct, and 3D is a property of the layer's **group** (spec "Group surfaces"
below). A layer is a **3D layer** (`Layer.is3DIn(layers)`) when any of these holds:

- it is paint on a group surface: its group (or, for a group, the group itself) has a profile;
- it is a 3D strokes layer or a 3D model layer.

The per-layer 3D flag (`plane3D`) is retired: it is no longer saved, and a project that still has
it loads those layers as flat (`OverlayLayer` and `Layer` skip the unknown key; a co-op peer's
`LayerExtProps` does too). Old projects are not migrated. A layer can still be *placed* in 3D
(`Layer.isPlacedIn3D`: a depth or an X/Y tilt, from an older project or view-plane adoption) and is
then drawn through the scene as before, but it is not a 3D layer for the rail and gestures.

On the layers rail the tile outline tells them apart: the active 2D layer wears the rail's
selected-tool accent (pink), the active 3D layer cyan (the X-axis colour), any other 3D layer white,
and any other 2D layer no outline. A profiled group's tile and every tile on its surface are 3D. A 3D tile's
TalkBack label also says "3D".

Groups keep their tile on the layers rail, open by default (a new group, and a group in a project that never toggled it, show their layers; only a collapse the user made stays closed). Every tile on a profiled group's surface is 3D, and so is the group's own.

The rail's **axis slot** (the slot the old 3D button had) is always there:

- on a 2D layer (its group has no profile) it reads **3D**; a tap arms "Draw surface" for the
  layer's group (the group itself when a group is active). Arming changes nothing in the document:
  the slot turns bright white and reads **Draw**, and a chip at the top of the canvas says "Set up
  this 3D group: draw its shape. Drag one line across the canvas. That line becomes the group's 3D
  surface. Your artwork is not changed." with a Cancel button, until a line is captured or the user cancels (Cancel, the Back key, or a tap on the slot).
  A layer in no group is wrapped in a new group only when the line is captured, the wrap and the
  profile together being one undo step. A line too short to be a curve leaves the chip up, says so,
  and stays armed. Once captured the chip says the surface is set and how to undo it, and the slot
  becomes the surface-drawings chooser (below). A tap that cannot arm (no layer, a locked layer, a layer that cannot take a
  surface) says why and what to do in the same chip;
- on a layer of a **surface group** (the layer is paint on a group with a surface, or is that group) it
  still reads **3D** but no longer chooses an axis: a tap opens the **Surface drawings** chooser
  (`EditorUiState.surfaceViewsMenuOpen`), a menu of the surface's views, each entry entering that view's
  drawing through the existing flows: "Draw surface" (front; `onArmSurfaceDraw`), and "Draw / Redraw side
  view" and "Draw / Redraw top view" (`onArmSurfaceView`; not offered for a photo's relief surface, as in
  the layer menu). The rotation triangle (below) turns the group;
- on any other 3D layer (a tilted plane, 3D strokes, a model) it shows the current axis as its letter in
  the axis colour (X cyan, Y pink, Z green); a tap steps X -> Y -> Z, the same state a double-tap steps. An
  "Axis:" chip confirms a change briefly, then clears.

On a 2D layer the camera can still have been moved by 3D work elsewhere; the document renders
through it as always, but a twist only ever rotates the canvas.

Two fingers act on the view unless the Move / Transform tool is in hand on a 3D layer, so no tool
can strand the user. The Move tool acts on the active layer wherever the fingers land; that keeps it
predictable on a tilted or off-screen plane, where hit-testing would be ambiguous. Each completed
two-finger gesture on a layer is one undo step; view gestures are never history (the camera is
saved with the project when the gesture ends). Every gesture follows the fingers continuously: the
canvas gesture handlers are keyed so a state write on every step never restarts them.

### Rotation triangle

With an **active 3D surface group** (`EditorViewModel.rotationGizmoVisible`: the active layer is paint on, or
is, a group with a surface, and it is not locked) a small right triangle (`RotationGizmo`, drawn at half size,
`GIZMO_DEFAULT_SCALE`) sits in the bottom corner on the brush hand's side (`RotationGizmoDock`, from
`EditorUiState.isRightHanded`): **bottom-right for a right-handed user, bottom-left for a left-handed one**. It is
in from the screen edge by a margin plus the system gesture, navigation-bar and cutout insets, so it stays off the
gesture areas, and it stays clear of the bottom sheet's handle in the middle. It does **not** follow the sheet: it
is the same with the sheet open, closed or dragged, and is composed after the sheet so it is above it. The layers
rail, which is on that side, stops above it while it shows. Only the glyph is small: each touch band is still 48dp.
Its right angle is bottom right: the **bottom leg is X** (cyan), the **vertical leg is Y** (pink), the
**hypotenuse is Z** (green), the rail's axis colours.

- Drag along an edge to turn the group about its **own** axis (body-fixed, through the orbit pivot:
  `turnedAboutPivot`, `SceneMath.rotatePoseAbout`). Sign: right along the bottom, up the side and up-right
  along the hypotenuse are **positive**.
- The angle is the finger's displacement projected on the edge's direction over the edge's length, times
  180 degrees (`RotationTriangle.angle`): the full length is 180. There is no handle and no end stop, so
  rotation accumulates across swipes (two swipes make 360); a backward drag reduces it.
- A drag **locks** to the edge it began on. An ambiguous touch near a vertex picks the nearest edge by distance
  to the edge segment (ties: bottom, side, hypotenuse).
- Each edge has a touch band 48dp wide centred on it. Only the bands take pointer input (the input box is
  clipped to their union): the interior and the rest of the box pass strokes through to the canvas.
- A live readout ("X 37°", the drag's angle so far) shows while dragging.
- A whole drag is one undo step (the first turn pushes history; the end saves and emits the co-op
  transform), through `onRotationGizmoTurn` / `onRotationGizmoEnd`.
- Accessibility: each edge is a node ("Rotate X" ...) with custom actions "+15°" and "-15°"
  (`onRotationGizmoStep`, one undo step each).
**Fit** on the rail is there whenever the 2D view or the 3D camera has moved, and puts both back to
the identity. **Reset plane** in a placed layer's menu puts it back on the identity plane (depth 0,
no X/Y tilt) as one undo step.

Nothing snaps back on its own. The camera stays where it was left and every layer keeps its
placement, rendered in perspective. When the active layer is tilted or the camera has moved, each
touch is mapped onto the layer through a ray-plane intersection (below), so brushes and tools work
unchanged on the tilted plane.

The old `Layer.is3D` field, its Transform-panel switch and its reducer intent are deleted, and so is
its successor `plane3D`, so a co-op peer or snapshot from an older build that still sends `is3D` decodes
cleanly, because `Layer` skips unknown keys. The Transform panel shows the X, Y and depth fields
whenever the layer is placed in 3D or the camera has moved.

## Data model

All in `:core:common`, package `com.hereliesaz.graffitixr.common.scene`, pure Kotlin.

Scene coordinates are the canvas box's pixels (the same "world" space every layer offset is in,
before the 2D viewport): x right, y down, z away from the viewer (right-handed). Depth 0 is the
picture plane.

- **Layer plane.** A layer's existing `offset` (x, y), `scale` and `rotationX/Y/Z` (degrees), plus
  a new `positionZ`. A point `L` of the layer's full-canvas box maps to the scene as
  `P = C + (ox, oy, z) + R * scale * (L - C)`, `C` the box centre, `R = Rz * Ry * Rx` (the stored form; the sliders turn about the body axes, see "Body-fixed axes"). With
  `z = rx = ry = 0` this is exactly the 2D transform the layer always had. Groups compose: a
  child's plane is its parent's model times its own.
- **Scene camera** (`SceneCamera`): `yaw`, `pitch` (radians), `distanceScale` and a target offset
  `panX, panY, panZ` (scene px). The focal length is `F = (H / 2) / tan(fov / 2)`, fov 45 degrees.
  The eye is `distanceScale * F` from the target. The default camera (all zero, distance 1) looks
  straight down +z from `(W/2, H/2, -F)`, which projects depth 0 onto the canvas pixel for pixel:
  that is why identity is exact, not approximate.
- **3D strokes layer.** `Layer.spatial: SpatialLayerContent?` (strokes + guide). The layer stays
  `LayerType.RASTER` on disk with no bitmap, the way adjustment layers do, so an older build opens
  it as an empty layer. Stroke coordinates keep the old spatial units; one unit is `F / 3` scene
  pixels, with y up and z toward the viewer, so the old sketch's default orbit camera (distance 3,
  pitch 0) is exactly the new default scene camera.
- **UI state.** `EditorUiState.sceneCamera` and `activeRotationAxis`. Written only through
  `updateUi` / the reducer. Camera moves are view state, not history, like the 2D viewport.

## 3D model layers

An imported `.obj` is a layer in the one space, not a separate window (the floating 3D window and
its "Add view to canvas" snapshot are retired). The 3D toolbox's **Import 3D model** entry, which
used to open the window, now imports into a layer.

- **Import.** `EditorViewModel.importModelLayer` reads the OBJ (at most 64 MB, the same cap the co-op
  path uses; loading a saved project's OBJ and sending one to co-op peers are capped the same way) and
  parses it (`ObjParser`). The parse refuses, with an `ObjParseException`, a line over 64 K characters
  (measured before it is copied, so one 60 MB line or a file with no newline is never split; `\n`, `\r\n`
  and a bare `\r` each end a line), a face of more than 16,384 corners, more than 400,000 triangles, and
  more than 1,200,000 positions, uvs, normals or emitted vertices (three per triangle, the most a
  flat-shaded or UV-seamed mesh emits). The corner limit is in practice also bounded by the line limit: an
  n-gon of about 10,000 six-digit corners is some 70 K characters, so it is refused, where the earlier
  parser accepted it. Its accumulators are primitive arrays, each capped at its limit, and an unboxed
  open-addressed map. Memory at the caps, computed in decimal MB (10^6 bytes) with GC slack ignored and
  not measured on a device: 106.8 MB held at the end of the parse (38.4 for the position, uv and normal
  lists, 38.4 for the emitted vertices, 25.17 for the map, 4.8 for the indices), and 139.7 MB at the last
  growth step of the vertex list (71.95 for the old 33.55 and new 38.4 arrays together, plus 38.4 + 25.17 +
  4.19), beyond the text. `importModelLayer` also holds the file's bytes (up to 64 MiB = 67.1 MB) and its
  text (the same, or twice that with non-Latin-1 characters), so the import path peaks near 274 MB (341 MB
  for such text), on a build that asks for a large heap. A typical model is a small fraction of that. A saved project whose model is over a
  cap (or otherwise unreadable) keeps its layer and its file, and a notice names the layer and the reason;
  a co-op host whose model cannot be sent (too large, or its file missing from the project) and a guest
  whose peer's model cannot be parsed are told the same way. A model whose saved paint is incomplete (a
  truncated PNG whose header is intact counts) or cannot be decoded opens unpaintable (the paint file is
  left alone, never overwritten) and a separate notice says the paint, not the model, failed. The file is copied
  into the project folder as `model_<layerId>.obj`, and adds a layer on top with
  `Layer.model: ModelLayerContent`, which makes it a 3D layer: the rail shows the axis
  cycler and the layers rail the 3D outlines. Its plane is placed by the view-plane rule
  (`SceneMath.viewPlanePose`): facing the camera through its orbit target, at the camera's distance
  scale. One undo step.
- **Framing.** `ModelSpace.meshToLocal` puts the mesh's bounding centre on the layer's box centre
  and its bounding radius at 0.3 of the canvas's shorter side, turning the OBJ's y up / z toward
  the viewer into the scene's y down / z away (a half turn about x, not a mirror). The layer's plane
  pose then places that box like any layer's, through its group chain.
- **Controls.** Exactly those of any 3D layer (the table above): two fingers move the view; with the
  Move tool they slide the model, pinch it in depth and twist it about the current axis, one undo
  step per completed gesture; double-tap and the rail's axis slot cycle the axis. Nothing here is
  model-specific: the Move tool acts on the layer's plane pose.
- **Painting.** With the brush or eraser on an active model layer, one finger paints its surface
  (`ModelPaintCanvas`, above every tool surface, so a 2D tool never also paints). Each sample casts
  the camera ray through the touch into mesh coordinates (`ModelSpace.ray`, the inverse of the
  layer's mesh-to-scene map), finds the nearest triangle and its UV (`MeshPicker.raycast`), and
  stamps dabs from the previous UV into the texture (`PaintableTexture`). A move is sampled along the
  way (`ModelSpace.pickAlong`, every half brush width, at most 8 samples), so a flick across a
  silhouette is not joined through paint the finger never crossed: a sample that hits nothing ends
  the run. Consecutive hits are joined only within one UV island (`UvIslands`: triangles continuous
  in position and UV across a shared edge, matched by the value of both its ends so a triangle soup still forms islands and a one-corner contact does not); a step
  across an island boundary paints only its destination (`TexturePaint.stampsAlong`), and so does
  one that drops onto a different stretch of the same island, such as a fold behind the front panel
  (`SurfaceContinuity`: the UV distance is bounded by the 3D distance times the UV-per-length). Each dab is
  clipped to its island's triangles, so it cannot bleed onto the texels of the island laid out beside
  it (an island whose UVs tile the texture is left unclipped). The brush's on-screen size is
  converted to texels at each hit (`ModelSpace.texelsPerPixel`: the triangle's texel area over its
  projected area; where that is degenerate the stroke keeps its last radius,
  `TexturePaint.radiusTexels`). The dab takes the 2D brush's settings as the 2D pipeline resolves them (`SurfaceBrush`): hardness is one minus the feathering, flow is per-dab strength (stamp brushes only), and opacity caps the whole stroke's coverage however often it crosses itself (the stroke is painted on a layer of its own and composed onto the pre-stroke surface at that opacity). A second finger ends the stroke. The 2D paint pipeline is not touched.
- **Selection.** Tap-select and the one-finger move's hit check cast the tap onto the model's
  surface (the same ray as painting), ranked by the depth of the surface hit; the selection outline
  is the mesh's framed x/y bounds on the layer's plane.
- **Flatten and composites.** A composite with no camera still uses the scene renderer, at the
  default camera, when a model or 3D strokes layer is present; Flatten All passes the scene camera;
  under a moved camera it renders at the canvas size and puts the flattened layer on the view plane
  (`SceneMath.viewPlanePose`), whose box projects onto the canvas pixel for pixel, so the result
  shows exactly as the layers did rather than projected a second time.
- **Paint revisions and undo.** The paint is `ModelLayerContent.texture`, a `ModelTexture`: one
  immutable revision, compared by identity. A stroke paints a copy (`EditorUiState.modelDraft`,
  which the depth pass draws in its place), and on lift that copy becomes the next revision. The
  stroke's first dab pushes an ordinary layer-list undo entry, so undo and redo swap revisions by
  restoring the layer, with no pixel replay. A revision only history still holds is compacted to PNG
  off the main thread (a 1024 texture is 4 MB of pixels and usually a few KB of PNG) and decoded
  again if undo brings it back.

### Rendering choice

Models are drawn by the existing depth pass (`DepthCompositor`, GLES 3), as members alongside 3D
strokes, rather than by the retired window's GLES 2 `ModelRenderer`. The depth pass already owns
the one depth buffer that planes and ink are tested against, so a model drawn there occludes and is
occluded per pixel with no second buffer to reconcile; a separate GL surface could only be stacked
above or below the whole pass. The shader is the old renderer's (paint over a base colour, a
headlight so no side is lost in shadow), ported to GLES 3 and fed the scene camera's clip matrix.
The mesh is uploaded once per mesh, the texture once per revision (or per dab while painting, under
the bitmap's lock so an upload never tears). Opaque models are drawn with the ink, before the
planes, so translucent plane pixels blend over them. Translucent members, a model or a plane whose
opacity is below 1, are drawn last in one pass, farthest first, depth-tested but without depth
writes, so each blends over whatever is behind it, translucent or not. A flat document without models still has no
depth pass.

## Render path

`SceneRenderPath.sceneLayerIds(layers, camera)` decides per top-level subtree:

- **Fast path (identity).** Camera at default and nothing in the subtree placed in 3D: the layer
  draws through the existing `graphicsLayer` code, untouched. No matrix work and nothing on the GPU
  changes: the only addition is one scan of the layer list for a 3D placement when the layers
  change, and a set lookup per layer that returns the 2D path.
- **Scene path.** Otherwise every node of the subtree draws with its plane-to-canvas homography.
  The `graphicsLayer` keeps only the RenderNode; `Modifier.scenePlane` concatenates the node's 3x3 homography (relative to its parent's,
  `H_parent^-1 * H_self`, so nested groups compose exactly) onto the canvas, after clipping to the
  half-plane in front of the near plane (a plane crossing the eye cannot be drawn as one
  homography, so the part behind the eye is clipped away in the layer's own coordinates). After
  the homography it applies the node's opacity and blend mode (a `saveLayer` in the plane's own
  coordinates, reaching four box sizes past it, instead of the `graphicsLayer`'s offscreen buffer,
  which is the flat box) and a frame's clip box. So a blend-mode layer, a clipping mask and a
  clipped frame follow their 3D placement.

**Why HWUI, not a wgpu pass.** Layers are composited by Compose's RenderNodes (HWUI, on the GPU)
today; the wgpu engine paints strokes into layers and presents the live stroke, it does not
composite the stack. A perspective `Canvas.concat` on a hardware canvas is a GPU draw of the
texture HWUI already caches for each layer bitmap: no upload, no copy, and every existing layer
feature (blend modes, clip-to-below, colour filters, opacity, groups, frames, the crisp zoom
overlay) keeps working because it is the same draw with a different matrix. A wgpu compositor
would have to upload every layer into wgpu and re-implement all of that, for no gain in a 2D
document and with new cost on the identity path, which the constraints forbid.

The wgpu **direct display** overlay presents the live stroke into a flat SurfaceView, so it is used
only for a layer on the fast path; a stroke on a scene-path layer previews through Compose (the
live bitmap drawn in perspective). The crisp zoom overlay is likewise fast-path only.

## Per-pixel depth

`DepthComposite.plan(layers, camera)` picks the layers drawn by one GPU depth pass
(`DepthCompositor`, GLES 3, in a transparent `TextureView`):

- every visible 3D strokes layer and 3D model layer, at its whole chain's placement, so a group placed in 3D moves
  the ink, and its opacity multiplies in;
- every scene-path paint, text or vector layer that composites normally (SrcOver, no clip to
  below, not an adjustment) inside groups that do too: normal blend, full opacity, and for a
  frame neither a clip nor a visible fill. A translucent group composites its children as one
  isolated image (overlaps at the group's opacity, not compounded per member), and a filled
  frame paints its fill over anything pulled out of its subtree, so their descendants stay in the
  stack.

Layers that need a blend mode, a clipping mask, an adjustment or a frame clip cannot take part in
a depth buffer, so they stay in the HWUI stack through their homographies (above). The pass draws
at the stack position of the first top-level subtree holding a member; LayerStackNode skips the
members. A non-member stacked between two members therefore draws above or below the whole pass,
by where its subtree sits relative to the anchor.

Inside the pass the ink is drawn first, then the planes farthest centre first, with depth test and
depth writes. The test makes crossings exact for opaque pixels; the order lets translucent pixels
blend over what is behind them. Texels with no alpha are discarded, so a layer's empty area hides
nothing. Where two planes cross inside a semi-transparent edge, the nearer edge occludes rather
than blends. A layer's colour adjustment runs in the fragment shader as the same 4x5 matrix the
canvas uses. Vector layers are rasterized once per shape list and canvas size
(`VectorRasterCache`; the image's placement is centred on the canvas, so a resize re-rasterizes).
The raster's bounds take each path node plus its Bézier handles (handles are node-relative
offsets); a cubic stays inside its control hull, so no curve is clipped.

Cost: a member's bitmap is uploaded to its texture only when its identity, `generationId` or live
stroke version changes. While a stroke is painted on a member, the pass uploads the live bitmap
each frame in place of HWUI uploading it for the Compose draw, which no longer happens for that
layer. The wgpu direct display stays fast-path only, as before. The pass does not exist for a flat
document without 3D ink.

## Inactive-layer cache

While the user draws on the active layer, every other layer is composited into at most two
temporary GPU renders: one for everything behind the active layer, one for everything in front of
it. Each frame then draws behind render + the live active layer + front render. The pure model is
`InactiveLayerCache.kt` (`:core:common`): `SplitCache` (the two slots and their keys), `RestGate`,
`StackSplit` and `DepthSplit`.

- **2D (flat document), stack order.** `StackLayerCache` splits the top-level list around the
  subtree holding the active layer. The behind render holds the page, the grid and the subtrees
  under it; the front render the subtrees over it. Each is an offscreen Compose `GraphicsLayer`
  (an HWUI hardware layer), recorded through the view transform and drawn back through its inverse,
  so its texels are screen pixels and zoom or rotation samples each layer bitmap once, as the
  uncached stack does. HWUI re-renders a render only when something in it is damaged. A side is
  cached only when every layer in it is plain source-over (no blend mode, clipping mask or
  adjustment, also inside groups); otherwise that side draws directly, since in an isolated buffer
  a blend mode would see a different backdrop. The active layer may have any blend mode: it blends
  over the same pixels either way. Not used for wrap-around, Animation Assist, a document crop or a
  scene-path stack.
- **3D (the depth pass), depth order from the current view.** `DepthCompositor` classifies the
  other members against the active one (`DepthSplit`): for an active plane, by which side of its
  plane each member lies on (the eye's side is in front); for active 3D ink or a model, by depth
  range. Each render is an RGBA8 texture drawn with its own depth attachment, so its members
  occlude one another exactly as uncached; the depth attachment is freed straight after the build.
  It is not needed when composing, because nothing behind can reach the eye's side of the active
  plane and nothing in front can be occluded by it: the active layer draws over the behind render
  with a fresh depth buffer, and the front render is drawn over both. If any member straddles (or
  lies in) the active plane, or the uncached draw order (opaque first, translucent after) does not
  put every behind member before the active layer and every front member after it, the frame is
  drawn uncached.
- **Invalidation.** A render is dumped (its texture freed) and rebuilt lazily, on the next frame
  of a stroke, when another layer becomes active, when any layer in it changes content,
  visibility, opacity, blend mode, transform or order, or when the view (2D pan, zoom and rotation;
  3D camera) changes. While the view moves the cache is bypassed; it is rebuilt on the first frame
  the view is the same as the frame before. A canvas resize does not rebuild: the renders are drawn
  scaled into the new viewport (exact again after the next rebuild). Only a stroke in progress
  builds a render.
- **Exactness.** Behind render + active layer is bit-exact (in 2D under the page). The front render
  is the same image by associativity of source-over, but composited once instead of layer by
  layer, so an 8-bit intermediate rounds differently where translucent front layers overlap: at
  most 1/255 in the emulated GLES pipeline and 3/255 in Skia's raster pipeline, measured.
- **Memory.** One render is width x height x 4 bytes of the canvas viewport (about 10 MB at
  1080 x 2400); two at most. A 3D build also holds a 24-bit depth buffer of the same size until the
  build ends.

## Painting on a tilted plane

The brush, fill and clone surfaces hand the view-model "world" points (screen with the 2D
viewport undone). When the active layer is on the scene path, `SceneMath.worldToLayerFlat`
first casts the ray from the eye through that point, intersects the layer's plane, takes the hit
in the layer's box coordinates `L`, and returns `C + (ox, oy) + Rz * scale * (L - C)`: the point
the existing pipeline would have received had the layer been flat. Every brush and tool then runs
unchanged (`mapScreenToBitmap` undoes exactly the 2D part). A ray that misses the plane (parallel,
the plane behind the eye, or a hit nearer than the near plane, which is the eye lying in an edge-on
plane) yields no point. The brush skips that sample (`PlaneStrokeFeed`): there is nothing of the
layer under the finger, and painting the plain world point would put the paint where the layer sat
before it was turned. The stroke starts at its first sample that hits, and a stroke that never hits
is cancelled, so a layer seen edge-on takes no paint. A fill or clone tap that misses still uses the
plain world point.

A Jetpack Ink stroke follows the same rule. Ink shows the stroke live in screen space (the viewport
undone, nothing more), so it stays under the finger; when it lifts, `commitInkThroughPlane` casts each
sample onto the plane through `ActivePlaneMapping` by the brush footprint (below: the ray, else the
nearest surface point the brush radius reaches; `ActivePlaneMapping.inkSegments`), cuts the stroke into
the runs that land (`planeSegments`, which is `PlaneStrokeFeed`), rebuilds an Ink stroke from each run's
flat points and commits the runs as one undo step. On a multi-view hull the runs are per view image,
cut at the creases exactly as `ViewStrokeFeed` cuts a Brush stroke, each committed into that view's
paint image and chained into the one undo step. An empty layer under a moved camera adopts the view plane first, as
for the other brushes, and a stroke with no hit commits nothing and puts the plane back. On a flat
layer the stroke is committed as it was.

## Group surfaces

Layer groups own surfaces. Every group (`LayerType.GROUP` or `FRAME`) has an optional surface
profile, `Layer.surface: SurfaceProfile?`; none means flat 2D, exactly as before, through exactly
the same render path. The layers inside a profiled group are paint on its surface: they stack, hide
and take their opacity as in 2D, all on the group's shape. The model is `SurfaceProfile.kt`
(`:core:common`, `SurfaceMath`).

- **Drawing the profile.** "Draw surface" in a group's layer menu arms the next stroke
  (`EditorUiState.surfaceDrawGroupId`; the capture surface lies over every tool and paints
  nothing). That stroke, drawn in the current view, is the profile: each point is cast from the eye
  onto the view plane (facing the camera through its orbit target, so at the default view it is the
  picture plane exactly under the stroke), simplified (Ramer-Douglas-Peucker, 1.5 px, at most 256
  vertices) and stored in the group's box-local space, with the view direction as the sweep. A
  straight stroke is one plane; an L is two. One undo step. A tap (under 4 px) changes nothing and the prompt stays up.
- **No depth limit.** The surface is the profile swept along the view direction away from the
  viewer, `P(u) + t * sweep`, `t >= 0`, without bound. There is no extent or depth setting: distance
  is just zoom, and each layer's own pixel bounds limit what shows.
- **Painting.** A stroke paints the active layer, mapped onto its group's surface by ray
  intersection (`SurfaceMath.hit`, one planar strip per profile segment, the nearest hit in front
  of the near plane). Paint maps by arc length: the surface's unrolled coordinates `(u, t)` are the
  group's flat box coordinates, so a stroke does not stretch however the profile bends. See
  "Painting by footprint" below for which samples land. A sample that misses the surface by more
  than the brush footprint is skipped and a stroke with no hits is cancelled (`PlaneStrokeFeed`, as
  on a tilted plane). Selection, tap-select and the lasso go through the same mapping
  (`ActivePlaneMapping`, `CanvasHitTest`). View-plane adoption never applies to paint on a surface.
- **Painting by footprint.** The Brush and the Eraser do not choose a plane or a segment for a
  stroke: wherever the brush footprint (a circle of the brush radius around the touch, on screen)
  lies on the layer's surface, the paint or erase lands there. A touch whose ray meets the surface
  maps exactly (any strip, across bends and creases; unrolled coordinates are continuous, so a
  stroke crossing from one flat part of the profile to another is one unbroken segment). A touch
  whose ray misses but whose footprint reaches the surface maps to the nearest point of the
  surface's projected outline (`SurfaceFootprint`, used by `ActivePlaneMapping.toFlatByFootprint`),
  so the stroke is not dropped sample by sample near an edge or when the finger grazes the surface.
  Only a footprint that touches the surface nowhere is a miss. There is no threshold choosing
  which plane a sample belongs to. Ink maps the same way when it lifts (its footprint is the Ink
  brush radius, world units), so a touch that just misses the surface keeps its samples. Flat and
  2D layers are unchanged.
- **A stroke lands under the finger (invariant).** Every sample a stroke accepts on a surface must
  show on the canvas, with the camera it was mapped with, within the brush footprint (plus 2 px) of
  its touch, and the stretch of stroke joining two accepted samples must too: the midpoint of the
  straight flat segment between them shows within the footprint of the segment between the two touches
  (distance to the segment, not to its midpoint: perspective moves a midpoint along the segment, never off it).
  `SurfaceStrokeGuard` states it; `PlaneStrokeFeed` and
  `ViewStrokeFeed` enforce it (`sampleOk`, `joinOk`), and `planeSegments` takes the same two. They are
  required parameters with no permissive default, so a stroke path cannot be built without the finger
  guard and the visibility check by forgetting to pass them; tests that do not exercise the guards pass
  `TestGuards` (test sources only), and `StrokeFeedGuardsRequiredTest` fails if a default returns. A failing sample is dropped like a miss; a
  failing join cuts the stroke there (a new segment starts at the sample), never painting across the
  part of the surface between. The cause it closes: a ray that skims a silhouette or fold hits a near
  face on one sample and a far one on the next, and the straight stroke between the two hits is
  painted across the surface between them, nowhere near the finger. `SurfaceStrokeGuard.dropped` and
  `.cuts` count what it rejects (debug). Cap regions (no 2D map back) and flat layers are not checked.
- **Paint reaches only what the camera sees.** A sample a stroke accepts on a surface must be in direct
  view of the camera it is mapped with: the ray from the eye to that surface point must first meet a
  surface at that point (within `SurfaceVisibility.toleranceAt`: 0.2% of the eye distance plus 0.5 scene
  px), over every drawn group's hull, relief or sheet together. A point behind a fold, behind another
  group, or on the far side of a solid is not painted even when the stroke path crosses it. It is the
  ray machinery (`SurfaceVisibility.nearest`), built once per
  `ActivePlaneMapping` from the camera it holds, so the camera is the one at stroke time; moving it and
  painting again judges visibility afresh. A group that is hidden, under a hidden group or at zero
  opacity is not drawn and does not occlude. Flat and 2D layers, and a tilted plane that is not a group
  surface, are unchanged. `flatOnFinger`/`viewFlatOnFinger` apply it after the footprint check, and
  `flatJoinOnFinger`/`viewJoinOnFinger` also test the middle of the joining stroke, so a fast stroke
  that steps over a hidden stretch is not bridged. An occluded sample is dropped exactly like a miss
  (`SurfaceStrokeGuard.visibleOk`, counted in `.hidden`, never in `.cuts`): it is not a gap in the
  surface. A stroke that goes behind a fold and comes back resumes as a new segment at the first
  visible sample (the feeds' existing gap rule), so nothing is painted between. Granularity is per
  sample: the paint pipeline stamps a dab in flat space and has no per-texel screen mask, so a dab
  whose centre is seen still spills onto hidden surface within its radius, and a dab whose centre is
  hidden paints nothing. Open sheets and reliefs are treated as double-sided (a sheet seen from behind
  is seen); a solid's far side is hidden by its near side. Cost: per sample one box-slab test per drawn
  group (groups the ray misses or enters beyond the point are skipped) plus one ray cast of each
  remaining group and a few small float arrays; per join one more. Cap regions are checked for
  visibility but not for finger distance.
- **Moving.** The Move tool and rotation triangle act on the group when the
  active layer is paint on its surface (`EditorViewModel.transformTargetId`); the surface lives in
  the group's space, so all its layers move with it.
- **Guide grid.** Lines along the profile (at sweep distances 0, 64, 128... box units, and closing at
  the grid's extent) and lines along the sweep (every 64 units of arc length, and at the profile's
  end). The grid is bounded to the surface: along the sweep it reaches the surface's box plus a margin
  of 32 units (`SurfaceMath.gridExtent`: the hull's box for a multi-view group, else the profile's own
  box, with a profile that has no depth given a cube's, its largest side), never to a far distance.
  It is projected through the
  scene camera and clipped to the near plane (`SurfaceMath.gridLines`), so they curve and
  foreshorten with the view and the user can trace strokes along them in more than 2D without
  moving the view. The "Surface grid" check in the menu of a group with a surface, or of a layer on
  one, and the "#" toggle on the tool rail (label "Surface wire mesh", selected state, there only
  while the active layer is on a surface), flip `EditorUiState.surfaceGridVisible` (view state, not
  history or project data; on by default, and since it was never stored there is no saved choice to migrate). A long press on the "#"
  opens its menu: Wire mesh on/off. It is drawn in the grid colour (see "How the surface looks"). Every visible
  group's surface shows its mesh (`SurfaceMath.gridGroupIds`): a group is skipped when it or a group above it is hidden
  or its opacity (times its enclosing groups') is zero, the orbit pivot's rule, and the mesh does not follow the active
  layer. A group has exactly one surface, so one mesh. It is
  overlay UI in the canvas's world space, never part of an export.
- **Wire mesh of a multi-view group.** The front profile's swept sheet is one face of a hull, so a group with a
  side or top drawing meshes the hull itself (`SurfaceHullGrid.segments`, called by `SurfaceMath.gridLines`): the
  iso-lines of every face's paint coordinates `(u, t)` at the grid spacing, over each view's sheet and the caps
  (`SurfaceHull.meshByView`, `capMeshByView`), plus the faces' open edges (creases and the box's rim). It stops
  at the solid and follows each view's drawing, not only the first plane's. Before, the mesh was always the front
  profile's sheet swept to the hull's depth, so side and top drawings never showed in it. A single-view group
  and a relief mesh as before. Every visible group shows its own mesh, each by this precedence on its own surface.
  *Precedence* (`SurfaceMath.gridLines`): photo depth first (a relief, including every group made by
  from a photo's depth estimate, `ReliefBuild.assemble`): its mesh is the relief's and the profile
  hull is neither drawn over it nor mixed with it, even if side or top views were drawn; otherwise the multi-view
  hull's skin (the one solid the three drawings define, each face trimmed by the other views, a shared crease
  drawn once); otherwise the single profile's sheet. Tests: `SurfaceProfileTest`, `ReliefSurfaceTest`, `ReliefBuildTest`.
- **Flatten surface** (the group's menu) clears the profile, as one undo step.
- **Nesting.** A layer's surface is its nearest enclosing group with a profile; groups between
  it and that group compose flat (their 2D transforms, in unrolled space).
- **Rendering.** A profiled group's subtree is on the scene path. Its visible paint, text and
  vector layers are depth-pass members whatever their blend mode, drawn by `DepthCompositor` as
  meshes: one strip per profile segment along the sweep, covering the image's unrolled bounds
  (`SurfaceMath.mesh`, `DepthScene.surfaceMesh`). A group's layers draw together in stack order,
  each pulled toward the eye by its stack position (polygon offset), so a layer above draws over the
  one below on the same surface and the surface still occludes itself. Layers the pass cannot draw
  (an adjustment) are not drawn flat either (`DepthComposite.surfaceIds`).
- **Group opacity and blend.** A group composites as one unit on a surface, as on the flat canvas:
  its layers merge first, then the merged image takes the group's opacity. Where two of its layers
  overlap this is not the same as fading each layer, which lets the lower one show through the upper.
  A group whose effective opacity (its own times its enclosing groups') is below 1 is an *isolated
  group* (`DepthComposite.isolatedGroups`). Its layers draw into an offscreen colour + depth target the
  size of the viewport, with their own opacity (times any groups between them and the profiled group),
  blend and stack order, exactly as an opaque group draws them to the screen. The target is then
  composited into the scene at the group's opacity as a full-screen pass that writes the target's own
  per-pixel depth (`gl_FragDepth`), so the group depth-tests against the other members like the
  surface itself (it draws as one item among the surface groups, in stack order, writing depth like the paint it replaces, so a group above it covers it by ordinary source-over alpha, including coincident groups). A fully
  opaque group takes no offscreen pass: its layers draw straight into the scene, as before.
  - *Target lifetime.* One target per isolated group, kept in `DepthCompositor` and reused as long as the
    viewport size is unchanged (a resize reallocates once). It is re-rendered only when its key
    changes: the camera, the viewport, or any member's pixels, placement, opacity or surface. A target
    not used in a frame (the inactive-layer cache served that side instead, the group became opaque or
    was removed) is freed at the end of that frame. Memory: an RGBA8 colour texture plus a 24-bit
    depth texture, 8 bytes a pixel (about 20 MB at 1080 x 2400), one per translucent group drawn
    this frame. Where the target cannot be made (incomplete framebuffer), the group's layers draw
    straight into the scene at the group's opacity each, the previous per-layer result.
  - *Blend mode.* A group blend mode other than normal is honoured when the profile is straight (one
    plane): the group's layers stay out of the depth pass and draw in the stack through the surface's
    plane with the group's blend (`ScenePaint`), the way a blend-mode layer on a straight surface
    already does. On a curved surface the blend mode is ignored (see Limits): the pass draws into a
    transparent view that is composited above the stack, so it has no backdrop to blend with.
  - *Nesting.* The unit is the profiled group. Groups above it fold their opacity into the unit's
    (exact when the profiled group is their only content); groups between it and a layer still fold
    theirs into that layer, so a translucent group nested inside a profiled group is not isolated.
- **Inactive-layer cache.** Split by group: the active group's other layers are behind or in front
  by stack order when its profile is straight; when it is curved the surface can fold in front of
  itself, so the split is ambiguous and the frame is drawn uncached. Other members are split as
  before (by the active plane when straight, by depth range when curved). An isolated group is one
  unit for the split: while a stroke is in progress inside it, the other members are split around the
  whole group, and the group itself is the live part: its target is reused and re-rendered each
  frame (one clear, its layers' draws, one full-screen composite; nothing is allocated). When the
  group is not the active one, its result is part of the behind or front render; the keys of both
  include the group's opacity and blend, so changing either rebuilds that render.
- **Co-op.** `LayerExtProps.surface` with `surfaceSent`; a peer that predates surfaces sends
  neither and keeps the receiver's.
- **glTF.** Paint on a surface is exported as its strips (`SceneGltfExporter.Quad.surface`), each
  with its layer's own material alpha (the layer's opacity times its groups'). glTF cannot isolate a
  group: its materials blend primitive by primitive, so where a translucent group's layers overlap the
  viewer fades each one separately, as the canvas did before groups were isolated. Matching the unit
  would mean baking the group into one texture; not done.
- **Persistence.** `OverlayLayer.surface`, project format 9; format 10 adds `SurfaceProfile.side` and
  `.top` (below). A version 9 file has neither and loads as the single-view surface; the migration only
  records the version.
- **Multi-view surfaces (format 10).** A group's surface can
  also be drawn from its side and top. The three outlines combine by *intersection*, like silhouette
  carving (`SurfaceHull`, `:core:common`): each drawn outline, closed by joining its last vertex to its
  first, is swept along its own view direction into an infinite prism, and the solid is every point all
  the prisms hold. The surface is that solid's boundary. The existing profile is the front view. A view
  left undrawn does not constrain. A view drawn with no area (an open curve or a line) stays a *thin
  wall*: the surface is that outline's swept sheet, trimmed to what the other views' closed outlines and
  the box hold (the closed views bound it; their own faces are not surface). One wall at most is kept
  (front, then side, then top); a lone open outline with no closed view is the single-view sheet; drawing a
  view again replaces it (`SurfaceProfile.withView`; redrawing the front keeps the side and top). Axes
  only one prism bounds are clipped to the box of the drawn outlines' vertices (padded to the widest
  side where an outline has no depth), so the solid spans a view only as far as the drawing does.
  A group with no side or top is untouched: same sheet, same render and paint paths.
  - *Painting.* `SurfaceHull.hit` returns the front-most surface point for a ray (entry, or the exit if
    the ray starts inside) with the carrying outline's unrolled `(u, t)` (arc length round the closed
    outline; signed distance along its sweep). `SurfaceMath.worldToSurface` is the world-to-surface API.
    Integration point: where `EditorViewModel`'s paint mapping calls `SurfaceMath.worldToFlat`, a group
    with `profile.hasHull` should call `worldToViewFlat` and paint the image of the view it returns.
  - *Paint storage (decided).* One paint image per view per layer (front, side, top painted
    independently, no atlas). `SurfaceHull.paintSize(view)` is a view's image size (arc length round its
    outline by the solid's reach along its sweep); `paintPoint` / `SurfaceMath.worldToViewFlat` map a
    touch to the view hit and the flat point in that view's image (through the same inner-chain unroll
    as `worldToFlat`).
  - *Paint per view (format 11).* Format 10 shipped in 1.63.0, so the per-view fields are format 11
    (migration 10 -> 11 only records the version). Each view's paint image is a hidden raster layer
    (`Layer.surfaceViewOf` = the paint layer, `.surfaceView` = the view) in the same group, created on the
    first stroke there: the layer panel and flat compositors skip it, removing the paint layer removes
    its images, and its bitmap file, stroke log and undo are those of any layer. Its flat space is the
    view's `(arc length, sweep offset)`; it is not moved by the paint layer's own transform. A brush or
    eraser touch maps by footprint (`SurfaceFootprint.worldToViewFlat`: the ray, else rings of rays out to
    the brush radius); a cap lands in its host view's image (see Caps). A stroke that moves from one view's
    face to another's is cut at the crease (`SurfaceViewStroke.crossing` brackets it by bisection, so each
    image gets its half right up to the crease and no gap is left) by `ViewStrokeFeed`; the segments are
    entries chained in `EditHistory` (`chainedToPrevious`), so undo and redo take the whole stroke. A
    single-view group and a flat layer take the old path unchanged. Co-op announces the image layers as
    ordinary layers (`Op.LayerAdd`), so a peer sees them as such.
  - *Caps.* A face of the solid that no drawn outline's sheet carries is a box face (a tilted sweep leaves a
    sliver of one; a lone closed outline leaves its two ends). It is drawn and painted like any other:
    `SurfaceHull.capMeshByView` tessellates it (the sheet's slab scan, no prism skipped, and only where the
    point is strictly inside every outline, so a box face lying in a drawn sheet is that sheet's and is never
    drawn twice). Rule: a cap belongs to the view that looks at it most squarely (its sweep most parallel to
    the face's axis, the earlier of front, side, top on a tie), and lives in that view's own image in a region
    of its own, to the right of the sheet: faces in `axis * 2 + side` order, a gap (5% of the box's widest
    side) before each. `PaintTarget` / `SurfaceHull.paintTarget` give a hit's view, point and `region`
    (0 the sheet, `1 + face` a cap); the mesh from `SurfaceHullMesh.textured` carries sheet and caps in one
    triangle list per view, so render, glTF and paint agree and there is no gap. Because two faces of one
    image can be far apart in space, a stroke that moves between faces (sheet to cap, or cap to cap) is cut
    exactly like one that moves between views (`SurfaceMath.ViewFlat.sameFace`). A ray that meets a plane two
    views share a rounding error apart is a tie (within `TIE_EPS`) and goes to the earlier view, the one whose
    patch the mesh emits (before, an oblique ray could name the later view, which draws nothing there).
  - *Paint made before the second view.* A paint layer on the flat front sheet that has pixels, and no front
    image yet, has them carried onto the hull when a group first gains a side or top: the front image is a
    copy of the layer with the same transform and frame and an offset moved by
    `SurfaceViewImages.carryOffset`, so every pixel shows where it did (the sheet's sweep distance runs from
    the outline's plane, the hull's from the solid's near end). The pixels are copied untouched, so the carry
    is lossless when the front outline has at most `SurfaceHull.MAX_MESH_POINTS` vertices (the hull
    simplifies a longer closed outline, shifting arc length by at most the capture tolerance). The layer keeps
    its own pixels, so "Flatten surface" gives the sheet back unchanged. It happens inside the one undo step
    that draws the view. v9, v10 and v11 projects load as before.
  - *Duplicating.* Duplicating a paint layer also duplicates its view images: new ids, linked to the copy
    (`surfaceViewOf`), placed after it, each with its own pixel file, stroke log and base
    (`SurfaceViewImages.duplicates`). One undo step takes the copy and its images back.
  - *Fill, Clone, lasso.* A lasso on a multi-view group is stored per view image (see *Selection on a multi-view
    surface*); a plain world-space lasso (one that reached no facing face) is cut to each image when used
    (`SurfaceViewSelection.forView`, which is also the cut a lasso is converted with: the ring is clipped to every
    projected triangle of the view's patches and caps, carried into the image through the triangle's own
    perspective-correct mapping, so a point is selected in an image exactly when it is under the lasso on
    the surface; a lasso crossing a crease is split across the images; triangles turned away from the viewer,
    judged by the solid's outer side against the eye, are left out, so the far side is never selected through
    the near one; a thin wall has no inside and keeps both sides. A face hidden behind another front-facing
    one is still selected). Brush, eraser, clone and fill
    take that per-image selection; a lasso that reaches nothing of a view makes it a miss. Fill (`onViewFillTap`)
    floods the view image under the tap and then **continues across creases**: `SurfaceHull.creaseLinks` pairs the
    paint points on either side of every face boundary (an edge no triangle of the same face continues past,
    in the image, so the two ends of a closed outline's unroll are linked too), and `SurfaceViewFlood.plan`
    walks them breadth first: a face is entered when the flood in the face before reaches a link point and
    the pixel on the far side (or within a pixel of it) is the colour being filled. Each face is flooded
    once, in the image of its view, bounded to its own face (`SurfaceViewSelection.faceRegion`, or the lasso
    cut to that view), and all of it is one gesture, so one undo step takes every image's part back. The
    tapped face is always filled. A face in a view with no image yet floods whole; the engine still fills
    only inside a layer's frame, as for any layer. Clone's source is a point of a view image
    (`cloneSourceView`); a clone stroke copies within that image and a touch on another view is a miss. The
    clone reticle is drawn where that point is on the surface (`SurfaceHull.pointAt`, the inverse of
    `paintTarget`). Moving selected pixels and Automatic (wand) selection are covered in
    *Selection on a multi-view surface* below.
  - *Selection on a multi-view surface.* **Representation:** a selection made on a multi-view surface (a **wand** tap
    or a **lasso**, any shape) is stored per view image: `Selection.surface` (`SurfaceSelection`: the paint layer, and per
    flooded face or lassoed view a `ViewSelection` of view, cap region and rings in that image's flat space, in recipe
    order). That is the camera-independent truth that persists (saved selections, undo's marquee snapshots).
    `Selection.rings` is its *footprint* for the current camera, so the marching ants, the clipping of flat layers and the
    hit test are unchanged consumers. The footprint is re-derived (`SurfaceViewSelection.footprint`: each region cut to the
    facing triangles of its view and carried to the canvas) whenever the camera, the surface or the canvas changes
    (`EditorViewModel`, one pass in flight, the newest pose wins; the projection of the last pose is remembered, and the
    same camera again recomputes nothing), so the selection follows the surface when the camera orbits. A face that now
    turns away contributes nothing and comes back when the camera does; the selection itself is kept. A face turned away
    when the wand was tapped or the lasso was drawn was never stored. Painting clips (`viewClip`) to the stored region of the
    view image cut to what faces the camera. Selections on a flat layer are not surface selections and are the code they
    were.
    **Lasso** (`EditorViewModel.onSelectionEnd`, `SurfaceViewSelection.composeLasso`): when the active layer is paint on a
    multi-view surface drawn in 3D, the lasso (freehand, rectangle, ellipse) is cut into the view images at the moment it is
    drawn with the lasso-to-view cut (`forView` per view that faces the camera, `fromLasso`) and folded in per image under
    New, Add and Remove. Parts of the lasso on faces turned away at that moment, and parts off the surface, are dropped, as
    for a wand (so a lasso is the part of the picture it covers on the surface, not its outline on the glass). A lasso that
    reaches no facing face is kept as drawn, a plain world-space region (the flat code), and a base that is a plain region
    (made on a flat layer, or such a lasso) stays plain under Add and Remove: only New, no selection, or a surface selection
    of the same paint layer fold per image.
    **Inverted** selections: an inverted selection is everything outside its recipe, so it is never a miss on a view. Its cut
    for a view (`forView`, `viewCut`) is the view's facing triangles with the recipe complemented ring by ring (an Add ring
    becomes a cut and a cut an Add), a plain selection bounded to what faces the camera like any other; a lasso that
    covers a view entirely selects nothing there and one away from it selects all of what faces. (An earlier version judged
    an inverted lasso by whether an additive ring reached the view, which it never does, so it was a miss on every view.)
    A part of a lasso dropped as turned away counts as outside under inversion, so it is selected where it comes into view.
    **Mixing** (`SurfaceViewSelection.compose`): Add and Remove of a lasso onto a surface selection, or of
    a wand onto a plain lasso, convert the lasso to per-image regions with the lasso-to-view cut (`forView`, faces turned
    away dropped) and append the regions in order, a hole staying a hole and the sense flipping under an inverted base;
    inversion and feather are the base's. Invert and feather are flags on the whole. **Move** drops the image
    regions (`translated`) and rebinds the moved marquee to the images (`rebind`), so a moved wand or lasso keeps following;
    undo restores the stored selection. A tapped triangle that faces away counts only in the footprint made at the tap.
    **Wand** (`EditorViewModel.onViewAutoSelect`): the flood runs in the image under the tap
    and continues across creases exactly as a fill does (`SurfaceViewFlood.plan`, same tolerance, a view with
    no image yet floods its whole face). Each flooded face is kept to its own rectangle of its image, traced
    (holes marked), and carried to the canvas by `SurfaceViewSelection.fromView`: the ring is cut to every
    triangle of the view (`SurfaceProjection`) and mapped through the triangle's perspective-correct inverse.
    Only triangles facing the viewer count (the far side is never selected through the near one), except the
    triangle under the tap; a copy of a surface that an earlier view's sheet already carries (two sheets in
    one plane) is skipped, so the area is the earlier view's. Flooded faces turned away contribute nothing.
    **Move** (`onSurfaceSelectionMove`, `SurfaceViewMove`): the selected paint slides over the surface as the
    viewer sees it. Each image's selected pixels are lifted (its `forView` cut, feather included); every
    pixel of every image receives what the surface shows `delta` behind it on the canvas (the nearest facing
    triangle there, in whichever image it belongs to), so a drag across a crease is resampled into the
    neighbouring image by the same mapping the surface is drawn with. A face square to the camera moves
    rigidly in its image; on an oblique face the paint follows the finger, so it is not rigid in the image.
    Edges: a pixel carried off the visible surface is lost (as one moved off a flat layer's edge), nothing
    comes in from beyond the surface, a pixel can only land on a face that faces the viewer, and pasting is
    inside the image's frame (it is grown to the visible canvas first), nearest-pixel sampling. Each image
    records a self-contained command (`StrokeCommand.movePatch`: the pixels it receives, in frame-relative
    coordinates, plus its lifted cut) so it replays without the others; the images are one gesture, so one
    undo step takes all of them and the marquee (`StrokeCommand.marquee`) back. The marquee moves by the
    drag. An inverted selection lifts everything outside the lasso that faces the camera and so moves the rest of the visible surface.
  - *Rendering the hull.* A paint layer on a group with a side or top view draws as one plane per
    view image (`DepthScene.hullPlanes`), each on its view's patch of the hull: `SurfaceHull.meshByView`
    triangles, with texture coordinates into the image from `SurfaceHullMesh.textured` (a vertex's
    `(u, t)` goes through `SurfaceHull.paintPointAt`, the mapping a brush touch uses, then the image
    layer's unroll and rect, so paint lands where it shows). The planes are depth-tested like any
    surface paint and take the paint layer's colour, opacity and place in the group's order, so group
    opacity, isolated compositing and depth against other layers and groups work as they do for a
    sheet. A face two views share is one patch, the earlier view's, and a ray hit is attributed to that
    same view. A group with one view renders exactly as before. The paint layer's own pixels (made on
    the sheet before a side or top was drawn) are kept, not drawn, on a hull, but a first side or top carries them
    onto the front image (above). A cap is drawn from its host view's image (above).
  - *Export.* glTF gets one textured mesh per view image (`hullQuads`), the same triangle-list form as a
    surface sheet; the no-GLES export fallback draws a patch triangle by triangle.
  - *Drawing the other views.* A group with a surface has "Draw side view" and "Draw top view" in its
    layer menu (labelled "Redraw ..." once drawn), and the "surface set" chip offers the same two
    buttons. Arming (`EditorViewModel.onArmSurfaceView`) turns the camera to look along the view
    (`SurfaceView.camera`: side a quarter turn, top the pitch limit) and stores the camera in
    `EditorUiState.surfaceReturnCamera`; the next stroke is that view's outline (a closed shape makes it
    solid, an open line a thin wall), merged with `SurfaceProfile.withView` (one undo step), and the
    camera goes back when the stroke is captured, on Cancel/Back, or if the group vanishes. The chip says
    what to do for each view and shows "Front: done. Side: not drawn yet. Top: ..." (words, not just
    colour). Switching between views while armed keeps the first camera to return to.
- **Outline assist.** When the finger lifts on a surface outline (the front profile or a side or top view),
  `OutlineAssist` (`:core:common`, pure Kotlin on the canvas points, run once, before `SurfaceMath.capture`)
  cleans the stroke, so a square drawn wobbly is a square. The raw stroke is committed first and the
  cleaned one as a second undo step, so one undo brings back exactly what was drawn. Settings has
  "Clean up surface outlines" (default on, `SettingsRepository.surfaceOutlineAssist`, mirrored into
  `EditorUiState.surfaceOutlineAssist`). What it does:
  - A stroke too small to be a shape (path or bounding diagonal under 12 px) is left as drawn.
  - *Closed* (ends meet within 12% of the path length and it encloses area): one that fits an ellipse
    (mean radial error 5%) becomes a circle (axes within 12%) or an ellipse (axis-aligned within 8
    degrees); otherwise it is split at its corners.
  - Corners are bends of 60 degrees or more at the scale of the stroke that are still 50 degrees or more
    over a 6% window, so a wavy side is not cut into straight lengths.
  - A piece with no point further than 5% of its length from its line becomes exactly straight, exactly
    horizontal or vertical within 8 degrees, and meets a neighbour at exactly 90 degrees within 12.
    Other pieces keep their shape with the jitter smoothed out ([1 2 1] passes, then simplified). Corners stay.
  - Closure is the profile's own rule (the ends meet within 12% of the path and `SurfacePayload.isClosedOutline`
    holds), not a shape-recognition threshold, so a thin bar is a closed bar. Tolerances scale with the stroke's
    size, or three times its thickness for a thin closed shape (never below 5% of the diagonal). A cleanup that
    changes the enclosed area by more than 25% to 33%, or moves an open stroke's end by more than 15% of the
    diagonal, is not trusted and the stroke is kept as drawn.
  - Sides are made parallel only within 3 degrees (so a trapezoid's legs stay as drawn), perpendicular within 12.
    Outline assist is tested on a library of exact clean shapes (bars from 1:1 to 1:40, V, W, trapezoids, arcs,
    3 to 8 sided regular polygons, circles, ellipses to 1:10, L, T, U) at several rotations, translations and
    windings: they come back with the same area (2%), closure, corners and bounding box (1% of the diagonal), and
    cleaning them again changes nothing.
  - A near-circular closed stroke whose radius swings once per side (5 to 8 sides, 2% or more, and following the
    polygon to within 5%) is a *regular polygon*, not a circle: pentagon to octagon keep their exact straight
    sides. Circles and ellipses are checked after that, so they still snap.
  - A closed result runs the way the stroke ran and starts at the point of the cleaned outline nearest the stroke's
    first point (a vertex is added there unless one is within 1% of the diagonal), because the paint's arc
    length starts at the profile's first vertex; cleaning, or undoing it, does not turn or flip the artwork.
  - Four right-angled sides are a rectangle, a square when the sides are within 12%; three sides within 10%
    of each other are an equilateral triangle.
  - *Symmetry:* a shape that mirrors itself about a vertical axis (closed: also horizontal) to within 2.5% RMS
    and 7% worst of its bounding diagonal is made exactly symmetric by averaging its two halves, corner to
    corner; an open stroke mirrors onto itself reversed. Lopsided shapes are left alone.
  - The result is checked the way the profile is: finite, at most `SurfaceMath.MAX_POINTS`, at least the
    capture's minimum length, and it still encloses area when the input did; otherwise the raw stroke is used.
    A closed result ends on its first point (the hull joins last to first).
  Every threshold is a field of `OutlineAssist.Config`, documented there.
- **Limits.** Blend modes and clipping masks inside a profiled group composite as normal
  (source-over) when its profile is curved, except that a layer's own blend or clip on a straight
  profile, and the group's blend on a straight profile, draw in the stack. glTF fades a translucent
  group's layers one by one (see above). The export's no-GLES fallback composites an isolated group
  into a bitmap the size of the canvas, then draws it at the group's opacity.

## Ready-made surfaces

A pack of surfaces is an azphalt `kind: "asset"` package whose assets are `type: "surface"` (azphalt
`spec/package-format.md`, "Surface format"). Each is a JSON file of profile curves only: a front
outline and optionally a side and a top outline, as points in the unit box. There is no depth image,
no texture and no paint, and no relief: a relief is a photo's baked depth grid, not parametric data, so
it is not carried (a surface that needs one is built from the photo, "Relief surfaces" below).

- **Discovery.** `HostAssetSupport.isSupported` is true for `AssetType.SURFACE`. `ExtensionRepository.installedSurfaceAssets()`
  lists every standalone, bundled `*.json` surface asset of every installed extension, named by its
  `params.name`; `loadSurface(id, index)` reads one through `ExtensionAssetReader` (digest-listed files only)
  and returns `SurfaceParse`.
- **Validation (untrusted input).** `SurfacePayload.parse` (`:core:common`) mirrors `@azphalt/azp` `parseSurface`
  rule for rule: at most 65 536 bytes, valid UTF-8 and JSON, nesting at most 4 (a non-recursive scan before
  parsing), no repeated top-level key, only the keys `format`, `version`, `name`, `front`, `side`, `top`,
  `format` `"azphalt.surface"` and `version` 1, `name` 1 to 64 characters without control characters, 2 to 256
  points a view, every number finite and within `[0, 1]`, and each view at least 0.01 long. A package's
  signature is not a reason to skip it: the file is parsed again on every read.
- **Insert surface.** "Insert surface" in the 3D toolbox (View, 3D) opens `SurfacePicker` (installed surfaces
  with their front outline). Choosing one calls `EditorViewModel.onInsertSurface`: a NEW top-level group named
  for the surface, with `SurfacePayload.toProfile` as its `surface`, made active, as one undo step (the same
  history, co-op and save path as drawing a surface). Nothing existing is replaced. The group is then an
  ordinary surface group: draw its side or top view, add layers to paint on it, flatten it, delete it.
- **Placement.** The unit box is scaled uniformly to 0.7 of the canvas's shorter side and centred. `x` and `y`
  map to the box's width and height (y down), `z` away from the viewer with the front at the picture plane.
  The front outline is the profile (sweep +z); the side outline lies at the box's middle in x (sweep +x) and the
  top outline at its middle in y (sweep +y), so the group is the same hull as one drawn by hand.
- **glTF.** `SurfacePayload.exportTriangles` gives the surface's faces (the carved solid, else the swept sheet) in
  the layout `SceneGltfExporter.Quad.surface` takes, so a surface exports through the existing scene exporter like
  any other surface; the scene export of a group with paint layers is unchanged.
- **Relation to models.** An imported `.obj` is a 3D model layer (`ModelLayer`, `ObjParser`): fixed geometry with
  UVs. A store surface is editable profile data that becomes a surface group. They are different things on
  purpose; a pack may later carry a GLB of the same shape as a reserved `alternate` (validated by azphalt,
  ignored by hosts), and import of standard meshes stays one path (model layers), not a second one for surfaces.

## Relief surfaces

A relief is a group surface made from a photo's depth map: a grid of heights over the photo's plane, with
the photo as the single paint image. It is the same kind of thing as a hand-drawn surface (not a model
layer), so ownership, undo, the wire mesh, camera presets, glTF export and group opacity come from the
group-surface path. Status: the data model, geometry and persistence (format 12) are implemented and tested on
synthetic depth maps (ramp, sphere bump, step, noise); the editor action ("Build surface from image") is
described under Editor below; rendering and glTF export are under Rendering and export below.

- **Representation (decided).** `SurfaceProfile.relief: ReliefSurface?` (`:core:common`, `ReliefSurface.kt`),
  not a sibling field on the layer. Every group-surface path keys on `Layer.surface` being a valid profile
  (`SurfaceMath.ownerOf`, the layer menu, flatten, co-op `LayerExtProps.surface`, history), so a relief inside
  the profile is found by all of them unchanged. `ReliefSurface.toProfile()` is a two-vertex base line along
  box x (to the photo's right edge) swept along box y, so the unrolled `(u, t)` are the group's box `(x, y)`
  and a point of the relief is `(u, t, z)`. `SurfaceProfile.isFlat` is false for a relief (the flat-surface
  shortcuts never apply), `hasHull` is false, and `side`/`top` are ignored.
- **Heights.** `cols x rows` samples, 0..1, 1 nearest, over the rectangle `left, top, spanU x spanT` of the
  unrolled plane that the photo covers; `z = -(height - 0.5) * displacement` (scene z points away from the
  viewer, so nearer is more negative, and a flat depth is the plane z = 0). `inverted` and `displacement`
  apply on read, so the strength slider and the invert toggle never touch the data. Stored as 16-bit samples in
  base64 (`heightData`): about 170 KB of JSON for a 64k-vertex grid.
- **Paint space.** The photo's own pixels: `ReliefSurface.photoPixel(u, t)` / `unrolled(px, py)`. The mesh's
  vertex UVs are photo pixel coordinates (`ReliefMath.photoMesh`); `ReliefMath.mesh` gives the same triangles
  with `(u, t)` as `SurfaceMath.mesh` does for a strip, so `DepthScene.surfaceMesh` can place any layer on it.
- **Resolution.** `ReliefSurface.gridSize`: the photo's aspect, at most `ReliefOptions.maxVertices` (65 536)
  vertices, never more than the photo or the depth map has samples, at least 2 x 2.
- **Building.** `ReliefSurface.build(depth, ...)` takes inverse depth of any scale (larger nearer; non-finite
  samples count as the far plane), samples it at the grid's vertices (corner aligned), clamps the 2% / 98%
  quantiles (`clipLow`/`clipHigh`) so outliers do not squash the rest, and smooths with a bilateral filter
  (`smoothingRadius`, `edgeSigma`) so steps survive. A depth with no range is the flat plane. Null for an
  empty or mismatched map, an empty photo or an empty rectangle.
- **Triangles.** Each cell is cut along the diagonal across the smaller height difference
  (`cutsAntiDiagonal`), so a step is not sliced across a cell; `heightAt`/`at` are exact on those triangles,
  so a ray hit and the surface point it names agree to the pixel.
- **Ray hit.** `SurfaceMath.hit` on a relief is `ReliefMath.hit`: a 2D DDA over the grid, clipped to the slab
  the displacement spans, with a two-sided triangle test per cell. It returns `SurfaceHit(u, t, distance)` with
  `(u, t)` the base-plane position, which is the paint space, so `SurfaceMath.worldToFlat`,
  `SurfaceFootprint.worldToFlat` and `flatToWorld` work as for any surface. A footprint that misses maps to the
  nearest point of the relief's projected outline (`ReliefMath.outline`: the photo's border plus a coarse net,
  because a tall relief seen from the side projects outside its border).
- **Wire mesh.** `SurfaceMath.gridLines` on a relief draws lines every 64 units across the photo, sampled
  along the grid's vertices, so they follow the displacement.
- **Editor: "Build surface from image".** On a photo layer: the layer menu row of that name, and the 3D slot's
  Draw-surface chip, which offers it beside Cancel while the active layer is a photo not yet on a surface (the
  rail gained no slot: its layout is pinned by the mockup test). Cancel while building is the chip's and the Back key. `EditorViewModel.onBuildSurfaceFromImage` (the active layer when none is named):
  1. `ReliefBuild.plan` checks the layer and works out where the photo will sit on the new group's plane: its
     image rectangle (`DepthComposite.imageRect`) through its own flat 2D pose. A layer that is a group, vector,
     text, model, 3D ink, adjustment or per-view image, is locked, is already on a surface, is placed in depth, or is
     rotated or mirrored is refused with a notice that says what to change (the grid is an axis-aligned rectangle
     and the depth is sampled over exactly the photo).
  2. The depth model is looked for (`ReliefDepthSource.availability`, which reads `ExtensionRepository`): none
     installed, still downloading (with the percentage when known) or unusable as listed each give a notice and
     change nothing. The install flow itself is the existing one (Store, `fetchModelWeights`, `retryModelDownloads`).
  3. `InstalledDepthSource` runs `OnnxDepthEstimator.forAsset` on a copy of the photo off the main thread. The
     chip shows the step (`EditorUiState.reliefBuild`: checking, reading depth, building) with Cancel (the chip and the
     Back key); cancelling stops inference and changes nothing. The UI is never blocked.
  4. `ReliefSurface.build` samples the raw inverse depth straight onto the grid (no resample to the layer's pixels
     first; the grid is the cap), then the layer is checked again: if it moved, was locked or removed while the
     model ran, nothing is changed and a notice says so.
  5. `ReliefBuild.assemble` wraps the photo in a new group "Relief" (taking the photo's place in the stack and
     its folder) carrying the relief, as ONE undo step (`pushHistory`, `emitMembership`, `emitGeometryChanges`).
  The relief chip (`ReliefChip`, in the Draw-surface chip's chrome) is shown for the active layer's relief group:
  a **strength** slider (live; `ReliefSurface.strength` is the displacement as a fraction of the photo's longer
  side, 0 to 1, default 0.25; a drag is one undo step, opened by its first change) and a **swap near and far**
  switch (`ReliefSurface.inverted`, one undo step). Both edit the stored relief and never rebuild or re-run the model.
  A relief group's menu has no side or top view (they do not apply); "Flatten surface" removes the relief and the
  photo is a plain layer again; "Draw surface" on the group replaces the relief with a drawn profile.
- **Painting on a relief (what is reused).** A relief has ONE image, the photo's own, so nothing multi-view
  applies: `SurfaceProfile.hasHull` is false, `ActivePlaneMapping.onHull` is false, and the per-view crease logic
  (`ViewStrokeFeed`, `SurfaceViewStroke`, per-view images) is never entered. Every tool goes through the single-image
  footprint path that curved drawn surfaces already use: Brush and Eraser (`toFlatByFootprint` ->
  `SurfaceFootprint.worldToFlat`), Fill, Clone (reticle through `flatToWorld`) and the Lasso and Wand (`toFlat`,
  and the selection follows the camera through `flatToWorldOrNull`). The flat point they get is the relief hit's
  `(u, t)`, which is the photo's plane, so the leaf's own 2D transform maps it to the photo's pixels exactly as on a flat
  layer. Undo, the layer's stroke log and bitmap file are the ordinary per-layer ones; group opacity and the wire mesh
  come from the group-surface path. Flat layers and hand-drawn surfaces take none of the new code (every relief
  branch is guarded by `SurfaceProfile.relief != null`).

- **Rendering and export.** The photo is drawn by the ordinary surface path: `DepthScene.surfaceMesh` asks
  `SurfaceMath.mesh`, which for a relief is `ReliefMath.mesh` (the grid's triangles with `(u, t)` mapped through
  the leaf's box to the photo's texture coordinates), so it is textured with the photo, depth-tested against
  itself and every other member, pulled toward the eye by its stack position, and takes the group's opacity
  (including the isolated-group offscreen pass) exactly as paint on a drawn surface does. Differences: the plane
  is `surfaceTriangles` (arbitrary triangles; the no-GLES export fallback draws it triangle by triangle) and is
  never `surfaceFlat`. A relief mesh is ~130k triangles (~2M floats), so `DepthScene.surfaceMeshForPass` builds it once per
  relief, photo rectangle and placement and hands the same array back until one changes (the strength slider and the
  near/far swap do change the relief, so they rebuild it: about 20 ms on a desktop JVM), and `DepthCompositor`
  keeps a mesh of 2000 triangles or more in a vertex buffer keyed by that array's identity (`surfaceVbo`, four kept)
  instead of re-sending it each draw. glTF: the photo is one textured, double-sided mesh at the group's opacity;
  `SceneGltfExporter` now indexes surface meshes (a vertex shared by triangles is written once), so a relief is
  one vertex per grid point and six indices per cell. The export builds its own copy of the mesh (it moves vertices into scene
  space in place), so it never touches the pass's. The wire mesh follows the displacement
  (`ReliefMath.gridLines`); it is the same overlay as any surface's.
- **Persistence (format 12).** Format 11 shipped in 1.71.0, so `SurfaceProfile.relief` is format 12. Migration
  11 -> 12 only records the version; 9, 10 and 11 files load unchanged.

## The orbit point

Everything that revolves revolves about one point, `OrbitPivot.of(layers, W, H)`: the rotation
slider on every axis, the Move tool's twist, the axis cycler's two-finger turn and the camera orbit
(`SceneMath.orbitAbout`, which keeps the pivot fixed on screen), and the point a newly adopted view
plane passes through. It is the centre of the box that holds the content.

- **Layer 1 alone.** Layer 1 (the first paint layer, the base) is flat, so the box is assumed from its
  own size: W wide, H tall and `D = OrbitPivot.assumedDepth(W, H) = max(W, H)` deep, a cube on the
  longer side. The owner did not state D, so it is one named function. The pivot is the box's centre:
  x and y at the centre of layer 1, D/2 BEHIND its plane (+z, away from the viewer). The box is
  carried by layer 1's pose, so the pivot is a fixed point of every turn about it: it cannot jump
  while the layer is rotated.
- **More content.** Once a second content layer exists (groups and adjustments do not count) the
  pivot is the centre of the union of every content layer's world-space bounds (each plane's four
  corners, through its group chain). Along any axis where that union is still flat the extent comes
  from layer 1's assumed box. The bounds follow the layers, so the point is held for the length of a
  gesture (a slider drag, a two-finger gesture), and recomputed between gestures.
- **Turning a layer.** `SceneMath.rotatePoseAbout` moves the slider's Euler angle exactly as before
  and moves the layer's offset and depth so its centre revolves about the pivot at a constant
  distance. A Z turn of a flat layer (not placed in 3D) stays in place, as a 2D layer's always did;
  for layer 1 that is the same thing, since the pivot sits straight behind its centre.
- **Surfaces (the rule once any group has a surface).** The pivot is the centre of the axis-aligned box
  that holds, in world space, EVERY defined surface (`OrbitPivot.ofSurfaces`): each profiled group's
  box (`SurfaceMath.localBounds`: a hull's clip box, a relief's photo rectangle with its displacement,
  else the profile's vertices and the same vertices swept as far as the guide grid reaches) through its
  group chain, plus the planes of any content layer that is not paint on a surface. Paint layers inside
  a surface group are skipped: their own plane is flat paint space, not where the paint is drawn. This
  replaces the plane-layer rule above as soon as one surface exists. The centre of the box was chosen over
  a vertex or area centroid because it depends only on extent: it does not shift when a stroke is
  simplified, a relief's grid is rebuilt, or one surface is tessellated finer than another, and it moves
  only when a surface is added, removed, changed or moved. It is a pure function of the layers, so it is
  recomputed at every use (so after any add, edit, undo, redo or removal) and held only for the length of
  one gesture. With no surface it is the rule above, unchanged.
- **Flat sheets.** A flat surface with a bounded sweep (`SurfaceProfile.sweepLimit`: the predefined default sheet and
  the art-sized sheet) is treated as a slab reaching `2n` behind its face along its normal (+z for a sheet in the plane
  z = 0), `n = ((w + h) / 2) / 2` with w, h its width and height (`OrbitPivot.sheetDepth`, `ArtSheet.pivotDepth`), so the
  box's centre is `n` behind the sheet's centre. Drawn and hull surfaces are unchanged. Derived only: there is no way
  to place the point by hand (an earlier build had one; its saved `orbitPivot` key is still read, ignored and no
  longer written).
- **Orientation per axis.** The camera's orientation is two independent, persisted angles, `yaw` (the Y
  axis) and `pitch` (the X axis), composed as pitch about the screen's horizontal after yaw about the
  vertical (`SceneMath.frame`). Turning X, then Y, then X again therefore gives the same view as turning
  X by the sum and Y once, in any interleaving, and the X turn keeps its own axis (the camera's `right`
  never depends on pitch). Recomputing the pivot does not touch the angles; `orbitAbout` only moves the
  target so the pivot stays put on screen. A layer's rotation is separate: it is body-fixed (see "Body-fixed axes").
- **Body-fixed axes.** X, Y and Z name lines of the OBJECT (the layer or surface group), not of the screen
  or the camera. The line through a square's sides 1 and 3 is X for ever; turn the square 90 degrees about Z and
  sides 1 and 3 stand where 2 and 4 began, and the X slider still turns about the line between them. Each
  slider turns the object about `R * e_axis`, its own line in the scene: `R' = R * Ra(delta)`
  (`SceneMath.rotatePoseAbout`, the one place every slider drag, two-finger twist and handle turn goes
  through). The result is stored back as the same `rotationX/Y/Z` (`SceneMath.eulerOf`, `Rz * Ry * Rx`), so
  the GPU scene, the 2D Compose fallback, glTF export, the wire mesh and hull placement and saved projects
  read exactly what they always read; nothing is migrated and the project format stays 13. The slider's
  thumb is no longer the raw stored angle but `SceneMath.bodyAngle`: how far the object has been turned about
  that own axis, the angle of the Euler decomposition with that axis innermost (`Rz*Ry*Rx` for X,
  `Rz*Rx*Ry` for Y, `Rx*Ry*Rz` for Z). It is a pure function of the orientation, so it needs no stored state,
  survives save, load, undo and redo, and moves by exactly the dragged amount. X reads as it always did; a
  flat layer's Z is its stored angle. Turning about a body axis moves the other sliders' readings
  (they measure turns about lines that moved), which is the price of the lines being the object's. The
  pivot (above) is unchanged: the turn is applied about it. Before this, a stored angle was incremented, which
  is body-fixed for X alone: Y turned about the line left after Z only, and Z about the scene's z.
  Typed angles in the Transform panel still set the stored Euler angle. At gimbal (Y at 90 degrees) the Z
  turn folds into X.
- **Before this**, the pivot was the scene camera's target: the canvas centre ON layer 1's plane
  (depth 0), and layers turned about their own centres, so an X or Y turn tilted the layer in place
  while the camera swung about a point on the plane.

## Adopting the view plane

An empty paint layer takes its plane from the view on its first stroke
(`EditorViewModel.adoptViewPlaneForStroke`, `SceneMath.viewPlanePose`), so drawing under an
orbited camera puts the paint where it is drawn rather than on the far-off picture plane:

- The plane's normal is the camera's view direction and it passes through the orbit point
  (`OrbitPivot`, above; scaled to that point's depth so pixels still show 1:1 there). The layer's box centre lands on the target, its x and y run along the view's
  right and down, and its scale is the camera's distance factor. Its pixels therefore show at the
  same size as a flat layer's at the default view, and the first stroke lands under the finger.
- Inside groups, the pose is expressed in the groups' space, so the group chain gives exactly
  that plane in the scene.
- "Empty" means no source image, text, shapes, adjustment or 3D ink, no recorded stroke, and no
  set pixel (checked once, only when everything else already says empty and the camera has moved).
  A layer with paint keeps its plane. So does a layer already placed in 3D by hand.
- At the default view nothing changes: the adopted pose would be the identity, so the layer stays
  on the 2D fast path.
- It happens as the stroke starts, before the first point is mapped, and joins that stroke's undo
  entry (`EditCommand.Draw.plane`; never merged into an earlier stroke chunk). Undo restores the
  old plane with the pixels and redo re-applies it. A cancelled stroke puts the plane back at once.
- A 3D strokes layer's default guide (VIEW_PLANE at depth 0) is the same rule already: it faces the
  camera through the orbit target.

Example (a unit test): draw a square at the default view; orbit 90 degrees, so it is edge-on;
draw a second square on a new layer starting at the edge-on line; tilt the view up 90 degrees.
The two squares share that edge, at right angles: an L.

## Draw plane depth

In a project with a 3D group the user can choose how far from the eye the next stroke lands, without moving any
art. The control is the wire-mesh button ("#", `SurfaceMeshButton`, rail id `view.surfaceMesh`), which now shows in
any project with a 3D group (`hasThreeDGroup`), not only on a surface layer. Its tap (grid on/off) and long-press (menu) are unchanged.

- **Relative, not absolute.** The depth the user sets is an offset from the *automatic* depth, stored as a multiplicative factor k
  (`DrawPlaneDepth.factor`; null or 1 = automatic). The automatic plane is the one through the orbit pivot, or parallel to the last
  stroke's plane, and it follows the camera, the pivot and that stroke, so it tracks dolly and orbit distance. The effective
  eye-to-plane distance is `clamp(k * d0, 2 * near, far / 10)` with `d0` the automatic distance worked out from the current
  reference when a stroke starts, when the guide is drawn and when the slider is read (`EditorViewModel.automaticDrawDepth`,
  `effectiveDrawDepth`, `DrawPlane.effectiveDepth`). "Reset draw distance" returns to automatic, which follows the camera again.
  A later "snap to stroked planes" feature will work on the effective depth (`effectiveDrawDepth`).
- **Drag.** A vertical drag on the button changes k: up moves the plane toward the camera, down away from it. It
  is multiplicative, as the Move-tool pinch is: 400dp of drag changes the effective distance (k) by a factor of 4
  (`DrawPlane.DRAG_FACTOR`, `DRAG_DP_PER_CHANGE`), clamped to twice the near plane up to a tenth of the far plane
  (`DrawPlane.depthRange`). Like the brush-size chip, the button owns the gesture only after the touch slop (it uses
  `detectVerticalDragGestures`, so only vertical movement claims it); the drag cancels the tap and the long-press, and
  the rail still scrolls from the gaps and the other buttons.
- **Non-drag alternatives.** The long-press menu gains "Draw distance nearer", "Draw distance farther" (one step of
  x1.25, `DrawPlane.STEP_FACTOR`) and, once a depth is set, "Reset draw distance"; they are ordinary 48dp menu rows
  from string resources. TalkBack gets the button as a slider (`progressBarRangeInfo`, `setProgress`) over the effective depth's
  log range, 0 = nearest, 1 = farthest (`EditorViewModel.drawDepthFraction`, `onSetDrawDepthFraction`).
- **The plane.** It is parallel to the plane of the last stroke drawn (the layer that took it, in its pose at the time),
  offset along that plane's normal until its box centre is the effective depth from the eye (`DrawPlane.world`). With no previous
  stroke, or when the reference plane is seen nearly edge-on (|normal . view| < 0.1), it is the camera-facing plane through
  the orbit pivot, as in "Adopting the view plane". The box keeps its on-screen pixel size at the new depth (its scale
  grows with the distance), so a brush stroke is as wide as it was. Nothing is moved: no existing layer changes.
- **Lapse.** The depth belongs to the view it was set in. Any change to the scene camera or the canvas viewport (orbit,
  pan, zoom, rotation, Reset view), to the active layer, or to the project makes it automatic again
  (`DrawPlaneDepth.holdsIn`, applied in `EditorViewModel.writeState`, so no path can miss it); the last-stroke reference
  lapses with the camera too (`LastStroke.holds`). A resized canvas lapses the depth as well (`canvasSize`). Undoing the last stroke (a whole chunk of strokes
  counts each) or dropping one whose commit failed (matched by the stroke, not its layer) moves the reference back along a chain of
  the last 8 strokes. Redoing a stroke brings back the view that stroke was drawn in (kept on its undo entry), not the current one, so a
  camera change between the undo and the redo leaves the reference lapsed. Switching the active layer by hand ends the reference too
  (as it ends the depth); the draw plane's own layer changes (create, undo, redo) do not. A newest stroke whose layer the depth refuses (paint on a surface) is skipped to the one before it;
  but a newest stroke whose view has lapsed is not: the older stroke is the reference again only once the newer one is undone. It is session state (`EditorUiState.drawPlane`): not saved, no format
  change (the format stays 14).
- **The next stroke** (`adoptDrawDepthPlane`, called from `adoptViewPlaneForStroke` as a stroke starts): on a paint layer,
  with or without art, the stroke goes onto a new empty layer on the plane, directly above the active layer, in the same
  group, active from then on; it is named like any new sketch layer. An empty paint layer is not duplicated: it is moved
  onto the plane (the adoption mechanism). The layer's creation rides on the stroke's undo entry
  (`PlaneSwap.created`): one undo removes the stroke and the layer, one redo puts both back (while a redo entry exists the layer's pixel cache is kept; it is released, with its first PNG, once no
  layer, undo or redo entry holds it). A cancelled stroke, a failed commit of the entry's only stroke and a double-tap dot taken back remove the layer at once, provided the
  layer still exists and holds nothing else (no other stroke entry on it in either stack, none in its store); otherwise only the failed
  stroke is dropped (a later stroke of a chunk, a stroke arriving after its entry was undone, a layer deleted since)
  (an empty layer moved onto the plane gets its old plane back). The depth is spent by the stroke; setting it again offsets from that stroke's plane.
- **Where it does not apply.** A layer on a surface keeps to the surface, and the 3D strokes layer keeps its own guide
  depth (`SpatialGuide.depth`, unchanged): the drag then does nothing and says so in a notice, once per drag. So does a
  group, text, vector or adjustment layer (paint layers only). With no depth set, everything behaves as before.
- **The guide.** While the depth is dragged, and about a second after it is released (`DrawPlane.GUIDE_LINGER_MS`), a flat
  grid on the plane shows in the surface mesh's colour (`SurfaceLook.GRID_ARGB`), overlay only, never exported. Its cells are
  a fixed 64 scene pixels (`SurfaceMath.GRID_SPACING`) over 16 cells each way from the box centre, so they shrink as the
  plane moves away, and it follows the plane's actual orientation (camera-facing in the common case). It fades over 300ms
  (`DrawPlaneGuide`). It does not stay up while a depth merely stays set.
- The gesture help (menu, Gestures) lists the drag among "Other touches".

Tests: `DrawPlaneTest` (the maths: mapping, clamp, parallel plane, edge-on fallback, groups, guide perspective),
`DrawPlaneDepthTest` (lapse on camera, pan, zoom, layer and project change; new layer above the active one on the offset
plane, art untouched; one undo and redo; cancel; surface and 3D strokes layer notices; automatic unchanged; menu steps,
fade timer), `Scene3DRailUiTest` (button in a 3D project without a surface layer, drag, tap, long-press, TalkBack
`SetProgress`, menu items), `NoHardCodedChromeTextTest` (no new literals).

Not tested, or not verified on a device:

- A real stroke's pixels landing on the offset plane, and the new layer's bitmap after undo and redo (a JVM test has no GPU,
  and the stroke path in the tests does not map points through the plane); the Ink path's `commitInkThroughPlane` with a depth.
- The guide's appearance and fade on screen (`DrawPlaneGuide` is untested as a composable); its faintness (the mesh colour
  is alpha 0.2).
- The drag against the real `RailPill` scroll on a device (the Robolectric drag is a plain touch input); a drag that starts on
  the button and is slow to leave the slop may still scroll the rail first. TalkBack and keyboard behaviour on a device.
- Pixels of the redo of a created layer (it comes back with no bitmap and is rebuilt from its base and strokes): the pose and
  stroke record are tested, the pixels are not.
- Ink multi-segment strokes (`continuesGesture`) failing partway, and a layer-list edit (not a delete) between a stroke and its failure.
- A draw-plane session does not carry across projects (the last-stroke reference and the layer file bookkeeping reset on project open
  and close; a file is never deleted across projects).
- Co-op: the created layer is emitted as `LayerAdd`/`LayerRemove` but not exercised against a guest.
- Animation mode, and instances/components, with a draw depth set.

## Tilted layers: selection and tap-select

`ActivePlaneMapping` (EditorScreen) carries the same mapping for the lasso, the magic wand and
selection moves. Each lasso point is the flat point of its ray-plane hit (a point off the plane is
dropped), so the selection and its clip live in the flat space the paint pipeline already uses.
`SceneMath.layerFlatToWorld` maps the ants back through the plane; a homography maps straight
segments to straight segments, so mapping the vertices is exact. A selection drag is converted at
the selection's centroid, the point that stays under the finger. The press that decides between
moving the selection and starting a new lasso is mapped the same way before the containment test,
so it lands on the rings where they are stored.

Tap-select (`CanvasHitTest.topHit`/`allHits`, given the camera) casts the tap onto every
scene-path layer's plane, tests the flat point against the layer's box, and orders hits by the
camera depth of the ray hit, nearest first, among depth-pass members only
(`CanvasHitTest.stackOrder`). The pass's members rank together just under the anchor's subtree;
every other hit (a blend-mode or clipped layer, on the scene path or not) keeps its stack
position, so a Screen layer above the pass wins over a nearer member, as it does on screen.
Ranks come from the renderer's own draw order, a pre-order walk of `buildLayerTree`, not flat
list positions (a reorder can leave a child away from its parent in the list).

## Co-op

`Op.LayerTransform` keeps its 16 floats (`LayerTransformCodec`): slots 0-5 are scale, offset and
the three rotations, as always, and slot 6 is now the depth. Older builds always wrote 0 there,
which is exactly their layers' depth, and ignore it when reading. Every transform message carries
the whole plane placement, and a peer's diff now notices depth and rotation changes.

## Migration and persistence (format 6 -> 7 -> 8)

- `OverlayLayer.positionZ` and `OverlayLayer.spatial`; absent in older files, so every older layer
  loads at depth 0 with its old rotations.
- `GraffitiProject.sceneCamera`; absent = default camera.
- `ProjectManager.migrateFormat` 6 -> 7: a project whose `spatialStrokes` are non-empty gains a
  top layer "3D strokes" carrying those strokes and the saved `spatialGuide`; the project-level
  lists are cleared. The strokes' coordinates are not touched.
- A layer tilted by the old X/Y rotation (GraffitiXR projects, or the retired Transform-panel
  switch) now shares the scene camera instead of Compose's per-layer camera, so its foreshortening
  changes slightly. Nothing in a document without such tilts changes.

Format 7 -> 8 (3D model layers):

- `OverlayLayer.model` holds the OBJ's and the texture's file names in the project folder (names,
  not paths, so a `.fux` imported under a new id still finds them). The texture is written as
  `model_tex_<layerId>.png` on save, only when its revision changed since the last write.
- `ProjectManager.migrateFormat` 7 -> 8 (`migrateFloatingModelToLayer`): a project whose
  `modelPath` is set gains a top 3D model layer for that file and its `modelTexturePath`, at the
  identity plane (facing the default camera through the canvas centre); the project-level fields
  are cleared and written empty from then on. A missing OBJ loads as an empty layer, a missing
  texture as unpainted.

The full-screen Spatial Sketch workspace and its separate undo stack are retired. Its guide tools
are the 3D strokes layer's Draw mode; its undo is the editor's undo (strokes live in the layer).

## Export

File menu, **Export 3D scene (glTF)**: one self-contained glTF 2.0 JSON (`SceneGltfExporter`).

- Each visible paint layer with pixels becomes a textured quad: its bitmap as an embedded PNG,
  the quad's corners at the layer's scene placement, unlit, alpha-blended, with the layer opacity.
- Each vector layer is rasterized by the canvas's own `drawVectorShape` (`VectorRaster`) onto a
  quad covering its shapes. That is chosen over tessellating: tessellation would re-implement
  stroking, joins, corner radii and curves, and would still differ from the canvas at the edges.
  The texture is at canvas resolution (up to 4096 a side).
- Each 3D strokes layer becomes its tube mesh (`SpatialGltfExporter.buildMesh`), transformed by
  the layer's placement.
- Each 3D model layer becomes its mesh with normals and UVs at the layer's placement, its painted
  texture (over the base colour, as the canvas shows it) as the base-colour texture; an unpainted
  model gets the base colour alone.
- Optionally the scene camera, as a perspective camera node.

**PNG, PDF, share and thumbnail** (`ExportManager.compositeLayers(..., camera)`, `SceneExport`):
a flat document takes the old composite unchanged. Otherwise the export draws what the canvas
shows from the current camera, at canvas size, before the artboard crop. The depth-pass members
come from the same `DepthCompositor`, rendered off screen on a pbuffer
(`OffscreenDepthRender`) and read back once. Every other layer draws through its homography
(Skia renders perspective bitmaps), groups with their opacity and blend, and a frame's fill and
clip through the frame's own plane. An isolated group (Group opacity and blend, above) renders the
same way in the off-screen pass (one target, composited at the group's opacity with its own depth), so
the export shows what the canvas does. Where GLES cannot start (unit tests), the members are drawn
farthest-first through their homographies and 3D ink and models are omitted; an isolated group's
layers are drawn into a canvas-sized bitmap first, which is then drawn at the group's opacity, at the
group's place in that order.

glTF axes are y-up, z toward the viewer, metres. One metre is one spatial unit (`F / 3` scene
pixels), origin at the canvas centre on the picture plane, so the strokes keep their coordinates.

### Co-op

A model layer syncs the way a 3D strokes layer does (`LayerAdd`, then transform and props diffs),
plus `Op.ModelData`, because neither the mesh nor the paint fits in a `LayerAdd`: it carries the OBJ
deflated and the texture as PNG, after the `LayerAdd` that introduces the layer, and the texture
alone whenever the paint revision changes (a stroke, an undo). The receiver validates the file name,
caps the inflated OBJ at 64 MB and the texture at 4096 a side, saves the OBJ into its own project
folder and installs the mesh once parsed. The cost is one OBJ transfer per model and one PNG per
finished stroke, not per dab. (Graffux has no co-op transport bound yet; this is the protocol.)

## Limits

- Per-pixel depth covers layers that composite normally. Blend-mode, clipping-mask, adjustment
  and frame-clipped layers, and descendants of translucent groups or filled frames, keep stack
  order among themselves and against the depth pass.
- Crossing semi-transparent edges occlude rather than blend (no order-independent transparency).
- A 3D strokes layer in a group with a non-normal blend mode is composited normally, in the pass.
- A group's blend mode on a curved surface is ignored (a straight surface honours it, in the stack).
- A translucent group nested inside a profiled group is not isolated: its opacity is folded into its
  layers, so overlaps inside it compound. Only the profiled group (with the groups above it) is a unit.
- Animation Assist's onion-skin fading does not apply to depth-pass members.
- A model layer has no pixels of its own, like a 3D strokes layer. Its blend mode is ignored
  (it is always in the depth pass), and the brush paints opaque round dabs in the active colour.
- A relief is one photo, one depth estimate. It is not re-estimated when the photo is painted on or replaced
  (build again from the layer after "Flatten surface"), and the paint does not move the geometry. A relief of a
  rotated, mirrored or depth-placed photo is refused. The grid is capped at 64k vertices whatever the photo's size,
  so fine detail in a large photo is in the paint, not the shape. A relief's blend mode is ignored like any curved
  surface's. Changing the strength rebuilds the 130k-triangle mesh each step (the displacement is not applied in the
  shader). The "nearest outline point" for a brush that misses is approximate on a tall relief seen edge-on.
- Verified only in JVM tests: the geometry, mesh, UVs, glTF structure and the editor flow with a fake depth source.
  Not verified off-device: the GL path (`surfaceVbo`, polygon offset on a displaced mesh), the real depth model's
  output on real photos, and gesture feel while painting a relief.
