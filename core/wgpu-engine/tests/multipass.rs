//! Multipass rendering on the GPU (Mesa lavapipe = Vulkan, llvmpipe = GL; skipped per backend when
//! no adapter exists).
//!
//! * With multipass off, nothing changes (an engine that enabled and disabled it paints the same
//!   bytes as one that never did).
//! * With it on, the layer (what commits use) is byte-identical to multipass off, for any
//!   interleaving of readbacks and refinement budgets, including zero and random ones.
//! * Drafts show up on the first readback, before any refinement.
//! * The display converges to exactly the layer, continuously and monotonically.
//! * A textured masked brush's draft correlates strongly with its final result.
//!
//! Side-by-side PNGs (draft, clarity stages, final) are written to
//! `target/multipass-png/` for a look; they are not committed.

use graffux_wgpu::wgpu::Backends;
use graffux_wgpu::{ColorSmudgeDab, Engine, GpuDab, GpuSecondaryDab, MaskedParams, MultipassConfig, SubstrateParams};

const W: i32 = 131;
const H: i32 = 97;

struct Rng(u64);
impl Rng {
    fn f(&mut self) -> f32 {
        self.0 = self
            .0
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        ((self.0 >> 40) as f32) / ((1u64 << 24) as f32)
    }
    fn byte(&mut self) -> u8 {
        (self.f() * 256.0) as u8
    }
    fn below(&mut self, n: usize) -> usize {
        ((self.f() * n as f32) as usize).min(n - 1)
    }
}

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

fn engine(b: Backends, w: i32, h: i32) -> Engine {
    Engine::with_backends(w, h, b).expect("adapter")
}

fn on(scale: u32) -> MultipassConfig {
    MultipassConfig {
        enabled: true,
        draft_scale: scale,
        ..MultipassConfig::default()
    }
}

fn seed_image(rng: &mut Rng, w: i32, h: i32) -> Vec<u8> {
    let mut v = vec![0u8; (w * h * 4) as usize];
    for px in v.chunks_exact_mut(4) {
        let a = rng.byte();
        for c in 0..3 {
            px[c] = ((rng.byte() as u32 * a as u32) / 255) as u8;
        }
        px[3] = a;
    }
    v
}

fn random_dabs(rng: &mut Rng, n: usize, w: i32, h: i32) -> Vec<GpuDab> {
    (0..n)
        .map(|i| {
            let mut d = GpuDab::legacy(
                rng.f() * w as f32,
                rng.f() * h as f32,
                0.5 + rng.f() * 16.0,
                0.2 + rng.f() * 0.8,
                rng.f() * 360.0,
            );
            if i % 2 == 0 {
                d.color_r = rng.f();
                d.color_g = rng.f();
                d.color_b = rng.f();
                d.color_a = 0.3 + rng.f() * 0.7;
                d.flow = 0.2 + rng.f();
                d.resolved = 1.0;
                d.tip_ratio = rng.f();
            }
            d.contact_depth = rng.f();
            d.substrate_response = rng.f();
            d
        })
        .collect()
}

fn soft_mask(size: u32, rng: &mut Rng, noise: bool) -> Vec<u8> {
    let c = (size as f32 - 1.0) / 2.0;
    (0..size * size)
        .map(|i| {
            let (x, y) = ((i % size) as f32, (i / size) as f32);
            let d = (((x - c) / c).powi(2) + ((y - c) / c).powi(2)).sqrt();
            let v = (1.0 - d).clamp(0.0, 1.0);
            let n = if noise { 0.55 + 0.45 * rng.f() } else { 1.0 };
            (v.powf(0.7) * n * 255.0) as u8
        })
        .collect()
}

/// One step of a random painting session.
enum Op {
    Stamp(Vec<GpuDab>, u32, f32, bool, bool, bool),
    Masked(Vec<GpuDab>, u32, bool, bool, bool),
    Smudge(Vec<ColorSmudgeDab>),
    Rows(i32, i32),
    PaintHeight,
    Substrate,
}

