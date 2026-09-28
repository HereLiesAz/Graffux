//! Multipass rendering, measured on whatever adapters exist (on a GPU-less host: Mesa lavapipe for
//! Vulkan, llvmpipe for GL -- SOFTWARE renderers; the absolute numbers say nothing about a phone,
//! only the on/off comparison on the same renderer means anything).
//!
//!     cargo run --release --example multipass_bench [-- WIDTH HEIGHT FRAMES]
//!
//! A drag is simulated at 60 Hz: frame `k`'s dab batch *arrives* at `k * 16.67 ms`. The engine is
//! driven like the render thread drives it: stamp the batch, read back (what the user sees), then
//! (multipass) give refinement the rest of the frame. If the work overruns a frame, the next batch
//! waits for it, exactly like a FIFO render thread.
//!
//! * **touch-to-visible**: from a batch's arrival to the end of the readback that shows it (first
//!   visible paint of those dabs), p50 / p95 / max over the stroke;
//! * **time to full quality**: from the last batch's arrival until the layer holds every final dab,
//!   and until the display has finished easing to it, with the frame loop continuing idle;
//! * **10x refinement**: the same with every refinement chunk costing 10x (a scratch ballast), to
//!   show touch-to-visible does not depend on refinement cost;
//! * **draft vs full cost** per batch, and the display composite per frame.

use std::time::{Duration, Instant};

use graffux_wgpu::wgpu::Backends;
use graffux_wgpu::{Engine, GpuDab, GpuSecondaryDab, MaskedParams, MultipassConfig, SubstrateParams};

const FRAME_MS: f64 = 1000.0 / 60.0;

#[derive(Clone, Copy, PartialEq)]
enum Brush {
    SoftRound,
    MaskedTextured,
    Bristles,
    /// A big textured tip laid down densely: the full dab costs more than a frame here.
    HeavyMasked,
}

impl Brush {
    fn name(self) -> &'static str {
        match self {
            Brush::SoftRound => "soft round r40",
            Brush::MaskedTextured => "masked+grain+dual r48",
            Brush::Bristles => "bristle population (64 small masked dabs/frame)",
            Brush::HeavyMasked => "heavy: masked+grain+dual r160, 24 dabs/frame",
        }
    }
}

struct Assets {
    mask: Vec<u8>,
    grain: Vec<u8>,
    secondary: Vec<u8>,
}

fn assets() -> Assets {
    let mut s = 12345u64;
    let mut rnd = || {
        s = s.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        (s >> 40) as f32 / (1u64 << 24) as f32
    };
    let n = 128u32;
    let c = (n as f32 - 1.0) / 2.0;
    let mask = (0..n * n)
        .map(|i| {
            let (x, y) = ((i % n) as f32, (i / n) as f32);
            let d = (((x - c) / c).powi(2) + ((y - c) / c).powi(2)).sqrt();
            ((1.0 - d).clamp(0.0, 1.0).powf(0.6) * (0.5 + 0.5 * rnd()) * 255.0) as u8
        })
        .collect();
    let grain = (0..64 * 64).map(|_| (120.0 + rnd() * 135.0) as u8).collect();
    let secondary = (0..32 * 32)
        .map(|i| {
            let (x, y) = ((i % 32) as f32 - 15.5, (i / 32) as f32 - 15.5);
            ((1.0 - (x * x + y * y).sqrt() / 16.0).clamp(0.0, 1.0) * 255.0) as u8
        })
        .collect();
    Assets {
        mask,
        grain,
        secondary,
    }
}

