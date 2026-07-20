# Graffux

A touch-native, multi-layer **image editor for Android** — sketch, paint, retouch, and composite
with layers, blend modes, curves, text, and stencils. Built entirely in Kotlin + Jetpack Compose.

Graffux is the standalone 2D editor extracted from [GraffitiXR](https://github.com/HereLiesAz/GraffitiXR)
(an AR mural-projection app). It hosts the shared editor stack as the single source of truth; GraffitiXR
consumes the same modules for its 2D work and adds AR on top.

## Features

- **Layers** — add image or blank layers, reorder, rename, toggle visibility, duplicate, link, flatten.
- **Transform** — move / scale / rotate by gesture, or type exact values in the numeric transform panel.
- **Adjustments** — opacity, brightness, contrast, saturation, colour balance, curves, invert.
- **Paint** — brush and eraser with size + feathering; OpenCV-backed Liquify.
- **Effects** — Canny edge outline, sketch, ML Kit subject isolation, multi-layer stencil generation.
- **Text** — parametric text layers with Google Fonts.
- **Artboard** — a fixed document size (social / print / custom presets) with a visible frame.
- **Export & share** — save the composite to the gallery, or hand it to another app (e.g. GraffitiXR).

The UI is driven by [AzNavRail](https://github.com/HereLiesAz/AzNavRail) — the rail hosts the design
tools; the canvas is its full-screen background.

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

## Building

Standard Gradle Android build. OpenCV is pulled from Maven Central (`org.opencv:opencv`), whose Prefab
part also exposes the native C++ world to CMake — nothing to vendor.

```bash
./gradlew :app:assembleDebug
```

Requirements: JDK 17, Android SDK (compileSdk 37), minSdk 26.

## Roadmap

Graffux is growing toward a full, touch-first design tool. Near-term:

- Bind layers to the artboard (letterbox) and export at exact document pixels.
- Snapping, smart guides, and align / distribute.
- Vector layers (shapes, pen, live text) alongside the raster engine.

## License

See the repository for license details.
