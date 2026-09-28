//! Multipass drafts of soft brushes must be the real stamp at lower quality -- never a flat or
//! hard-edged disc -- at stroke start (first readback, no ETA or throughput estimate yet), after a
//! pause (held-pointer build-up), and for large soft tips (Soft Round with hardness turned down at
//! 164 px, the owner's report). The committed layer stays byte-identical to multipass off.

use graffux_wgpu::wgpu::Backends;
use graffux_wgpu::{Engine, GpuDab, MaskedParams, MultipassConfig, SubstrateParams};

fn backends() -> Vec<(&'static str, Backends)> {
    let mut out = Vec::new();
    for (name, b) in [("vulkan", Backends::VULKAN), ("gl", Backends::GL)] {
        if Engine::with_backends(8, 8, b).is_some() {
            out.push((name, b));
        } else {
            eprintln!("[{name}] no adapter -- skipped");
        }
    }
    out
}

fn soft(x: f32, y: f32, r: f32, alpha: f32, hardness: f32) -> GpuDab {
    let mut d = GpuDab::legacy(x, y, r, alpha, 0.0);
    d.color_r = 0.85;
    d.color_g = 0.85;
    d.color_b = 0.85;
    d.color_a = 0.9;
    d.flow = 1.0;
    d.resolved = 1.0;
    d.tip_ratio = hardness; // resolved round dabs carry hardness here
    d
}

fn dark(w: i32, h: i32) -> Vec<u8> {
    (0..w * h).flat_map(|_| [30u8, 30, 30, 255]).collect()
}

fn red(img: &[u8], w: i32, x: i32, y: i32) -> i32 {
    img[((y * w + x) * 4) as usize] as i32
}

/// Largest jump between neighbouring pixels along the row through `y`, over `x0..x1`.
fn max_step(img: &[u8], w: i32, y: i32, x0: i32, x1: i32) -> i32 {
    (x0..x1 - 1).map(|x| (red(img, w, x + 1, y) - red(img, w, x, y)).abs()).max().unwrap_or(0)
}

/// A soft falloff, not a flat fill: the profile from the centre outward keeps falling across many
/// distinct levels.
fn assert_falloff(tag: &str, img: &[u8], w: i32, cx: i32, cy: i32, r: i32) {
    let prof: Vec<i32> = (0..r).map(|d| red(img, w, cx + d, cy)).collect();
    let mut levels = prof.clone();
    levels.dedup();
    assert!(levels.len() >= (r as usize / 4).min(20), "[{tag}] flat draft: {prof:?}");
    let inner = prof[r as usize / 5];
    let outer = prof[(r as usize * 4) / 5];
    assert!(inner - outer >= 20, "[{tag}] no falloff: {prof:?}");
}

fn mp(scale: u32) -> MultipassConfig {
    MultipassConfig { enabled: true, draft_scale: scale, ..MultipassConfig::default() }
}

#[test]
fn stroke_start_draft_of_a_large_soft_round_keeps_its_falloff() {
    for (name, b) in backends() {
        for (hardness, r, scale) in [(0.05f32, 82.0f32, 4u32), (0.0, 40.0, 2), (0.35, 82.0, 8)] {
            let (w, h) = (200, 200);
            let mut e = Engine::with_backends(w, h, b).unwrap();
            assert!(e.set_multipass(mp(scale)));
            e.set_multipass_clock(Some(0.0));
            let bg = dark(w, h);
            assert!(e.upload(&bg));
            let mut shown = bg.clone();
            // The first batch of the stroke: no refinement has run, nothing is measured yet.
            assert!(e.stamp_dabs(&[soft(100.0, 100.0, r, 1.0, hardness)], 0, hardness, false, SubstrateParams::default(), true));
            assert!(e.readback(&mut shown));
            let tag = format!("{name} h={hardness} r={r} scale={scale} draft");
            assert_falloff(&tag, &shown, w, 100, 100, r as i32);
            let draft_step = max_step(&shown, w, 100, 0, w);
            assert!(e.flush());
            let fin = e.read_all().unwrap();
            let fin_step = max_step(&fin, w, 100, 0, w);
            // No hard edge the final does not have.
            assert!(draft_step <= fin_step * 2 + 6, "[{tag}] hard edge: draft step {draft_step} vs final {fin_step}");
        }
    }
}

