# Verification Contract: Remaining Todo Task Items

## Scope
Derivation of the verification contract for the remaining prioritized TODOs listed in `docs/Material-Aware Paint Engine Implementation Status.md`, including hardware parity, archetype tuning, substrate-aware deposition (Phase 3), persistent wetness (Phase 4), Impasto v2 (Phase 5), and semantic media profiles (Phase 7).

## 1. Hardware Parity, Telemetry & Performance (TODOs 1-3, 5)
### Acceptance Criteria
- CPU and Vulkan pigment mixing and reservoir/pickup suites produce identical pixel output on physical Adreno and Mali hardware.
- High-quality stylus, basic stylus, and finger traces exhibit robust confidence lifecycle and metadata preservation.
- Physical tuft population applies a bounded capability-tier policy.
### Behavioral & Contract Tests
- `VulkanParityHardwareTest`: Deploy and execute `MaterialGoldenFixtureMatrixTest` on real Vulkan devices; assert zero deviation from the deterministic CPU reference.
- `TelemetryProvenanceTest`: Assert availability, provenance, and phase remain intact through all live/replay and interpolation pipelines.
### Invariants
- `pickupRate = 0` remains the zero-cost historical baseline.
- Missing stylus axes are explicitly unavailable, never fabricated.
### Failure Scenarios
- Vulkan execution failure seamlessly falls back to CPU reference without reservoir state loss.

## 2. Morphology & Archetypes (TODO 4)
### Acceptance Criteria
- Round, Flat, Filbert, Rigger, Fan, and Rake archetypes exhibit visually distinct characteristics dictated by physics (cohesion, splay, stiffness), not static mask swapping.
### Contract Tests
- `ArchetypeLiveParityTest`: Generated multi-contact tuft geometry matches canonically resolved stamps across straight drag, corner, reversal, and lift.
### Edge Cases
- Extreme yaw/pitch transitions during high-velocity drag maintain stable tuft identities without random scatter.

## 3. Substrate-Aware Deposition (Phase 3)
### Acceptance Criteria
- Deposition respects the contact-depth-vs-substrate-height rule based on pressure and absorbency.
- Crest-only scumble is deposited during light contact; valleys are filled during high-pressure or fluid deposition.
### Behavioral Tests
- `SubstrateScumbleTest`: Low-pressure strokes intersect only substrate peaks.
- `SubstratePenetrationTest`: High-pressure strokes entirely mask underlying substrate topography.
### Failure Scenarios
- Invalid substrate textures gracefully default to a uniform/flat tooth field.

## 4. Persistent Wetness & Transport (Phase 4)
### Acceptance Criteria
- Bounded local diffusion/advection processes correctly without unbounded canvas-wide iteration costs.
- Dry documents exhibit exactly zero effective idle processing cost.
### Invariants
- Conservation of mass: Material transfer via active tiles does not spontaneously create or destroy pigment volume.
- Simulation-time advancement is explicitly deterministic to ensure reproducible replays.

## 5. Impasto v2 (Phase 5)
### Acceptance Criteria
- Brush strokes correctly deposit, displace, and pick up volumetric material height.
- Wet leveling and structure recovery behave deterministically.
### Contract Tests
- `HeightTransferTest`: Stroke volume intersecting an existing impasto ridge deforms and carries away height material based on reservoir volume.
### Invariants
- The versioned persistence boundary strictly governs all canonical material channels.

## 6. Semantic Media Profiles (Phase 7)
### Acceptance Criteria
- Media product controls (e.g., Heavy Oil, Acrylic, Watercolor) apply pre-tuned, stable material parameters over the underlying renderer channels.
### Invariants
- Color is not a medium: Material semantics do not dictate or override the explicit RGB/pigment color intent.