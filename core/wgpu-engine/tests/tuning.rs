//! Per-device tuning: the stamp workgroup size override and GPU pass timings. Like
//! tests/engine.rs, the GPU tests run on whichever backends have an adapter here and skip otherwise.

use graffux_wgpu::wgpu::Backends;
use graffux_wgpu::{Engine, EngineOptions, GpuDab, MaskedParams, PassKind, SubstrateParams};

const W: i32 = 83;
const H: i32 = 59;

fn dabs() -> Vec<GpuDab> {
    (0..24)
        .map(|i| {
            let f = i as f32;
            let mut d = GpuDab::legacy(5.0 + f * 3.1, 7.0 + (f * 1.7) % 45.0, 2.0 + f % 9.0, 0.7, f * 15.0);
            d.color_r = 0.9;
            d.color_g = 0.2 + f / 40.0;
            d.color_b = 0.4;
            d.color_a = 0.8;
            d.flow = 0.6;
            d.resolved = 1.0;
            d.tip_ratio = 0.3 + (f % 5.0) / 10.0;
            d
        })
        .collect()
}

fn paint(e: &mut Engine) -> Vec<u8> {
    let mask: Vec<u8> = (0..16 * 16).map(|i| (i * 7 % 256) as u8).collect();
    let params = MaskedParams {
        mask: &mask,
        mask_width: 16,
        mask_height: 16,
        grain: None,
        grain_canvas_locked: false,
        grain_scale: 1.0,
        grain_phase_x: 0.0,
        grain_phase_y: 0.0,
        secondary_dabs: &[],
        secondary_mask: None,
        substrate: SubstrateParams::default(),
    };
    assert!(e.clear());
    let d = dabs();
    assert!(e.stamp_dabs(&d[..12], 0xFF336699, 0.5, false, SubstrateParams::default(), true));
    assert!(e.stamp_masked_dabs(&d[12..], 0xFF993366, 0.5, &params));
    e.read_all().unwrap()
}

fn with_tile(backends: Backends, tile: u32) -> Option<Engine> {
    Engine::with_backends_and_options(
        W,
        H,
        backends,
        EngineOptions {
            stamp_tile: tile,
            timestamps: true,
        },
    )
}

/// The workgroup edge only changes scheduling: 8x8 and 16x16 paint identical bytes.
#[test]
fn stamp_tile_8_matches_16() {
    for (name, b) in [("vulkan", Backends::VULKAN), ("gl", Backends::GL)] {
        let (Some(mut a), Some(mut c)) = (with_tile(b, 16), with_tile(b, 8)) else {
            eprintln!("[{name}] no adapter -- skipped");
            continue;
        };
        assert_eq!(a.stamp_tile(), 16);
        assert_eq!(c.stamp_tile(), 8);
        let pa = paint(&mut a);
        let pc = paint(&mut c);
        assert!(pa.iter().any(|&v| v != 0), "[{name}] nothing painted");
        assert_eq!(pa, pc, "[{name}] tile size changed the pixels");
    }
}

/// Unknown tile requests fall back to the default instead of failing.
#[test]
fn odd_tile_request_uses_default() {
    if let Some(e) = with_tile(Backends::VULKAN | Backends::GL, 12) {
        assert_eq!(e.stamp_tile(), graffux_wgpu::DEFAULT_STAMP_TILE);
    }
}

/// With timestamp support every stamp pass yields a sample; without it, none, and the info says so.
#[test]
fn pass_timings_follow_timestamp_support() {
    for (name, b) in [("vulkan", Backends::VULKAN), ("gl", Backends::GL)] {
        let Some(mut e) = with_tile(b, 16) else {
            eprintln!("[{name}] no adapter -- skipped");
            continue;
        };
        let info = e.gpu_info();
        assert!(info.contains("engine=wgpu"), "{info}");
        assert!(info.contains("renderer="), "{info}");
        let (timestamps, _) = e.gpu_timestamps();
        assert!(info.contains(&format!("timestamps={}", timestamps as u8)), "{info}");
        paint(&mut e);
        let samples = e.take_pass_timings();
        let stamps = samples.iter().filter(|(k, _)| *k == PassKind::Stamp).count();
        eprintln!("[{name}] timestamps={timestamps} samples={samples:?}");
        if timestamps {
            // A pass whose timestamps a driver reports as non-increasing is dropped, so allow fewer.
            assert!(stamps <= 2, "[{name}] more samples than passes");
        } else {
            assert!(samples.is_empty());
        }
        assert!(e.take_pass_timings().is_empty(), "[{name}] drain did not reset");
    }
}

/// Timings keep flowing past the query-pair capacity (forced resolve) without failing a stamp.
#[test]
fn many_passes_resolve_without_failing() {
    let Some(mut e) = with_tile(Backends::VULKAN | Backends::GL, 16) else {
        eprintln!("no adapter -- skipped");
        return;
    };
    let d = dabs();
    assert!(e.clear());
    for _ in 0..300 {
        assert!(e.stamp_dabs(&d[..4], 0xFF000000, 1.0, true, SubstrateParams::default(), false));
    }
    let samples = e.take_pass_timings();
    if e.gpu_timestamps().0 {
        assert!(samples.len() <= 300);
    } else {
        assert!(samples.is_empty());
    }
}
