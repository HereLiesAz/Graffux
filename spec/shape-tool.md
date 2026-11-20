# The Shape tool: the rail's Frame tool becomes a general shape tool, with framing as one option

Status: phase 1 implemented (below); phases 2 to 4 are design only. Written against `main` at 1.88.0.1 (`CURRENT_FORMAT_VERSION` = 14, `GraffitiProject.kt`). Every statement about existing code was read in the tree; anything unmeasured is marked "unmeasured".

This document extends `spec/design-tools.md` ("The Frame tool", "Frames", "Saving", "Export"). It uses the vocabulary of `spec/3d-space.md` (plane, scene camera, view plane, depth pass) without redefining it.

Owner brief, decided (every choice below is decided; there are no open questions):

- The rail's Frame tool (`Tool.FRAME`, `FrameOps`, `FrameToolCanvas`, `LayerType.FRAME`) becomes a general **Shape** tool with framing options.
- Today's Frame behaviour stays available as the **Frame** option, with no regression and no format break: `Tool.FRAME`'s ordinal stays, tools are only ever appended, and an older `project.json` opens.
- A cropping tool already exists elsewhere, so the icon is not a crop or corner-bracket glyph, and it is not "#" (the grid toggle's).
- Undo and history code, and the docs outside `spec/`, are not touched.

## 1. Goals and non-goals

Goals

- G1. Draw a rectangle, rounded rectangle, ellipse, line, polygon or star by dragging on the canvas, with fill, stroke and corner radius, as one undo step.
- G2. Frame mode behaves exactly as it does today: the same gestures, the same properties, the same saved bytes, the same export.
- G3. Shapes are the app's existing vector layers. No new layer type, no new persisted enum value, no new CPU render pass.
- G4. Shapes take part in constraints, auto layout, the properties sheet and side panel, and PNG / SVG / PDF export the way any vector layer does today.
- G5. No project-format change. A file written with shapes opens in the build before this one.

Non-goals

- N1. Boolean operations, shape masks, an ellipse-clipped frame, shapes that contain children. Only frames are containers.
- N2. Replacing the Pen (`Tool.PEN`) or the node editor (`PathEditOverlay`). Freeform paths stay the Pen's.
- N3. A cropping tool. Cropping is the artboard's and the Transform group's job; this tool never crops.
- N4. Changing the existing Vector-group entry points `onAddShapeLayer` and `onAddPolygonLayer` (they stay; section 5.1 shares their defaults).

### What exists today

- `Tool.FRAME` is the last entry of `Tool` (`UiState.kt`); a stroke persists its tool by ordinal (`BrushStroke.blendModeOrdinal`), which is why it is last.
- The rail's Frame circle is a `ModeButton` (`ToolRail.kt`) with `RailIcons.Frame`, a "#" (`M7 3v18M17 3v18M3 7h18M3 17h18`), tag `rail.tool.frame`, label string `rail_frame`. `TOOL_CATALOG` lists `Tool.FRAME` as id `tool.frame`, label "Frame", glyph `GraffuxIcons.Artboard`. The same "#" is the surface-mesh / canvas-grid toggle (`SurfaceMeshButton`), which is why it cannot be this tool's glyph.
- `FrameToolCanvas` (`FrameCanvas.kt`) owns the gestures: tap selects (`actions.tap`), a drag on empty canvas draws a frame (`actions.commitFrame`), a drag on a layer moves it (`dragStart`, `move`, `dragEnd`), a drag on a selected frame's corner handle resizes it (`resize`), a second finger is the camera's. It draws the rubber band as one `drawRect` and one stroked `drawRect`.
- `FrameOps` (`core/common`) is the pure geometry; `LayoutOps` applies a box change to the children (`applyResize`, `applyAutoLayout`, `hugContentsSize`, `resizeLayer`). **`LayoutOps` has no snapping**; section 7 says where snapping goes.
- `ShapeKind { RECTANGLE, ELLIPSE, LINE, POLYGON, PATH }` and `VectorShape` (`VectorShape.kt`) already model every shape this tool needs. A rectangle has `cornerRadius`; a line is a horizontal run of `width` centred on the origin, drawn with the stroke; a polygon is a regular `sides`-gon inscribed in the box; a path is interleaved points, optionally closed (fillable), with optional bezier handles. The Vector group already creates them (`EditorViewModel.onAddShapeLayer`, `onAddPolygonLayer`) as `LayerType.VECTOR` layers with `shapes` set.
- `VectorScene` (`export/`) already writes every `ShapeKind` to the display list that `SvgWriter` and `PdfExport` read, and the canvas draws them (`EditorScreen.drawVectorShape`). The existing Frame / Fill / Stroke / constraint panels already act on vector layers (`design-tools.md`, "Properties", "Constraints, styles and components").
- `ProjectManager`'s `Json` has `ignoreUnknownKeys = true` and **no** `coerceInputValues`. An unknown field is ignored by an older build; an unknown enum value (a new `ShapeKind` or `LayerType`) makes it fail to decode the whole project. That asymmetry decides section 5.

## 2. Naming and identity (nothing renamed that is persisted or tested)

- The enum constant stays `Tool.FRAME`, at its current ordinal. No `Tool.SHAPE` is added: a second constant would give the rail two tools for one circle and would have to be appended, leaving `FRAME` as a dead ordinal. Tool `FRAME` is "the Shape tool"; its KDoc is rewritten, nothing else about it.
- Tool id `tool.frame`, rail test tag `rail.tool.frame`, and the canvas tag `design.frameTool` are unchanged, so every existing UI test and the `activeRailClassifiers` mapping keep working.
- The user-visible name changes from "Frame" to "Shape" (rail label, `TOOL_CATALOG` label). "Frame" becomes the name of the first mode. Strings are in section 10.
- `LayerType.FRAME`, `Layer.isFrame`, `FrameOps`, `FrameToolCanvas`, `FrameOverlay` keep their names. Framing is a thing a shape tool can do, and renaming them buys nothing and risks the saved form (`isFrame` is a serialized key).

## 3. The mode: Frame or a shape

One state value, not a tool: `ShapeToolMode`.

~~~
enum class ShapeToolMode { FRAME, RECTANGLE, ROUNDED_RECTANGLE, ELLIPSE, LINE, POLYGON, STAR }
~~~

- It lives in `EditorUiState.shapeToolMode` (default `FRAME`). It is a **preference**, not project data: `SettingsRepository.shapeToolMode`, key `shape_tool_mode`, stored as the enum's name and read with a fallback to `FRAME`, the way `properties_placement` is. Nothing about it is written to `project.json`, so it cannot affect a format.
- Rounded rectangle is a mode with its own glyph, but not a stored kind: it draws a `ShapeKind.RECTANGLE` whose `cornerRadius` starts at a default (one fifth of the shorter side, set in the options row). Afterwards it is an ordinary rectangle with a radius, and the radius is editable in the options row or the properties. Rectangle mode starts at radius 0.
- Because the default is `FRAME`, a user who never opens the selector sees the tool they had.

### 3.1 The selector

Two surfaces, one state, following rail conventions (`ToolRail.kt`; `spec/3d-space.md`, "Non-gesture alternatives": a long-press is never the only way).

1. **Long-press on the rail circle** opens a small menu of the seven modes, each a 48dp row with its glyph and name, the current one checked. This is the same convention as the Brush and Eraser's long-press (pack wheel), the size chip's long-press (brush menu) and the mesh button's long-press (`onMeshMenu`). A long-press on the circle while the tool is not in hand picks the tool up first, as the Brush's does. A tap is unchanged: pick up / put down (`setActiveTool(FRAME)` / `NONE`).
2. **The tool's options row**, shown while the tool is in hand: the design sheet's first tab, "Shape", gets a segmented control of the seven modes above the existing LAYOUT: Frame content (a horizontally scrolling chip row at phone width, 48dp targets). In the side panel it is the top section. This is the discoverable and TalkBack route; each chip is a radio (`Role.RadioButton`, `selected`), and the group has a content description.

The rail circle's glyph is the selected mode's own glyph and changes when the mode changes (section 11); the options chips and menu rows use the same glyphs.

## 4. Gestures

One handler (`FrameToolCanvas`) with the mode as an input. Everything that is not "a drag on empty canvas" is the same in every mode.

| Gesture | Frame mode | Shape modes |
|---|---|---|
| Tap | selects the artwork under the finger, else the frame, else a frame by its label; nothing clears | the same |
| Drag on empty canvas | draws a frame; artwork under it moves into it | draws a new shape in the current mode |
| Drag on a layer or frame (its middle) | moves it, then drops it into or out of a frame | the same |
| Drag a selected frame's corner handle | resizes it; children follow their constraints | the same; plus the corner handles of a selected shape (phase 2) |
| Second finger | camera | camera |

Standard behaviour, decided: a drag from inside a shape or frame (its middle) moves it, a corner handle resizes it, and a drag on empty canvas draws a new one in the current mode. The same in every mode, so the Frame column is today's behaviour verbatim and is pinned by tests (section 13). To draw over a photo or other artwork, the user draws on empty canvas around it or, for a layer that fills the view, adds the shape with the options row's Add button and moves it.

Drawing, per mode (screen points go through `CanvasHitTest.screenToWorld`, as `commitFrame` does):

- **Rectangle, Ellipse, Polygon, Star**: the drag is the box's opposite corners. A drag shorter than the touch slop is a tap, not a shape.
- **Line**: the drag is the segment from touch to lift.
- **Proportions**: an options toggle "Keep proportions" (off by default) makes the box square (a circle for the ellipse, a regular figure for polygon and star). There is no second finger for a modifier (it is the camera's), so the toggle is a chip, not a gesture. Independently, the box snaps to square and a line to 15 degree steps when within 3 degrees / 4dp of them, with a guide drawn (the snap of section 7).
- **Preview**: the rubber band is one Compose draw call on the tool's own `Canvas` (`drawRect`, `drawRoundRect`, `drawOval`, `drawLine`, `drawPath`), in the selection ink, per move event. Nothing is rasterised and nothing is added to the layer stack until the lift.
- **Commit** is one `dispatch(AddLayer)` plus `emitMembership(Op.LayerAdd)` and `saveProject()` after `pushHistory()`, exactly the path `onAddShapeLayer` and `commitFrame` already use. One undo step per shape. The new layer is selected and active. Undo and history code is not modified; a shape is "add a layer", an operation they already handle.

## 5. How each kind is stored

Every shape is an ordinary `Layer(type = LayerType.VECTOR, shapes = [one VectorShape])`, the model the Vector group already creates. Geometry is in the layer's local space centred on its origin; the layer's `offset`, `scale` and `rotation` place it, like any layer. `FrameOps.worldTransform` and its inverse place it inside a parent frame.

| Option | Stored as | Notes |
|---|---|---|
| Rectangle | `ShapeKind.RECTANGLE`, `width` x `height`, `cornerRadius` | rounded rectangle mode = radius > 0 at creation. Radius is clamped to half the shorter side when it is drawn (the existing clamp) |
| Ellipse | `ShapeKind.ELLIPSE`, `width` x `height` | a circle with Keep proportions |
| Line | `ShapeKind.LINE`, `width` = length, `strokeArgb`, `strokeWidth` > 0 | direction is the layer's `rotation` (atan2 of the drag); the layer's offset is the segment's midpoint. `hasFill` is false for lines already |
| Polygon | `ShapeKind.POLYGON`, `sides` 3..12 | regular, vertex up, inscribed in the box (existing) |
| Star | `ShapeKind.PATH`, `closed = true`, `points` = 2 x n vertices, no handles | built by a pure `ShapeOps.star(width, height, points, innerRatio)`; afterwards an ordinary closed path |
| Frame | unchanged | `LayerType.FRAME` (saved as GROUP + `isFrame`), `layoutWidth` x `layoutHeight`, `frameFillArgb`, `frameRadius`, `clipsContent` |

Why the star is a closed path and not a new kind:

- A new `ShapeKind.STAR` (or a `starInnerRatio` field read by `POLYGON`) is the obvious data model, but a new enum value is a decode error in every older build (no `coerceInputValues`), so a project that used a star would not open there. A closed path uses only what every build since the pen tool reads, and it renders, exports (`VectorScene.pathOutline`) and node-edits (`PathEditOverlay`) with no new code.
- The cost: a star is not re-parametrised after creation (its point count and inner ratio are chosen before drawing, in the options row). It can be resized (a path stretches with its box) and node-edited. A parametric star is not planned.

Defaults for a new shape (shared with the Vector group's, so both entry points agree):

- Closed shapes: fill = the active colour (`EditorUiState.activeColor`), no stroke. Line: stroke = the active colour, width = 6 (the Vector group's line default; the options row can change it). Today's Vector group uses grey `0xFF888888`; the Shape tool uses the active colour because the user has just chosen it.
- Corner radius 0, sides 5 (polygon), star 5 points with inner ratio 0.4.
- Names: "Rectangle 3", "Ellipse 2", ... counted the way `onAddShapeLayer` counts, "Star n" for stars.

Editing after creation uses what exists: the Frame properties tab (X Y W H, rotation, opacity, radius), the Fill and Stroke sections (`StyleOps` colour styles apply), and the Vector group's shape controls. Nothing new is needed for a selected shape except the corner handles (section 9, phase 2): `DesignGeometry` already maps X Y W H onto a vector (a vector stretches).

## 6. Frame, as the framing option

Frame mode is the tool as it is today (`design-tools.md`, "The Frame tool", "Frames"): `FrameOps.create`, `nextPlacement`, `adoptContents`, `reparentAfterMove`, `resizeFromCorner`, `setBox`, `LayoutOps.applyResize`, clip, fill, radius, constraints, auto layout, export. The only code change for it is that the gesture handler reads the mode and takes the old branch unchanged. This is pinned by running the existing `FrameOpsTest` and `FrameResizeReducerTest` untouched and adding a mode-parametrised copy of the gesture tests (section 13).

Shapes and frames compose, as in Figma: a shape drawn with its start inside a frame becomes that frame's child at the top of its stack (`FrameOps.frameAt` + `reparent`, the drop rule already used on release); a start outside any frame is top level. A frame never becomes a shape or the reverse; "Frame selection" (wrapping selected shapes in a new frame) is a later nicety, not in these phases.

## 7. Constraints and snapping

**Constraints.** A shape inside a frame is a vector layer inside a frame, so the existing Pin X / Pin Y (`Layer.constraints`: START, END, STRETCH, CENTER, SCALE) apply with no change, offered under the same rule (the parent is a frame that does not run auto layout). `LayoutOps.applyResize` and `ConstraintSolver.resized` already stretch or scale a vector child when its frame is resized. A new shape starts with the defaults (START, START), as any layer dropped into a frame does today.

**Auto layout.** A shape child of an auto-layout frame is laid out by its box like any child (`LayoutOps.applyAutoLayout`, `hugContentsSize`). Drawing a shape into an auto-layout frame lets the frame place it on commit; there is no free placement there, matching Figma.

**Snapping.** Verified: no snapping exists in `LayoutOps` or `FrameOps` today (`PlaneSnap` is the 3D plane's, unrelated). Snapping is new, and is a pure function beside `FrameOps` so it is unit-testable and has no UI dependence:

- `ShapeOps.snap(layers, parentId, box, thresholdWorld): Snap` returns the nudged box and the guide lines to draw. Candidates: the parent frame's edges and centre lines; sibling layers' edges and centres (their world boxes from `FrameOps`/`DesignGeometry`); the canvas grid when `canvasGridVisible`; square / 15 degree line steps (section 4).
- Threshold: 6dp in screen space, converted to world through the viewport zoom. Snapping never fires while the user holds a handle at a precise value typed in X / Y / W / H (typing is exact).
- It is used by this tool's draw and resize, and is optional for Frame mode's existing drag (phase 3, behind the same function; Frame mode without it is the no-regression baseline).
- Guides are drawn on the tool's overlay `Canvas` as hairlines in the selection ink, only during a drag, hidden at lift; with reduced motion nothing animates.
- `LayoutOps` is called after the commit, not for snapping: it applies the constraint and auto-layout consequences of the final box.

## 8. 3D behaviour

Shapes are vector layers, which `spec/3d-space.md` already handles in 3D ("Group surfaces", "Rendering": a visible vector layer on the scene path is a depth-pass member drawn by `DepthCompositor`; "Export": a vector layer is rasterised by `drawVectorShape` onto a quad). So a shape needs no 3D code of its own; it needs only the right plane at creation.

- **Default view (the camera not moved):** nothing changes. The shape is a 2D layer on the 2D fast path.
- **Camera moved, grid on (the "#" toggle's on state, the default):** the new shape is created on the **view plane**, facing the camera through the orbit point, with the pose `SceneMath.viewPlanePose` gives (the same plane "Adopting the view plane" gives a first stroke), and the drag's screen points are mapped through that plane so the shape lands under the finger. This is "shapes on the facing plane": the user never draws a shape edge-on by accident. A shape is not a brush stroke, so `adoptViewPlaneForStroke`'s "empty layer" test does not apply; the shape layer is created already posed, in the same undo entry as its `AddLayer`.
- **Camera moved, grid off:** the shape goes where the finger touches the active layer's plane, as strokes do with the grid off (unmeasured: the exact attribution rule is `spec/smart-stroke.md`, design only; until that lands the grid-off case uses the view plane as well, which is the safe choice).
- **Inside a frame:** a shape is added to a frame only when the frame faces the camera within `PlaneSnap.ORBIT_CAPTURE_DEGREES` (5); otherwise it is created top level on the view plane. A frame's contents share its plane (`spec/3d-space.md`, "Export": a frame's fill and clip draw through the frame's own plane), so mixing planes inside one frame is avoided.
- **Frame mode in 3D** is exactly what it does today.
- 3D thickness (`spec/3d-thickness.md`, design only) applies to paint on a plane; a vector shape has no thickness channel and stays flat. Out of scope here.
- The 3D strokes layer and 3D models are untouched.

## 9. Fill, stroke, radius, clip, and the options row

Options shown below the mode selector, per mode; each is a 48dp-target control from existing components (`DesignControls.kt`):

| Mode | Options |
|---|---|
| Frame | none new (Clip, Fill, Radius stay in the Frame section as today) |
| Rectangle | Fill, Stroke colour and width, Corner radius, Keep proportions |
| Ellipse | Fill, Stroke colour and width, Keep proportions |
| Line | Stroke colour and width |
| Polygon | Fill, Stroke, Sides (3..12), Keep proportions |
| Star | Fill, Stroke, Points (3..12), Inner ratio (0.1..0.9), Keep proportions |

- With nothing selected the options set the **next shape's** values (`EditorUiState.shapeDefaults`, session-only, not saved). With a shape selected the same controls edit that layer through the existing paths (`StyleOps`, the Vector group's setters, one undo step per edit, as the Properties section already specifies); there is no new editing path.
- Fill and stroke bind to colour styles exactly as today (`fillStyleId`, `strokeStyleId`).
- Clip: only frames clip (`clipsContent`). A shape has none; a "Clip content" row is never shown for a shape. Corner radius: Rectangle only for shapes (`cornerRadius`), and the frame's own `frameRadius` for Frame mode.
- Phase 2 adds corner handles on a selected shape (the same four handles `FrameOverlay` draws for a frame, 24dp hit tolerance, `resizeFromCorner`-style) so a drawn shape can be resized on canvas; a line gets two end handles. Until then X / Y / W / H do it.

## 10. Accessibility and strings

All text is a string resource; none is concatenated into a sentence at a call site. Names are in `core/design` `strings_chrome.xml` beside `rail_frame`; new ones:

~~~
rail_shape                  "Shape"            rail label and TOOL_CATALOG label
rail_shape_with_mode        "Shape, %1$s"      content description of the rail circle: "Shape" plus the mode name
shape_mode_group            "Shape type"       content description of the selector
shape_mode_frame            "Frame"
shape_mode_rectangle        "Rectangle"
shape_mode_rounded_rectangle "Rounded rectangle"
shape_mode_ellipse          "Ellipse"
shape_mode_line             "Line"
shape_mode_polygon          "Polygon"
shape_mode_star             "Star"
shape_mode_menu_label       "Choose shape type"   onLongClickLabel of the rail circle
shape_option_keep_proportions "Keep proportions"
shape_option_sides          "Sides"
shape_option_points         "Points"
shape_option_inner_ratio    "Inner size"
shape_added                 "%1$s added"        live-region announcement after a commit
shape_layer_name_*          "Rectangle %1$d" ... one per kind, for layer names
~~~

- `rail_frame` stays as the Frame mode's label. The rail circle's content description is `rail_shape_with_mode` ("Shape, Rectangle"), so it follows the glyph, with `onLongClickLabel = shape_mode_menu_label`, so TalkBack announces that a long-press opens the type menu; the options chips are the long-press-free route.
- Each chip and menu row is 48dp, a radio with `selected` state; the circle's content description names the mode, so the glyph is never the only cue.
- Drawing by drag is not available to switch access or TalkBack; the non-gesture route is the existing "Add frame" / Add shape actions: the options row gets an **Add** button (adds the current mode's shape at the default size, 200 x 200, centred in view or placed by `nextPlacement` for a frame, selected), the same affordance the sheet's "Add frame (390 x 844)" gives today. Numbers are then set in X / Y / W / H, which are already accessible text fields.
- The commit announces `shape_added` through a polite live region; names use `shape_layer_name_*` resources so they localise.
- Colour is never the only cue: the selected mode chip also has a check, and guides are lines, not colour alone.

## 11. Icons: one glyph per mode

The rail circle shows the glyph of the selected mode and swaps it when the mode changes (`RailIcons.shapeGlyph(mode)`), so the rail always says what a drag will draw. The long-press menu rows and the options chips use the same glyphs. Constraints (from `RailIcons`): 24 x 24 viewBox, stroke 1.8, white (tinted), no fill, butt caps and miter joins unless `round = true`, drawn at 22dp, each a `stroked("rail.shape.<mode>", ...)` entry. None is "#" (the grid toggle's) or a crop corner bracket (`ic_gx_crop`, the Transform group's), and none reuses `GraffuxIcons.Artboard`, which carries corner marks.

~~~
val ShapeFrame: ImageVector by lazy { stroked("rail.shape.frame", "M4 8h16v12H4z", "M4 4.5h6") }
val ShapeRect: ImageVector by lazy { stroked("rail.shape.rect", "M4 6h16v12H4z") }
val ShapeRounded: ImageVector by lazy {
    stroked("rail.shape.rounded", "M8 6h8a4 4 0 014 4v4a4 4 0 01-4 4H8a4 4 0 01-4-4v-4a4 4 0 014-4z")
}
val ShapeEllipse: ImageVector by lazy { stroked("rail.shape.ellipse", "M3 12a9 6.5 0 1 0 18 0a9 6.5 0 1 0 -18 0z") }
val ShapeLine: ImageVector by lazy { stroked("rail.shape.line", "M5 19L19 5", round = true) }
val ShapePolygon: ImageVector by lazy { stroked("rail.shape.polygon", "M12 3l7.8 4.5v9L12 21l-7.8-4.5v-9z") }
val ShapeStar: ImageVector by lazy {
    stroked("rail.shape.star", "M12.0 3.1L14.4 9.4L21.0 9.7L15.8 13.8L17.6 20.3L12.0 16.6L6.4 20.3L8.2 13.8L3.0 9.7L9.6 9.4z")
}
~~~

- **Frame** is a plain rectangle with a short name tab above its top-left corner (`M4 4.5h6`), because a frame shows its name there (`FrameOverlay`). It is not "#", and a tab above one corner is not a corner bracket (a bracket is two perpendicular strokes meeting at a corner; the tab is a separate dash).
- The seven glyphs are visibly distinct at 22dp: a tabbed box, a box, a rounded box, an oval, a diagonal stroke, a hexagon, a star.
- `RailIcons.Frame` ("#") stays defined for a frame's layer tile (`design-tools.md`) and is no longer on the rail circle. `TOOL_CATALOG` holds a drawable resource per tool, so it takes `ic_gx_shape_frame` (the Frame glyph, replacing `GraffuxIcons.Artboard`) for places that show the tool without a mode; the rail circle itself reads `RailIcons.shapeGlyph(mode)`.
- A unit test pins that no mode glyph equals the grid toggle's path data, that each has the 24 viewBox and 1.8 stroke, and that the seven are pairwise different.

## 12. Export

No new exporter. `VectorScene` already walks every `ShapeKind` and nested frames (`design-tools.md`, "Export: PNG, SVG, PDF"):

- **SVG**: `<path>` per shape (`<rect>`-equivalent outline with its radius, ellipse, line, polygon, pen path with curves), inside the parent's `<g matrix(...)>`; a frame's `<clipPath>` clips shapes drawn into it. A star is a path.
- **PDF**: `PdfExport` draws the same display list on the page canvas; shapes stay vector.
- **PNG**: `ExportManager.drawNode` / `compositeFrame` draw the shape through the same transforms; a frame's PNG cuts to its box.
- A line with stroke width 0 is invisible on canvas and in export alike (`hasStroke`); the tool never creates one.
- 3D: a shape on a tilted plane exports through the existing scene path (`compositeLayers(..., camera)`; glTF rasterises a vector layer by `drawVectorShape`). Unchanged.
- Parity: `VectorParityTest` and `VectorExportTest` gain a star and an angled line, so the canvas, SVG and PDF agree on the new cases.

## 13. Migration and format version

- **No format bump.** `CURRENT_FORMAT_VERSION` stays 14. No field, enum value, layer type or file is added: shapes are `VECTOR` layers with `VectorShape` (all present since the vector layers), stars are closed `PATH`s, and the mode is a settings preference. Numbers 15 (`spec/3d-puppetry.md` section 4) and the next free number after it are reserved elsewhere and are not consumed.
- **Older files open.** Nothing about a project changes on open; `ProjectManager.migrateFormat` gains no step. A frame saves as it does now (GROUP + `isFrame` + fill, clip, radius), so a build before frames still shows it as a group.
- **Newer files in an older build** (a project with shapes, opened by the build before this one): every shape is a layer type and kind that build already decodes and draws; nothing is lost and no read-only fallback (`isNewerFormat`) is involved.
- **If the star is ever made parametric** it adds an optional field with a default (an older build ignores an unknown key) and would be the one format bump; it takes the next free version at that time, coordinated with `spec/3d-puppetry.md` and `spec/3d-thickness.md`.
- `Tool` gains no entry. The pin: a test asserts `Tool.FRAME` is still the last constant and its ordinal is unchanged, so a future tool is appended after it.

## 14. Phases

Phase 1 status (implemented): `ShapeToolMode` and `EditorUiState.shapeToolMode`, persisted as `shape_tool_mode` and read back with a fallback to Frame; the rail circle relabelled "Shape" (`TOOL_CATALOG`, `rail_shape_with_mode`) with the selected mode's glyph (`RailIcons.shapeGlyph`); the long-press menu and the option chips (radios) on the Frame tab and the side panel; Frame mode unchanged; rectangle, rounded rectangle, ellipse and line drawn as ordinary `VECTOR` layers by `ShapeOps` (one undo step, filled or stroked with the colour in hand), with the rubber band as a single Compose draw call; the options row's Add button; mode names and layer names as string resources. The mode enum already declares polygon and star, and their glyphs exist, but they are not offered or drawable until phase 3 (`ShapeToolMode.OFFERED`). Not in phase 1: the `shape_added` live-region announcement, the `ic_gx_shape_frame` catalogue drawable (`TOOL_CATALOG` keeps the Artboard glyph), the per-mode options (stroke, radius, Keep proportions, `shapeDefaults`), corner handles for a selected shape (the frame keeps its four), snapping, 3D view-plane creation, and parenting a shape into a frame on creation (a drag that starts inside a frame moves it, per section 4).

Each phase ships and leaves the app working; the default mode is Frame throughout.

1. **Relabel and glyph.** `rail_shape` and `rail_shape_with_mode` strings, the seven glyphs and the mode-dependent rail face (section 11), `TOOL_CATALOG` label; `ShapeToolMode` state and preference with only Frame offered. No behaviour change. Tests: ordinal pin, tag unchanged, glyph tests.
2. **Rectangle, Ellipse, Line.** The selector (long-press menu and options chips), draw gesture, commit as a vector layer, preview, options row, Add button, a11y strings, defaults. 2D only.
3. **Polygon and Star, handles, snapping.** `ShapeOps.star`, sides / points / ratio options, corner and end handles for a selected shape, `ShapeOps.snap` with guides, Keep proportions.
4. **3D and export polish.** View-plane creation under a moved camera, frame-facing rule, export parity cases, side panel layout, tablet checks, the optional snap for Frame mode's drag.

## 15. Tests

Pure (`core/common`, JVM):

- `ShapeOpsTest`: star vertices for n = 3..12 and ratios (count, alternating radii, closed, inside the box); polygon sides clamp; line from two points (length, rotation, midpoint); box from a drag (any quadrant, Keep proportions); snap candidates, thresholds, no snap when exact; names and counting.
- `FrameOpsTest`, `LayoutOps` tests: **unchanged** and passing (the no-regression pin).
- `ToolOrdinalTest`: `Tool.FRAME.ordinal` equals its current value and `Tool.values().last() == Tool.FRAME`.
- Format: a `project.json` written by this build with each shape kind and a frame round-trips; the fixtures of format 3, 4, 6 and 14 still open (`ProjectManager` migration tests); a file with a star decodes with `ignoreUnknownKeys` and no new key (assert the JSON of a star layer contains no key absent from a format-14 layer).

Editor (`feature/editor`, Robolectric / unit):

- `EditorViewModelTest`: commit in each mode adds one layer of the right kind, selected, one history entry; undo removes it; Frame mode commit equals the old `commitFrame` result byte for byte (same layer fields).
- Gesture tests: `FrameToolCanvas` in Frame mode is parametrised over the existing cases (tap, draw, move + drop, handle resize, second finger) and passes with `shapeToolMode = FRAME`; in every mode a drag from a layer's middle moves it, a corner handle resizes it, a drag on empty canvas draws in the current mode, and a drag shorter than slop is a tap.
- Defaults: closed shapes take `activeColor`, lines the stroke default; options with a shape selected edit it, with none edit `shapeDefaults`.
- 3D: a shape under a moved camera, grid on, is created with the view-plane pose and lands under the finger; a frame that is not facing is not the parent.
- Export: `VectorParityTest` / `VectorExportTest` with a star and an angled line (SVG and PDF displays equal the canvas outline); a frame clipping a shape.

UI (`app`, Compose UI test):

- Long-press on `rail.tool.frame` opens the menu with seven rows, 48dp, one selected; choosing one changes the options row and the preference; `onLongClickLabel` and `stateDescription` are set; tap still toggles the tool.
- The Add button creates a shape with no pointer input; chips are radios.
- The rail circle's glyph and content description change with the mode; no glyph equals the grid toggle's; the vectors have viewBox 24 and stroke 1.8.

Not run here: this change is a design document. Nothing was compiled or executed.

## 16. Decisions

- `Tool.FRAME`, the id `tool.frame` and the tag `rail.tool.frame` stay; the tool is relabelled "Shape".
- Gestures are the standard ones (section 4): move from the middle, resize from a corner handle, draw on empty canvas.
- A star is a closed path; point count and inner ratio are fixed once drawn.
- The rail glyph is the selected mode's own glyph and changes with the mode; seven modes, each with its own glyph (Frame is a tabbed box, not "#" or a crop bracket).
- A new closed shape's fill is the active colour, for both the Shape tool and the Vector group.
- The last-used mode persists (`shape_tool_mode`), defaulting to Frame on a fresh install.
