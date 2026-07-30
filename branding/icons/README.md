# The Graffux icon set

409 flat vector icons across 13 families, covering everything Photoshop, Illustrator, and
Procreate have an icon for, plus what Graffux is likely to need that none of the three do
yet (generative fill, AR placement, stencil generation — GraffitiXR's own heritage).

Every icon is original geometry, authored on a shared grid with one stroke weight and one
rule for the solid mark. Where a concept has an inherited visual convention — Photoshop's
half-black adjustment disc, the pen nib, the marching-ants selection edge, the floppy disc
for Save — that convention is kept, because throwing it away to prove originality would cost
recognisability for nothing. The geometry itself is drawn from scratch every time.

## Why code, not an icon font or a drawing tool

Every glyph is *exact* geometry: `circle(12, 12, 8.4)`, not a freehand Bézier eyeballed in a
vector editor. That buys three things a font or an SVG library made by hand can't:

- **Consistency that isn't manual.** All 409 icons share one stroke weight, one grid, one
  keystone rule, because the numbers are constants, not muscle memory repeated 409 times.
- **A cheap validator.** `build.py` checks every icon against the live area and a
  path-length budget before it ships, so a glyph that wandered off-grid or got overdrawn is
  caught at build time, not in a screenshot review months later.
- **One source, many targets.** The same geometry becomes an SVG master, an Android
  VectorDrawable, and a Kotlin registry — change the shape once, every output updates.

## The grid

- 24×24 viewport. Live area 2–22; nothing of consequence sits in the 2-unit bleed margin.
- One stroke weight (1.4), round caps and joins, throughout.
- At most one **keystone** per icon: a single solid mark placed exactly where the operation
  acts (the working end of a tool, the result of a boolean op, the layer being changed).
  Never two. The discipline is what keeps 409 icons from turning into 409 different styles.
- A path-length budget flags icons that are describing their subject instead of signalling
  it — if a glyph needs more than ~260 characters of path data, it is saying too much.

## Layout

```
branding/icons/
  kit.py              the geometry DSL: line/circle/rect/arc/poly/smooth/pie/ring, transforms
  build.py            validates every icon, emits SVG + VectorDrawable + the Kotlin registry
  sets/               one file per family; importing sets/ registers every icon
    tools.py          brushes, erasers, retouching tools, brush parameters, symmetry & guides
    selection.py      marquees, lassos, boolean ops, masks
    layers.py         the stack and everything done to it
    color.py          tone, hue, gradients, colour instruments
    filters.py        blur/sharpen/noise/distort/stylize, generative fill & sky replace
    vector.py         pen tool, anchors, boolean path ops, live shapes
    typography.py     type tools, character & paragraph controls
    transform.py       move/scale/rotate/skew, align & distribute
    document.py       new/open/save, canvas & image size, crop, view & zoom
    history.py        undo/redo, history states, actions/macros
    animation.py      timeline, keyframes, playback transport, export
    generative.py     AI generation, AR view, camera capture, stencils
    ui.py             navigation chrome, status, sharing, gestures
  dist/               build output (regenerated, not hand-edited)
    svg/<key>.svg      one master per icon
    icons.json          the manifest: keys, categories, keywords, lineage
    contact-sheet.html   a searchable, reviewable sheet at 24px and 16px
```

Android output lands in `core/design/src/main/res/drawable/ic_gx_<key>.xml` and
`core/design/.../design/GraffuxIcons.kt` (a typed registry: `GraffuxIcons.Brush`,
`GraffuxIcons["layer-mask-add"]`, `GraffuxIcons.search("blur")`).

## Building

```
python3 branding/icons/build.py
```

Regenerates every output from the geometry in `sets/`. Errors (out-of-bounds geometry, a
missing lineage note) fail the build; warnings (over the path-data budget, brushing the
bleed margin) print but don't block — use judgement.

## Adding an icon

1. Pick the right file in `sets/`.
2. Call `icon(key, category, keywords, s=[...strokes], f=[...one keystone], apps=..., basis=..., note=...)`.
   - `apps`: which of PS / AI / PR ship the concept, or `"new"` if none do.
   - `basis`: the specific tool/panel/convention being drawn from, if `apps` isn't `"new"`.
     The build fails without one — an icon claiming lineage has to name it.
   - `note`: one sentence describing the actual drawing, for the contact sheet.
3. Build, then look at the result — render `dist/svg/<key>.svg` or open the contact sheet.
   A description that sounds right on paper is not the same as a glyph that reads at 16px.
4. Check it against its neighbours. The most common failure in this set wasn't a bad
   drawing — it was two different concepts landing on the same silhouette by accident
   (an adjustment-layer badge and a duplicated layer, a chain and a swirl). If two icons in
   the same family could be swapped without anyone noticing, one of them needs to change.