#[test]
fn held_pointer_build_up_draft_is_soft_and_the_layer_matches_off() {
    for (name, b) in backends() {
        let (w, h) = (160, 64);
        let run = |multipass: bool| -> (Vec<u8>, Vec<u8>) {
            let mut e = Engine::with_backends(w, h, b).unwrap();
            if multipass {
                assert!(e.set_multipass(mp(2)));
                e.set_multipass_clock(Some(0.0));
            }
            let bg = dark(w, h);
            assert!(e.upload(&bg));
            let mut shown = bg.clone();
            let s = SubstrateParams::default();
            let mut t = 0.0;
            for i in 0..15 {
                assert!(e.stamp_dabs(&[soft(20.0 + i as f32 * 1.9, 32.0, 12.0, 0.5, 0.05)], 0, 0.05, false, s, true));
                t += 8.0;
                e.set_multipass_clock(Some(t));
                e.refine(0.001);
                assert!(e.readback(&mut shown));
            }
            // The pointer rests: repeated build-up dabs at one spot, refinement starved.
            for _ in 0..25 {
                assert!(e.stamp_dabs(&[soft(46.6, 32.0, 12.0, 0.5, 0.05)], 0, 0.05, true, s, false));
                t += 8.0;
                e.set_multipass_clock(Some(t));
                e.refine(0.001);
                assert!(e.readback(&mut shown));
            }
            let live = shown.clone();
            assert!(e.flush());
            (live, e.read_all().unwrap())
        };
        let (_, off) = run(false);
        let (live, layer) = run(true);
        assert_eq!(layer, off, "[{name}] layer differs from multipass off");
        let live_step = max_step(&live, w, 32, 20, 80);
        let fin_step = max_step(&off, w, 32, 20, 80);
        assert!(live_step <= fin_step * 2 + 6, "[{name}] held draft has a hard edge: {live_step} vs {fin_step}");
        // Held build-up is shown, not trimmed away: the draft's disc is as wide as the final's.
        let wide = |img: &[u8]| (0..h).filter(|&y| red(img, w, 47, y) > 60).count() as i32;
        assert!((wide(&live) - wide(&off)).abs() <= 3, "[{name}] draft {} rows vs final {}", wide(&live), wide(&off));
    }
}

#[test]
fn masked_draft_of_a_soft_tip_keeps_its_falloff_at_any_size() {
    let size = 64u32;
    let c = (size as f32 - 1.0) / 2.0;
    let mask: Vec<u8> = (0..size * size)
        .map(|i| {
            let (x, y) = ((i % size) as f32, (i / size) as f32);
            let d = (((x - c) / c).powi(2) + ((y - c) / c).powi(2)).sqrt();
            ((1.0 - d).clamp(0.0, 1.0) * 255.0) as u8
        })
        .collect();
    for (name, b) in backends() {
        for (r, scale) in [(82.0f32, 4u32), (24.0, 8)] {
            let (w, h) = (200, 200);
            let mut e = Engine::with_backends(w, h, b).unwrap();
            assert!(e.set_multipass(mp(scale)));
            e.set_multipass_clock(Some(0.0));
            let bg = dark(w, h);
            assert!(e.upload(&bg));
            let mut shown = bg.clone();
            let mut d = soft(100.0, 100.0, r, 1.0, 1.0);
            d.tip_ratio = 1.0;
            let p = MaskedParams {
                mask: &mask,
                mask_width: size,
                mask_height: size,
                grain: None,
                grain_canvas_locked: false,
                grain_scale: 1.0,
                grain_phase_x: 0.0,
                grain_phase_y: 0.0,
                secondary_dabs: &[],
                secondary_mask: None,
                substrate: SubstrateParams::default(),
            };
            assert!(e.stamp_masked_dabs(&[d], 0, 0.0, &p));
            assert!(e.readback(&mut shown));
            assert_falloff(&format!("{name} masked r={r} scale={scale}"), &shown, w, 100, 100, r as i32);
        }
    }
}
