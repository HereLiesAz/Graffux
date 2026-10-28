# Photo adjustment layers

Status: implemented (every tab: Light, Color, HSL, Curves + Levels, Detail, Effects, Crop, Heal, Red-eye, Local). The tabs still to come are listed under "Tabs".
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
| 3 | `GraffitiProject.documentCrop`. Absent means no crop. |

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

### HSL

Eight swatches, one per `HueRange` (the mockup's colours), with the picked one ringed. Below them
are Hue, Saturation and Luminance for that range. Hue moves ±30° at ±100. Luminance is scaled by
the pixel's own saturation, so greys never move. The header's eyedropper arms
`HslEyedropperOverlay` over the canvas. A touch there picks the range of the colour under the
finger (`EditorViewModel.photoHueRangeAt`, read from a composite of the photo made once when the
eyedropper is armed). Dragging then raises (up) or lowers (down) that range's **saturation**, 100
per 200dp. The mockup's note does not say which component the drag changes; saturation is the one
picked here.

### Curves and Levels

Channel chips (RGB, Red, Green, Blue) sit above the existing `CurvesAdjustment` editor (feature
editor), restyled with `CurvesStyle.Photo`. Its new `editablePoints` mode works like this: tap the
line to add a point; drag a point off the top or bottom of the graph to remove it. The ends stay.
The line drawn is the curve's own LUT (`CurveLut`, monotone cubic). Behind it is the luma
histogram of the photo under the layer (`EditorViewModel.photoHistogram`: 64 bins, square-rooted,
recomputed when the tab opens and after each undoable change). Levels is the row under the graph:
black point, midpoint and white point. The midpoint handle sits at `0.5^(1/gamma)` of the way from
black to white.

### Detail and Effects

Detail has Clarity, Texture, Dehaze, Noise reduction and Sharpen; Noise reduction and Sharpen are
unipolar and start at 0. Effects has Vignette amount, Midpoint, Feather, Grain amount and Grain
size. They are plain slider lists. Their pixel work is in the shader (Detail reads the blurred-luma
planes).

### Crop (geometry)

Crop is a whole-document setting, `EditorUiState.documentCrop` (saved as
`GraffitiProject.documentCrop`), not a layer. Every pixel is kept. `CropGeometry.matrix`
(core/engine) is a 3×3 matrix about the artboard centre. It applies, in order: a keystone for
Vertical and Horizontal perspective (±100 narrows or widens the far edge by 40%), the flips, the
quarter turns and Straighten (±45°). The crop rectangle is a set of fractions of the artboard,
applied after the transform as a clip.

- The canvas (`Modifier.documentCrop`, wrapped around the layer stack and the artboard page) draws
  through the matrix and clips to the crop. While the Crop tab is open (`cropEditing`) it does not
  clip, and `CropOverlay` dims everything outside the crop. The overlay draws the frame and
  L-shaped corner handles, and shows the thirds grid only while a handle is held. It draws in the
  canvas camera's own transform, so it stays on the photo when the view moves.
- Gestures: drag a corner or edge to resize (an aspect chip holds the ratio from the width), drag
  inside to move, twist with two fingers to straighten. A gesture is one undo step. Undo restores
  the crop because `EditCommand.PropertyChange` now records the crop alongside the layers.
- Chips: Free, Original, 1:1, 4:5, 3:2, 16:9, 9:16 (`CropGeometry.withAspect`: the largest
  rectangle of that ratio, centred where the crop is). Rotate 90° turns the content and the crop
  together. Flip and Flip are horizontal and vertical. Auto level (`CropGeometry.autoLevel`) builds
  a strength-weighted histogram of Sobel edge directions near horizontal and vertical (±15°, 0.5°
  bins) and sets Straighten to undo the peak. Each of these is one undo step.
- Export, share, the thumbnail and GIF export draw through `DocumentCropRender.render`. That is the
  same matrix, cut to the crop rectangle, at the document's resolution scaled by the crop's share
  of the artboard.
- Not done: the frame does not constrain itself to stay inside the straightened image (Lightroom's
  "constrain to image"). Straightening can leave empty corners until the frame is pulled in.

### Heal and Red-eye

Each has **one** layer that holds all of its spots (`PhotoAdjustment.healSpots`,
`redEyeSpots`). The first spot creates the layer above the selected one; later spots go on the same
layer wherever it is. These layers do no shader work. The renderer writes only their spots, from
the composite below, with `RetouchMath` on the CPU. That happens once per change, and a spot is
small.

- Heal applies the Clone tool's idea (copy from an offset) content-aware. `autoSource` picks a
  source 2.2 to 3.6 radii away in 16 directions, whichever ring of pixels best matches the spot's
  own ring. The patch is then shifted by the difference between the two rings' mean colours, so a
  lighter source lands at the target's brightness. Clone mode (the chip) copies as is. The overlay
  draws the target (solid) and the source (dashed) with a line between them. Drag the dashed ring
  to re-pick. Brushing over an area heals the circle that holds the brushed path. Size, Feather and
  Opacity shape the next spot.
- Red-eye: tap each eye. Inside the circle, red becomes the mean of green and blue wherever red
  clearly leads (r > 1.4 × both); the outer 30% fades.

### Local (masks)

Local paints the mask of the selected adjustment layer. With none selected, the first stroke makes
a Light layer. Masks are one byte per world pixel in `AdjustmentMasks`, keyed by layer and
revision. Each stroke makes a new revision. The layer's `AdjustmentMask.revision` says which
revision it uses, so undo and redo simply move between revisions. 12 revisions are kept per layer.

- Brush adds and Erase removes, both with a soft disc (`MaskPaint.dab`, Flow builds up across
  passes). A layer's first Brush stroke starts from an empty mask, so the effect is only where it is
  painted. A first Erase starts from a full mask.
- From selection rasterises the lasso selection (`SelectionMask`, with its feather), including a
  Subject selection (the selection carousel's Subject, ML Kit subject isolation of the selected
  layer). Invert flips a flag; no pixels change. Reset turns the mask off.
- The overlay draws the mask in white over the photo. The layer's tile gets a badge: a real
  thumbnail of the mask at its bottom-left.
- The GPU multiplies by the mask (`set_mask` is uploaded once per change of revision or stroke
  generation). The CPU paths do the same.
- Saving: the mask is written as a PNG beside the layer files (`mask_<layer>_<revision>.png`, white
  with the mask as alpha) and recorded in `AdjustmentMask.file`. On load it is read back and scaled
  to this screen's world size.
- Limit: while the Heal, Red-eye or Local overlay is up, it takes every touch on the canvas,
  two-finger pan and zoom included.

## Tablet and keyboard

On a tablet the sheet is the same, a 390-wide slider column centred over the tab list (PhotoTablet).

With a hardware keyboard (a tablet's, a Chromebook's, or Android desktop windowing), handled in
`photoShortcut` and `nudge` (`app/.../PhotoSheet.kt`):

- **A** opens Adjust, or leaves it, exactly as the rail button does.
- **\\** held compares with the original while photo mode is on; releasing it shows the edit again.
- **← / →** nudge the focused slider by 1% of its range, **Shift** for 10%. Each press is one undo
  step. Touching a row focuses it, and Tab moves between rows.

The PhotoDesktop board's letter hints beside every rail button are not drawn: the app's rail has
no hints for any tool, and adding them is a rail-wide change outside the photo tools. The
`desktop` module is a separate single-canvas app with no layers, so it has no Adjust.
