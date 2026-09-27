//! Resident layers and dirty-rect readback. Like tests/engine.rs, each GPU test runs once per
//! backend with an adapter on this machine (lavapipe Vulkan, llvmpipe GL) and is skipped with a
//! note when there is none.
//!
//! The parity tests replay the same multi-stroke sequences -- with an undo in the middle -- through
//! two engines: one that uploads the whole layer before every stroke (the pre-residency path) and
//! one that keeps the layer resident and re-uploads only when the caller's generation says the CPU
//! layer changed. Every stroke's result must be byte-identical.

use graffux_wgpu::wgpu::Backends;
use graffux_wgpu::{ColorSmudgeDab, Engine, GpuDab, SubstrateParams};

const W: i32 = 131;
const H: i32 = 89;
const LAYER: u64 = 0xA11CE;

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

fn engine(b: Backends) -> Engine {
    Engine::with_backends(W, H, b).expect("adapter probed above")
}

fn seed(rng: &mut Rng) -> Vec<u8> {
    let mut v = vec![0u8; (W * H * 4) as usize];
    for px in v.chunks_exact_mut(4) {
        let a = rng.byte();
        for c in 0..3 {
            px[c] = ((rng.byte() as u32 * a as u32) / 255) as u8;
        }
        px[3] = a;
    }
    v
}

/// A stroke confined to a band so different strokes touch different rectangles.
fn stroke(rng: &mut Rng, n: usize, band: (f32, f32)) -> Vec<GpuDab> {
    (0..n)
        .map(|_| {
            let mut d = GpuDab::legacy(
                band.0 + rng.f() * (band.1 - band.0),
                rng.f() * H as f32,
                1.5 + rng.f() * 9.0,
                0.3 + rng.f() * 0.7,
                rng.f() * 360.0,
            );
            d.color_r = rng.f();
            d.color_g = rng.f();
            d.color_b = rng.f();
            d.color_a = 0.4 + rng.f() * 0.6;
            d.flow = 0.3 + rng.f();
            d.resolved = 1.0;
            d.tip_ratio = rng.f();
            d
        })
        .collect()
}

/// Paints `dabs` in per-frame batches with strokeMax (the live-stroke mode), reading back after
/// each batch into `buf`, the caller's per-stroke copy of the layer.
fn paint(e: &mut Engine, dabs: &[GpuDab], buf: &mut [u8]) {
    for part in dabs.chunks(7) {
        assert!(e.stamp_dabs(
            part,
            0xFF3080C0,
            0.4,
            false,
            SubstrateParams::default(),
            true
        ));
        assert!(e.readback(buf));
    }
}

/// The pre-residency path: upload the whole layer, paint, read back.
fn old_path(e: &mut Engine, truth: &[u8], dabs: &[GpuDab]) -> Vec<u8> {
    let mut buf = truth.to_vec();
    assert!(e.upload(truth));
    paint(e, dabs, &mut buf);
    buf
}

/// Caller-side bookkeeping for the resident path: the CPU layer's current generation, and the
/// session/generation the last stroke bound with.
struct Resident {
    next_gen: u64,
    gen: u64,
    uploads: usize,
}

impl Resident {
    fn new() -> Self {
        Resident {
            next_gen: 1,
            gen: 0,
            uploads: 0,
        }
    }
    fn bump(&mut self) -> u64 {
        self.gen = self.next_gen;
        self.next_gen += 1;
        self.gen
    }
    /// Starts a stroke: bind, or upload on a miss. Returns the session.
    fn begin(&mut self, e: &mut Engine, truth: &[u8]) -> u64 {
        let s = e.bind_layer(LAYER, self.gen);
        if s != 0 {
            return s;
        }
        self.uploads += 1;
        let s = e.upload_layer(LAYER, self.gen, truth);
        assert_ne!(s, 0);
        s
    }
}

