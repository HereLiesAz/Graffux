# The Graffux icon set

404 icons across 13 families, covering everything Photoshop, Illustrator and Procreate have
an icon for, plus what Graffux needs that none of the three ship — generative fill, AR
placement, stencil generation.

The drawings are authored as SVG and live in `masters/`. They are the source: changing a
glyph means editing its master. `build.py` turns them into the three things that ship — an
Android VectorDrawable per icon, a typed Kotlin registry, and a contact sheet to review them
on — and validates them on the way.

## The grid

- 24×24 viewport. Live area 2–22, with a bleed to 1–23 that the build refuses to let
  anything cross.
- One stroke weight (0.5), round caps and joins.
- Outline first. A region large enough to hold three rules may be **ruled** instead of
  filled: rows of parallel lines at pitch 1.05, weight 0.3, anchored to the **viewport**
  rather than to the region. That anchoring is the whole point — two ruled icons side by
  side in a rail read as ruled from one plate. 100 of the 404 rule something.

Two things to know about the ruling in code:

**SVG** uses a `<pattern>` whose id carries the icon's key. The contact sheet inlines 404
masters into one document, and a shared id would have every glyph after the first borrowing
the first one's pattern.

**VectorDrawable** has no pattern fill, so the rules are emitted as real geometry: a
`<group>` with a `<clip-path>` set to the region, holding explicit line paths at the same y
values the SVG pattern lays down. Two consequences: those files get long (a full-height
region is twenty-odd extra paths), and VectorDrawable clip paths are not antialiased on
every Android version, so a ruled edge can look harder on device than in the master. Check
the dense ones — `eraser`, `smudge`, `document-save` — on real hardware before shipping.

## Where the drawings come from

316 are Graffux's own. 88 are Phosphor Icons drawings used as delivered; Phosphor's licence
is MIT and requires its notice to travel with the geometry, so `NOTICE` is generated from the
manifest and a copy is written to `app/src/main/res/raw/` and shown in the app under
Settings. Add or drop a borrowed icon and both update on the next build.

Phosphor's construction differs from ours: it draws an outline as a *filled* shape with a
counter-wound hole, where a Graffux glyph strokes a centreline. Both are supported
throughout, and it is why the path-data budget has two numbers — stating both walls of an
outline costs two to three times what stating its centreline does.

## Layout

```
branding/icons/
  masters/<key>.svg    the drawings — the source of truth
  overrides/<key>.svg  drawings this repo owns because the delivered one could not ship;
                       see overrides/README.md for what was wrong with each
  meta.json            per-icon category, search keywords and lineage
  grid.py              the numbers everything agrees on
  svgpath.py           path data: parse, transform, flatten, measure
  import_package.py    a delivered package -> masters (normalising as it goes)
  build.py             validate, then emit every output
  NOTICE               third-party attribution (generated)
  dist/
    icons.json         the manifest
    contact-sheet.html a searchable sheet at 24px and 16px
```

Android output lands in `core/design/src/main/res/drawable/ic_gx_<key>.xml` and
`core/design/.../design/GraffuxIcons.kt` (`GraffuxIcons.Brush`,
`GraffuxIcons["layer-mask-add"]`, `GraffuxIcons.search("blur")`).

## Building

```
python3 branding/icons/build.py
```

Errors fail the build: geometry outside the bleed, an unknown family, a ruled path pointing
at another icon's pattern, or **two icons that draw the same thing**. That last one is the
check worth having. It compares rendered silhouettes rather than markup, because the ways
two icons end up identical are not visible in a diff — in the delivered set, one pair
differed only by a line drawn exactly along an edge another shape already had, and three
more pairs differed only in `<circle>` elements, which a scan of `d` attributes cannot see.
404 icons is well past what anyone can hold in their head, and two keys that look the same
are indistinguishable to the person using the app.

Warnings print but do not block: over the path-data budget, or reaching into the bleed
margin. Use judgement.

## Changing an icon

1. Edit `masters/<key>.svg`. Keep the 24×24 viewBox and the pattern id — the build checks
   both.
2. Build, then **look at it**: open `dist/contact-sheet.html`, which shows every glyph at
   24px and at 16px. A description that sounds right is not a glyph that reads at 16px, and
   ruling in particular is a size judgement — a region that reads as texture at 44px can
   collapse into a grey smear at 16px.
3. Check it against its neighbours. The most common failure in this set has never been a bad
   drawing; it has been two concepts landing on the same silhouette by accident. The build
   catches an exact match, but two icons that are merely *hard to tell apart* are still your
   problem — `boolean-intersect` and `select-intersect` sit 7% apart and are deliberately
   distinguished by solid versus marching-ants framing.

## Taking a new delivery

```
python3 branding/icons/import_package.py <package>/exports
python3 branding/icons/build.py
```

The importer normalises as it copies: it bakes Phosphor's 256-unit grid and its scaling
group down into the 24 grid, re-anchors the ruling to the shared phase, and gives every file
its own pattern id. Anything in `overrides/` wins over the package, so a fix already made
here cannot be silently undone by a later drop; the importer prints which ones it held back.
New keys get keywords derived from the key and are listed for you to improve in `meta.json`.