fn ops(seed: u64, count: usize) -> Vec<Op> {
    let mut rng = Rng(seed);
    let mut out = Vec::new();
    for _ in 0..count {
        let k = rng.below(20);
        out.push(match k {
            0..=8 => {
                let n = 1 + rng.below(12);
                let build_up = rng.f() < 0.3;
                Op::Stamp(
                    random_dabs(&mut rng, n, W, H),
                    0xFF000000 | (rng.byte() as u32) << 16 | (rng.byte() as u32) << 8 | 0x40,
                    rng.f(),
                    build_up,
                    rng.f() < 0.3,
                    rng.f() < 0.7,
                )
            }
            9..=15 => {
                let n = 1 + rng.below(10);
                Op::Masked(
                    random_dabs(&mut rng, n, W, H),
                    0xC0000000 | (rng.byte() as u32) << 8 | 0x20,
                    rng.f() < 0.6,
                    rng.f() < 0.4,
                    rng.f() < 0.3,
                )
            }
            16 => {
                let n = 2 + rng.below(4);
                Op::Smudge(
                    (0..n)
                        .map(|i| ColorSmudgeDab {
                            x: 20.0 + i as f32 * 6.0 + rng.f() * 40.0,
                            y: 20.0 + rng.f() * 50.0,
                            smudge_rate: 0.3 + rng.f() * 0.6,
                            color_rate: rng.f() * 0.5,
                            opacity: 0.5 + rng.f() * 0.5,
                            smudge_radius: 1.0,
                            color_rate_multiplier: 1.0,
                            distance_delta_px: 4.0,
                            base_color_rate: rng.f(),
                            charge_decay_rate: 0.0,
                            pickup_rate: 0.0,
                        })
                        .collect(),
                )
            }
            17 => {
                let y = rng.below(H as usize) as i32;
                Op::Rows(y, 1 + rng.below(20) as i32)
            }
            18 => Op::PaintHeight,
            _ => Op::Substrate,
        });
    }
    out
}

struct Assets {
    base: Vec<u8>,
    rows_src: Vec<u8>,
    mask: Vec<u8>,
    grain: Vec<u8>,
    secondary_mask: Vec<u8>,
    heights: Vec<Vec<f32>>,
    substrates: Vec<Vec<u8>>,
}

fn assets() -> Assets {
    let mut rng = Rng(99);
    Assets {
        base: seed_image(&mut rng, W, H),
        rows_src: seed_image(&mut rng, W, H),
        mask: soft_mask(24, &mut rng, true),
        grain: (0..64).map(|_| 128 + rng.byte() / 2).collect(),
        secondary_mask: soft_mask(16, &mut rng, false),
        heights: (0..3)
            .map(|_| (0..W * H).map(|_| rng.f() * 0.6).collect())
            .collect(),
        substrates: (0..3).map(|_| (0..64).map(|_| rng.byte()).collect()).collect(),
    }
}

