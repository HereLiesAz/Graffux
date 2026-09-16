# Verification Contract: Complete Remaining TODO Task Items

This verification contract defines the acceptance test plans, behavioral tests, contract tests, invariants, edge cases, and failure scenarios for the "Remaining prioritized TODO" items specified in `docs/Material-Aware Paint Engine Implementation Status.md` and `docs/Brush Stroke Mechanics Roadmap.md`.

## 1. Hardware Parity & Performance Baselines (TODO 1, 2, 5)

### Acceptance Test Plans
*   **Vulkan Parity Matrix:** Execute the existing CPU ↔ Vulkan pigment and reservoir/pickup parity suites on a matrix of real Android Vulkan devices (including representative Adreno and Mali GPUs).
*   **Performance Baselines:** Measure and record material execution latency per dab and per frame across premium and low-tier devices.
*   **Tuft Population Tiers:** Verify that bounding the tuft count using the capability-tier policy yields stable frame rates on Adreno/Mali devices without breaking deterministic semantics.

### Contract Tests & Invariants
*   **Invariant:** The CPU and GPU Vulkan results for pigment mixing, reservoir depletion, and pickup must fall within an approved numerical tolerance.
*   **Invariant:** The physical tuft solver must execute within bounded O(new dabs) time; it must never exhibit O(total stroke history) scaling.
*   **Invariant:** Lowering the capability tier reduces the number of evaluated tufts but preserves the overarching stable topology and contact center.

### Edge Cases & Failure Scenarios
*   **Failure:** A Vulkan device fails to compile or execute the material shader.
    *   **Behavior:** The system must deterministically fall back to the CPU reference implementation without dropping reservoir or pigment state.
*   **Edge Case:** An extreme brush size exceeds the max physical tuft count.
    *   **Behavior:** The population caps at the defined maximum threshold instead of scaling boundlessly, retaining its structural grid/radial layout.

## 2. Brush Mechanics & Archetype Tuning (TODO 3, 4)

### Acceptance Test Plans
*   **Device Profiles:** Capture real-world telemetry traces using premium styluses (pressure, tilt, orientation), basic styluses (pressure only), and fingers (touch geometry). Replay these traces and assert they produce the expected visual intent and lifecycle states.
*   **Archetype Validation:** Present golden reference strokes for Round, Flat, Filbert, Rigger, Fan, and Rake archetypes. Validate against visual outputs without exposing raw solver coefficients to the user interface.

### Contract Tests & Invariants
*   **Invariant:** Telemetry inputs that lack certain axes (e.g., tilt on basic styluses, or orientation) must NOT fabricate those axes; they must report explicitly as unavailable.
*   **Invariant:** Morphology selection alters stable topology and mechanical coefficients (splay, cohesion, bend) rather than merely swapping static masks.

### Edge Cases & Failure Scenarios
*   **Edge Case:** A finger trace abruptly shifts its minor/major contact area.
    *   **Behavior:** The solver resolves the footprint intent using stiffness/hysteresis, not instantaneous angle/width jumps.
*   **Edge Case:** High-speed flicks vs. slow liftoffs.
    *   **Behavior:** A fast flick causes tuft trailing/taper before lift, while a slow liftoff exhibits tuft convergence and recovery.

## 3. Substrate, Wetness, and Impasto v2 (Phase 3, 4, 5) (TODO 6, 7, 8)

### Acceptance Test Plans
*   **Substrate Deposition:** Run strokes with varying pressure and verify contact-depth-vs-substrate-height logic. Light strokes must scumble crests; heavy strokes must penetrate into valleys.
*   **Wetness & Transport:** Assert that repeated strokes over a wet tile accumulate/displace material based on bounded local transport, stopping when wetness dries out or leaves the active tile set.
*   **Impasto v2 Persistence:** Assert that material height, structure recovery, and wet/dry optics serialize correctly into the canonical material channels and reconstruct identically on reload.

### Contract Tests & Invariants
*   **Invariant:** Impasto and Wetness transport must have zero effective idle cost for dry documents or untouched canvas regions.
*   **Invariant:** Deposition cannot overdraw the reservoir load; bounded transport must obey material conservation where practical.

### Edge Cases & Failure Scenarios
*   **Edge Case:** The material transport model encounters highly saturated wet-over-wet boundaries.
    *   **Behavior:** Execution budget stays strictly bounded to small iteration limits; fidelity degrades gracefully before latency thresholds are breached.
*   **Edge Case:** Brush picks up material from a deep Impasto valley.
    *   **Behavior:** Reservoir volume transfer accurately represents the contact volume up to the tuft's bounded capacity.

## 4. Semantic Media-Profile Controls (Phase 7) (TODO 9)

### Acceptance Test Plans
*   **Catalogue UI:** The UI must expose product profiles (e.g., Heavy Oil, Watercolor, Marker) and map them to the underlying `PaintMediaProfile` boundary.
*   **Control Simplification:** Verify that only Load, Pickup, and Pigment Mixing controls are available for the Color Smudge/Wet Mix path, maintaining minimal artist-facing mechanical friction.

### Contract Tests & Invariants
*   **Invariant:** Selecting an RGB color does not silently assign a viscosity or pigment medium (Color is not Medium).
*   **Invariant:** Importing a legacy preset or disabled configuration defaults exactly to `LEGACY_DRY` compatibility.

### Edge Cases & Failure Scenarios
*   **Failure:** A product profile requests an unversioned or absent material channel.
    *   **Behavior:** Fallback to exact `LEGACY_DRY` baseline or a compatible versioned profile gracefully without corrupting the stroke intent.

## 5. Negative Constraints & Exclusions (TODO 10)

### Invariants (Strict Enforcement)
*   **Invariant:** NO second wet-mix/pickup backend can be introduced alongside Color Smudge.
*   **Invariant:** NO renderer (CPU or Vulkan) may reinterpret raw device telemetry; they must only consume resolved geometry.
*   **Invariant:** NO bounded input latency may be traded for higher simulation fidelity.
*   **Invariant:** R1-R5 research paths (spectral mixing, full individual-bristle PBD, FLIP fluids, porous-paper capillary, Gaussian-splat) remain strictly deferred unless documented explicit evidence proves the bounded models fail visual goals. Any PR attempting to introduce them without explicit evidence must be rejected.
