# Overrides

Drawings this repo owns, because the delivered ones could not ship as they were.

`import_package.py` copies these into `masters/` instead of the package's version of the
same key, so re-importing a later drop cannot quietly undo a fix. To go back to whatever the
package supplies, delete the file and re-import.

Each entry says what was wrong and what replaced it. "Rendered identically" means exactly
that — both keys rasterised to the same bitmap at 128px, so no user could have told them
apart.

## Two keys, one drawing

Eight pairs in the delivered set rendered identically. In each pair the more conventional
member kept its drawing and the other was redrawn.

| Key | Was identical to | Now |
|---|---|---|
| `action-play` | `play` | The transport triangle riding a three-rule list — an action is a recorded list of steps, played back |
| `action-stop` | `stop` | The same list under the transport square |
| `filter-emboss` | `black-white` | A bevel: the band between the outer edge and a raised inner face, ruled |
| `select-intersect` | `boolean-intersect` | Marching ants over the existing selection, a solid rectangle for the new one, the overlap ruled. The boolean family keeps two solid rectangles |
| `view-actual-colors` | `color-cmyk` | The frame with a check in it — proofing off, showing what is actually there |
| `resample` | `color-lookup` | One frame at two sample densities, coarse against fine |
| `guide-ruler` | `rulers` | A guide pulled off a ruler's edge, dashed |
| `shadows-highlights` | `threshold` | The two ends of the range: one disc ruled, the other carrying the highlight |

`brush-opacity` was not identical to `color-profile` but differed from it by 1% of its ink —
a single arc. It is now opaque, part-way, clear: three discs, filled, ruled and empty.

## Off the grid

`font-family` and `type-allcaps` ran past the left edge of the 24-unit viewport — the second
one to x = −0.02, outside even the bleed. Both are nudged back to centre in the live area
(+2.52 and +2.70 units in x). The drawings are untouched; only their position moved.

## Missing geometry

`select-add` and `select-subtract` were delivered with the entire left side of the marching
ants missing — nine dashes where `select-rect` and `select-none` have twelve, leaving the
marquee visibly open on one side. The left side is restored from `select-rect`'s coordinates,
which the rest of the selection family already shares.
