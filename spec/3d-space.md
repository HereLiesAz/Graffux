# One 3D space: layers as planes, a scene camera, 3D strokes as a layer

Status: implemented on Android (project format 7). The desktop app does not use `:core:common` or
`:feature:editor`, so it is unaffected and has no 3D.

## What the user sees

The canvas is one 3D space. Every layer is a flat plane. A layer that was never moved in 3D sits
where it always has: at depth 0, facing the camera. A document never touched in 3D looks and
behaves exactly as before, through exactly the same render path.

The **3D** button on the left tool rail (cube glyph, lit while on) switches the canvas gestures:

| 3D | One finger | Two fingers |
|---|---|---|
| Off | the tool in hand paints, fills, selects... | pan / pinch-zoom / twist the 2D view (unchanged) |
| On, Orbit | orbit the camera about its target | pan the camera target, pinch to dolly |
| On, Move | slide the active layer's plane across the view | (camera, as above) |
| On, Depth | drag up/down: push the plane away / pull it closer along the view direction | (camera) |
| On, Tilt | drag across: turn about the plane's Y axis; up/down: about its X axis | (camera) |
| On, Spin | drag across: turn about the plane's Z axis | (camera) |
| On, Draw (3D strokes layer) | draw a tube on the layer's guide | (camera) |
| On, Erase (3D strokes layer) | erase the stroke under the finger | (camera) |

The modes are a strip of chips over the top of the canvas while 3D is on, with **Reset view**
(camera back to the default) and **Reset plane** (active layer back to the identity plane). Two
fingers always drive the camera in 3D, so no mode can strand the user. Move, Depth, Tilt and Spin
act on the active layer wherever the finger lands; that keeps them predictable on a tilted or
off-screen plane, where hit-testing would be ambiguous. Each drag is one undo step.

Turning 3D off does not snap anything back. The camera stays where it was left and every layer
keeps its placement, still rendered in perspective. One finger paints with every tool; when the
active layer is tilted or the camera has moved, each touch is mapped onto the layer through a
ray-plane intersection (below), so brushes and tools work unchanged on the tilted plane. Two
fingers pan and zoom the 2D view on top of the 3D projection, as before.

There is no per-layer "3D" toggle. A layer is "in 3D" exactly when it has a depth or an X/Y tilt
(`Layer.isPlacedIn3D`); the old `is3D` switch in the Transform panel is gone (the field is still
read from old files, and ignored). The Transform panel shows the X, Y and depth fields whenever
the layer is placed in 3D or 3D mode is on.

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
- **UI state.** `EditorUiState.sceneCamera`, `is3DMode`, `scene3DTool`. Written only through
  `updateUi` / the reducer. Camera moves are view state, not history, like the 2D viewport.

## Render path

`SceneRenderPath.sceneLayerIds(layers, camera)` decides per top-level subtree:

- **Fast path (identity).** Camera at default and nothing in the subtree placed in 3D: the layer
  draws through the existing `graphicsLayer` code, untouched. Zero extra cost: one boolean check
  per layer per recomposition, no matrix work.
- **Scene path.** Otherwise every node of the subtree draws with its plane-to-canvas homography.
  The `graphicsLayer` keeps alpha, blend mode and offscreen compositing but drops its geometric
  fields; a `drawWithContent` concatenates the node's 3x3 homography (relative to its parent's,
  `H_parent^-1 * H_self`, so nested groups compose exactly) onto the canvas, after clipping to the
  half-plane in front of the near plane (a plane crossing the eye cannot be drawn as one
  homography, so the part behind the eye is clipped away in the layer's own coordinates).

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

**3D strokes layers** render with the existing depth-tested GLES 3 `SpatialStrokeRenderer` (the
same tube mesh as the glTF export) into a transparent `TextureView` at the layer's place in the
stack. A TextureView composites in view order, unlike a SurfaceView, so the strokes sit between
the paint layers above and below. Its MVP is `clip * camera * layerModel * strokeToLocal`.
Within the layer, tubes are depth-tested; between layers, order is the layer stack (painter's
order), as in 2D, so blend modes and clipping keep their meaning. Planes that intersect each other
in space do not occlude per pixel; the upper layer wins.

## Painting on a tilted plane

The brush, fill and clone surfaces hand the view-model "world" points (screen with the 2D
viewport undone). When the active layer is on the scene path, `SceneMath.worldToLayerFlat`
first casts the ray from the eye through that point, intersects the layer's plane, takes the hit
in the layer's box coordinates `L`, and returns `C + (ox, oy) + Rz * scale * (L - C)`: the point
the existing pipeline would have received had the layer been flat. Every brush and tool then runs
unchanged (`mapScreenToBitmap` undoes exactly the 2D part). A ray that misses the plane (parallel,
or the plane behind the eye) yields no point; the sample is dropped.

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
- Each 3D strokes layer becomes its tube mesh (`SpatialGltfExporter.buildMesh`), transformed by
  the layer's placement.
- Optionally the scene camera, as a perspective camera node.

glTF axes are y-up, z toward the viewer, metres. One metre is one spatial unit (`F / 3` scene
pixels), origin at the canvas centre on the picture plane, so the strokes keep their coordinates.

## Not done here

- Vector shapes are not exported to glTF (no pixels to texture); rasterize first.
- Selection (lasso) and tap-to-select still work in flat 2D space on a tilted layer.
- PNG/PDF export still renders layers flat, as before.
- A 3D strokes layer inside a group placed in 3D draws with the group's placement ignored.
- Co-op transform ops carry no depth.
