# Azphalt Engine 2 — Convergence

This pass completes the live-brush performance convergence begun in PRs #316 and #317.

## Performance contract

- Every physical `Tool.BRUSH` input sample is recorded into canonical stroke history before preview cadence is considered.
- Preview cadence may defer rendering, but may never discard canonical input.
- Render work is losslessly coalesced behind one background consumer so a slow renderer grows batch size, not a coroutine/job backlog.
- Live cost for Azphalt stamp brushes is proportional to newly available samples/dabs, not total stroke history.
- Basic Brush preserves its existing Catmull–Rom geometry, dynamic width recursion, symmetry, wrap-around behavior, opacity, feathering, and CPU fallback while its blocking GPU/readback work moves off the input thread.
- Basic Brush curve-run boundaries remain intact when coalesced because the existing Vulkan `buildUp=false` combine is call-scoped; scheduler batching must not change low-opacity pixels.
- Eligible Azphalt brushes continue to use AHardwareBuffer-backed zero-copy display; software/impasto/airbrush fallbacks retain dirty-region readback.

## Vulkan submission decision

The persistent live image is also the AHardwareBuffer displayed by Compose. Returning from `vkQueueSubmit` before its fence signals and immediately publishing that same buffer would allow the display to sample it while compute is still writing it. A correct multi-submit design therefore requires multiple presentation images (or explicit cross-API completion-aware presentation), not merely a ring of command buffers/fences.

Engine 2 instead removes the fence from the input-critical path: native submit/fence/readback runs on the single coalescing render worker while input continues losslessly. `AzphaltLatencyTracker` measures input→generation→submission→presentation so the native fence portion remains observable. A single-buffer “async” shortcut is intentionally rejected because it would trade latency for tearing/data races.

## Validation

The convergence suite covers:

- render-cadence behavior independent of input capture;
- fixed-size latency telemetry and stage percentiles;
- long static scatter/count-jitter strokes;
- long dynamic/taper/blot/masked strokes;
- high-rate held-airbrush generation;
- existing live stamp, airbrush, impasto, replay, and editor regression suites.
