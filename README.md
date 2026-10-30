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
- **Paint** — brush and eraser with size + hardness, colour, node-edited vector paths (Pen), Heal,
  Clone, Blur, Sharpen, Smudge, Liquify (native GLES warper), Dodge, Burn, Colorize, plus
  wraparound painting mode. Brush Studio for building custom brushes.
- **Selection** — freehand/rectangle/ellipse/automatic (magic-wand) selection, Subject (ML Kit
  subject isolation, on the device and offline), add/remove/replace,
  save named selections, colour-fill/copy/cut/paste within a selection.
- **Effects** — Canny edge outline, sketch, ML Kit subject isolation, multi-layer stencil generation.
- **Text** — parametric text layers with Google Fonts.
- **Vector shapes** — rectangle, ellipse, line, and regular polygons as their own editable layers.
- **3D spatial sketch (first slice)** — draw pressure-sensitive world-space curves on a
  camera-facing depth guide, orbit to inspect parallax, erase and undo/redo curves, and reopen them
  from a saved `.fux`. Open View → 3D → Sketch in 3D. The live workspace now draws the same
  tube triangles used by glTF export through GLES 3.0 with a real depth buffer. Curved/primitive
  guides, mesh-guided curves, transforms and
  OBJ export and advanced glTF materials remain outstanding; the sketch toolbar exports
  self-contained glTF 2.0 tube geometry.
- **3D model texture painting** — load an `.obj`, paint directly on its UV surface, and add the
  painted view as a flattened 2D layer. This remains independent of spatial strokes.
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

Brushes paint on the GPU through the wgpu engine (`core/wgpu-engine`, shared with the desktop app),
and on the CPU on any device where wgpu can't start. The wgpu library ships for both 64-bit
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