/// Runs the session. With `interleave`, readbacks and refinement calls with random budgets are
/// sprinkled between the operations (what a render thread would do under changing load).
fn run(e: &mut Engine, ops: &[Op], a: &Assets, interleave: Option<u64>) -> Vec<u8> {
    let mut rng = Rng(interleave.unwrap_or(1));
    // The caller's display buffer: complete after the first readback (the upload dirties it all),
    // kept current by every later one.
    let mut out = vec![0u8; (W * H * 4) as usize];
    let mut heights = 0;
    let mut substrates = 0;
    assert!(e.upload(&a.base));
    assert!(e.upload_substrate_height(&a.substrates[0], 8, 8));
    for op in ops {
        match op {
            Op::Stamp(dabs, color, hardness, build_up, substrate, stroke_max) => {
                let s = SubstrateParams {
                    enabled: *substrate,
                    has_paint_height: *substrate,
                    base_height: 0.2,
                    height_scale: 0.7,
                    texture_scale: 1.5,
                    texture_offset_x: 0.25,
                    texture_offset_y: -3.0,
                };
                assert!(e.stamp_dabs(dabs, *color, *hardness, *build_up, s, *stroke_max));
            }
            Op::Masked(dabs, color, grain, secondary, substrate) => {
                let sec: Vec<GpuSecondaryDab> = dabs
                    .iter()
                    .map(|d| GpuSecondaryDab {
                        x: d.x + 2.0,
                        y: d.y - 1.0,
                        radius: d.radius * 0.8,
                        tip_ratio: 0.7,
                        alpha: 0.9,
                        angle_deg: 30.0,
                        flow_multiplier: 1.0,
                        keep_inside: 1.0,
                    })
                    .collect();
                let p = MaskedParams {
                    mask: &a.mask,
                    mask_width: 24,
                    mask_height: 24,
                    grain: grain.then_some((&a.grain[..], 8, 8)),
                    grain_canvas_locked: false,
                    grain_scale: 1.3,
                    grain_phase_x: 0.5,
                    grain_phase_y: 1.5,
                    secondary_dabs: if *secondary { &sec } else { &[] },
                    secondary_mask: secondary.then_some((&a.secondary_mask[..], 16, 16)),
                    substrate: SubstrateParams {
                        enabled: *substrate,
                        ..SubstrateParams::default()
                    },
                };
                assert!(e.stamp_masked_dabs(dabs, *color, 0.0, &p));
            }
            Op::Smudge(dabs) => {
                assert!(e.color_smudge(dabs, 0, 6.0, 0.5, true, 0xFF3366AA, 0.2, None));
            }
            Op::Rows(y, rows) => {
                assert!(e.upload_rows(&a.rows_src, *y, *rows));
            }
            Op::PaintHeight => {
                heights = (heights + 1) % a.heights.len();
                assert!(e.upload_paint_height(&a.heights[heights], W, H));
            }
            Op::Substrate => {
                substrates = (substrates + 1) % a.substrates.len();
                assert!(e.upload_substrate_height(&a.substrates[substrates], 8, 8));
            }
        }
        if interleave.is_some() {
            match rng.below(4) {
                0 => {
                    assert!(e.readback(&mut out));
                }
                1 => {
                    // Anything from a starving 1 us to a generous 4 ms.
                    let budget = [0.001, 0.05, 0.5, 4.0][rng.below(4)];
                    assert!(e.refine(budget) >= 0);
                    assert!(e.readback(&mut out));
                }
                2 => {
                    assert!(e.refine(0.001) >= 0);
                }
                _ => {}
            }
        }
    }
    assert!(e.readback(&mut out));
    out
}

#[test]
fn multipass_off_is_unchanged_and_on_matches_it_byte_for_byte() {
    let a = assets();
    for (name, b) in backends() {
        for seed in [1u64, 2, 3] {
            let session = ops(seed, 70);
            let mut off = engine(b, W, H);
            run(&mut off, &session, &a, None);
            let expected = off.read_all().unwrap();

            // Enabled then disabled before painting: the plain path, bit for bit.
            let mut toggled = engine(b, W, H);
            assert!(toggled.set_multipass(on(2)));
            assert!(toggled.set_multipass(MultipassConfig::default()));
            run(&mut toggled, &session, &a, None);
            assert_eq!(toggled.read_all().unwrap(), expected, "[{name}] seed {seed}: toggled off");

            // On, with different interleavings and budgets, and different draft scales: the layer
            // is always exactly the multipass-off layer, and after a flush so is the display.
            for (k, scale) in [(11u64, 2u32), (12, 4), (13, 1), (14, 8), (15, 0)] {
                let mut e = engine(b, W, H);
                assert!(e.set_multipass(on(scale)));
                let mut shown = run(&mut e, &session, &a, Some(seed * 100 + k));
                let got = e.read_all().unwrap();
                let diff = got.iter().zip(&expected).filter(|(x, y)| x != y).count();
                assert_eq!(diff, 0, "[{name}] seed {seed} interleaving {k}: {diff} bytes differ");
                assert!(e.flush());
                assert!(e.readback(&mut shown));
                assert!(shown == expected, "[{name}] seed {seed} interleaving {k}: display != layer");
            }
        }
    }
}

