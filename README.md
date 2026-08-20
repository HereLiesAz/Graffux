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
  Clone, Blur, Sharpen, Smudge, Liquify (OpenCV-backed), Dodge, Burn, Colorize, plus symmetry and
  wraparound painting modes. Brush Studio for building custom brushes.
- **Selection** — freehand/rectangle/ellipse/automatic (magic-wand) selection, add/remove/replace,
  save named selections, colour-fill/copy/cut/paste within a selection.
- **Effects** — Canny edge outline, sketch, ML Kit subject isolation, multi-layer stencil generation.
- **Text** — parametric text layers with Google Fonts.
- **Vector shapes** — rectangle, ellipse, line, and regular polygons as their own editable layers.
- **3D** — load an `.obj` model, paint directly on its surface, and drop the painted view back onto
  the canvas as a layer.
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

The UI is driven by [AzNavRail](https://github.com/HereLiesAz/AzNavRail) — the rail hosts the design
tools; the canvas is its full-screen background. See `ARCHITECTURE.md` for module boundaries,
invariants, and a currently-open upstream AzNavRail bug affecting the layers panel.

## Architecture

MVI: a pure `EditorReducer` maps `EditorIntent`s to the next `EditorUiState`; `EditorViewModel`
orchestrates side effects (history, persistence, OpenCV) around each dispatch.

| Module | Responsibility |
|---|---|
| `:app` | The Graffux application shell — `MainActivity` hosts the AzNavRail + the shared editor. |
| `:feature:editor` | The editor: reducer, view-model, canvas, panels, brush/stroke engine, export. |
| `:core:common` | Models (`Layer`, `EditorUiState`, `GraffitiProject`), serialization, shared utilities. |
| `:core:domain` | Repository interfaces. |
| `:core:data` | Project + settings persistence. |
| `:core:design` | Design system: theme, strings, reusable components. |
| `:core:nativebridge` | JNI bridge to the native (OpenCV) world used by Liquify and drawing. |

The `:core:*` and `:feature:editor` modules keep the `com.hereliesaz.graffitixr` namespace — they are
the shared single source of truth. Only `:app` is Graffux-specific (`com.hereliesaz.graffux`).


## Roadmap

Graffux is growing toward a full, touch-first design tool. Near-term:

- Bind layers to the artboard (letterbox) and export at exact document pixels.
- Snapping, smart guides, and align / distribute.
- A real upstream fix for the AzNavRail bug that currently leaves the layers panel's rows
  unclickable (see `ARCHITECTURE.md` invariant 7) and the nested-group hidden-menu gap
  (invariant 8) — both filed against `HereLiesAz/aznavrail`, not fixable from this repo alone.

## License

See the repository for license details.