/// GPU-truth commits (the desktop canvas): stroke, stroke, undo, stroke, stroke.
#[test]
fn resident_gpu_commit_matches_full_upload_across_undo() {
    for (name, b) in backends() {
        let mut old = engine(b);
        let mut new = engine(b);
        let mut rng = Rng(11);
        let mut truth = seed(&mut rng);
        let mut history: Vec<Vec<u8>> = Vec::new();
        let mut res = Resident::new();
        res.bump();
        let strokes: Vec<Vec<GpuDab>> = (0..4)
            .map(|i| stroke(&mut rng, 30, (i as f32 * 25.0, i as f32 * 25.0 + 60.0)))
            .collect();
        let plan = ["s0", "s1", "undo", "s2", "s3"];
        let mut next = 0;
        for step in plan {
            if step == "undo" {
                truth = history.pop().expect("something to undo");
                res.bump(); // the CPU layer changed outside the engine
                continue;
            }
            let dabs = &strokes[next];
            next += 1;
            let want = old_path(&mut old, &truth, dabs);
            let session = res.begin(&mut new, &truth);
            let mut got = truth.clone();
            paint(&mut new, dabs, &mut got);
            assert_eq!(got, want, "[{name}] {step}: resident result differs from full upload");
            history.push(std::mem::replace(&mut truth, got));
            let g = res.bump();
            assert!(new.commit_layer(LAYER, session, g), "[{name}] {step}");
            // The resident copy must equal the committed layer exactly.
            assert_eq!(new.read_all().unwrap(), truth, "[{name}] {step}: resident copy");
        }
        // One upload for the first stroke and one after the undo; every other stroke bound.
        assert_eq!(res.uploads, 2, "[{name}]");
        eprintln!("[{name}] 4 strokes + undo: {} uploads, all byte-identical", res.uploads);
    }
}

/// CPU-truth commits (Android): the committed layer differs from the GPU preview somewhere (here
/// a band the CPU "rounded differently" plus a pixel outside the stroke), so the resident copy is
/// refreshed from the committed pixels over the stroke's rows plus the CPU's change rectangle.
#[test]
fn resident_cpu_commit_refresh_matches_full_upload_across_undo() {
    for (name, b) in backends() {
        let mut old = engine(b);
        let mut new = engine(b);
        let mut rng = Rng(23);
        let mut truth = seed(&mut rng);
        let mut history: Vec<Vec<u8>> = Vec::new();
        let mut res = Resident::new();
        res.bump();
        let strokes: Vec<Vec<GpuDab>> = (0..4)
            .map(|i| stroke(&mut rng, 25, (10.0 + i as f32 * 20.0, 40.0 + i as f32 * 20.0)))
            .collect();
        let plan = ["s0", "s1", "undo", "s2", "s3"];
        let mut next = 0;
        for step in plan {
            if step == "undo" {
                truth = history.pop().expect("something to undo");
                res.bump();
                continue;
            }
            let dabs = &strokes[next];
            next += 1;
            let want = old_path(&mut old, &truth, dabs);
            let session = res.begin(&mut new, &truth);
            let mut got = truth.clone();
            paint(&mut new, dabs, &mut got);
            assert_eq!(got, want, "[{name}] {step}");
            // The CPU commit: the preview, nudged inside the stroke and at one pixel outside it.
            let mut committed = got.clone();
            for v in committed.iter_mut().skip(4 * (W as usize * 40 + 30)).step_by(97).take(50) {
                *v = v.wrapping_add(1);
            }
            let (ox, oy) = (W - 2, H - 3);
            committed[((oy * W + ox) * 4) as usize] ^= 0x10;
            // The caller's change rectangle: what differs between the pre-stroke layer and the
            // committed one (computed by comparison, as the Android commit does).
            let extra = change_rect(&truth, &committed);
            history.push(std::mem::replace(&mut truth, committed));
            let g = res.bump();
            assert!(new.refresh_layer(LAYER, session, g, &truth, extra), "[{name}] {step}");
            assert_eq!(new.read_all().unwrap(), truth, "[{name}] {step}: refreshed copy");
        }
        assert_eq!(res.uploads, 2, "[{name}]");
    }
}

