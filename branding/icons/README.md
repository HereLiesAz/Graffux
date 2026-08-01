# The Graffux icon set

404 flat vector icons across 13 families, covering everything Photoshop, Illustrator, and
Procreate have an icon for, plus what Graffux is likely to need that none of the three do
yet (generative fill, AR placement, stencil generation — GraffitiXR's own heritage).

Every icon is original geometry, authored on a shared grid with one stroke weight, one
hatching pitch, and one rule for the mark that carries mass. Where a concept has an inherited
visual convention — Photoshop's half-black adjustment disc, the pen nib, the marching-ants
selection edge, the floppy disc for Save — that convention is kept, because throwing it away
to prove originality would cost recognisability for nothing. The geometry itself is drawn
from scratch every time.

## Why code, not an icon font or a drawing tool

Every glyph is *exact* geometry: `circle(12, 12, 8.4)`, not a freehand Bézier eyeballed in a
vector editor. That buys three things a font or an SVG library made by hand can't:

- **Consistency that isn't manual.** All 404 icons share one stroke weight, one grid, one
  hatching pitch, because the numbers are constants, not muscle memory repeated 404 times.
- **A cheap validator.** `build.py` checks every icon against the live area and a
  path-length budget before it ships, so a glyph that wandered off-grid or got overdrawn is
  caught at build time, not in a screenshot review months later.
- **One source, many targets.** The same geometry becomes an SVG master, an Android
  VectorDrawable, and a Kotlin registry — change the shape once, every output updates.

## The grid

- 24×24 viewport. Live area 2–22; nothing of consequence sits in the 2-unit bleed margin.
- One stroke weight (0.5), round caps and joins, throughout.
- At most one **keystone** per icon: a single mark carrying mass, placed exactly where the
  operation acts (the working end of a tool, the result of a boolean op, the layer being
  changed). Never two. The discipline is what keeps 404 icons from turning into 404
  different styles.
- A path-length budget flags icons that are describing their subject instead of signalling
  it — if a glyph needs more than ~260 characters of path data, it is saying too much.

## Hatching

The set carries no large solid areas. Where a glyph would have been solid, it is **ruled**
instead: rows of parallel lines at a fixed pitch, in the same ink as the stroke. Density does
the work weight used to do, and it does it without putting a heavy blob next to a 0.5-unit
hairline — which at this stroke weight is the difference between a mark and a hole in the
composition.

- Pitch 1.05, rule weight 0.3, first rule at 0.28 of a pitch below `y=0`.
- The rules are anchored to the **viewport**, not to the region being filled. Every hatched
  area in every icon therefore sits on one shared set of horizontals: two icons side by side
  in a rail read as ruled from the same plate, which is the whole reason to anchor them.
- A region carrying fewer than **three** rules stays solid. Two lines in a small mark is a
  smudge, not a texture. The keystone dot is the common case — it is small, it stays solid,
  and it stays the one deliberate piece of mass in the glyph.
- Hatched regions are never outlined unless the outline is a genuine container (the spray
  can's body, the eyedropper's bulb).

Two things to know about it in code:

**SVG** uses a `<pattern>` in `currentColor`, declared per file as `#gfxHatch-<key>`. The id
carries the icon's name so that inlining several masters into one document — which the
contact sheet does 404 times — cannot have one glyph borrowing another's pattern.

**VectorDrawable** has no pattern fill, so the hatching is emitted as real geometry: a
`<group>` with a `<clip-path>` set to the region, holding explicit line paths at the same y
values the SVG pattern lays down. Two consequences: those files are longer (a full-height
region is twenty-odd extra paths), and VectorDrawable clip paths are not antialiased on
every Android version, so a ruled edge can look harder on device than it does in the master.
Check the dense ones on real hardware before shipping.

A `<clip-path>` also takes no fill-type attribute, which is why `cut()` punches holes by
**counter-winding** the inner contour rather than by an even-odd fill rule: even-odd is the
one construction that comes back solid the moment the region is ruled. `reverse()` does the
winding arithmetically, so it is not a thing an author has to get right by hand.

## Layout

```
branding/icons/
  kit.py              the geometry DSL: line/circle/rect/arc/poly/smooth/pie/ring/cut,
                      transforms, and the grid and hatch constants everything is built on
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
   - Whether the keystone comes out hatched or solid is not a choice you make. It follows
     from its size, so that the same-sized mark is treated the same way everywhere. If a
     mark wants hatching, give it the room to hold three rules.
3. Build, then look at the result — render `dist/svg/<key>.svg` or open the contact sheet.
   A description that sounds right on paper is not the same as a glyph that reads at 16px.
   Hatching in particular is a 24px judgement: a region that reads as texture at 44px can
   collapse into a grey smear at 16px, and the contact sheet shows both for that reason.
4. Check it against its neighbours. The most common failure in this set wasn't a bad
   drawing — it was two different concepts landing on the same silhouette by accident
   (an adjustment-layer badge and a duplicated layer, a chain and a swirl). If two icons in
   the same family could be swapped without anyone noticing, one of them needs to change.
