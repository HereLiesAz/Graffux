# Azphalt Engine 2

## Performance contract

The existing Basic Brush is the latency baseline and remains untouched until this engine matches it perceptually.

A live stroke must have bounded latency independent of stroke length. Work performed for a new frame must be proportional to newly arrived samples/dabs and dirty pixels, never to the complete stroke or canvas.

## Live pipeline

1. Input collection is independent of presentation. Samples are retained even when preview frames are coalesced.
2. A latest-wins scheduler permits one render in flight and one replaceable pending snapshot. There is no per-sample render-job queue.
3. Dab generation is stateful. Generator state retains arc-distance remainder, deterministic RNG state, previous sample, dab index, and dynamics state. New samples emit only new dabs.
4. The Vulkan stroke target remains GPU-resident from pointer-down to pointer-up. New dabs are appended to that target.
5. The live target is presented directly. Full GPU-to-CPU bitmap readback is forbidden in the drag hot path.
6. Dirty tiles bound expensive secondary work such as impasto shading and eventual CPU synchronization.
7. Pointer-up performs canonical reconciliation where a brush feature requires retroactive information (for example end taper), then synchronizes dirty tiles and creates undo deltas.

## Overload behavior

Rendering may reduce preview frequency, but may not discard source input or accumulate latency. If rendering takes longer than input arrival, intermediate preview snapshots are replaced by the newest state. The next render catches up to the latest recorded input.

## Compatibility

Existing Azphalt brush definitions remain the public brush format. Tip masks, grain, dual tips, scatter, airbrush and dynamics are parameters/resources consumed by one live pipeline rather than reasons to select different CPU/GPU architectures.

## Migration

Phase 1: latest-wins scheduling and background dab preparation.
Phase 2: incremental stateful dab generation.
Phase 3: persistent GPU stroke target and removal of per-frame readback.
Phase 4: dirty-tile impasto/effects and commit synchronization.
Phase 5: performance/parity gate against Basic Brush. Only after that gate may Basic Brush be considered for migration.