fn change_rect(a: &[u8], b: &[u8]) -> (i32, i32, i32, i32) {
    let (mut x0, mut y0, mut x1, mut y1) = (W, H, -1, -1);
    for y in 0..H {
        for x in 0..W {
            let i = ((y * W + x) * 4) as usize;
            if a[i..i + 4] != b[i..i + 4] {
                x0 = x0.min(x);
                y0 = y0.min(y);
                x1 = x1.max(x);
                y1 = y1.max(y);
            }
        }
    }
    if x1 < 0 {
        (0, 0, 0, 0)
    } else {
        (x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }
}

#[test]
fn stale_generation_tainted_copy_and_invalidation_all_miss() {
    for (name, b) in backends() {
        let mut e = engine(b);
        let s = seed(&mut Rng(5));
        let session = e.upload_layer(LAYER, 10, &s);
        assert_ne!(session, 0);
        // A fresh bind of the same generation hits (nothing painted yet).
        assert_ne!(e.bind_layer(LAYER, 10), 0, "[{name}]");
        assert_eq!(e.bind_layer(LAYER, 11), 0, "[{name}] other generation must miss");
        // Painting taints: the copy no longer matches generation 10.
        let session = e.bind_layer(LAYER, 10);
        let mut buf = s.clone();
        paint(&mut e, &stroke(&mut Rng(1), 5, (0.0, 50.0)), &mut buf);
        assert_eq!(e.bind_layer(LAYER, 10), 0, "[{name}] tainted copy must miss");
        // A commit from a superseded session is refused.
        assert!(!e.commit_layer(LAYER, session + 1000, 12), "[{name}]");
        assert!(e.commit_layer(LAYER, session, 12), "[{name}]");
        assert_eq!(e.resident_generation(LAYER), Some(12), "[{name}]");
        // Invalidation always wins.
        assert!(e.invalidate_layer(LAYER));
        assert_eq!(e.bind_layer(LAYER, 12), 0, "[{name}] invalidated copy must miss");
        assert_eq!(e.resident_generation(LAYER), None);
        // A commit after invalidation cannot resurrect it.
        assert!(!e.commit_layer(LAYER, session, 13), "[{name}]");
    }
}

#[test]
fn rebinding_supersedes_an_older_session() {
    for (name, b) in backends() {
        let mut e = engine(b);
        let s = seed(&mut Rng(8));
        let first = e.upload_layer(LAYER, 1, &s);
        let mut buf = s.clone();
        paint(&mut e, &stroke(&mut Rng(2), 5, (0.0, 60.0)), &mut buf);
        // A second stroke starts before the first commits (it misses: the copy is tainted).
        assert_eq!(e.bind_layer(LAYER, 1), 0);
        let second = e.upload_layer(LAYER, 1, &s);
        assert_ne!(first, second);
        // The late commit of the first stroke must not retag the second stroke's copy.
        assert!(!e.refresh_layer(LAYER, first, 2, &buf, (0, 0, W, H)), "[{name}]");
        assert!(!e.commit_layer(LAYER, first, 2), "[{name}]");
        assert_eq!(e.resident_generation(LAYER), Some(1));
    }
}

#[test]
fn lru_budget_evicts_least_recently_used_layer() {
    for (name, b) in backends() {
        let mut e = engine(b);
        let bytes = (W * H * 4) as u64;
        e.set_resident_budget(bytes * 2);
        let s = seed(&mut Rng(9));
        assert_ne!(e.upload_layer(1, 100, &s), 0);
        assert_ne!(e.upload_layer(2, 200, &s), 0);
        assert_ne!(e.bind_layer(1, 100), 0); // 1 is now most recently used
        assert_ne!(e.upload_layer(3, 300, &s), 0); // evicts 2
        assert_eq!(e.resident_stats(), (2, bytes * 2), "[{name}]");
        assert_eq!(e.bind_layer(2, 200), 0, "[{name}] 2 was evicted");
        assert_ne!(e.bind_layer(1, 100), 0, "[{name}] 1 stayed");
        // Shrinking the budget keeps only the active layer.
        e.set_resident_budget(0);
        assert_eq!(e.resident_stats(), (1, bytes), "[{name}]");
        assert_ne!(e.bind_layer(1, 100), 0, "[{name}] active layer survives");
        e.invalidate_all_layers();
        assert_eq!(e.bind_layer(1, 100), 0, "[{name}]");
    }
}

/// upload()/clear() (StampEngine.h) paint an anonymous layer and never disturb resident ones.
#[test]
fn legacy_upload_and_clear_leave_resident_layers_alone() {
    for (name, b) in backends() {
        let mut e = engine(b);
        let s = seed(&mut Rng(12));
        assert_ne!(e.upload_layer(LAYER, 7, &s), 0);
        assert!(e.clear());
        assert!(e.read_all().unwrap().iter().all(|v| *v == 0), "[{name}] scratch cleared");
        let other = seed(&mut Rng(13));
        assert!(e.upload(&other));
        let mut buf = other.clone();
        paint(&mut e, &stroke(&mut Rng(3), 5, (0.0, 60.0)), &mut buf);
        assert_ne!(e.bind_layer(LAYER, 7), 0, "[{name}] resident layer untouched");
        assert_eq!(e.read_all().unwrap(), s, "[{name}]");
    }
}

/// The first readback of a bound stroke copies only the dabs' rectangle, and reports it.
#[test]
fn dirty_rect_readback_reports_and_copies_only_the_rect() {
    for (name, b) in backends() {
        let mut e = engine(b);
        let s = seed(&mut Rng(4));
        assert_ne!(e.upload_layer(LAYER, 1, &s), 0);
        assert_eq!(e.dirty_rect(), (0, 0, 0, 0), "[{name}] bound layer starts clean");
        let d = GpuDab::legacy(40.0, 30.0, 5.0, 1.0, 0.0);
        assert!(e.stamp_dabs(&[d], 0xFFFFFFFF, 1.0, false, SubstrateParams::default(), false));
        // dabRegion: floor(35)..ceil(45)+1 in both axes, dispatched as one 16x16 workgroup.
        assert_eq!(e.dirty_rect(), (35, 25, 16, 16), "[{name}]");
        let mut buf = vec![0x5Au8; (W * H * 4) as usize];
        assert_eq!(e.readback_rect(&mut buf), Some((35, 25, 16, 16)), "[{name}]");
        let at = |x: i32, y: i32| ((y * W + x) * 4) as usize;
        assert_eq!(&buf[at(40, 30)..at(40, 30) + 4], &[255, 255, 255, 255], "[{name}]");
        assert_eq!(&buf[at(34, 30)..at(34, 30) + 4], &[0x5A; 4], "[{name}] left of rect");
        assert_eq!(&buf[at(51, 30)..at(51, 30) + 4], &[0x5A; 4], "[{name}] right of rect");
        assert_eq!(&buf[at(40, 24)..at(40, 24) + 4], &[0x5A; 4], "[{name}] above rect");
        // Inside the rect but untouched by the dab: the layer's own pixels came back.
        assert_eq!(&buf[at(35, 25)..at(35, 25) + 4], &s[at(35, 25)..at(35, 25) + 4]);
        assert_eq!(e.readback_rect(&mut buf), Some((0, 0, 0, 0)), "[{name}] now clean");
        // read_region agrees with read_all for narrow and wide rectangles.
        let all = e.read_all().unwrap();
        for (x, y, w, h) in [(35, 25, 11, 11), (0, 10, W, 7), (3, 0, 90, H)] {
            let mut region = vec![0u8; (w * h * 4) as usize];
            assert!(e.read_region(x, y, w, h, &mut region), "[{name}]");
            for row in 0..h {
                let src = at(x, y + row);
                assert_eq!(
                    &region[(row * w * 4) as usize..((row + 1) * w * 4) as usize],
                    &all[src..src + (w * 4) as usize],
                    "[{name}] region {x},{y},{w},{h} row {row}"
                );
            }
        }
        assert!(!e.read_region(W - 2, 0, 5, 5, &mut vec![0u8; 100]), "[{name}] out of bounds");
    }
}

/// Smudge taints the whole layer, so its refresh covers every row.
#[test]
fn color_smudge_taints_the_whole_layer() {
    for (name, b) in backends() {
        let mut e = engine(b);
        let s = seed(&mut Rng(6));
        let session = e.upload_layer(LAYER, 1, &s);
        let dab = |x: f32| ColorSmudgeDab {
            x,
            y: 40.0,
            smudge_rate: 0.8,
            color_rate: 0.0,
            opacity: 1.0,
            smudge_radius: 6.0,
            color_rate_multiplier: 1.0,
            distance_delta_px: 2.0,
            base_color_rate: 0.0,
            charge_decay_rate: 0.0,
            pickup_rate: 0.0,
        };
        assert!(e.color_smudge(&[dab(20.0), dab(24.0), dab(28.0)], 0, 6.0, 0.5, true, 0, 0.0, None));
        assert_eq!(e.bind_layer(LAYER, 1), 0, "[{name}]");
        let committed = e.read_all().unwrap();
        assert!(e.refresh_layer(LAYER, session, 2, &committed, (0, 0, 0, 0)));
        assert_eq!(e.read_all().unwrap(), committed, "[{name}]");
    }
}

/// A masked tip's rotated rectangle reaches past its radius into the dispatch's workgroup padding.
/// The rectangle readback must still return every pixel the dispatch wrote.
#[test]
fn masked_writes_past_the_radius_are_read_back() {
    for (name, b) in backends() {
        let mut e = engine(b);
        let s = seed(&mut Rng(31));
        assert_ne!(e.upload_layer(LAYER, 1, &s), 0);
        let mut d = GpuDab::legacy(40.0, 40.0, 8.0, 1.0, 45.0);
        d.tip_ratio = 1.0;
        let mask = vec![255u8; 16 * 16];
        let p = graffux_wgpu::MaskedParams {
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
        assert!(e.stamp_masked_dabs(&[d], 0xFFFF0000, 1.0, &p));
        let mut rect_read = s.clone();
        assert!(e.readback(&mut rect_read));
        assert_eq!(rect_read, e.read_all().unwrap(), "[{name}]");
    }
}
