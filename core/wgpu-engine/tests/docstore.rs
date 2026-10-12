//! The document store (docstore.rs): layers as canonical GPU buffers. Runs once per backend with an
//! adapter; skipped with a note when there is none.

use graffux_wgpu::resident::Rect;
use graffux_wgpu::wgpu::Backends;
use graffux_wgpu::{Engine, GpuDab, SubstrateParams};

const W: i32 = 64;
const H: i32 = 48;
const BYTES: usize = (W * H * 4) as usize;

fn engines() -> Vec<(&'static str, Engine)> {
    let mut out = Vec::new();
    for (name, b) in [("vulkan", Backends::VULKAN), ("gl", Backends::GL)] {
        match Engine::with_backends(W, H, b) {
            Some(mut e) => {
                assert!(e.doc_open());
                out.push((name, e));
            }
            None => eprintln!("[{name}] no adapter -- skipped"),
        }
    }
    out
}

fn pattern(seed: u8) -> Vec<u8> {
    (0..BYTES)
        .map(|i| {
            if i % 4 == 3 {
                255
            } else {
                (i as u8).wrapping_mul(31).wrapping_add(seed)
            }
        })
        .collect()
}

fn read(e: &mut Engine, key: u64) -> Vec<u8> {
    let mut out = vec![0u8; BYTES];
    assert!(e.doc_layer_read(key, 0, 0, W, H, &mut out));
    out
}

fn dab(x: f32, y: f32) -> GpuDab {
    let mut d = GpuDab::legacy(x, y, 6.0, 1.0, 0.0);
    d.resolved = 0.0;
    d
}

#[test]
fn layers_round_trip_and_stay_independent() {
    for (name, mut e) in engines() {
        let (a, b) = (pattern(1), pattern(9));
        let ga = e.doc_layer_put(1, &a);
        let gb = e.doc_layer_put(2, &b);
        assert!(ga > 0 && gb > ga, "[{name}] generations increase");
        assert_eq!(read(&mut e, 1), a, "[{name}] layer 1");
        assert_eq!(read(&mut e, 2), b, "[{name}] layer 2");
        assert_eq!(e.doc_stats(), (2, 2 * BYTES as u64));
        assert!(e.doc_layer_drop(1));
        assert_eq!(e.doc_layer_gen(1), 0);
        assert_eq!(read(&mut e, 2), b, "[{name}] layer 2 after dropping 1");
    }
}

#[test]
fn a_stroke_paints_its_layer_in_place_and_reports_its_rectangle() {
    for (name, mut e) in engines() {
        let base = pattern(3);
        e.doc_layer_put(1, &base);
        e.doc_layer_put(2, &pattern(4));
        let before = e.doc_layer_gen(1);
        let session = e.doc_layer_bind(1);
        assert!(session > 0, "[{name}] bind");
        assert!(e.stamp_dabs(
            &[dab(20.0, 20.0)],
            0xFFFF0000,
            1.0,
            false,
            SubstrateParams::default(),
            true
        ));
        let (generation, rect) = e.doc_layer_commit(1, session).expect("commit");
        assert!(generation > before, "[{name}] commit bumps the generation");
        assert!(
            rect.x <= 14 && rect.y <= 14 && rect.x + rect.w >= 26 && rect.y + rect.h >= 26,
            "[{name}] {rect:?}"
        );
        let after = read(&mut e, 1);
        let px = |b: &[u8], x: i32, y: i32| {
            b[((y * W + x) * 4) as usize..((y * W + x) * 4 + 4) as usize].to_vec()
        };
        assert_eq!(px(&after, 20, 20), vec![255, 0, 0, 255], "[{name}] painted");
        assert_eq!(
            px(&after, 50, 40),
            px(&base, 50, 40),
            "[{name}] untouched pixel kept"
        );
        assert_eq!(
            read(&mut e, 2),
            pattern(4),
            "[{name}] other layer untouched"
        );
        // A session superseded by a newer stroke on the same layer no longer commits.
        let next = e.doc_layer_bind(1);
        assert!(
            e.doc_layer_commit(1, session).is_none(),
            "[{name}] superseded session"
        );
        assert!(e.doc_layer_commit(1, next).is_some());
    }
}