fn batch(brush: Brush, frame: usize, w: i32, h: i32) -> Vec<GpuDab> {
    let (n, radius, step) = match brush {
        Brush::SoftRound => (6, 40.0, 5.0),
        Brush::MaskedTextured => (6, 48.0, 5.0),
        Brush::Bristles => (64, 5.0, 0.5),
        Brush::HeavyMasked => (24, 160.0, 2.0),
    };
    (0..n)
        .map(|k| {
            let t = (frame * n + k) as f32 * step;
            let x = w as f32 * 0.1 + t % (w as f32 * 0.8);
            let mut y = h as f32 * 0.5 + (t * 0.01).sin() * h as f32 * 0.25;
            if brush == Brush::Bristles {
                y += ((k * 7919) % 41) as f32 - 20.0; // a tuft across the stroke
            }
            let mut d = GpuDab::legacy(x, y, radius, 0.85, t * 0.7);
            d.resolved = 1.0;
            d.color_r = 0.2;
            d.color_g = 0.3;
            d.color_b = 0.8;
            d.color_a = 1.0;
            d.flow = 0.6;
            d.tip_ratio = if brush == Brush::SoftRound { 0.2 } else { 0.7 };
            d
        })
        .collect()
}

fn stamp(e: &mut Engine, brush: Brush, dabs: &[GpuDab], a: &Assets) {
    match brush {
        Brush::SoftRound => {
            assert!(e.stamp_dabs(dabs, 0, 0.2, false, SubstrateParams::default(), true));
        }
        Brush::MaskedTextured | Brush::Bristles | Brush::HeavyMasked => {
            let secondary: Vec<GpuSecondaryDab> = dabs
                .iter()
                .map(|d| GpuSecondaryDab {
                    x: d.x + 3.0,
                    y: d.y - 2.0,
                    radius: d.radius * 0.9,
                    tip_ratio: 0.8,
                    alpha: 0.9,
                    angle_deg: 20.0,
                    flow_multiplier: 1.0,
                    keep_inside: 1.0,
                })
                .collect();
            let p = MaskedParams {
                mask: &a.mask,
                mask_width: 128,
                mask_height: 128,
                grain: Some((&a.grain, 64, 64)),
                grain_canvas_locked: true,
                grain_scale: 1.0,
                grain_phase_x: 0.0,
                grain_phase_y: 0.0,
                secondary_dabs: &secondary,
                secondary_mask: Some((&a.secondary, 32, 32)),
                substrate: SubstrateParams::default(),
            };
            assert!(e.stamp_masked_dabs(dabs, 0, 0.0, &p));
        }
    }
}

struct Run {
    visible_ms: Vec<f64>,
    layer_done_ms: f64,
    settled_ms: f64,
    draft_ms: f64,
    compose_ms: f64,
    present_ms: f64,
    batch_ms: Vec<f64>,
    scale: u32,
}

fn pct(v: &[f64], p: f64) -> f64 {
    let mut s = v.to_vec();
    s.sort_by(|a, b| a.partial_cmp(b).unwrap());
    s[((s.len() - 1) as f64 * p).round() as usize]
}

fn run(
    backends: Backends,
    w: i32,
    h: i32,
    frames: usize,
    brush: Brush,
    mp: Option<MultipassConfig>,
    a: &Assets,
) -> Option<Run> {
    let mut e = Engine::with_backends(w, h, backends)?;
    let base: Vec<u8> = (0..(w * h * 4) as usize)
        .map(|i| if i % 4 == 3 { 255 } else { 235 })
        .collect();
    let mut out = base.clone();
    if let Some(cfg) = mp {
        assert!(e.set_multipass(cfg));
    }
    // Warm-up stroke (pipelines, allocations, cost estimates), then the measured stroke.
    for pass in 0..2 {
        assert!(e.upload(&base));
        assert!(e.readback(&mut out));
        let n = if pass == 0 { 20 } else { frames };
        let start = Instant::now();
        let at = |k: usize| start + Duration::from_secs_f64(k as f64 * FRAME_MS / 1000.0);
        let mut visible = Vec::new();
        let mut batch_ms = Vec::new();
        for k in 0..n {
            let arrive = at(k);
            let now = Instant::now();
            if now < arrive {
                std::thread::sleep(arrive - now);
            }
            let t0 = Instant::now();
            let dabs = batch(brush, k, w, h);
            stamp(&mut e, brush, &dabs, a);
            assert!(e.readback(&mut out));
            let done = Instant::now();
            batch_ms.push((done - t0).as_secs_f64() * 1000.0);
            visible.push((done - arrive).as_secs_f64() * 1000.0);
            if mp.is_some() {
                e.refine(0.0);
            }
        }
        // Keep the frame loop going, idle, until everything has landed and the display settled.
        let last = at(n - 1);
        let mut layer_done = None;
        let mut settled = None;
        let mut k = n;
        if mp.is_some() {
            loop {
                let arrive = at(k);
                let now = Instant::now();
                if now < arrive {
                    std::thread::sleep(arrive - now);
                }
                assert!(e.readback(&mut out));
                let s = e.multipass_stats();
                if layer_done.is_none() && s.pending_refinement == 0 {
                    layer_done = Some(Instant::now());
                }
                if s.pending_refinement == 0 && s.active_tiles == 0 {
                    settled = Some(Instant::now());
                    break;
                }
                e.refine(0.0);
                k += 1;
                assert!(k < n + 100_000);
            }
        }
        if pass == 1 {
            let s = e.multipass_stats();
            let since = |t: Option<Instant>| t.map_or(0.0, |t| (t - last).as_secs_f64() * 1000.0);
            return Some(Run {
                visible_ms: visible,
                layer_done_ms: since(layer_done),
                settled_ms: since(settled),
                draft_ms: s.draft_ms,
                compose_ms: s.compose_ms,
                present_ms: s.present_ms,
                batch_ms,
                scale: s.draft_scale,
            });
        }
    }
    None
}

