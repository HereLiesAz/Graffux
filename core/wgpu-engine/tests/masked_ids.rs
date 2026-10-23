//! Masked-stamp content ids (`Engine::set_masked_content_ids`): a named image the engine already
//! holds is not hashed or uploaded again, an unnamed one still is, and ids apply to one call.
//! Runs once per backend with an adapter.

mod common;

use graffux_wgpu::wgpu::Backends;
use graffux_wgpu::{Engine, GpuDab, MaskedParams, SubstrateParams};

const W: i32 = 32;
const H: i32 = 32;
const M: u32 = 8;

fn engines() -> Vec<(&'static str, Engine)> {
    let mut out = Vec::new();
    for (name, b) in [("vulkan", Backends::VULKAN), ("gl", Backends::GL)] {
        match Engine::with_backends(W, H, b) {
            Some(e) => out.push((name, e)),
            None => common::no_adapter(name),
        }
    }
    out
}

/// Stamps one white dab through `mask` onto a cleared layer; the centre pixel's alpha.
fn centre_alpha(e: &mut Engine, mask: &[u8]) -> u8 {
    assert!(e.upload(&vec![0u8; (W * H * 4) as usize]));
    let params = MaskedParams {
        mask,
        mask_width: M,
        mask_height: M,
        grain: None,
        grain_canvas_locked: false,
        grain_scale: 1.0,
        grain_phase_x: 0.0,
        grain_phase_y: 0.0,
        secondary_dabs: &[],
        secondary_mask: None,
        substrate: SubstrateParams::default(),
    };
    let dab = GpuDab::legacy(16.0, 16.0, 8.0, 1.0, 0.0);
    assert!(e.stamp_masked_dabs(&[dab], 0xFFFFFFFF, 0.0, &params));
    let px = e.read_all().unwrap();
    px[((16 * W + 16) * 4 + 3) as usize]
}

#[test]
fn a_held_id_skips_the_upload_and_ids_last_one_call() {
    let full = vec![255u8; (M * M) as usize];
    let empty = vec![0u8; (M * M) as usize];
    for (name, mut e) in engines() {
        e.set_masked_content_ids(7, 0, 0);
        assert_eq!(
            centre_alpha(&mut e, &full),
            255,
            "[{name}] named mask uploads"
        );
        // Same id, other bytes: the caller broke its promise, which proves the engine trusted the
        // id instead of hashing (production never reuses an id for other bytes).
        e.set_masked_content_ids(7, 0, 0);
        assert_eq!(
            centre_alpha(&mut e, &empty),
            255,
            "[{name}] held id: no re-upload"
        );
        // No id (it was consumed by the previous call): the hash notices the new bytes.
        assert_eq!(
            centre_alpha(&mut e, &empty),
            0,
            "[{name}] unnamed: hashed and uploaded"
        );
        // A new id uploads again.
        e.set_masked_content_ids(8, 0, 0);
        assert_eq!(centre_alpha(&mut e, &full), 255, "[{name}] new id uploads");
    }
}