#[test]
fn each_stroke_starts_fresh_stroke_max_state() {
    for (name, mut e) in engines() {
        e.doc_layer_clear(1);
        let s1 = e.doc_layer_bind(1);
        let mut weak = dab(20.0, 20.0);
        weak.alpha = 0.5;
        assert!(e.stamp_dabs(
            &[weak],
            0xFF0000FF,
            1.0,
            false,
            SubstrateParams::default(),
            true
        ));
        e.doc_layer_commit(1, s1).unwrap();
        let once = read(&mut e, 1);
        // The same dab again as a new stroke composites over the first instead of max-combining.
        let s2 = e.doc_layer_bind(1);
        assert!(e.stamp_dabs(
            &[weak],
            0xFF0000FF,
            1.0,
            false,
            SubstrateParams::default(),
            true
        ));
        e.doc_layer_commit(1, s2).unwrap();
        let twice = read(&mut e, 1);
        let a = |b: &[u8]| b[((20 * W + 20) * 4 + 3) as usize];
        assert!(
            a(&twice) > a(&once),
            "[{name}] second stroke builds on the first: {} vs {}",
            a(&twice),
            a(&once)
        );
    }
}

#[test]
fn partial_writes_reads_and_copies() {
    for (name, mut e) in engines() {
        e.doc_layer_clear(1);
        let patch: Vec<u8> = (0..(10 * 5 * 4)).map(|i| (i % 251) as u8).collect();
        assert!(e.doc_layer_write(1, 7, 9, 10, 5, &patch) > 0);
        let mut back = vec![0u8; patch.len()];
        assert!(e.doc_layer_read(1, 7, 9, 10, 5, &mut back));
        assert_eq!(back, patch, "[{name}] rect round trip");
        assert!(
            e.doc_layer_write(1, 60, 0, 10, 5, &patch) == 0,
            "[{name}] out of bounds refused"
        );
        assert!(e.doc_layer_copy(1, 3) > 0);
        assert_eq!(read(&mut e, 3), read(&mut e, 1), "[{name}] copy");
        assert!(
            !e.doc_layer_read(99, 0, 0, W, H, &mut vec![0u8; BYTES]),
            "[{name}] absent layer"
        );
    }
}

#[test]
fn nothing_is_evicted_in_document_mode() {
    for (name, mut e) in engines() {
        // Far past the normal 256 MiB residency budget's layer count for this size is not needed:
        // shrinking the budget must not drop canonical layers either.
        for k in 1..=8 {
            e.doc_layer_put(k, &pattern(k as u8));
        }
        e.set_resident_budget(0);
        for k in 1..=8 {
            assert_eq!(read(&mut e, k), pattern(k as u8), "[{name}] layer {k} kept");
        }
        let _ = Rect::default();
    }
}

#[test]
fn snapshots_undo_and_redo_a_stroke_on_the_gpu() {
    for (name, mut e) in engines() {
        let before = pattern(3);
        e.doc_layer_put(1, &before);
        let session = e.doc_layer_bind(1);
        assert!(session > 0);
        assert!(e.stamp_dabs(
            &[dab(20.0, 20.0)],
            0xFF2244CC,
            1.0,
            false,
            SubstrateParams::default(),
            true
        ));
        let (_, r) = e.doc_layer_commit(1, session).expect("committed");
        let after = read(&mut e, 1);
        assert_ne!(after, before, "[{name}] the stroke painted");
        let id = e.doc_snapshot_stroke(1, session, r.x, r.y, r.w, r.h);
        assert!(id > 0, "[{name}] snapshot");
        assert_eq!(e.doc_snapshot_stats(), (1, (r.w * r.h * 4) as u64));
        assert!(
            e.doc_snapshot_swap(id, 12345, 7).is_none(),
            "[{name}] other content"
        );
        let (g1, back) = e.doc_snapshot_swap(id, 0, 0).expect("undo");
        assert_eq!(back, r);
        assert_eq!(read(&mut e, 1), before, "[{name}] undo");
        let (g2, _) = e.doc_snapshot_swap(id, g1, g1 + 100).expect("redo");
        assert_eq!(g2, g1 + 100);
        assert_eq!(read(&mut e, 1), after, "[{name}] redo");
        assert!(e.doc_layer_drop(1));
        assert!(
            e.doc_snapshot_swap(id, 0, 0).is_none(),
            "[{name}] dropped with its layer"
        );
        assert_eq!(e.doc_snapshot_stats().0, 0);
    }
}

#[test]
fn a_later_bind_retires_the_stroke_shadow_for_snapshots() {
    for (name, mut e) in engines() {
        e.doc_layer_put(1, &pattern(4));
        e.doc_layer_put(2, &pattern(5));
        let first = e.doc_layer_bind(1);
        let (_, r) = e.doc_layer_commit(1, first).expect("committed");
        let _second = e.doc_layer_bind(2);
        let r = Rect::new(r.x, r.y, r.w.max(4), r.h.max(4));
        assert_eq!(
            e.doc_snapshot_stroke(1, first, r.x, r.y, r.w, r.h),
            0,
            "[{name}] stale shadow"
        );
    }
}
