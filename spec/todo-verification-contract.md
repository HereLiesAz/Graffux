# Verification Contract: Complete Remaining Todo Task Items

This document serves as the formal verification contract for completing the remaining Todo task items in the Graffux repository, derived entirely from approved specifications, architecture documents, and roadmaps without inspecting implementation code.

## 1. Documentation Gaps & Open Architectural Questions (`ARCHITECTURE.md`)

**Objective:** Author the 6 missing normative specifications and resolve the 3 open unconfirmed architectural questions.

### Acceptance Criteria
- `spec/extension-manifest.md`, `spec/pack.md`, `spec/companion-app.md`, `spec/mcp-server.md`, `spec/ui-schema.md`, and `spec/repository-api.md` must be authored.
- New specifications must be derived entirely by reading referencing files in the codebase and grounding every claim in code.
- Unconfirmable assertions must be marked as `TODO` rather than invented.
- Product or Architect resolutions must be provided for:
  - The missing host-function bindings for `bitmap` and `audio` capabilities (intentional reservation vs. gap).
  - QuickJS exception message propagation in `JsSandbox.eval()` (generic `RuntimeException` vs. detailed propagation).
  - The spending mechanism of `EXTRA_REPORT_TOKEN` against a repository endpoint (deferred scope vs. gap).

### Behavioral & Contract Tests
- **Traceability Test:** Every feature, API, and schema property defined in the new specifications must have an identifiable usage site in the existing specification or code.
- **Consistency Test:** The new documents must not contradict existing definitions in `spec/package-format.md` or `spec/state-reporting.md`.

### Invariants
- Documentation reflects the codebase "as-is"; it does not prescribe unbuilt features without explicitly marking them as such.

### Edge Cases
- Referencing files use features in inconsistent ways. The specification must document the inconsistency or escalate for architectural alignment.

### Failure Scenarios
- **Failure:** A specification invents a capability or schema block that has no concrete usage in the codebase, violating the rule against inventing unconfirmable claims.

---

## 2. Material-Aware Paint Engine (`docs/Material-Aware Paint Engine Implementation Status.md`)

**Objective:** Complete Phase 3–7 material features and Vulkan hardware validation from the remaining prioritized TODO list.

### Acceptance Criteria
- The CPU ↔ Vulkan pigment and reservoir/pickup parity suite is successfully executed on real Android Vulkan hardware, with a documented device/result matrix.
- Phase-0 physical-device material latency and performance baselines are recorded.
- Brush mechanics are hardened on real hardware (premium stylus, basic stylus, and finger traces).
- Initial archetypes (Round, Flat, Filbert, Rigger, Fan, Rake) are tuned using visual reference strokes rather than exposing raw solver coefficients.
- Capability-tier/performance policies for physical tuft population are added and their bounded cost is verified on representative Adreno/Mali devices.
- Phase 3 (substrate-aware deposition), Phase 4 (persistent wetness + bounded active-tile transport), and Phase 5 (Impasto v2) are sequentially integrated.
- Phase 7 (semantic media-profile catalogue/product controls) is built upon the landed versioned profile boundary.

### Behavioral & Contract Tests
- **Vulkan Parity Test:** Contract tests asserting that CPU rendering outputs strictly match Vulkan rendering outputs within defined tolerances for pigment mixing and reservoir pickup.
- **Substrate Deposition Test:** Behavioral tests ensuring that paint deposition adheres to the physical contact topology constraints on varying substrates.
- **Wetness Transport Boundary Test:** Tests asserting that active-tile transport of wet paint does not exceed defined tile boundaries or bounding constraints.
- **Performance Baseline Test:** Execution time and memory footprint of physical tuft population must not exceed established baselines for Adreno/Mali capability tiers.

### Invariants
- Brushstroke mechanics improvements take priority over material work unless material work is required to preserve correctness.
- Renderers must not reinterpret raw telemetry.
- Bounded input latency must not be traded for higher simulation fidelity.
- Advanced research tracks (e.g., full individual-bristle PBD/DER, Kubelka-Munk) must remain deferred unless cheaper models fail to produce required marks.

### Edge Cases
- Vulkan driver idiosyncrasies on specific Adreno or Mali chipsets cause parity failures.
- Device thermal throttling impacts bounded input latency metrics during sustained testing.

### Failure Scenarios
- **Failure:** A material improvement introduces unbounded active-tile transport, violating Phase 4 limits.
- **Failure:** A second wet-mix/pickup backend is started beside Color Smudge.

---

## 3. Brush Stroke Mechanics (`docs/Brush Stroke Mechanics Roadmap.md`)

**Objective:** Finalize M1 validation and implement the M2 stable tuft topology contract.

### Acceptance Criteria
- Hosted validation of device-aware telemetry and M1 contact integration is finished.
- M2 introduces a renderer-independent stable tuft topology contract.
- Deterministic multi-contact geometry is emitted from the topology without any random bristle scatter.
- Stateful tuft splay, separation, and cohesion are implemented, driven by mechanical state and confidence-weighted intent evidence.
- Live/canonical parity fixtures are created for: straight drag, corner, reversal, pressure ramp, tilt change, and finger contact change.
- Artist-facing mechanical controls are exposed only *after* mechanical behavior is tuned via parity fixtures.

### Behavioral & Contract Tests
- **Telemetry Preservation Test:** Asserts that telemetry metadata remains strictly intact through every live/replay transform and interpolation path.
- **Deterministic Geometry Contract Test:** Given an identical sequence of telemetry inputs, the emitted multi-contact geometry must be precisely identical across multiple runs.
- **Mechanical Parity Fixture Tests:** Automated visual regression tests executing straight drag, corner, reversal, pressure ramp, tilt change, and finger contact change match canonical expected states.

### Invariants
- Telemetry metadata must never be lost, stripped, or distorted during transformation or interpolation.
- The basic/static brush must remain the latency baseline.
- Telemetry remains a first-class citizen; its physical interpretation progresses over time without discarding the raw input.

### Edge Cases
- Fast interpolation across a sequence with sparse telemetry points (e.g., fast strokes) resulting in unnatural tuft splay.
- Rapidly alternating between high-confidence (stylus tilt/pressure) and low-confidence (finger contact) inputs.

### Failure Scenarios
- **Failure:** Random bristle scatter is introduced into the multi-contact geometry, breaking deterministic replayability.
- **Failure:** Artist-facing mechanical controls (raw solver coefficients) are exposed to the UI prematurely.