#[test]
fn drafts_show_on_the_first_readback_and_the_display_lands_exactly_on_the_layer() {
    for (name, b) in backends() {
        let mut rng = Rng(5);
        let base = seed_image(&mut rng, W, H);
        let mut e = engine(b, W, H);
        assert!(e.set_multipass(MultipassConfig {
            transition_ms: 100.0,
            ..on(2)
        }));
        e.set_multipass_clock(Some(0.0));
        assert!(e.upload(&base));
        let mut shown = vec![0u8; (W * H * 4) as usize];
        assert!(e.readback(&mut shown));
        assert_eq!(shown, base);
        // A stroke; no refinement yet.
        let mut dabs = Vec::new();
        for i in 0..20 {
            let mut d = GpuDab::legacy(20.0 + i as f32 * 4.5, 50.0, 9.0, 0.9, 0.0);
            d.tip_ratio = 0.3; // hardness (resolved dabs carry it here)
            d.color_r = 0.9;
            d.color_g = 0.1;
            d.color_b = 0.2;
            d.color_a = 1.0;
            d.flow = 1.0;
            d.resolved = 1.0;
            dabs.push(d);
        }
        for chunk in dabs.chunks(4) {
            assert!(e.stamp_dabs(chunk, 0, 0.3, false, SubstrateParams::default(), true));
        }
        assert!(e.readback(&mut shown));
        let stats = e.multipass_stats();
        assert_eq!(stats.pending_drafts, 0, "[{name}] every draft ran");
        assert_eq!(stats.pending_refinement, 5, "[{name}] no final work yet");
        let changed = shown.iter().zip(&base).filter(|(x, y)| x != y).count();
        assert!(changed > 1000, "[{name}] the draft is visible at once ({changed} bytes changed)");
        // Frames 8 ms apart; refinement gets a small slice each frame.
        let mut t = 0.0;
        let mut prev = shown.clone();
        let mut max_step = 0u8;
        let mut frames = 0;
        let expected = {
            let mut off = engine(b, W, H);
            assert!(off.upload(&base));
            for chunk in dabs.chunks(4) {
                assert!(off.stamp_dabs(chunk, 0, 0.3, false, SubstrateParams::default(), true));
            }
            off.read_all().unwrap()
        };
        loop {
            t += 8.0;
            frames += 1;
            e.set_multipass_clock(Some(t));
            e.refine(0.3);
            assert!(e.readback(&mut shown));
            for (x, y) in shown.iter().zip(&prev) {
                max_step = max_step.max(x.abs_diff(*y));
            }
            prev.copy_from_slice(&shown);
            let s = e.multipass_stats();
            if s.pending_refinement == 0 && s.active_tiles == 0 {
                break;
            }
            assert!(frames < 2000);
        }
        let bad: Vec<usize> = shown.iter().zip(&expected).enumerate().filter(|(_, (x, y))| x != y).map(|(i, _)| i / 4).collect();
        if !bad.is_empty() {
            panic!("[{name}] {} px differ, first {:?}", bad.len(), bad.iter().take(8).map(|p| (p % W as usize, p / W as usize)).collect::<Vec<_>>());
        }
        assert_eq!(e.read_all().unwrap(), expected);
        eprintln!("[{name}] converged in {frames} frames, largest per-frame step {max_step} levels");
    }
}

