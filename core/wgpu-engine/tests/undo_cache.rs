//! GPU undo on a cache-mode engine ([`Engine::undo_open`]): the Android document engine keeps its
//! layers canonical on the CPU, binds them by content generation, and still keeps each stroke's
//! pre-stroke rectangle on the GPU. Runs once per backend with an adapter.

mod common;

use graffux_wgpu::wgpu::Backends;
use graffux_wgpu::{Engine, GpuDab, SubstrateParams};

const W: i32 = 64;
const H: i32 = 48;
const BYTES: usize = (W * H * 4) as usize;

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

fn paint(e: &mut Engine) {
    let mut d = GpuDab::legacy(20.0, 20.0, 6.0, 1.0, 0.0);
    d.resolved = 0.0;
    assert!(e.stamp_dabs(
        &[d],
        0xFFFF0000,
        1.0,
        false,
        SubstrateParams::default(),
        true
    ));
}

#[test]
fn without_undo_open_no_snapshot_is_kept() {
    for (name, mut e) in engines() {
        assert!(!e.undo_is_open());
        let session = e.upload_layer(1, 1, &pattern(2));
        assert!(session > 0, "[{name}] upload");
        paint(&mut e);
        assert!(e.commit_layer(1, session, 2));
        assert_eq!(
            e.doc_snapshot_stroke(1, session, 0, 0, W, H),
            0,
            "[{name}] no shadow taken"
        );
    }
}

#[test]
fn undo_open_snapshots_a_cache_mode_stroke_and_swaps_it_back() {
    for (name, mut e) in engines() {
        assert!(e.undo_open());
        assert!(
            e.undo_is_open() && !e.doc_is_open(),
            "[{name}] undo without document mode"
        );
        let base = pattern(5);
        let session = e.upload_layer(1, 1, &base);
        assert!(session > 0, "[{name}] upload");
        paint(&mut e);
        let mut cpu = base.clone();
        assert!(e.readback(&mut cpu));
        assert_ne!(cpu, base, "[{name}] the stroke painted");
        let painted = cpu.clone();
        assert!(e.commit_layer(1, session, 2));

        let id = e.doc_snapshot_stroke(1, session, 0, 0, W, H);
        assert!(id > 0, "[{name}] snapshot kept");
        // Wrong expected generation: nothing changes.
        assert!(
            e.doc_snapshot_swap(id, 99, 3).is_none(),
            "[{name}] stale generation refused"
        );
        let (g, r) = e.doc_snapshot_swap(id, 2, 3).expect("undo");
        assert_eq!(
            (g, r.w, r.h),
            (3, W, H),
            "[{name}] tagged with the caller's generation"
        );
        assert!(e.readback(&mut cpu));
        assert_eq!(cpu, base, "[{name}] undo restores the pre-stroke layer");
        assert!(e.doc_snapshot_swap(id, 3, 4).is_some(), "[{name}] redo");
        assert!(e.readback(&mut cpu));
        assert_eq!(cpu, painted, "[{name}] redo restores the stroke");

        // Still a cache: the caller's generation binds, invalidation forgets the layer.
        assert!(
            e.bind_layer(1, 4) > 0,
            "[{name}] binds at the caller's generation"
        );
        assert!(e.invalidate_layer(1), "[{name}] invalidation still applies");
        assert_eq!(
            e.bind_layer(1, 4),
            0,
            "[{name}] an invalidated layer misses"
        );
    }
}
