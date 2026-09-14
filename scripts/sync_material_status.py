from pathlib import Path

PATH = Path("docs/Material-Aware Paint Engine Implementation Status.md")
text = PATH.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match, found {count}: {old[:100]!r}")
    text = text.replace(old, new, 1)


def replace_section(start: str, end: str, replacement: str) -> None:
    global text
    start_count = text.count(start)
    end_count = text.count(end)
    if start_count != 1 or end_count != 1:
        raise SystemExit(
            f"section markers not unique: start={start_count}, end={end_count}, "
            f"start_marker={start!r}, end_marker={end!r}"
        )
    start_index = text.index(start)
    end_index = text.index(end, start_index)
    text = text[:start_index] + replacement + text[end_index:]


replace_once("## 2026-09-13 session checkpoint", "## 2026-09-14 checkpoint")
replace_once(
    "Current `main` includes the session's material, telemetry, brush-mechanics, morphology, physical-population, and validation merges through the telemetry-profile validation merge.",
    "Current `main` includes the session's material, telemetry, brush-mechanics, morphology, physical-population, and validation work plus Vulkan reservoir parity, translucent-pickup hardening, the versioned media-profile boundary, the consolidated material golden matrix, and minimal material controls.",
)

replace_once(
    "- 🟡 Deterministic fixtures now cover pigment, reservoir, telemetry, and brush-mechanics behavior, but the original complete material fixture set is not yet present as one consolidated golden suite.\n- ⬜ Explicit semantic media profiles are not yet the source of material behavior.",
    "- ✅ `MaterialGoldenFixtureMatrixTest` now consolidates explicit numeric golden vectors for the current material contract: pigment mixing, long-stroke depletion, wet pickup, repeated crossings, and the legacy dry baseline. Future substrate/wetness/Impasto-v2 vectors extend this same matrix.\n- ✅ Versioned renderer-independent `PaintMediaProfile` boundary landed with stable id/version, `PaintMedium`, channel requirements, initial reservoir state, and an exact `LEGACY_DRY` compatibility profile. Colour remains explicit stroke intent rather than profile identity.\n- ⬜ Named/tuned semantic product profiles such as Heavy Oil/Acrylic/etc. are not yet the source of material behavior.",
)
replace_once(
    "- 🟡 repeated crossings are exercised by material/brush tests, but are not yet a consolidated material golden fixture;",
    "- ✅ repeated crossings are pinned in the consolidated material golden fixture matrix;",
)

replace_section(
    "### Phase 2 — Stateful reservoir, depletion, and pickup — 🟡\n",
    "### Phase 3 — Substrate-aware deposition and dry-brush breakup — ⬜\n",
    """### Phase 2 — Stateful reservoir, depletion, and pickup — ✅ implementation; 🟡 hardware parity gate

Implemented:

- ✅ Renderer-independent deterministic `BrushReservoirModel`.
- ✅ Analytic charge/load depletion compatible with historical Color Smudge Charge behavior.
- ✅ Bounded deposition that cannot overdraw reservoir load.
- ✅ Bounded pickup that cannot overfill the reservoir.
- ✅ Carried-colour contamination using the selected material mixer.
- ✅ Load-weighted carried material/wetness state transitions.
- ✅ Color Smudge Charge routes through the reservoir model.
- ✅ CPU reservoir pickup with `pickupRate`, default `0`.
- ✅ Pickup samples contact material before the dab mutates it and affects subsequent dabs rather than retroactively changing the sampling dab.
- ✅ Smear and Dulling both feed reservoir pickup while keeping their existing spatial behavior.
- ✅ Sample Merged can supply pickup from the same pre-composited source used by Color Smudge.
- ✅ Native/Vulkan reservoir depletion, pickup, carried-colour contamination, and ordered post-dab pickup are implemented in the existing Color Smudge backend.
- ✅ Renderer-neutral resolved plans carry per-dab distance and Color Rate dynamics into the native reservoir state machine.
- ✅ Real-device Vulkan parity instrumentation covers Smear, Dulling, pigment pickup, and translucent layer pickup.
- ✅ Non-Sample-Merged translucent pickup unpremultiplies `layerImage` before alpha-weighted sampling, preventing double-alpha darkening while preserving the straight-alpha Sample Merged path.
- ✅ Failed/unavailable Vulkan execution still falls back to the CPU reference rather than dropping reservoir state.
- ✅ Minimal artist-facing **Load / Pickup / Pigment Mixing** controls are wired to the existing Color Smudge/Wet Mix state rather than a second material backend.

Still pending:

- 🟡 Execute the existing CPU ↔ Vulkan pigment/reservoir parity suite on representative real Android Vulkan hardware and record the results.
- ⬜ Persistent canvas wetness; current pickup does not fabricate a wetness field.
- ⬜ Per-sub-tuft reservoir load; current reservoir is stroke-level.

Compatibility/backend gate:

- ✅ `pickupRate = 0` remains the historical default path.
- ✅ Imported Krita presets keep pickup at the Graffux default of zero.
- ✅ Positive pickup has native/Vulkan support; unsupported/failed GPU execution recomputes through the CPU reference instead of silently ignoring pickup.

""",
)

