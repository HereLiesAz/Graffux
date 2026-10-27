# Stroke programs — `spec/stroke-program.md`

A **stroke program** is an optional script on a brush asset. It runs once for every *dab step* of a live
stroke and reshapes what that step paints: it can move, resize and recolour the brush's own dabs, drop
them, and add dabs of its own. State carries from one step to the next, so a brush can have memory — ink
that runs dry, paint that pools and drips, a hatch that keeps its own rhythm.

It is data, not native code. The engine compiles the text itself; a program cannot read a file, reach the
network, see the clock, or touch anything outside its own stroke. A package that carries one is still
`kind: "asset"`.

Implementation: `core/engine/.../azphalt/StrokeProgram.kt` (compiler + interpreter),
`IncrementalDynamicDabGenerator` (the one place it is invoked), `AzphaltBrush.program` (the manifest field).
Tests: `StrokeProgramTest`. Render harness: `PackRenderHarness` (see the end of this file).

## Where it runs

Dabs come from `IncrementalDynamicDabGenerator`, which every live stroke, every commit and every undo/redo
replay runs over the recorded samples in order. A brush with a `program` always takes that path. For each
dab step the generator builds the brush's own dabs (size, taper, blot, contact, tufts, dynamics — all of it),
then hands them to the program. What the program returns is what is painted. Everything downstream — the
GPU stamp engine, symmetry, the eraser's mirror of the brush, undo — sees ordinary dabs.

Because replay feeds the same samples through a fresh runner with the same stroke seed, a program must be,
and is, a pure function of the stroke: the same samples give the same dabs, on screen and after undo.

## Declaring one

In the brush asset's `params`:

~~~json
"program": {
  "state": { "load": 1, "drips": 0 },
  "init":  "load = 1;",
  "step":  [
    "load -= stepPx * 0.0004;",
    "alphaMul = clamp(load, 0, 1);"
  ]
}
~~~

`state` names the persistent variables and their starting values (at most 32). `init` runs once, before the
first step. `step` runs every dab step and is required — without it there is no program. `tickMs` (optional,
at least 8) also runs `step` every that many milliseconds of pen time that pass with no distance step, so a
brush can keep working where the pen is held or crawling (see *Time ticks*). Source may be one
string or an array of lines (joined with newlines). A program that fails to compile is ignored and the brush
paints as its plain self; tooling should surface the error (`StrokeProgram.compile` returns the message and
line).

## Language

Statements end in `;`. Comments are `//` to end of line. All numbers are 32-bit floats; a condition is true
when it is not `0`. Angles are **degrees** everywhere.

~~~
let a = expr;                 // a local, scoped to its block
name = expr;   name += expr;  // assign a local, a state variable or an output (also -= *= /=)
if (cond) { ... } else if (cond) { ... } else { ... }
for i in 0..32 { ... }        // literal whole-number bounds, end exclusive, at most 64 iterations
emit { x: px, y: py + 8, size: 6, alpha: 0.5 };
~~~

Operators, low to high precedence: `? :`, `||`, `&&`, `== !=`, `< <= > >=`, `+ -`, `* / %`, unary `- !`.
Division by zero is `0`; any non-finite result is `0`. `PI` is a constant.

### Inputs (read-only)

| Name | Meaning |
|---|---|
| `px`, `py` | stroke position of this dab step, canvas px |
| `pressure` | 0..1 (1 for a finger) |
| `speed` | px per millisecond |
| `heading` | direction of travel, degrees (0 = +x, 90 = +y which is *down* the screen) |
| `turn` | change of heading since the previous step, −180..180 |
| `dist` | arc length travelled since the stroke began, px |
| `time` | ms since the stroke began |
| `dt` | ms since the previous dab step (large when the pen is slow) |
| `index` | dab step number, from 0 |
| `diameter` | the brush's resolved diameter this step, px (after size dynamics, taper, blot) |
| `tilt`, `azimuth` | stylus tilt (0 = upright, 90 = flat) and orientation, degrees |
| `dwell` | ms the pen stayed put at touchdown before moving |
| `stepPx` | the distance the brush will move to the next step, px |
| `baseAlpha`, `baseAngle`, `baseTip`, `baseHard` | the brush's own dab's alpha, angle, tip ratio, hardness |
| `layerSpeed` | layer px per millisecond over the last ~50 ms. Unlike `speed` (finger px on the glass) it follows the drawn line, so it does not depend on zoom |
| `phase` | 0 touchdown, 1 moving, 2 held (a time tick), 3 lift-off |
| `penAngle` | the nib's edge direction, degrees like `heading` (0 = +x, 90 = down). Set by the editor's nib-angle dial and kept true to the world as the device turns; −40 when nothing sets it (an italic hand: the edge rising to the right at 40°). Recorded with each sample, so replay draws the same angle |

### Outputs (assignable; reset every step)

They reshape the brush's own dabs for this step.

| Name | Default | Effect |
|---|---|---|
| `shiftX`, `shiftY` | 0 | move the dabs, px |
| `sizeMul`, `alphaMul` | 1 | multiply radius / opacity |
| `tipMul`, `hardMul` | 1 | multiply tip ratio / hardness |
| `rotate` | 0 | add to the tip angle, degrees |
| `hueShift`, `satMul`, `valMul` | 0, 1, 1 | colour shift / multipliers |
| `gapMul` | 1 | multiply the distance to the next step (clamped 0.05..20) |
| `hide` | 0 | non-zero drops the brush's own dabs, leaving only what the program `emit`s |

### `emit`