fn main() {
    let args: Vec<usize> = std::env::args().skip(1).filter_map(|a| a.parse().ok()).collect();
    let (w, h) = (*args.first().unwrap_or(&1536) as i32, *args.get(1).unwrap_or(&1024) as i32);
    let frames = *args.get(2).unwrap_or(&120);
    let a = assets();
    println!(
        "SOFTWARE RENDERER RESULTS (Mesa, no GPU). Layer {w}x{h}, a {frames}-frame drag at 60 Hz. \
         Times in ms."
    );
    for (bname, backends) in [("vulkan (lavapipe)", Backends::VULKAN), ("gl (llvmpipe)", Backends::GL)] {
        if Engine::with_backends(8, 8, backends).is_none() {
            println!("\n[{bname}] no adapter -- skipped");
            continue;
        }
        println!("\n## {bname}\n");
        println!(
            "| brush | mode | touch-to-visible p50 | p95 | max | batch work p50 | layer final after last batch | display settled | draft/frame | composite/frame | readback/frame |"
        );
        println!("|---|---|---|---|---|---|---|---|---|---|---|");
        for brush in [Brush::SoftRound, Brush::MaskedTextured, Brush::Bristles, Brush::HeavyMasked] {
            let base = MultipassConfig {
                enabled: true,
                frame_ms: FRAME_MS as f32,
                ..MultipassConfig::default()
            };
            let modes: Vec<(&str, Option<MultipassConfig>)> = vec![
                ("off", None),
                ("multipass", Some(base)),
                (
                    "multipass, refinement x10",
                    Some(MultipassConfig {
                        refine_ballast: 10.0,
                        ..base
                    }),
                ),
            ];
            for (mode, cfg) in modes {
                let Some(r) = run(backends, w, h, frames, brush, cfg, &a) else {
                    continue;
                };
                let mp = cfg.is_some();
                let opt = |v: f64| if mp { format!("{v:.1}") } else { "-".into() };
                println!(
                    "| {} | {}{} | {:.1} | {:.1} | {:.1} | {:.2} | {} | {} | {} | {} | {} |",
                    brush.name(),
                    mode,
                    if mp { format!(" (draft 1/{})", r.scale) } else { String::new() },
                    pct(&r.visible_ms, 0.5),
                    pct(&r.visible_ms, 0.95),
                    pct(&r.visible_ms, 1.0),
                    pct(&r.batch_ms, 0.5),
                    if mp { format!("{:.0}", r.layer_done_ms) } else { "0 (in the frame)".into() },
                    if mp { format!("{:.0}", r.settled_ms) } else { "-".into() },
                    opt(r.draft_ms),
                    opt(r.compose_ms),
                    opt(r.present_ms),
                );
            }
        }
    }
}
