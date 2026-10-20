//! The round shader's erase and alpha-lock composites (`Engine::set_stamp_mode`) against a CPU
//! reference: erase is `dst * (1 - a)`, alpha lock is SRC_ATOP (`src * a * dst.a + dst.rgb *
//! (1 - a)`, alpha kept). Runs once per backend with an adapter.

use graffux_wgpu::wgpu::Backends;
use graffux_wgpu::{Engine, GpuDab, MaskedParams, SubstrateParams, STAMP_ATOP, STAMP_ERASE, STAMP_OVER};

const W: i32 = 40;
const H: i32 = 32;
const N: usize = (W * H) as usize;

fn engines() -> Vec<(&'static str, Engine)> {
    let mut out = Vec::new();
    for (name, b) in [("vulkan", Backends::VULKAN), ("gl", Backends::GL)] {
        match Engine::with_backends(W, H, b) {
            Some(e) => out.push((name, e)),
            None => eprintln!("[{name}] no adapter -- skipped"),
        }
    }
    out
}

/// Premultiplied pixels; the right half is transparent so alpha lock has somewhere to refuse.
fn base() -> Vec<u8> {
    (0..N)
        .flat_map(|i| {
            let x = (i % W as usize) as u32;
            if x >= W as u32 / 2 {
                [0, 0, 0, 0]
            } else {
                let a = 120 + (i as u32 * 7 % 120);
                [(a * 3 / 5) as u8, (a / 3) as u8, (a / 2) as u8, a as u8]
            }
        })
        .collect()
}

fn dab() -> GpuDab {
    let mut d = GpuDab::legacy(20.0, 16.0, 12.0, 0.8, 0.0);
    d.resolved = 0.0;
    d
}

#[test]
fn erase_and_alpha_lock_match_the_cpu_composites() {
    for (name, mut e) in engines() {
        assert!(!e.set_stamp_mode(9));
        // The plain coverage of the same dab, painted white over transparent, is each pixel's `a`.
        assert!(e.upload(&vec![0u8; N * 4]));
        assert!(e.stamp_dabs(
            &[dab()],
            0xFFFFFFFF,
            0.6,
            false,
            SubstrateParams::default(),
            true
        ));
        let cover: Vec<f32> = e
            .read_all()
            .unwrap()
            .chunks(4)
            .map(|p| p[3] as f32 / 255.0)
            .collect();
        for (mode, label) in [(STAMP_ERASE, "erase"), (STAMP_ATOP, "atop")] {
            let before = base();
            assert!(e.upload(&before));
            assert!(e.set_stamp_mode(mode));
            assert!(e.stamp_dabs(
                &[dab()],
                0xFF3366CC,
                0.6,
                false,
                SubstrateParams::default(),
                true
            ));
            let out = e.read_all().unwrap();
            assert!(e.set_stamp_mode(STAMP_OVER));
            let src = [
                0x33 as f32 / 255.0,
                0x66 as f32 / 255.0,
                0xCC as f32 / 255.0,
            ];
            let mut worst = 0i32;
            for i in 0..N {
                let a = cover[i];
                let d: Vec<f32> = before[i * 4..i * 4 + 4]
                    .iter()
                    .map(|&v| v as f32 / 255.0)
                    .collect();
                let want = if mode == STAMP_ERASE {
                    [
                        d[0] * (1.0 - a),
                        d[1] * (1.0 - a),
                        d[2] * (1.0 - a),
                        d[3] * (1.0 - a),
                    ]
                } else {
                    [
                        src[0] * a * d[3] + d[0] * (1.0 - a),
                        src[1] * a * d[3] + d[1] * (1.0 - a),
                        src[2] * a * d[3] + d[2] * (1.0 - a),
                        d[3],
                    ]
                };
                for c in 0..4 {
                    let w = (want[c].clamp(0.0, 1.0) * 255.0).round() as i32;
                    worst = worst.max((out[i * 4 + c] as i32 - w).abs());
                }
            }
            assert!(
                worst <= 2,
                "[{name}] {label}: worst channel difference {worst}"
            );
        }
    }
}

/// The masked (tip/grain) shader honours the same modes: a tipped brush on the Eraser removes
/// exactly the coverage it would have painted (`dst * (1 - a)`), and atop keeps alpha.
#[test]
fn masked_dabs_erase_and_alpha_lock_match_the_cpu_composites() {
    // A soft square tip: coverage falls off from the centre, so partial erase is exercised.
    const M: u32 = 16;
    let mask: Vec<u8> = (0..M * M)
        .map(|i| {
            let (x, y) = ((i % M) as f32 - 7.5, (i / M) as f32 - 7.5);
            (255.0 * (1.0 - (x * x + y * y).sqrt() / 11.0).clamp(0.0, 1.0)) as u8
        })
        .collect();
    let params = MaskedParams {
        mask: &mask,
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
    for (name, mut e) in engines() {
        assert!(e.upload(&vec![0u8; N * 4]));
        assert!(e.stamp_masked_dabs(&[dab()], 0xFFFFFFFF, 0.0, &params));
        let cover: Vec<f32> = e
            .read_all()
            .unwrap()
            .chunks(4)
            .map(|p| p[3] as f32 / 255.0)
            .collect();
        assert!(cover.iter().any(|&a| a > 0.1 && a < 0.9), "[{name}] tip has no soft edge");
        for (mode, label) in [(STAMP_ERASE, "erase"), (STAMP_ATOP, "atop")] {
            let before = base();
            assert!(e.upload(&before));
            assert!(e.set_stamp_mode(mode));
            assert!(e.stamp_masked_dabs(&[dab()], 0xFF3366CC, 0.0, &params));
            let out = e.read_all().unwrap();
            assert!(e.set_stamp_mode(STAMP_OVER));
            let src = [0x33 as f32 / 255.0, 0x66 as f32 / 255.0, 0xCC as f32 / 255.0];
            let mut worst = 0i32;
            for i in 0..N {
                let a = cover[i];
                let d: Vec<f32> = before[i * 4..i * 4 + 4].iter().map(|&v| v as f32 / 255.0).collect();
                let want = if mode == STAMP_ERASE {
                    [d[0] * (1.0 - a), d[1] * (1.0 - a), d[2] * (1.0 - a), d[3] * (1.0 - a)]
                } else {
                    [
                        src[0] * a * d[3] + d[0] * (1.0 - a),
                        src[1] * a * d[3] + d[1] * (1.0 - a),
                        src[2] * a * d[3] + d[2] * (1.0 - a),
                        d[3],
                    ]
                };
                for c in 0..4 {
                    let w = (want[c].clamp(0.0, 1.0) * 255.0).round() as i32;
                    worst = worst.max((out[i * 4 + c] as i32 - w).abs());
                }
            }
            assert!(worst <= 2, "[{name}] masked {label}: worst channel difference {worst}");
        }
    }
}
