# Painterly realism: device verification matrix (phase P9)

Companion to `spec/painterly.md`. Everything marked verified in the painterly PRs was run on a desktop JVM and on
Mesa lavapipe (software Vulkan). **Nothing has been run on a real Android GPU.** This is the checklist that closes
that gap; each row names what to measure, the target it must meet, and the spec section it comes from. Record results
in the PR that fixes them; a row is not done until it is recorded on at least one device per tier.

## Devices

| Tier (`BrushPerformancePolicy`) | Example class of device | Why |
|---|---|---|
| CONSTRAINED | low-RAM Android 10, Mali-G52 or Adreno 610 class | the floor (Vulkan 1.1) |
| BALANCED | mid-range, Adreno 618-642 or Mali-G76/G77 | the target (spec 6.7) |
| FULL | flagship with a stylus (tilt, azimuth, pressure) | pen telemetry, the Reel demo |

At least one pressure-only stylus and one finger-only device (no tilt, no azimuth).

## Measurements

| # | Check | Target | Spec |
|---|---|---|---|
| 1 | Paint transaction time per batch of 48 dabs, 64 x 64 px footprint | under 2 ms (BALANCED); report CONSTRAINED and FULL | 6.7 |
| 2 | Generator cost per step for each shipped painterly brush (`PackRenderHarness` `peakOps` plus on-device timing) | at most 8,000 ops p95 | 6.7 |
| 3 | Live stroke input-to-pixel latency with a painterly brush vs the plain Round | no worse than Round | 6.7 |
| 4 | GPU memory with the 512 x 512 board and a 2048 x 2048 painterly canvas at 30% coverage | under 35 MB beyond today | 6.7 |
| 5 | Frame time while mixing on the board | no dropped frames at 60 Hz on BALANCED | 6.3 |
| 6 | Drawn-brush overlay cost per frame (when built) | under 0.5 ms on BALANCED | E9 |
| 7 | Touch-to-paint latency with and without the drawn brush | report both; the brush must not lag the paint | E9 item 3 |

## Correctness on real GPUs

| # | Check | Pass |
|---|---|---|
| 8 | `core/wgpu-engine` tests with `GRAFFUX_REQUIRE_GPU=vulkan` on the device GPU (not lavapipe): `pigment`, `transaction`, `watercolour`, `wet`, `layout_parity` | within the stated tolerances (colour 2 levels, amount 1e-4, concentration bytes within one step) |
| 9 | Replay: draw a petal, undo, redo | identical pixels on the same device |
| 10 | Reopen the project | board recipes, brush load and recorded strokes restored |
| 11 | Replay a `StrokeRecording` on a second device | within tolerance (cross-device) |
| 12 | The Loaded Petal Flat pull with a stylus | swell, two-tone load, bristle streaks, wet pickup, rim ridge with highlight, point on lift (the five behaviours of the reference) |
| 13 | The same pull by finger | the lift tail closes the stroke to a point |

## Accessibility (spec 6.6)

TalkBack: every tube, blob and brush announces its name and recipe; targets at least 48 dp with 8 dp gaps; no state by
colour alone; reduced-motion; font scale 200% does not clip the board controls; colour-vision simulation keeps tubes
distinguishable by name and pattern; the "Hide drawn brush" setting (when built) removes the overlay and changes nothing
else.

## Verified so far (and where)

Kotlin reference tests, golden-fixture parity of the WGSL ports against Kotlin on lavapipe (bit-exact for the transaction
and watercolour passes, 1e-6 for the pigment mixer), codec round trips, Robolectric rendering of the mixer dialog, detekt.
None of it substitutes for rows 1 to 13.
