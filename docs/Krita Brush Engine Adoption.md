# Krita Brush Engine Adoption

Graffux should borrow solved painting-engine ideas from Krita aggressively while keeping a distinctly phone-first product model.

Krita is the reference for mature brush-engine decomposition; Graffux is not trying to reproduce Krita's desktop/tablet interface. The invariant is **Krita underneath, Graffux on top**: deep engine capability, small transient controls, presets, gestures, and progressive disclosure that preserve canvas space on phones.

## 1. Canonical input telemetry

Graffux normalizes Android input into `BrushSample` before rendering. A sample carries:

- x/y position;
- event time;
- pressure;
- stylus tilt;
- stylus orientation;
- cumulative travelled distance;
- instantaneous speed;
- drawing angle;
- whether the sample is prediction-only.

`BrushSampleBuilder` derives the kinematic fields. Predicted samples are presentation-only and are never allowed to become the basis of the next authoritative sample.

Sensor kinematics remain in physical screen/hand space. Viewport and layer transforms may remap x/y for rasterization, but zooming a canvas must not alter what the brush considers the same physical drawing speed or distance.

## 2. Sensor-to-parameter routing

`BrushSensorDynamics` provides the same important separation used by mature paint engines: input sensors do not directly know about renderers. A sensor is normalized, passed through a response curve, and then routed to a brush parameter.

Current sensors include pressure, speed, tilt, orientation, distance, time, drawing angle, per-dab random, and per-stroke random.

Current targets include size, opacity, flow, spacing, scatter, rotation, hue, saturation, and value.

Existing brushes have no routes by default, so their historical rendering path remains unchanged.

## 3. Determinism and replay

Authoritative strokes record canonical brush telemetry alongside the existing path and pressure arrays. Live rendering, commit, undo/redo replay, and future collaboration serialization should consume the same recorded sensor state instead of recomputing it from current device conditions.

Only x/y are transformed into bitmap coordinates during replay. Recorded speed, distance, tilt, orientation, time, and drawing angle remain unchanged.

Seeded random sensors and existing stamp jitter remain deterministic.

## 4. Phone-first Brush Studio

The engine may expose many sensors and targets without putting all of them permanently on screen.

Brush Studio keeps its compact floating-window form. Dynamics are collapsed by default and begin with useful mappings such as:

- Pressure → Size
- Pressure → Opacity
- Speed → Thin
- Tilt → Rotation

An advanced routing/curve editor can sit one level deeper. The normal drawing surface must remain focused on drawing rather than permanent configuration panels.

Phone-specific inputs should be treated as first-class opportunities: finger velocity, temporary thumb controls, long-press modifiers, device orientation where useful, and transient HUDs that disappear while painting.

## 5. CPU and Vulkan contract

`BrushStamps.dynamicDabs` resolves geometry and per-dab paint dynamics into concrete dabs. The Android raster renderer consumes those resolved instructions rather than reimplementing sensor logic.

The current Vulkan dab ABI supports per-dab geometry/alpha/angle but still receives color/flow at stroke level. Therefore dynamics that alter per-dab flow or HSV remain on the CPU path until the native dab structure is widened. Geometry-only dynamics can continue to use Vulkan.

The next native milestone is to add resolved per-dab color/flow to the Vulkan contract so all sensor routes can stay on the GPU path.

## 6. Next engine: Color Smudge

Graffux's existing Smudge tool already performs directional color carry and must not be replaced by blur. The next implementation generalizes that behavior using the same high-level decomposition proven by Krita's Color Smudge engine:

### Smear

Carry the sampled source region from the previous dab toward the current dab. This is the natural extension of Graffux's current directional smudge and should preserve the current behavior as the compatibility/default preset.

### Dulling

Sample a weighted color beneath the brush over a configurable smudge radius, then mix/fill the current dab with that sampled color while preserving the brush mask.

### Color Rate

Deposit the current foreground paint separately from the smudge stage. Smudge strength and paint deposition must be independently controllable so a brush can range from pure smudge through wet mixing to ordinary paint with a small amount of pickup.

### Initial Color Smudge settings

- mode: Smear or Dulling;
- smudge rate;
- color rate;
- smudge radius;
- opacity;
- alpha carry behavior.

The existing `Tool.SMUDGE` defaults should map to Smear with zero Color Rate so existing tests and user expectations remain valid.

## 7. Color Smudge implementation order

1. Factor the current directional smudge implementation into a reusable Color Smudge engine without changing existing output.
2. Preserve the current smudge test suite as compatibility coverage.
3. Add explicit Smear tests for direction, alpha, strength, and flat-color invariance.
4. Add Dulling with weighted color sampling and radius tests.
5. Add independent Color Rate/deposition and mixed-pigment tests.
6. Route Color Smudge parameters through the same sensor/curve system.
7. Move the correct CPU implementation to a persistent GPU read/modify/write path.
8. Benchmark local/tiled Vulkan strategies on actual Adreno and Mali devices before selecting an optimization.

Overlay/all-layer sampling and paint-thickness/height-map simulation come after the core Smear/Dulling/Color Rate behavior is correct and fast.

## 8. Non-negotiable architecture rules

- Prediction is disposable presentation state, never authoritative paint.
- Recorded physical gesture telemetry is deterministic replay data.
- View transforms alter geometry, not hand-motion sensor meaning.
- Static brushes keep their established rendering path unless dynamics are enabled.
- Brush behavior is resolved before renderer-specific code wherever practical.
- CPU and GPU paths must produce equivalent visible behavior.
- Engine power must not turn the phone interface into a desktop control panel.