/// Per-pixel monotonic convergence over a landing with frame timestamps, and the quality parameter
/// never falls or jumps.
#[test]
fn landing_is_monotonic_and_continuous_per_pixel() {
    for (name, b) in backends() {
        let mut e = engine(b, 64, 64);
        let landing = 120.0;
        assert!(e.set_multipass(MultipassConfig {
            transition_ms: landing as f32,
            ..on(2)
        }));
        e.set_multipass_clock(Some(0.0));
        let white = vec![255u8; 64 * 64 * 4];
        assert!(e.upload(&white));
        let mut shown = white.clone();
        assert!(e.readback(&mut shown));
        let mut d = GpuDab::legacy(32.0, 32.0, 20.0, 1.0, 0.0);
        d.color_r = 0.1;
        d.color_g = 0.2;
        d.color_b = 0.6;
        d.color_a = 0.8;
        d.flow = 1.0;
        d.resolved = 1.0;
        d.tip_ratio = 0.2;
        assert!(e.stamp_dabs(&[d], 0, 0.2, false, SubstrateParams::default(), true));
        assert!(e.readback(&mut shown));
        let draft = shown.clone();
        let q0 = e.multipass_tile_quality(32, 32);
        // Let the reveal run a little, then land the final result in one go.
        let mut t = 0.0;
        let mut qs = vec![q0];
        for _ in 0..5 {
            t += 16.0;
            e.set_multipass_clock(Some(t));
            assert!(e.readback(&mut shown));
            qs.push(e.multipass_tile_quality(32, 32));
        }
        let before_landing = shown.clone();
        e.refine(1e6);
        let landed_at = t;
        let mut frames = vec![before_landing.clone()];
        let mut ts = 0.0;
        while ts <= landing + 20.0 {
            ts += 7.0;
            e.set_multipass_clock(Some(landed_at + ts));
            assert!(e.readback(&mut shown));
            frames.push(shown.clone());
            qs.push(e.multipass_tile_quality(32, 32));
        }
        // read_all is a flush point (it would finish the ease at once), so only now.
        let fin = e.read_all().unwrap();
        assert_eq!(shown, fin, "[{name}] lands exactly");
        // Quality parameter: non-decreasing, no jump at the landing.
        for w in qs.windows(2) {
            assert!(w[1] + 1e-6 >= w[0], "[{name}] quality fell {:?}", qs);
            assert!(w[1] - w[0] <= 0.2, "[{name}] quality jumped {:?}", qs);
        }
        // Every pixel moves monotonically from what was on screen at the landing to the final.
        let mut worst_step = 0u8;
        for i in 0..fin.len() {
            let from = before_landing[i] as i32;
            let to = fin[i] as i32;
            let mut prev = from;
            for f in &frames {
                let v = f[i] as i32;
                // Toward the target, never overshooting (1 level of rounding slack).
                if to >= from {
                    assert!(v + 1 >= prev && v <= to + 1, "[{name}] px {i}: {from}->{to} went {prev}->{v}");
                } else {
                    assert!(v <= prev + 1 && v + 1 >= to, "[{name}] px {i}: {from}->{to} went {prev}->{v}");
                }
                worst_step = worst_step.max((v - prev).unsigned_abs() as u8);
                prev = v;
            }
        }
        let changed = draft.iter().zip(&fin).filter(|(x, y)| x != y).count();
        eprintln!(
            "[{name}] landing: {} frames over {landing} ms, largest per-frame step {worst_step} levels; \
             draft vs final: {changed} bytes differ",
            frames.len()
        );
    }
}

/// Pearson correlation of two images' luminance after box-downsampling both by `k`.
fn correlation(a: &[u8], b: &[u8], w: i32, h: i32, k: i32) -> f64 {
    let lum = |img: &[u8], x: i32, y: i32| {
        let i = ((y * w + x) * 4) as usize;
        // Composite over white so alpha counts.
        let al = img[i + 3] as f64 / 255.0;
        let c = |j: usize| img[i + j] as f64 / 255.0 + (1.0 - al);
        0.299 * c(0) + 0.587 * c(1) + 0.114 * c(2)
    };
    let (dw, dh) = (w / k, h / k);
    let mut xs = Vec::new();
    let mut ys = Vec::new();
    for by in 0..dh {
        for bx in 0..dw {
            let (mut sa, mut sb) = (0.0, 0.0);
            for y in 0..k {
                for x in 0..k {
                    sa += lum(a, bx * k + x, by * k + y);
                    sb += lum(b, bx * k + x, by * k + y);
                }
            }
            xs.push(sa);
            ys.push(sb);
        }
    }
    let n = xs.len() as f64;
    let (ma, mb) = (xs.iter().sum::<f64>() / n, ys.iter().sum::<f64>() / n);
    let (mut cov, mut va, mut vb) = (0.0, 0.0, 0.0);
    for (x, y) in xs.iter().zip(&ys) {
        cov += (x - ma) * (y - mb);
        va += (x - ma) * (x - ma);
        vb += (y - mb) * (y - mb);
    }
    cov / (va.sqrt() * vb.sqrt()).max(1e-12)
}

