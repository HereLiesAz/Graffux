# Verification Contract: Remaining TODO Items

## Objective
To codify the verification contract for the remaining deferred implementations and "open items" documented in `docs/Material-Aware Paint Engine Implementation Status.md`, `spec/package-format.md`, and `spec/store-app.md`. Correct implementation must satisfy the following acceptance test plans, behavioral tests, contract tests, invariants, and edge cases.

## 1. Material-Aware Paint Engine

### 1.1 CPU ↔ Vulkan Parity & Hardware Baseline
**Acceptance Criteria:**
The CPU ↔ Vulkan parity suite must run on representative real Android Vulkan hardware. Physical-device material latency and performance baselines must be documented.
**Contract Tests:**
- Execute the Vulkan pigment and reservoir pickup parity suites on target hardware. The Vulkan output must strictly match the deterministic CPU reference output.
**Behavioral Tests:**
- Render pipeline latency during material stages must not violate the 120fps (8.33ms) frame budget.
**Failure Scenarios:**
- If the Vulkan device driver lacks required extensions or fails context creation, execution must deterministically fall back to the CPU reference without corrupting the stroke state.

### 1.2 Brush Mechanics Hardening & Tuning
**Acceptance Criteria:**
Mechanics must be hardened against premium stylus, basic stylus, and finger traces. Initial physical archetypes (Round, Flat, Filbert, Rigger, Fan, Rake) must be visually tuned.
**Invariants:**
- Input modalities lacking advanced telemetry (tilt, azimuth) must flag those axes as `unavailable`. The engine must never fabricate missing hardware telemetry.
- Physical bundle diameter must remain fixed, independently of parent brush scale.
- Tuft counts must be strictly bounded by configured minimum and maximum limits.
**Edge Cases:**
- Lift-off mechanics must deterministically branch between rapid flicks (trailing/tapering) and slow, deliberate lifts (convergence/recovery).

### 1.3 Material Phases 3, 4, 5, 7 Integration
**Acceptance Criteria:**
Implement Phase 3 (substrate-aware deposition), Phase 4 (persistent wetness / bounded transport), Phase 5 (Impasto v2 with wet/dry optics), and Phase 7 (semantic media profiles).
**Invariants:**
- Bounded local transport (Phase 4) must conserve material; total material in the system must not spontaneously increase during transport.
- Pickup cannot overfill the reservoir, and deposition cannot draw more than the reservoir load.
- Legacy brushes must preserve the `LEGACY_DRY` profile and strict RGB mode semantics unless explicitly overridden.
**Behavioral Tests:**
- (Phase 3) Light pressure scumble must only deposit on substrate crests; heavy pressure must penetrate and fill substrate valleys.
- (Phase 5) Brush pickup must demonstrably remove material volume and height from the canvas.
- (Phase 7) Explicit medium selection must apply material channels without overriding the user's RGB intent (colour is not medium).

## 2. Package Format & Sandboxing

### 2.1 Ghost Capabilities (`bitmap` and `audio`)
**Acceptance Criteria:**
Host-function bridges for `bitmap` and `audio` capabilities must be fully implemented in `WasmSandbox` and `JsSandbox`, or the `Capability` enum must be purged of them.
**Contract Tests:**
- Extensions that request `bitmap` or `audio` without user grant must receive an explicit capability denial at runtime.
- Extensions possessing the capability grant must successfully bind to and invoke the host functions.

### 2.2 JS Exception Transparency
**Acceptance Criteria:**
QuickJS exceptions must propagate detailed errors rather than being swallowed by a generic `RuntimeException`.
**Behavioral Tests:**
- When guest JavaScript execution throws a runtime error, `JsSandbox.eval()` must capture the specific QuickJS error message, value, and context, passing it across the host boundary.

### 2.3 Missing Normative Specifications
**Acceptance Criteria:**
The absent normative documents (`spec/extension-manifest.md`, `spec/pack.md`, `spec/companion-app.md`, `spec/mcp-server.md`, `spec/ui-schema.md`, `spec/repository-api.md`) must be authored.
**Invariants:**
- Implemented internal schemas for app blocks, MCP config, and repository APIs must exactly reflect the constraints defined in these new specifications.

## 3. Store App Handoff & Entitlements

### 3.1 Telemetry Token Spending
**Acceptance Criteria:**
The engine must implement the consumption of `EXTRA_REPORT_TOKEN` against an external repository endpoint as defined in the state-reporting contract.
**Contract Tests:**
- The `EXTRA_REPORT_TOKEN` must be securely transmitted and subsequently invalidated to prevent replay attacks.
**Failure Scenarios:**
- Network failure during token transmission must degrade gracefully, either queuing the token or failing the telemetry request without crashing the host application.

### 3.2 Entitlement Redemption
**Acceptance Criteria:**
Graffux must formally validate and redeem `EXTRA_ENTITLEMENT` (the registry-signed token) for paid packages.
**Contract Tests:**
- An extension attempting initialization with an expired, malformed, or incorrectly signed `EXTRA_ENTITLEMENT` token must be denied execution.
- Valid tokens must fully unlock designated paid capabilities within the extension boundary.
