//! Rust half of the Kotlin <-> WGSL dab layout parity check. The tables below are the same ones
//! core/engine/src/commonTest/.../wgpu/WgpuDabsLayoutTest.kt packs against (`WgpuDabs`); here they
//! are pinned to the `Dab` struct comments in both stamp shaders and to the `#[repr(C)]` structs
//! the C API reinterprets the Kotlin float arrays as.

use graffux_wgpu::engine::{ColorSmudgeDab, GpuDab, STAMP_MASKED_WGSL, STAMP_WGSL};
use std::mem::{offset_of, size_of};

/// Keep in sync with WgpuDabsLayoutTest.DAB_LAYOUT. Slot 11 is hardness in stamp.wgsl and the tip
/// ratio in stamp_masked.wgsl (whose mask encodes the falloff, so it has no per-dab hardness).
const DAB_LAYOUT: [&str; 16] = [
    "x", "y", "radius", "alpha",
    "angleDeg", "colorR", "colorG", "colorB",
    "colorA", "flow", "resolved", "hardness|tipRatio",
    "contactDepth", "reservoirLoad", "depositionRate", "substrateResponse",
];

/// Keep in sync with WgpuDabsLayoutTest.SMUDGE_LAYOUT.
const SMUDGE_LAYOUT: [&str; 11] = [
    "x", "y", "smudgeRate", "colorRate", "opacity", "smudgeRadius", "colorRateMultiplier",
    "distanceDeltaPx", "baseColorRate", "chargeDecayRate", "pickupRate",
];

/// Field names from the `struct Dab { ... }` member comments, four per vec4, in order.
fn shader_dab_fields(src: &str) -> Vec<String> {
    let body = src.split("struct Dab {").nth(1).expect("struct Dab").split("};").next().unwrap();
    body.lines()
        .filter_map(|l| l.split_once("//").map(|(_, c)| c))
        .flat_map(|c| {
            c.split(',')
                .map(|f| f.split('(').next().unwrap().trim().to_string())
                .collect::<Vec<_>>()
        })
        .collect()
}

#[test]
fn shader_dab_comments_match_packing_table() {
    for (name, src, slot11) in [("stamp", STAMP_WGSL, "hardness"), ("stamp_masked", STAMP_MASKED_WGSL, "tipRatio")] {
        let fields = shader_dab_fields(src);
        assert_eq!(fields.len(), DAB_LAYOUT.len(), "[{name}] {fields:?}");
        for (i, (got, want)) in fields.iter().zip(DAB_LAYOUT).enumerate() {
            let want = if i == 11 { slot11 } else { want };
            assert_eq!(got, want, "[{name}] slot {i}");
        }
    }
}

#[test]
fn rust_structs_match_packing_table() {
    assert_eq!(size_of::<GpuDab>(), DAB_LAYOUT.len() * 4);
    let dab_offsets = [
        offset_of!(GpuDab, x), offset_of!(GpuDab, y), offset_of!(GpuDab, radius),
        offset_of!(GpuDab, alpha), offset_of!(GpuDab, angle_deg), offset_of!(GpuDab, color_r),
        offset_of!(GpuDab, color_g), offset_of!(GpuDab, color_b), offset_of!(GpuDab, color_a),
        offset_of!(GpuDab, flow), offset_of!(GpuDab, resolved), offset_of!(GpuDab, tip_ratio),
        offset_of!(GpuDab, contact_depth), offset_of!(GpuDab, reservoir_load),
        offset_of!(GpuDab, deposition_rate), offset_of!(GpuDab, substrate_response),
    ];
    for (i, off) in dab_offsets.iter().enumerate() {
        assert_eq!(*off, i * 4, "GpuDab {}", DAB_LAYOUT[i]);
    }

    assert_eq!(size_of::<ColorSmudgeDab>(), SMUDGE_LAYOUT.len() * 4);
    let smudge_offsets = [
        offset_of!(ColorSmudgeDab, x), offset_of!(ColorSmudgeDab, y),
        offset_of!(ColorSmudgeDab, smudge_rate), offset_of!(ColorSmudgeDab, color_rate),
        offset_of!(ColorSmudgeDab, opacity), offset_of!(ColorSmudgeDab, smudge_radius),
        offset_of!(ColorSmudgeDab, color_rate_multiplier),
        offset_of!(ColorSmudgeDab, distance_delta_px), offset_of!(ColorSmudgeDab, base_color_rate),
        offset_of!(ColorSmudgeDab, charge_decay_rate), offset_of!(ColorSmudgeDab, pickup_rate),
    ];
    for (i, off) in smudge_offsets.iter().enumerate() {
        assert_eq!(*off, i * 4, "ColorSmudgeDab {}", SMUDGE_LAYOUT[i]);
    }

    // Kotlin-packed floats (WgpuDabs.colorSmudge order) reinterpret as the Rust struct.
    let packed: Vec<f32> = (1..=11).map(|v| v as f32).collect();
    let d: &ColorSmudgeDab = bytemuck::from_bytes(bytemuck::cast_slice(&packed));
    assert_eq!((d.x, d.distance_delta_px, d.pickup_rate), (1.0, 8.0, 11.0));
}