/// A stroke rendered through multipass: the display at the draft (q = 0), at two reveal stages, and
/// the final layer.
fn stroke_stages(b: Backends, masked: bool, scale: u32) -> (Vec<Vec<u8>>, Vec<u8>, i32, i32) {
    let (w, h) = (192, 96);
    let mut e = engine(b, w, h);
    assert!(e.set_multipass(MultipassConfig {
        transition_ms: 0.0,
        ..on(scale)
    }));
    e.set_multipass_clock(Some(0.0));
    let white = vec![255u8; (w * h * 4) as usize];
    assert!(e.upload(&white));
    let mut shown = white.clone();
    assert!(e.readback(&mut shown));
    let mut rng = Rng(7);
    let mask = soft_mask(48, &mut rng, true);
    let grain: Vec<u8> = (0..16 * 16).map(|_| 110 + rng.byte() / 2).collect();
    let mut dabs = Vec::new();
    for i in 0..40 {
        let x = 20.0 + i as f32 * 3.8;
        let y = 48.0 + (i as f32 * 0.25).sin() * 18.0;
        let mut d = GpuDab::legacy(x, y, 16.0, 0.8, i as f32 * 9.0);
        d.color_r = 0.15;
        d.color_g = 0.25;
        d.color_b = 0.7;
        d.color_a = 1.0;
        d.flow = 0.6;
        d.resolved = 1.0;
        d.tip_ratio = if masked { 0.6 } else { 0.1 };
        dabs.push(d);
    }
    for chunk in dabs.chunks(5) {
        if masked {
            let p = MaskedParams {
                mask: &mask,
                mask_width: 48,
                mask_height: 48,
                grain: Some((&grain, 16, 16)),
                grain_canvas_locked: true,
                grain_scale: 1.0,
                grain_phase_x: 0.0,
                grain_phase_y: 0.0,
                secondary_dabs: &[],
                secondary_mask: None,
                substrate: SubstrateParams::default(),
            };
            assert!(e.stamp_masked_dabs(chunk, 0, 0.0, &p));
        } else {
            assert!(e.stamp_dabs(chunk, 0, 0.1, false, SubstrateParams::default(), true));
        }
    }
    let mut stages = Vec::new();
    assert!(e.readback(&mut shown));
    stages.push(shown.clone()); // draft, trimmed feather
    for t in [60.0, 400.0] {
        e.set_multipass_clock(Some(t));
        assert!(e.readback(&mut shown));
        stages.push(shown.clone()); // reveal growing toward the full feather
    }
    assert!(e.flush());
    assert!(e.readback(&mut shown));
    let fin = shown.clone();
    assert_eq!(fin, e.read_all().unwrap());
    (stages, fin, w, h)
}

#[test]
fn draft_of_a_textured_masked_brush_correlates_with_its_final_and_pngs_are_written() {
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("target/multipass-png");
    let _ = std::fs::create_dir_all(&dir);
    for (name, b) in backends() {
        for (label, masked) in [("masked-textured", true), ("soft-round", false)] {
            for scale in [2u32, 4] {
                let (stages, fin, w, h) = stroke_stages(b, masked, scale);
                let r_draft = correlation(&stages[0], &fin, w, h, 8);
                let r_revealed = correlation(&stages[2], &fin, w, h, 8);
                eprintln!(
                    "[{name}] {label} draft 1/{scale}: correlation with final (8x8 downsampled) \
                     {r_draft:.4} trimmed, {r_revealed:.4} fully revealed"
                );
                assert!(r_revealed > 0.9, "[{name}] {label} 1/{scale}: {r_revealed}");
                assert!(r_draft > 0.8, "[{name}] {label} 1/{scale}: {r_draft}");
                let mut row: Vec<&[u8]> = stages.iter().map(Vec::as_slice).collect();
                row.push(&fin);
                write_strip(&dir.join(format!("{name}-{label}-draft{scale}.png")), &row, w, h);
            }
        }
    }
}