`emit { ... }` adds one dab. Any field you leave out is copied from the brush's own dab for this step
(`size`, `alpha`, `angle`, `tip`, `hard`, `flow`, `mix`) or neutral (`hue` 0, `sat` 1, `val` 1). Position
defaults to `px, py`. `size` is a diameter in px; a dab with `size <= 0` or `alpha <= 0` is dropped.

| Field | Meaning |
|---|---|
| `x`, `y` | centre, canvas px |
| `size` | diameter, px |
| `alpha` | opacity 0..1 |
| `angle` | tip rotation, degrees |
| `tip` | tip ratio, height / width (0.05..1) |
| `hard` | hardness 0..1 (used by tips without an image) |
| `hue`, `sat`, `val` | colour shift degrees / saturation × / value × |
| `mix` | foreground→background colour mix 0..1 |
| `flow` | flow multiplier |

Emitted dabs are painted after the brush's own dabs, in emit order, so emit order is paint order.

### Functions

`abs min max clamp(v,lo,hi) lerp(a,b,t) smoothstep(e0,e1,x) gate(edge,x)` (1 once `x >= edge`) ·
`sin cos tan atan2(y,x)` (degrees) · `sqrt pow exp log floor ceil round fract mod sign hypot` ·
`hash(n)` and `hash2(a,b)` (stateless, 0..1, seeded by the stroke) ·
`noise(t)` and `noise2(x,y)` (smooth value noise, 0..1, seeded by the stroke) ·
`rand()` (0..1) and `rands()` (−1..1), a sequential stream seeded by the stroke.

Use `hash`/`noise` for things that must stay put as the stroke advances (a bristle's identity), `rand` for
fresh draws (when a drip lets go).

## Time ticks and lift-off

A brush's dab steps are spaced by distance, so a held pen takes none. With `tickMs` set, the program also
runs on pen time: whenever at least `tickMs` ms have passed since the last step, it steps again at the pen's
position with `phase` 2, `dt` = the tick, and the brush's own dab **not** painted (`emit` is the only way to
paint). While the pen creeps, ticks interleave with distance steps; every step carries its own `dt`, so a
program that integrates over `dt` gets the same answer however the time was cut up.

The editor repeats the pen's position every `tickMs` while a held pen sends no events, and a brush with
`tickMs` starts its stroke at touchdown rather than after the touch slop; holding still sprays instead of
opening the eyedropper. The lift-off sample runs the program once more with `phase` 3 (and `dt` = time since
the last step), the one place a program can finish what it started: a drip that is still running when you let
go can be completed there.

Ticks and the lift step depend only on the recorded sample times, so replay takes the same ones.

## Wet paint

Programs cannot read the canvas, but they can read and write a coarse record of wet film: thickness in
micrometres on an 8-layer-px grid, drying with a time constant of 45 s. It is *history*, not pixels: a stroke
starts from the film its layer's previous spray stroke left (dried to now) and hands its own on, so a second
burst over the same spot a few seconds later lands on the first one's still-wet paint. After a project is
reopened the film is gone, which is also what is true.

| Call | Does |
|---|---|
| `spray(x, y, r, dh);` | lay up to `dh` µm at the centre, falling to nothing at radius `r` (px) |
| `drain(x, y, r, dh);` | take up to `dh` µm off, never below dry (a drip leaving) |
| `wetAt(x, y, r)` | centre-weighted mean film under a footprint of radius `r`, in µm, dried to now |

Film is stored as thickness, never as volume, so it does not depend on the brush size that laid it.
A footprint wider than 320 px is clamped. Cost is counted against the step's operation budget in proportion to
the area touched. A step that overruns its budget rolls back its state variables but not the film already laid.

## Limits

Programs are bounded so a brush can never hang the editor or fill memory. All are deterministic, so a
limit behaves identically on replay.

| Limit | Value | When exceeded |
|---|---|---|
| Source length (`init` + `step`) | 12,000 chars | refused at compile |
| State variables | 32 | refused |
| Locals | 96 | refused |
| Block nesting (`if`/`for`) | 8 | refused |
| Expression nesting (parentheses, calls) | 32 | refused |
| Loop iterations (one `for`) | 64 | refused |
| Operations per step | 40,000 | the step paints the brush's own dabs unchanged and the program's state is rolled back |
| `emit`s per step | 256 | further emits that step are ignored |
| `emit`s per stroke | 40,000 | further emits are ignored |

## Authoring notes

- Cost scales with dabs per second. Prefer `emit` loops of 24–48 over 64-iteration nests, and check
  `peakOps` in the harness output: a healthy program peaks in the low thousands.
- A program that `emit`s many dabs per step should usually set `hide = 1` for the brush's own dab, or paint
  little itself, and raise `spacing` so steps are not also dense.
- Dab emits are dabs: with a custom tip they stamp that tip. Give a program brush a tip designed for what
  it emits (a soft round tip for a drip, a hard sliver for a hatch).
- The dabs of one step share the step's colour; vary `hue`/`sat`/`val` per emit for tonal shading.
- `dt` and `speed` are how a program feels the hand. `dt/stepPx` is how long the pen lingered.

## Render harness

`PackRenderHarness` (`core/engine/src/desktopTest`) renders unpacked packs through the real generator —
every brush against six test strokes — and reports each program's compile result, dab counts, worst step cost
and the time the generator took. Run it standalone or through Gradle:

~~~
./gradlew :core:engine:desktopTest --tests '*PackRenderHarness*' --offline \
    -Pgraffux.packs=<dir of unpacked packs> -Pgraffux.packs.out=<out dir> [-Pgraffux.packs.previews=true]
~~~

The stamping is a CPU approximation of the GPU engine's; shapes and behaviour are real.
