# Photo adjustment layers

Status: implemented (Light, Color). The tabs still to come are listed under "Tabs".
Source of truth for the UI: the approved "Graffux UI Rework" canvas, boards PhotoEntry …
PhotoDesktop and their notes.

## Model

An adjustment layer is an ordinary `Layer` whose `adjustment: PhotoAdjustment` is set
(`core/common` `EditorModels.kt`; the model lives in `core/engine` `common/photo/`, shared with the
desktop app).

~~~
PhotoAdjustment
  tool        PhotoTool        LIGHT COLOR HSL CURVES DETAIL EFFECTS HEAL RED_EYE
  values      Map<String, Float>  slider id -> value; a missing id is that slider's default
  curves      Map<CurveChannel, List<CurvePoint>>
  mask        AdjustmentMask   enabled, inverted, file, revision
  healSpots   List<HealSpot>
  redEyeSpots List<RedEyeSpot>
~~~

The sliders, their ranges, defaults and how they print are `PhotoSliders` (`PhotoSliders.kt`).
Crop is not a layer: it is `DocumentCrop`, a whole-document setting. Local is not a layer either: it
paints the mask of the selected adjustment layer.

The layer keeps no pixels of its own. Its `bitmap` is a render: the layers below it in its own
parent, composited, adjusted, times its mask. `AdjustmentLayerRenderer` (`feature/editor`) keeps it
current. The canvas, the layers rail and every compositor draw it as an ordinary layer. So an
adjustment layer can be hidden, reordered, grouped, given an opacity or blend mode, and deleted,
all through the existing layer actions.

## Editing

- Each tab that adjusts the photo edits its own layer. The first slider move on a tab whose layer
  is not the selected one makes a new layer of that tab's tool, **directly above the selected
  layer** (`LayerListOps.insertAbove`: same parent, past a group's children).
- One gesture (a slider drag, a curve point, a swatch) is one undo step: `onPhotoGestureStart`
  pushes history and, the first time, creates the layer in the same step. Undo therefore removes
  a layer that the gesture created.
- A double tap on a slider puts it back to its default (one step). Reset in the tab header resets
  every setting of the tab's layer (one step); the layer stays.
- Hold-to-compare sets `EditorUiState.photoCompare`. While it is set the canvas draws no
  adjustment layer. It is transient UI state, never in history.
- A paint tool picked while an adjustment layer is selected selects the nearest ordinary layer
  under it, or makes a blank layer if there is none. Paint never lands on an adjustment layer.

## Rendering

~~~
layers change ──> sync(): per adjustment layer (bottom first)
                    sourceKey = what draws the layers below (their data, bitmap identity, generation)
                    renderKey = sourceKey + the layer's adjustment
  source changed ─> CPU composite of the layers below (once per change) ─> upload to the GPU
  render changed ─> GPU: one compute pass + readback ─> double-buffered bitmap ─> SetAdjustmentRender
~~~

- GPU: `core/wgpu-engine/src/adjust.rs` with `shaders/adjust.wgsl`. It is its own wgpu device,
  apart from the stamp engine's. JNI: `WgpuNative.nativeAdjust*`, wrapped by
  `WgpuPhotoAdjuster` (`core/engine` jvmShared). The Detail tab's blurred luma depends only on the
  source, so `adjust_blur.wgsl` runs once per upload, not per slider move.
- A slider drag never composites on the CPU. Each move is one dispatch over the composite plus a
  readback of `width × height × 4` bytes. Measured on lavapipe (Mesa's software Vulkan, 4 cores)
  at 1080 × 2400: about 37 ms a move (`PhotoAdjustGpuParityTest` prints it). A phone GPU should be
  well under that. The number is an upper bound.
- No GPU (no wgpu adapter, JVM tests): `PhotoAdjustMath.render`, the CPU reference, gives the same
  pixels.
- Export and thumbnails: `ExportManager.compositeLayers` renders each adjustment layer again from
  what its canvas holds at that point (`ExportManager.adjustmentRenderer`). It does this on the
  GPU where there is one. A composite made *for* the live renderer passes
  `renderAdjustments = false` and draws the live renders.
- Known limit: while a stroke is in progress on a layer under an adjustment layer, the stroke is
  drawn beneath that layer's last render. It shows adjusted once the stroke commits.

## Formulas

`PhotoAdjustParams.pack` turns a `PhotoAdjustment` into a 56-float block. `PhotoAdjustParams.kt`
documents the layout. `adjust.wgsl` and `PhotoAdjustMath` read the same block, and their steps
follow the same order:

1. White balance (`whiteBalance`: Tanner Helland black-body fit, 6500 K neutral,
   luminance-normalised; tint scales green) and exposure (`2^EV`), in linear light.
2. Tone, per channel. Blacks and whites move the end points by up to 15%. Shadows and highlights
   add `0.9·s·x(1−x)²` and `0.9·h·x²(1−x)`. Contrast blends toward smoothstep.
3. Detail: clarity, texture and sharpen add local contrast from blurred luma. Noise reduction
   blends toward the small blur.
4. Dehaze, then curves (master, then per channel), then levels (black, gamma, white).
5. HSL: eight ranges centred at 0, 30, 60, 120, 180, 240, 270 and 300°, with linear weights
   between neighbours.
6. Vibrance and saturation around Rec. 709 luma.
7. Vignette, then grain (a hash per cell, the same on every device), then the mask.

Tests: `PhotoAdjustMathTest` checks the formulas against hand-computed values.
`core/wgpu-engine/tests/adjust.rs` checks the GPU against a CPU port. `PhotoAdjustGpuParityTest`
checks the GPU against `PhotoAdjustMath` with every stage on.

## File format

`GraffitiProject.formatVersion` is the project.json format.

| Version | Change |
|---|---|
| 1 (no key) | Before adjustment layers. |
| 2 | `OverlayLayer.adjustment`. |

`ProjectManager.decodeProject` reads the key before it decodes, then runs `migrateFormat` one step
at a time. Step 1 → 2 changes no data. On disk an adjustment layer's `layerType` stays `RASTER`, so
an older build still opens the file and shows the layer as an empty one.

## Tabs

The sheet shows the tabs in `PHOTO_TABS` (`app` `PhotoSheet.kt`). The rail's Adjust circle comes
after Transform. It swaps the sheet's tab list to the photo tabs and reopens the last tab used; the
first time, it opens at the tab list. Any other tool puts the sheet back on brushes. Tapping an
adjustment layer's tile opens the sheet on that layer's tab.

Dragging a slider hides all chrome except the row under the finger (`PhotoDrag`). That row stays
in place as a pill showing its live value. The sheet does not move and the scrim goes. Everything
comes back when the finger lifts.