/// Images side by side, composited over white, as a PNG (stored deflate blocks, no dependencies).
fn write_strip(path: &std::path::Path, images: &[&[u8]], w: i32, h: i32) {
    let gap = 4;
    let total_w = images.len() as i32 * (w + gap) - gap;
    let mut raw = Vec::new();
    for y in 0..h {
        raw.push(0u8);
        for x in 0..total_w {
            let (i, lx) = ((x / (w + gap)) as usize, x % (w + gap));
            if lx >= w {
                raw.extend_from_slice(&[200, 200, 200]);
                continue;
            }
            let p = ((y * w + lx) * 4) as usize;
            let img = images[i];
            let a = img[p + 3] as u32;
            for c in 0..3 {
                raw.push((img[p + c] as u32 + (255 - a)).min(255) as u8);
            }
        }
    }
    let mut z = vec![0x78, 0x01];
    for (k, block) in raw.chunks(65535).enumerate() {
        let last = (k + 1) * 65535 >= raw.len();
        z.push(last as u8);
        z.extend_from_slice(&(block.len() as u16).to_le_bytes());
        z.extend_from_slice(&(!(block.len() as u16)).to_le_bytes());
        z.extend_from_slice(block);
    }
    let (mut s1, mut s2) = (1u32, 0u32);
    for &b in &raw {
        s1 = (s1 + b as u32) % 65521;
        s2 = (s2 + s1) % 65521;
    }
    z.extend_from_slice(&((s2 << 16) | s1).to_be_bytes());
    let crc = |data: &[u8]| {
        let mut c = 0xFFFF_FFFFu32;
        for &b in data {
            c ^= b as u32;
            for _ in 0..8 {
                c = if c & 1 != 0 { 0xEDB8_8320 ^ (c >> 1) } else { c >> 1 };
            }
        }
        !c
    };
    let mut png = b"\x89PNG\r\n\x1a\n".to_vec();
    let mut chunk = |kind: &[u8], data: &[u8]| {
        png.extend_from_slice(&(data.len() as u32).to_be_bytes());
        let mut body = kind.to_vec();
        body.extend_from_slice(data);
        png.extend_from_slice(&body);
        png.extend_from_slice(&crc(&body).to_be_bytes());
    };
    let mut ihdr = Vec::new();
    ihdr.extend_from_slice(&(total_w as u32).to_be_bytes());
    ihdr.extend_from_slice(&(h as u32).to_be_bytes());
    ihdr.extend_from_slice(&[8, 2, 0, 0, 0]);
    chunk(b"IHDR", &ihdr);
    chunk(b"IDAT", &z);
    chunk(b"IEND", &[]);
    let _ = std::fs::write(path, png);
}

/// Resident layers with multipass: the CPU-commit refresh drops queued refinement (nothing waits
/// for it) and still leaves the resident copy exactly equal to the committed pixels.
#[test]
fn resident_refresh_drops_queued_work_and_stays_exact() {
    for (name, b) in backends() {
        let mut rng = Rng(21);
        let base = seed_image(&mut rng, W, H);
        let committed = seed_image(&mut rng, W, H); // stands in for the CPU's authoritative commit
        let mut e = engine(b, W, H);
        assert!(e.set_multipass(on(2)));
        let s = e.upload_layer(7, 1, &base);
        assert!(s != 0);
        let dabs = random_dabs(&mut rng, 8, W, H);
        assert!(e.stamp_dabs(&dabs, 0xFF102030, 0.5, false, SubstrateParams::default(), true));
        let mut out = base.clone();
        assert!(e.readback(&mut out));
        assert!(e.multipass_stats().pending_refinement > 0);
        // The CPU committed; queued refinement is dropped, the refresh covers its footprint.
        assert!(e.refresh_layer(7, s, 2, &committed, (0, 0, 0, 0)));
        assert_eq!(e.multipass_stats().pending_refinement, 0);
        let s2 = e.bind_layer(7, 2);
        assert!(s2 != 0, "[{name}] rebinding the refreshed layer hits");
        let got = e.read_all().unwrap();
        // Everywhere the stroke could have painted now matches the committed image; elsewhere the
        // resident copy still holds the (unchanged) base, exactly like the plain refresh path.
        let mut plain = engine(b, W, H);
        let ps = plain.upload_layer(7, 1, &base);
        assert!(plain.stamp_dabs(&dabs, 0xFF102030, 0.5, false, SubstrateParams::default(), true));
        assert!(plain.refresh_layer(7, ps, 2, &committed, (0, 0, 0, 0)));
        assert!(plain.bind_layer(7, 2) != 0);
        assert_eq!(got, plain.read_all().unwrap(), "[{name}]");
    }
}

