# Verification Contract: Remaining TODOs & Open Items

This document defines the verification contract for the unconfirmed open items and remaining prioritized implementation tasks across the Graffux repository, derived solely from approved architecture, constraints, and specification documents.

## 1. Sandbox Capabilities: `bitmap` and `audio`

**Context:** The `Capability` wire enum (`spec/package-format.md`) declares `bitmap` and `audio`, but no host-function binding was found in the sandboxes. The architecture permits unknown capabilities to degrade safely (less privilege).

### 1.1 Invariants
- **Fail-Safe Degradation:** If `bitmap` or `audio` capabilities are declared in a manifest but unsupported by the host engine, the engine must safely ignore the request without failing to load the extension.
- **Privilege Separation:** Until explicit host bindings for `bitmap` and `audio` are implemented and merged, no extension code may read, write, or allocate audio buffers or canvas bitmap states directly via sandbox host functions.

### 1.2 Acceptance Test Plan
- **Test:** Load an extension manifest asserting `capabilities: ["bitmap", "audio"]`.
- **Expected:** The manifest parses successfully. The engine grants only the capabilities it understands and drops `bitmap` and `audio` safely. No access is granted to internal media APIs.
- **Test:** Attempt to call a putative host function for `bitmap` or `audio` from sandboxed WASM/JS.
- **Expected:** The sandbox runtime rejects the call (e.g., undefined function or sandbox trap), terminating the execution context cleanly without crashing the host app.

## 2. Sandbox Exception Propagation

**Context:** `JsSandbox.eval()` throws a generic `RuntimeException` instead of surfacing the underlying QuickJS error detail (`ARCHITECTURE.md`).

### 2.1 Contract Tests
- **Contract:** If an extension evaluation faults (e.g., invalid syntax, out of bounds memory), the host must trap the error and expose sufficient diagnostic information to the extension developer without leaking host memory or stack traces.
- **Test:** Deliberately fault an extension script.
- **Expected:** The resulting exception logged or returned must contain the guest error message (e.g., "ReferenceError: foo is not defined") instead of a generic "RuntimeException".

### 2.2 Failure Scenarios
- **Scenario:** Guest throws a very large or malformed error string.
- **Expected:** The host truncates or safely encodes the error message to prevent log-stuffing or OOM during error handling.

## 3. Store Hand-off: `EXTRA_REPORT_TOKEN` & `EXTRA_ENTITLEMENT`

**Context:** `EXTRA_REPORT_TOKEN` (`spec/state-reporting.md`) and `EXTRA_ENTITLEMENT` (`spec/store-app.md`) are received but unspent.

### 3.1 Invariants
- **Token Security:** Tokens received from the store app must be treated as opaque, sensitive secrets. They must not be persisted in plain text, logged, or exported.
- **Entitlement Verification:** Paid extensions cannot be fully activated or executed until the entitlement token is cryptographically verified against the registry.

### 3.2 Acceptance Test Plan
- **Test:** Complete a mock store installation flow that yields a valid `EXTRA_ENTITLEMENT`.
- **Expected:** The application stores the token securely and requires a successful validation pass against the repository's public keys before marking the package as active.
- **Test:** Complete a mock store flow providing an `EXTRA_REPORT_TOKEN`.
- **Expected:** The token is eventually spent (via an HTTP POST or similar authenticated channel) to the registry endpoint to report the successful installation state, as required by the inventory contract.

## 4. Normative Documentation Gaps

**Context:** Six normative documents (`spec/extension-manifest.md`, `spec/pack.md`, `spec/companion-app.md`, `spec/mcp-server.md`, `spec/ui-schema.md`, `spec/repository-api.md`) are cited but do not exist in the repository (`ARCHITECTURE.md`).

### 4.1 Verification Contract
- **Contract:** All normative claims referenced in code regarding manifest schema, pack format, UI schemas, and repository APIs must be strictly grounded in documented specifications.
- **Test:** Run a documentation linter or script to verify that links/references to `spec/extension-manifest.md` (and the others) resolve to actual files in the repository.
- **Expected:** The files exist, and their contents mathematically and logically describe the schema constraints enforced by the engine (e.g., validating the `compat` comparator grammar, UI panel definitions).

## 5. Material-Aware Paint Engine: Priority TODOs

**Context:** The `docs/Material-Aware Paint Engine Implementation Status.md` identifies 9 prioritized steps remaining, primarily focused on hardware parity, telemetry, and advanced material phases (Substrate, Wetness, Impasto v2).

### 5.1 Parity & Performance Baselines (Steps 1, 2, 5)
- **Invariant:** Execution of the CPU reference implementation and the Vulkan shader implementation for pigment mixing and reservoir pickup must yield bit-exact or perceptually indistinguishable results (within a defined floating-point epsilon).
- **Test:** Execute the `MaterialGoldenFixtureMatrixTest` across at least three representative physical Android devices (e.g., Adreno high-tier, Mali mid-tier).
- **Expected:** The native Vulkan results match the recorded golden vectors. Performance bounds (latency and memory) must fall within the engine's defined budget.

### 5.2 Device Telemetry & Archetype Tuning (Steps 3, 4)
- **Contract:** The brush engine must safely classify missing telemetry axes (e.g., missing tilt on a basic stylus) as "unavailable" rather than fabricating zero-values, and gracefully degrade the mechanical solver.
- **Test:** Apply physical finger traces and basic pressure-only stylus traces to the physical brush models (Round, Flat, Filbert).
- **Expected:** The engine behaves deterministically without crashing. Width/splay logic falls back to pressure-only or velocity-only heuristics as designed.

### 5.3 Advanced Material Phases (Steps 6, 7, 8)
- **Substrate Deposition (Phase 3):**
  - **Edge Case:** Light pressure on a highly textured substrate.
  - **Expected:** Deposition occurs only on the substrate crests.
- **Persistent Wetness (Phase 4):**
  - **Invariant:** The wetness field must be bounded; diffusion must not cost idle compute cycles for fully dry regions.
- **Impasto v2 (Phase 5):**
  - **Test:** Deposit material over existing thick Impasto strokes.
  - **Expected:** Height transfer adheres to reservoir-volume constraints; wet strokes level appropriately according to the configured viscosity.

### 5.4 Semantic Media Profiles (Step 9)
- **Contract:** The rule "colour is not medium" must remain strictly enforced.
- **Test:** Select a legacy RGB color from the color picker while a Phase 7 "Heavy Oil" media profile is active.
- **Expected:** The selected color is applied to the stroke, but the viscosity, wetness, and mixing behavior remain strictly governed by the "Heavy Oil" profile's constraints.
