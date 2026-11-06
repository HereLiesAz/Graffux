# Graffux

A touch-native, multi-layer **image editor for Android** — sketch, paint, retouch, and composite
with layers, blend modes, curves, text, and stencils. Built entirely in Kotlin + Jetpack Compose.

Graffux is the standalone 2D editor extracted from [GraffitiXR](https://github.com/HereLiesAz/GraffitiXR)
(an AR mural-projection app). It hosts the shared editor stack as the single source of truth; GraffitiXR
consumes the same modules for its 2D work and adds AR on top.

## Features

- **Layers** — add image, blank, or grouped layers; reorder, rename, toggle visibility, duplicate,
  merge down, flatten, link (so a contiguous run moves/scales/rotates together), clip to below,
  alpha lock, blend modes, per-layer opacity/tone.
- **Transform** — move / scale / rotate by gesture, or type exact values in the numeric transform
  panel; Distort (4 handles) and Warp (16 handles) for perspective/bend.
- **Adjustments** — opacity, brightness, contrast, saturation, colour balance, curves (a monotone
  cubic-spline LUT baked into the layer), invert, colour-lookup (`.cube` LUT) extensions.
- **Paint** — brush and eraser with size + hardness, brush packs on a half wheel that opens from the rail's Brush and Eraser, colour, node-edited vector paths (Pen), Heal,
  Clone, Blur, Sharpen, Smudge, Liquify (native GLES warper), Dodge, Burn, Colorize, plus
  wraparound painting mode. Brush Studio for building custom brushes.
- **Selection** — freehand/rectangle/ellipse/automatic (magic-wand) selection, Subject (ML Kit
  subject isolation, on the device and offline), add/remove/replace,
  save named selections, colour-fill/copy/cut/paste within a selection.
- **Effects** — Canny edge outline, sketch, ML Kit subject isolation, multi-layer stencil generation.
- **Text** — parametric text layers with Google Fonts.
- **Vector shapes** — rectangle, ellipse, line, and regular polygons as their own editable layers.
- **One 3D space** — every layer is a plane that can be moved, pushed back and tilted in XYZ,
  seen through one perspective camera, with no separate 3D mode. Two fingers pan, zoom and twist
  the view about the current axis (Z rotates the canvas; X and Y orbit the camera in depth); with
  the Move / Transform tool they move, push and rotate the active layer instead. Layers stay 2D or
  3D by their group: the rail's axis slot reads "3D" while the active layer's group is flat; a tap arms
  "Draw surface" (the slot turns bright white and reads "Draw" and a chip says to drag one line; Cancel, Back or the slot
  again changes nothing, and a layer in no group is grouped only once the line is drawn); on a 3D layer it (or a
  double-tap) steps X / Y / Z and always shows the current one. A hairline slider docked at the bottom of the screen while the bottom
  sheet is closed (and live while drawing) rotates the active layer (about Z, or the current axis on a 3D layer). Every brush and tool
  paints on the tilted plane where the finger touches it. A document never moved in 3D draws exactly
  as before. A layer group can own a surface: "Draw surface" in its menu turns the next stroke
  into a profile curve swept into depth, and the group's layers are painted on that shape
  ("Flatten surface" makes it flat again). Its guide grid follows the shape in perspective, so
  strokes can be traced in depth without moving the view. Groups keep their tile on the layers rail,
  open by default. See `spec/3d-space.md`. (A group's surface can also be drawn from the side and top; the outlines intersect into one 3D shape. You can paint on every face: each view has its own paint image, and a stroke that crosses from one face to another is one undo step. The faces a drawn view does not bound (caps) are drawn and paintable too, paint you made on the flat surface before drawing a second view is carried onto the shape, duplicating a layer copies its view paint, and Fill (which spreads across creases), Clone (with its reticle) and the lasso (which skips faces turned away) work on the right face. Moving a selection's pixels and Automatic selection are not offered on these shapes yet. The painted shape shows in the canvas and exports to glTF with its textures.)
- **3D strokes layers** — draw real XYZ pressure-sensitive curves against adjustable camera-facing
  planes, anchored planes, cylinders and spheres, in a layer of the stack composited with the paint
  layers (View → 3D → New 3D strokes layer). Missing a bounded guide ends that segment rather than
  bridging empty space; editing a guide never moves existing curves. Erase and undo/redo with the
  editor's history. Projects with the old Spatial Sketch open with it as a 3D strokes layer
  (project format v7).
- **glTF scene export** — File → Export 3D scene (glTF): each paint, text and vector layer as a
  textured quad at its placement, each 3D strokes layer as tube geometry, and the camera. PNG, PDF
  and Share render the 3D placement as seen from the current camera; a flat document exports as
  before.
- **Per-pixel depth** — planes that cross in space, and 3D strokes among them, hide each other
  pixel by pixel on the GPU.
- **3D model layers** — import an `.obj` as a layer in the same 3D space as every other layer:
  moved with the same Move-tool gestures, painted on its UV surface with the brush and eraser,
  depth-tested against planes and 3D strokes, and exported to glTF with its painted texture.
- **Animation Assist** — each top-level layer as one frame, with onion skinning, playback, and
  time-lapse recording of a drawing session.
- **Reference & import** — a floating reference image that stays on top while you draw; import
  frames from a Figma file (via a personal access token) as layers; import a Procreate document.
- **Extensions (azphalt)** — install signed filter/LUT/code extensions from a separate store app or
  an `azphalt://` deep link (confirmed before install); code extensions run sandboxed (Chicory
  WASM/QuickJS, deny-by-default capabilities, bounded memory and execution time — see
  `spec/package-format.md`).
- **Artboard** — a fixed document size (social / print / custom presets) with a visible frame.
- **Export & share** — save the composite to the gallery, or hand it to another app (e.g. GraffitiXR).
  Poster PDF tiles the artwork (after the document crop) at real size across Letter, A4, A3 or
  Legal pages, with margins, overlap, crop and registration marks and row/column labels.

The UI is driven by [AzNavRail](https://github.com/HereLiesAz/AzNavRail) — the rail hosts the design
tools; the canvas is its full-screen background. See `ARCHITECTURE.md` for module boundaries,
invariants, and the AzNavRail version floors the layers panel depends on.

## Architecture

MVI: a pure `EditorReducer` maps `EditorIntent`s to the next `EditorUiState`; `EditorViewModel`
orchestrates side effects (history, persistence) around each dispatch.

| Module | Responsibility |
|---|---|
| `:app` | The Graffux application shell — `MainActivity` hosts the AzNavRail + the shared editor. |
| `:feature:editor` | The editor: reducer, view-model, canvas, panels, brush/stroke engine, export. |
| `:core:engine` | Kotlin Multiplatform engine shared with desktop: azphalt brush/manifest models, dab generation. |
| `:desktop` | The desktop (Linux/Windows) Compose app; see `DESKTOP.md`. |
| `:core:common` | Models (`Layer`, `EditorUiState`, `GraffitiProject`), serialization, shared utilities. |
| `:core:domain` | Repository interfaces. |
| `:core:data` | Project + settings persistence. |
| `:core:design` | Design system: theme, strings, reusable components. |
| `:core:nativebridge` | JNI bridge to the native world: the Liquify warper (`LiquifyEngine`) and the GPU brush engine. |

Brushes paint on the GPU through the wgpu engine (`core/wgpu-engine`, shared with the desktop app).
There is no CPU renderer: on a device where wgpu can't start, strokes are not painted and the App
says so (`HardwareFloorNotice`). The wgpu library ships for both 64-bit
(arm64-v8a) and 32-bit (armeabi-v7a) devices, so both paint on the GPU. wgpu is the only GPU backend: the earlier
Vulkan and OpenGL ES engines, and the Settings switch between them, were retired (see
`ARCHITECTURE.md`, Decisions, and `docs/Native Rendering Engine Design.md` §2c).

The `:core:*` and `:feature:editor` modules keep the `com.hereliesaz.graffitixr` namespace — they are
the shared single source of truth. Only `:app` is Graffux-specific (`com.hereliesaz.graffux`).


## Roadmap

Graffux is growing toward a full, touch-first design tool. Near-term:

- Bind layers to the artboard (letterbox) and export at exact document pixels.
- Snapping, smart guides, and align / distribute.

## License

See the repository for license details.
