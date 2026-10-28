# Design tools (Phase 1: design essentials)

Status: frames, the properties (sheet and side panel), constraints, and the styles and components
panels and SVG / PDF export are implemented: Phase 1 is complete.
Source of truth for the UI: the approved "Graffux UI Rework" canvas, the "Design tools" row (boards
DesignFrames, DesignComponent, DesignInspector, DesignInspect, DesignTablet, "Tablet · properties
side panel", DesignDesktop). Live presence on those boards is out of scope: collaboration is
comments only, and comments are a later phase.

## Frames

A frame is an ordinary `Layer` of type `LayerType.FRAME` (`core/common` `EditorModels.kt`): a
container like a group, with a box of its own.

~~~
Layer (as a frame)
  layoutWidth, layoutHeight   the box, centred on the frame's origin
  frameFillArgb               fill drawn behind the children; null = none
  frameRadius                 corner radius
  clipsContent                clip the children to the box
~~~

A document holds any number of frames on the unbounded canvas. `FrameOps` (`core/common`) is the
pure geometry: creating and naming frames, placing a new one beside the rest (80 apart, tops
aligned), world transforms through nested parents, hit tests on a frame's box and name label,
reparenting a layer without moving it, Figma's drop rule (a layer dropped inside a frame moves into
it; dragged out, it leaves), adopting what a newly drawn frame covers, and setting a box (children
follow `LayoutOps.applyResize`, so their constraints apply).

Rendering stays on the existing path: `LayerStackNode` draws a frame as a group whose
`graphicsLayer` clips to the frame's box (on its own RenderNode) with the fill drawn behind; the
exporter (`ExportManager.drawNode`) clips and fills the same box. No per-frame CPU pass is added.

The document's artboard (`documentWidth` x `documentHeight`) stays the page it was. Raster layers
are fitted to the screen's canvas, so the artboard has no fixed size in world units and cannot be
turned into a frame without changing where every existing layer sits.

## Saving

project.json format 4 (`GraffitiProject.formatVersion`). A frame is written as `layerType: GROUP`
plus `isFrame`, `frameFillArgb`, `clipsContent` and `frameRadius` on `OverlayLayer`, so an app that
predates frames opens the file and shows the frame as a group. `ProjectManager.migrateFormat` 3 -> 4
turns a sized group from an older file (a group given a Frame Size) into a frame with no fill and no
clip, so it looks exactly as it did; files of every older version still open.

## The Frame tool

The rail's Frame circle, after Transform (lit as a filled ink circle, like Adjust). `Tool.FRAME` is
appended last to `Tool`: strokes persist their tool by ordinal.

- Drag on empty canvas: draws a frame; artwork under it moves into it. The new frame is selected.
- Drag on a layer: moves it, then drops it into or out of a frame.
- Drag a selected frame's corner handle: resizes it; its children follow their constraints.
- Tap: selects the artwork under the finger, else the frame, else a frame by its name label;
  a tap on nothing clears the selection.
- A second finger is the camera's. A drag hides the chrome as a stroke does; a tap does not, so the
  properties stay up to show the selection.

Every frame shows its name above its top-left corner; with the tool in hand the selection shows its
outline, its size, and a frame's handles (`FrameOverlay`, `FrameToolCanvas`, `feature/editor`).

## Properties

The same sections render in two places (`DesignSheet.kt`, `DesignControls.kt`, `app`):

- **Bottom sheet** (phones, and tablets set to it): picking up the Frame tool opens the sheet on the
  design tab list (LAYOUT: Frame). The tab's body shows the selected layer: X, Y, W, H; rotation,
  radius, opacity; for a frame, Clip and Fill. Nothing selected: a hint and Add frame (390 x 844).
- **Side panel** (tablet and desktop, smallest width 600dp and up, by default): 320dp on the layers'
  side, the selected layer's kind and name, the Design tab, every section stacked, and at its foot
  "Show properties in: Side panel / Bottom sheet". While it is docked the layers rail moves beside
  the tool rail and the logo menu keeps its corner over the panel's header.

The choice is the `properties_placement` preference (`SettingsRepository.propertiesPlacement`),
also in Settings on a tablet. A phone always uses the sheet.

X / Y / W / H are the layer's world box (`DesignGeometry`). Setting them sets a frame's box exactly;
a vector stretches; a raster or text layer scales uniformly. Each edit is one undo step; a drag is
one undo step for the whole gesture.

## Constraints, styles and components

On the models that already existed (`LayoutOps` / `Constraints`, `StyleOps`, `ComponentOps`);
`DesignPanels.kt` (`app`). Each is a tab in the sheet (LAYOUT: Constraints; STYLE: Fill, Stroke,
Text; COMPONENT: Components) and a section in the side panel. The sheet's Frame tab also shows the
pins and the shape's fill and stroke after its fields, as the mockup's Properties board does.

- **Constraints**: Pin X (Left, Right, L+R, Center, Scale) and Pin Y (Top, Bottom, T+B, Center,
  Scale) set `Layer.constraints` (START, END, STRETCH, CENTER, SCALE). Offered only for a layer
  inside a frame whose parent does not run auto layout. A frame's resize handles and its W / H
  then move and resize its children by them. T+B (vertical stretch) is the app's; the mockup's Pin Y
  leaves it out.
- **Fill / Stroke**: the shape's colour, or the colour style it is bound to ("style"), the document's
  colour styles (Apply / Linked to detach, Update to the colour in hand, delete), and + / "New style
  from fill". A frame's own fill stays in the Frame section.
- **Text**: the document's text styles for a text layer: apply, update from the layer, delete, new.
- **Components**: Make component, or for an instance Go to main and Detach, or for a main Release;
  then every main component with its instance count, to select or Place a new instance of.

## Export: PNG, SVG, PDF

The Export tab (HANDOFF) in the sheet and the Export section in the side panel. It exports the
selected frame (or the frame the selection sits in), else the document's page, as the chosen format,
into Downloads (PNG to the gallery). A PDF can take every top-level frame, a page each.

One scene walk, two sinks (`feature/editor` `export/`):

- `VectorScene` walks the layer tree in the raster exporter's order with its transforms
  (`ExportManager.drawNode`, `getLayerScreenMatrix`) into a small display list: groups and frames
  (with a frame's fill and clip box), shapes as path outlines (rectangles with their radius,
  ellipses, lines, polygons, pen paths with their curves), and pixel layers as images. A frame's
  page is exactly its box; the document's page is the artboard at document size.
- `SvgWriter` writes it as SVG text: `<g>` with `matrix(...)`, a frame's `<clipPath>` and fill
  `<rect>`, `<path>` per shape, PNG `<image>` per pixel layer, group opacity, and the blend modes CSS
  has (`mix-blend-mode`).
- `PdfExport` draws it on Android's `PdfDocument` page canvas, which keeps paths as PDF vectors and
  bitmaps as images. Group opacity is carried down to each paint; blend modes are not kept.
- A frame's PNG is `ExportManager.compositeFrame`: the frame's subtree composited on the existing
  path, cut to its box.

Raster, text and photo adjustment layers go in as images of their current pixels: text is not
written as live SVG / PDF text (fonts would not travel with the file). The desktop app has no
layers or frames, so it has no SVG / PDF export to add.

## Not in this phase

Auto layout UI, variants, prototype, Inspect code, comments and presence (later phases of the
Figma-parity plan).
