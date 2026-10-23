# Verification Contract: Implement Objective (Remaining Prioritized TODO)

This document derives the verification contract for completing the authoritative "Remaining prioritized TODO" from `docs/Material-Aware Paint Engine Implementation Status.md` and the constraints in `docs/Brush Stroke Mechanics Roadmap.md`.

## Architectural Invariants and Constraints
- **Single Source of Truth**: Telemetry metadata must remain intact through every live/replay transform and interpolation path.
- **Renderer Contract**: Renderers must consume resolved geometry; they must not reinterpret raw device telemetry.
- **Backend Limit**: Do not start a second wet-mix/pickup backend beside Color Smudge.
- **Latency Priority**: Do not trade bounded input latency for higher simulation fidelity.
- **Simplicity Gate**: Full spectral/Kubelka-Munk, full individual-bristle PBD/DER, FLIP/PIC/pressure-projected fluids, and porous-paper capillary simulation must not be implemented unless explicit evidence confirms cheaper models fail to produce required marks.
- **Compatibility**: Legacy behavior is the default unless a brush/material feature explicitly opts in.
- **Telemetry Boundaries**: Missing stylus axes remain unavailable rather than synthesized. Finger input remains a distinct modality.

---

## Objective 1: Real-Hardware Parity Verification
**Requirement**: Run the existing CPU ↔ Vulkan pigment + reservoir/pickup parity suite on real Android Vulkan hardware and record the device/result matrix.

### Contract & Behavioral Tests
- **Parity Contract**: Given an identical multi-contact input state, the CPU engine and Vulkan engine must emit identical (or deterministic delta-bounded) pixel output across varying blending operations.
- **Acceptance Criteria**: The parity suite must execute on at least one representative Adreno device and one Mali device, outputting a test matrix log without crashing or hanging.

---

## Objective 2: Performance Baselines
**Requirement**: Complete the remaining Phase-0 measurement gap: physical-device material latency/performance baselines.

### Contract & Behavioral Tests
- **Performance Contract**: Input-to-pixel latency must not exceed established thresholds across the versioned media-profile boundary.
- **Acceptance Criteria**: A benchmark suite must capture and log end-to-end response times for standard drag, pressure ramp, and cornering maneuvers on target hardware.

---

## Objective 3: Hardware-Specific Mechanics Hardening
**Requirement**: Harden brush mechanics on real hardware: premium stylus, pressure-only/basic stylus, and finger traces; tune pressure/tilt/orientation confidence and lifecycle behavior.

### Behavioral Tests & Edge Cases
- **Premium Stylus Test**: Reversal, lift-off, and cornering traces must demonstrate stable tuft lag and split retention without snapping abruptly.
- **Finger Trace Test**: Input lacking tilt/azimuth must deterministically project symmetric geometry without relying on injected "ghost" orientations.
- **Edge Case (Signal Dropout)**: Sudden pressure or azimuth discontinuity must not cause math/NaN explosions in the contact topology.

---

## Objective 4: Archetype Tuning
**Requirement**: Tune the initial Round, Flat, Filbert, Rigger, Fan, and Rake archetypes with visual reference strokes rather than exposing raw solver coefficients prematurely.

### Contract & Behavioral Tests
- **Acceptance Criteria**: Each archetype must successfully recreate its established golden reference stroke (straight drag, 90° corner, 180° reversal, pressure ramp) within acceptable visual tolerance.
- **Invariant**: Raw mechanical coefficients (stiffness, cohesion, splay, bend) remain encapsulated and not exposed as permanent UI sliders.

---

## Objective 5: Dynamic Performance Policy
**Requirement**: Add capability-tier/performance policy for physical tuft population and verify bounded cost on representative Adreno/Mali devices.

### Contract & Edge Cases
- **Behavioral Contract**: On constrained hardware, the physical engine must deterministically bound or down-sample the tuft count to maintain framerate.
- **Invariant**: This down-sampling must remain strictly deterministic (no RNG scatter) to preserve live/canonical replay parity.
- **Failure Scenario**: Requesting max brush diameter on a low-tier capability device successfully culls physical bristle limits without OOM or freezing.

---

## Objective 6: Substrate-Aware Deposition (Phase 3)
**Requirement**: Begin Phase 3 substrate-aware deposition using the now-stable physical contact topology.

### Contract & Behavioral Tests
- **Contract Test**: Ink deposition must respect the masking properties of substrate heightmaps; valleys collect ink and peaks resist ink deterministically based on pressure state.

---

## Objective 7: Persistent Wetness (Phase 4)
**Requirement**: Add Phase 4 persistent wetness + bounded active-tile transport.

### Contract & Edge Cases
- **Bounding Contract**: Wetness transport simulation must only execute on the explicit set of active tiles touched by the current action.
- **Edge Case**: rapid strokes crossing multiple tile boundaries predictably invoke transport initialization and finalization without corrupting adjacent tiles.

---

## Objective 8: Impasto v2 (Phase 5)
**Requirement**: Evolve the existing height engine into Phase 5 Impasto v2, including material persistence/reconstruction and wet/dry optics.

### Contract & Behavioral Tests
- **Behavioral Test (Wet vs Dry)**: A new wet stroke layered on a dry stroke must visibly overlay or build height. A new wet stroke intersecting existing wet material must mix according to the Color Smudge/Wet Mix semantics.

---

## Objective 9: Semantic Media-Profile UX (Phase 7)
**Requirement**: Build the tuned Phase 7 semantic media-profile catalogue/product controls on the landed versioned profile boundary.

### Contract & Behavioral Tests
- **Acceptance Criteria**: Toggling UI options between media profiles must instantly trigger the correct deterministic mechanical behavior state transitions.
- **Invariant**: Exposing media catalogs must strictly use existing material channels; no new parallel backends.

---

## Objective 10: Research Walls
**Requirement**: Keep experimental rendering tracks behind explicit evidence walls.
- **Invariant**: The core engine must successfully fail capabilities checks before any experimental R1-R5 features (Kubelka-Munk, PBD, FLIP/PIC, Splats) are authorized to begin execution.