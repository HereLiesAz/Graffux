# Verification Contract: Remaining TODO Task Items

This document defines the verification contract for the remaining TODO task items identified in the architecture, specification, and documentation files. It establishes acceptance criteria, behavioral tests, contract tests, invariants, edge cases, and failure scenarios that must be satisfied by the implementation.

**Mode:** Specification (Derived without inspecting implementation code).

---

## 1. Missing Normative Specifications

**Source:** `ARCHITECTURE.md`
**TODO:** The following normative documents are missing: `spec/extension-manifest.md`, `spec/pack.md`, `spec/companion-app.md`, `spec/mcp-server.md`, `spec/ui-schema.md`, `spec/repository-api.md`.

### Acceptance Test Plan
- Verify that each of the missing `.md` files is created in the `spec/` directory.
- Verify that the contents of each file are grounded in the referencing code (without inventing unconfirmable details), marking anything unconfirmable as a TODO.

### Invariants
- No normative document invents behavior not present in the current codebase unless marked as a future/unconfirmed TODO.

---

## 2. Capability Bridges (`bitmap` and `audio`)

**Source:** `ARCHITECTURE.md`, `spec/package-format.md`
**TODO:** `bitmap` and `audio` capabilities are declared in the `Capability` wire enum, but no host-function bridge exists in `WasmSandbox`/`JsSandbox`. Need to resolve whether this is a gap or an intentional future reservation.

### Acceptance Test Plan
- If resolved as a gap: Verify that `WasmSandbox` and `JsSandbox` implement the required host functions for `bitmap` and `audio` capabilities.
- If resolved as a reservation: Verify that the codebase gracefully ignores or logs unhandled `bitmap`/`audio` capability requests without breaking extension execution.

### Behavioral Tests
- **Sandbox execution:** An extension requesting `bitmap` or `audio` capabilities must either correctly utilize the newly bridged functions (if implemented) or fallback gracefully without causing an `UnlinkableException` or runtime crash (if reserved).

### Edge Cases & Failure Scenarios
- An extension requests both `bitmap` and an unknown capability.
  - *Expected:* The unknown capability resolves to `Capability.UNKNOWN` and is safely ignored.

---

## 3. JS Exception Detail Propagation

**Source:** `ARCHITECTURE.md`, `spec/package-format.md`
**TODO:** `JsSandbox.eval()` discards actual QuickJS exception details in favor of a generic `RuntimeException`. Need to propagate more detail if expected.

### Acceptance Test Plan
- Verify that `JsSandbox.eval()` extracts the actual error message and stack trace (if available) from the QuickJS runtime when an exception occurs.
- Verify that the generic `RuntimeException` includes this extracted detail in its message or cause.

### Contract Tests
- Given a QuickJS script that throws a specific error (e.g., `throw new Error("Specific failure");`), `JsSandbox.eval()` must produce a `RuntimeException` where the message contains "Specific failure".

### Failure Scenarios
- The QuickJS engine fails catastrophically or throws a non-string error object.
  - *Expected:* The exception detail extractor handles invalid error objects gracefully and falls back to a generic but identifiable error message, never crashing the host application.

---

## 4. `EXTRA_REPORT_TOKEN` Usage

**Source:** `ARCHITECTURE.md`, `spec/store-app.md`
**TODO:** `EXTRA_REPORT_TOKEN` is not spent against a repository endpoint anywhere in the codebase. Need to resolve if this is deferred scope or a gap.

### Acceptance Test Plan
- If resolved as a gap: Verify that the token is correctly attached to the repository endpoint HTTP request during the install-report flow.
- Verify that token expenditure happens exactly once per successful installation report.

### Edge Cases
- The report network request fails or times out.
  - *Expected:* The token must be retained or the flow must support retrying with the same token, based on product requirements.

---

## 5. Entitlement Redemption

**Source:** `spec/store-app.md`
**TODO:** `EXTRA_ENTITLEMENT` is read into `StoreResult.entitlement` but never used. Needs validation or redemption logic.

### Acceptance Test Plan
- Verify that `StoreResult.entitlement` is processed/validated by the engine before granting access to a paid package.

---

## 6. Material-Aware Paint Engine TODOs

**Source:** `docs/Material-Aware Paint Engine Implementation Status.md`
**TODO:** Remaining prioritized tasks (Hardware parity, Phase 3-5, 7, performance policies, etc.).

### 6.1 Hardware Parity & Baselines (Items 1, 2, 5)
- **Contract:** Real Android Vulkan hardware must execute the CPU ↔ Vulkan pigment + reservoir/pickup parity suite.
- **Invariant:** Execution results must be recorded.
- **Contract:** Physical tuft population must have a capability-tier/performance policy bounding cost on Adreno/Mali devices.

### 6.2 Brush Mechanics Tuning (Items 3, 4)
- **Behavioral Test:** Premium stylus, basic stylus, and finger traces must produce intended contact, lifecycle, pressure, and tilt responses without instantaneous angle jumps or unhandled telemetry gaps.
- **Contract:** The initial archetypes (Round, Flat, Filbert, Rigger, Fan, Rake) must be tuned with visual reference strokes.

### 6.3 Phase 3: Substrate-aware Deposition (Item 6)
- **Acceptance Criteria:**
  - Deposition uses the stable physical contact topology.
  - Crest-only scumble occurs under light contact.
  - Pressure-driven penetration fills substrate valleys.
- **Invariant:** Deposition does not overdraw reservoir load.

### 6.4 Phase 4: Persistent Wetness + Bounded Transport (Item 7)
- **Acceptance Criteria:**
  - Wetness material channel exists.
  - Wetness controls pickup eligibility and short-range bounded diffusion.
- **Failure Scenario:** Unbounded diffusion causing OOM or frame drops must be prevented by a fixed small iteration budget.

### 6.5 Phase 5: Impasto v2 (Item 8)
- **Acceptance Criteria:**
  - Reservoir-volume/contact-driven height transfer exists.
  - Brush pickup/removal of height/material is supported.
  - Wet/dry optics respond to the new material channels.
- **Invariant:** All new canonical material channels are persisted and versioned.

### 6.6 Phase 7: Semantic Media-Profile Catalogue (Item 9)
- **Acceptance Criteria:** Product UI/controls are built on the landed versioned profile boundary.
- **Invariant:** Colour remains explicit stroke intent and does not silently assign viscosity/pigment semantics ("colour is not medium").
