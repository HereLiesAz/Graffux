# Azphalt Engine 2

The existing Basic Brush is the latency baseline and remains untouched until this engine matches it perceptually.

## Performance contract

Live-stroke latency must be bounded independently of stroke length. Per-frame work is proportional to newly arrived samples/dabs and dirty pixels, never the complete stroke or canvas.

1. Input collection is independent of presentation; source samples are never discarded when preview frames coalesce.
2. One render may be in flight and one pending snapshot is replaceable. No per-sample render-job queue.
3. Dab generation becomes stateful: arc-distance remainder, deterministic RNG, prior sample, dab index and dynamics state persist across frames. New samples emit only new dabs.
4. The Vulkan stroke target remains GPU-resident from pointer-down to pointer-up.
5. Full GPU-to-CPU bitmap readback is forbidden in the drag hot path.
6. Dirty tiles bound impasto/effects and eventual CPU synchronization.
7. Pointer-up performs canonical reconciliation where retroactive data is genuinely required, then synchronizes dirty tiles and creates undo deltas.

Under overload the engine may reduce preview frequency, but it may not accumulate latency. Intermediate preview states are replaced by the newest state and the next frame catches up to the latest recorded input.

Existing Azphalt brush definitions remain the public brush format. Basic Brush is not migrated until parity and latency tests prove this path is at least as responsive.
