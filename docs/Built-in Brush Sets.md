# Built-in Brush Sets

Every bundled brush lives in `core/engine/.../azphalt/BuiltInBrushes.kt`, grouped into
`BuiltInBrushes.sets`. `presets` is the sets flattened in order, `Round` first (the default
everywhere). All are procedural: no tip or grain bitmap, because built-ins have no extension asset
bundle to resolve one from. Texture comes from engine behaviour instead: dynamics, contact
mechanics, scatter/count, build-up, Impasto and the project substrate.

## Where they show

- **Android:** the Brushes page of the bottom carousel. A chip row above the strip names each set
  (plus `Studio` for saved Brush Studio brushes and `Installed` for extension brushes). Tapping a chip
  selects that set's first brush; the lit chip follows the hero slot. The hero card reads
  `Built-in · <set>`.
- **Desktop:** one rail host per set, each unfolding its brushes.

## Sets

| Set | Brushes | Character |
| --- | --- | --- |
| Basics | Round, Soft Round, Hard Round, Airbrush, Ink Pen | Neutral references. Unchanged. |
| Aerosol | Skinny Cap, Fat Cap, Mist, Speckle | Can control: pools when held, ghosts when fast, droplet overspray. |
| Ink & Line | Brush Pen, Fineliner, Italic Nib, Chisel Marker, Dry Ink | Committed marks; width from pressure, speed and pen angle. |
| Sketch & Dry | Graphite, Charcoal, Chalk, Conté | Pigment on tooth; strong substrate response, tilt side-shading. |
| Paint | Flat Bristle, Filbert, Dry Brush, Palette Knife, Wash | Physical bristle populations, Impasto v2 knife, glazing wash. |

## Tuning rules

- Sensor ranges are physical: `SPEED` px/ms (≈0.2 careful, ≈1.5 confident, 3+ flick), `TILT`
  radians (0 upright, ≈1.2 laid over), `DISTANCE` px.
- Every size/opacity route's floor is above zero, so finger input (flat pressure, no tilt) still
  leaves a mark. `BuiltInBrushesTest` pins this.
- Every preset equals its own `sanitized()`: tuning never relies on clamping.
- Scatter is a fraction of the *resolved* dab diameter. Brushes with tiny random dabs (Speckle) use
  large scatter to cover the brush width.
- Adding a set: add a `List<AzphaltBrush>` and a `BuiltInBrushSet` entry. Names must be unique across
  all sets; the carousel and rail pick it up with no further wiring.

~~~kotlin
BuiltInBrushes.setOf("Chalk")?.name // "Sketch & Dry"
~~~
