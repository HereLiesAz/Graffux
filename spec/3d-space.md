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
| Two-finger drag | pan the view | pan the view | slide the layer so it follows the fingers |
| Pinch | zoom the view | zoom the view | move the layer in depth (out = nearer) |
| Twist | rotate the canvas (the 2D view rotation) | axis Z: rotate the canvas; X / Y: orbit the camera (pitch / turn) | rotate the layer about the current axis |
| Double-tap | nothing | step the axis X -> Y -> Z | step the axis X -> Y -> Z |
| Brush / Eraser on a 3D strokes layer | | draw a tube on its guide / erase the curve under the finger | |
| Brush / Eraser on a 3D model layer | | paint / erase the model's surface under the finger | |

A double-tap never changes the active layer; a buried layer is picked from the layers rail. (The
old double-tap that cycled through the layers under the finger is gone.)

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
  "Axis:" chip confirms a change briefly, then clears. The rotation slider stays for these layers.

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
is, a group with a surface, and it is not locked) a right triangle (`RotationGizmo`) sits in the right
corner, on the top edge of the bottom sheet (`RotationGizmoDock`; the sheet reports its top edge through
`CarouselSheet.onTopChanged`, so the triangle moves with the sheet as it opens, closes and is dragged) and on
the bottom safe area, the slider's line, when no sheet is shown. It is in from the edge by a rail's width.
Its right angle is bottom right: the **bottom leg is X** (cyan), the **vertical leg is Y** (pink), the
**hypotenuse is Z** (green), the rail's axis colours.

- Drag along an edge to turn the group about its **own** axis (body-fixed, through the orbit pivot:
  `turnedAboutPivot`, `SceneMath.rotatePoseAbout`). Sign: right along the bottom, up the side and up-right
  along the hypotenuse are **positive**, the same sense the slider's positive values had.
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
- The rotation slider is not shown for a surface group (`rotationSliderValue` is null there); it stays for
  every other layer.

### Rotation slider

Whenever the bottom sheet is closed, or hidden for drawing, a hairline slider (`RotationSlider`, in
`RotationSliderDock`) is docked at the bottom of the screen: above the nav bar's inset and the
bottom gesture strip, and in from both sides by a rail's width, so it is clear of the tool and layers
rails on either hand. Opening the sheet takes it away. It rotates the **active layer**, not
the view: about Z on a 2D layer, about the axis slot's current axis on a 3D layer that is not on a surface group
(`EditorViewModel.rotationSliderAxis`). It reads the layer's angle on that axis wrapped to
-180..180°, with a centre tick at 0°, light haptic detents at 0 / ±45 / ±90 / ±180, a soft snap at
0 only, and a degree readout only while dragging. A double-tap on the thumb resets to 0°. One drag
(or reset) is one undo step, through the same gesture start/end as a transform gesture. It is the one
control that stays live while drawing: it is never ghosted with the chrome, it is used between
strokes, and its touches never reach the canvas (they cannot start a stroke).

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

- **Import.** `EditorViewModel.importModelLayer` parses the OBJ (`ObjParser`), copies the file
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
  stamps dabs from the previous UV (`TexturePaint.stamps`, which breaks the stroke at UV seams) into
  the texture (`PaintableTexture`). The brush's on-screen size is converted to texels at the hit
  (`ModelSpace.texelsPerPixel`: the triangle's texel area over its projected area). A miss ends the
  run. A second finger ends the stroke. The 2D paint pipeline is not touched.
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
  tap pick's ray machinery (`OrbitPivot.pick` now calls `SurfaceVisibility.nearest`), built once per
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
- **Moving.** The Move tool, the rotation slider and the X/Y/Z cycler act on the group when the
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
  opens its menu: Wire mesh on/off, Relocate rotation point, Reset rotation point to automatic. It is drawn in a dark under-line and a cyan line over the artwork. Every visible
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
- **Placed by hand.** The wire-mesh button's long-press menu has "Relocate rotation point": the next tap
  in the scene sets `EditorUiState.manualPivot` to the surface point under it (`OrbitPivot.pick`: the
  first surface the ray meets, else the point at the current pivot's depth), and "Reset rotation point
  to automatic" clears it. The "#" button stays on the rail while a point is placed, even with no surface under the active layer, so Reset is never stranded; removing the last surface does not discard the choice (it is still a valid point for plane layers). While set it replaces the automatic point everywhere (`OrbitPivot.resolve`).
  It is saved as `GraffitiProject.orbitPivot` (format 13); view state, not history.
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
