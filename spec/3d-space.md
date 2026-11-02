# One 3D space: layers as planes, a scene camera, 3D strokes as a layer

Status: implemented on Android (project format 7). The desktop app does not use `:core:common` or
`:feature:editor`, so it is unaffected and has no 3D.

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

A double-tap never changes the active layer; a buried layer is picked from the layers rail. (The
old double-tap that cycled through the layers under the finger is gone.)

## 2D and 3D layers

The two stay distinct. A layer is a **3D layer** (`Layer.is3DLayer`) when any of these holds:

- it was made 3D by hand: `Layer.plane3D`, a stored flag, saved with the project
  (`OverlayLayer.plane3D`; absent from older saves, which load every layer as 2D unless placed);
- it is placed in 3D (`Layer.isPlacedIn3D`: a depth or an X/Y tilt), by any means: view-plane
  adoption, the Move tool's depth or tilt, the Transform panel, an older project;
- it is a 3D strokes layer.

A dedicated flag is needed because `isPlacedIn3D` is derived from the transform alone: a layer made
3D by hand still sits on the identity plane until it is moved, and must stay 3D. There is no way
back to 2D by design: Reset plane flattens the plane and sets `plane3D`, so the layer stays 3D.

On the layers rail the tile outline tells them apart: the active 2D layer wears the rail's
selected-tool accent (pink), the active 3D layer cyan (the X-axis colour), any other 3D layer white,
and any other 2D layer no outline. A 3D tile's TalkBack label also says "3D".

The rail's **axis slot** (the slot the old 3D button had) is always there:

- on a 2D layer it reads **3D**; a tap makes the layer 3D as one undo step, on axis Z, and the
  slot then reads **Z**;
- on a 3D layer it shows the current axis as its letter in the axis colour (X cyan, Y pink, Z
  green), so the user can always see which axis a twist turns about; a tap steps X -> Y -> Z,
  the same state a double-tap steps. An "Axis:" chip confirms a change briefly, then clears.

On a 2D layer the camera can still have been moved by 3D work elsewhere; the document renders
through it as always, but a twist only ever rotates the canvas.

Two fingers act on the view unless the Move / Transform tool is in hand on a 3D layer, so no tool
can strand the user. The Move tool acts on the active layer wherever the fingers land; that keeps it
predictable on a tilted or off-screen plane, where hit-testing would be ambiguous. Each completed
two-finger gesture on a layer is one undo step; view gestures are never history (the camera is
saved with the project when the gesture ends). Every gesture follows the fingers continuously: the
canvas gesture handlers are keyed so a state write on every step never restarts them.

**Fit** on the rail is there whenever the 2D view or the 3D camera has moved, and puts both back to
the identity. **Reset plane** in a placed layer's menu puts it back on the identity plane (depth 0,
no X/Y tilt) as one undo step.

Nothing snaps back on its own. The camera stays where it was left and every layer keeps its
placement, rendered in perspective. When the active layer is tilted or the camera has moved, each
touch is mapped onto the layer through a ray-plane intersection (below), so brushes and tools work
unchanged on the tilted plane.

The old `Layer.is3D` field, its Transform-panel switch and its reducer intent are deleted; `plane3D`
is a different key, so a co-op peer or snapshot from an older build that still sends `is3D` decodes
cleanly, because `Layer` skips unknown keys. The Transform panel shows the X, Y and depth fields
whenever the layer is placed in 3D or the camera has moved.

## Data model

All in `:core:common`, package `com.hereliesaz.graffitixr.common.scene`, pure Kotlin.

Scene coordinates are the canvas box's pixels (the same "world" space every layer offset is in,
before the 2D viewport): x right, y down, z away from the viewer (right-handed). Depth 0 is the
picture plane.

- **Layer plane.** A layer's existing `offset` (x, y), `scale` and `rotationX/Y/Z` (degrees), plus
  a new `positionZ`. A point `L` of the layer's full-canvas box maps to the scene as
  `P = C + (ox, oy, z) + R * scale * (L - C)`, `C` the box centre, `R = Rz * Ry * Rx`. With
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

- every visible 3D strokes layer, at its whole chain's placement, so a group placed in 3D moves
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

## Painting on a tilted plane

The brush, fill and clone surfaces hand the view-model "world" points (screen with the 2D
viewport undone). When the active layer is on the scene path, `SceneMath.worldToLayerFlat`
first casts the ray from the eye through that point, intersects the layer's plane, takes the hit
in the layer's box coordinates `L`, and returns `C + (ox, oy) + Rz * scale * (L - C)`: the point
the existing pipeline would have received had the layer been flat. Every brush and tool then runs
unchanged (`mapScreenToBitmap` undoes exactly the 2D part). A ray that misses the plane (parallel,
or the plane behind the eye) yields no point. The brush then keeps the plain world point, so a
stroke never breaks.

## Adopting the view plane

An empty paint layer takes its plane from the view on its first stroke
(`EditorViewModel.adoptViewPlaneForStroke`, `SceneMath.viewPlanePose`), so drawing under an
orbited camera puts the paint where it is drawn rather than on the far-off picture plane:

- The plane's normal is the camera's view direction and it passes through the camera's orbit
  target (the pivot). The layer's box centre lands on the target, its x and y run along the view's
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

## Migration and persistence (format 6 -> 7)

- `OverlayLayer.positionZ` and `OverlayLayer.spatial`; absent in older files, so every older layer
  loads at depth 0 with its old rotations.
- `GraffitiProject.sceneCamera`; absent = default camera.
- `ProjectManager.migrateFormat` 6 -> 7: a project whose `spatialStrokes` are non-empty gains a
  top layer "3D strokes" carrying those strokes and the saved `spatialGuide`; the project-level
  lists are cleared. The strokes' coordinates are not touched.
- A layer tilted by the old X/Y rotation (GraffitiXR projects, or the retired Transform-panel
  switch) now shares the scene camera instead of Compose's per-layer camera, so its foreshortening
  changes slightly. Nothing in a document without such tilts changes.

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
- Optionally the scene camera, as a perspective camera node.

**PNG, PDF, share and thumbnail** (`ExportManager.compositeLayers(..., camera)`, `SceneExport`):
a flat document takes the old composite unchanged. Otherwise the export draws what the canvas
shows from the current camera, at canvas size, before the artboard crop. The depth-pass members
come from the same `DepthCompositor`, rendered off screen on a pbuffer
(`OffscreenDepthRender`) and read back once. Every other layer draws through its homography
(Skia renders perspective bitmaps), groups with their opacity and blend, and a frame's fill and
clip through the frame's own plane. Where GLES cannot start (unit tests), the members are drawn
farthest-first through their homographies and 3D ink is omitted.

glTF axes are y-up, z toward the viewer, metres. One metre is one spatial unit (`F / 3` scene
pixels), origin at the canvas centre on the picture plane, so the strokes keep their coordinates.

## Limits

- Per-pixel depth covers layers that composite normally. Blend-mode, clipping-mask, adjustment
  and frame-clipped layers, and descendants of translucent groups or filled frames, keep stack
  order among themselves and against the depth pass.
- Crossing semi-transparent edges occlude rather than blend (no order-independent transparency).
- A 3D strokes layer in a group with a non-normal blend mode is composited normally, in the pass.
- Animation Assist's onion-skin fading does not apply to depth-pass members.