replace_once(
    "- ⬜ Semantic media profiles such as Heavy Oil, Soft Oil, Acrylic, Gouache, Watercolour, Ink, Dry Bristle, and Marker-like legacy mode are not implemented as the material-engine product layer.\n- ⬜ The intended minimal artistic material vocabulary — Load, Wetness/Dilution, Flow/Body, Pickup, Drying, Thickness, Texture Interaction — is not fully exposed.",
    "- ✅ The versioned `PaintMediaProfile` core boundary is implemented, including an exact `LEGACY_DRY` compatibility profile and the invariant that colour is not medium.\n- ⬜ Tuned semantic product profiles such as Heavy Oil, Soft Oil, Acrylic, Gouache, Watercolour, Ink, Dry Bristle, and Marker-like legacy mode are not yet implemented as a catalogue/product layer.\n- 🟡 Minimal **Load / Pickup / Pigment Mixing** controls are exposed on Color Smudge/Wet Mix; the broader artistic vocabulary — Wetness/Dilution, Flow/Body, Drying, Thickness, Texture Interaction — remains phase-dependent and intentionally incomplete.",
)

replace_once(
    "- ⬜ Full artist-facing material controls for Load/Pickup/Pigment Mixing after CPU/GPU parity.",
    "- ✅ Minimal artist-facing material controls for **Load / Pickup / Pigment Mixing** are exposed on the existing Color Smudge/Wet Mix path.",
)

replace_once(
    "- ✅ deterministic CPU reservoir pickup and compatibility gate.",
    "- ✅ deterministic CPU reservoir pickup and compatibility gate;\n- ✅ native/Vulkan reservoir depletion + pickup implementation and device-test instrumentation;\n- ✅ translucent non-Sample-Merged reservoir sampling parity regression coverage;\n- ✅ consolidated current-material golden fixture matrix;\n- ✅ versioned media-profile boundary and exact legacy-dry profile.",
)
replace_once(
    "- ⬜ native/Vulkan reservoir pickup + real-device parity;",
    "- 🟡 execute the landed native/Vulkan reservoir pickup parity tests on representative real Android Vulkan hardware;",
)

replace_section(
    "# Remaining prioritized TODO\n",
    "Do not start a second wet-mix/pickup backend beside Color Smudge,",
    """# Remaining prioritized TODO

This is the recommended order from the current state, not the original dependency order.

1. Run the existing **CPU ↔ Vulkan pigment + reservoir/pickup parity suite on real Android Vulkan hardware** and record the device/result matrix.
2. Complete the remaining Phase-0 measurement gap: physical-device material latency/performance baselines. The versioned media-profile boundary and consolidated current-material golden matrix are landed.
3. Harden brush mechanics on real hardware: premium stylus, pressure-only/basic stylus, and finger traces; tune pressure/tilt/orientation confidence and lifecycle behavior.
4. Tune the initial **Round / Flat / Filbert / Rigger / Fan / Rake** archetypes with visual reference strokes rather than exposing raw solver coefficients prematurely.
5. Add capability-tier/performance policy for physical tuft population and verify bounded cost on representative Adreno/Mali devices.
6. Begin **Phase 3 substrate-aware deposition** using the now-stable physical contact topology.
7. Then add **Phase 4 persistent wetness + bounded active-tile transport**.
8. Then evolve the existing height engine into **Phase 5 Impasto v2**, including material persistence/reconstruction and wet/dry optics.
9. Build the tuned **Phase 7 semantic media-profile catalogue/product controls** on the landed versioned profile boundary after the underlying material channels have stable behavior.
10. Keep full spectral/Kubelka-Munk, full individual-bristle PBD/DER, FLIP/PIC/pressure-projected fluids, porous-paper capillary simulation, and Gaussian-splat material research behind explicit evidence that the cheaper model cannot produce the required marks.

""",
)

replace_once(
    "- native/Vulkan reservoir pickup and parity;",
    "- real Android Vulkan execution/results for the landed pigment and reservoir/pickup parity suites;",
)
replace_once(
    "- media-profile/product UX after engine behavior is stable.",
    "- tuned semantic media-profile catalogue/product UX after engine behavior is stable (the versioned core profile boundary is already landed).",
)

PATH.write_text(text, encoding="utf-8")
