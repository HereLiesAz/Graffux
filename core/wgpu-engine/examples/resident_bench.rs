//! First-dab latency and per-frame cost, before vs after resident layers and rectangle readback.
//!
//!     cargo run --release --example resident_bench [-- WIDTH HEIGHT]
//!
//! Runs on every backend with an adapter (on a GPU-less host: Mesa lavapipe for Vulkan, llvmpipe
//! for GL -- SOFTWARE renderers, so the absolute numbers say nothing about a phone GPU; only the
//! before/after ratio on the same renderer is meaningful).
//!
//! * first dab, before: `upload()` of the whole layer, stamp one frame's dabs, `readback()` (which
//!   after an upload copies the whole layer back) -- what every stroke used to start with.
//! * first dab, after: `bind_layer()` hit, stamp, `readback_rect()` (only the dabs' rectangle).
//! * first dab, after (miss): `upload_layer()` then the same -- still skips the full readback.
//! * per frame, before: stamp + copy of the dirty *rows* at full layer width (the old readback's
//!   GPU transfer, reproduced with `read_region` over whole rows).
//! * per frame, after: stamp + `readback_rect()` (compact per-row copy of just the rectangle).

use std::time::{Duration, Instant};

use graffux_wgpu::wgpu::Backends;
use graffux_wgpu::{Engine, GpuDab, SubstrateParams};

const ITER: usize = 15;
const KEY: u64 = 1;

fn frame_dabs(i: usize, w: i32, h: i32) -> Vec<GpuDab> {
    // One frame of a drag: 8 dabs, radius 12, walking across the middle of the layer.
    (0..8)
        .map(|k| {
            let x = (w as f32 * 0.2) + ((i * 8 + k) as f32 * 3.0) % (w as f32 * 0.6);
            let mut d = GpuDab::legacy(x, h as f32 * 0.5, 12.0, 0.8, 0.0);
            d.resolved = 1.0;
            d.color_r = 0.9;
            d.color_g = 0.3;
            d.color_b = 0.1;
            d.color_a = 1.0;
            d.flow = 0.7;
            d.tip_ratio = 0.5;
            d
        })
        .collect()
}

fn stamp(e: &mut Engine, dabs: &[GpuDab]) {
    assert!(e.stamp_dabs(dabs, 0xFFE04020, 0.5, false, SubstrateParams::default(), true));
}

fn median(mut v: Vec<Duration>) -> f64 {
    v.sort();
    v[v.len() / 2].as_secs_f64() * 1000.0
}

fn main() {
    let args: Vec<i32> = std::env::args().skip(1).filter_map(|a| a.parse().ok()).collect();
    let (w, h) = (*args.first().unwrap_or(&2048), *args.get(1).unwrap_or(&2048));
    let layer: Vec<u8> = (0..(w * h * 4) as usize).map(|i| (i * 31 % 251) as u8).collect();
    let mut out = layer.clone();
    println!("layer {w}x{h} ({} MiB), {ITER} iterations, median ms", w as usize * h as usize * 4 >> 20);
    for (name, backends) in [("vulkan", Backends::VULKAN), ("gl", Backends::GL)] {
        let Some(mut e) = Engine::with_backends(w, h, backends) else {
            println!("[{name}] no adapter -- skipped");
            continue;
        };
        println!("[{name}] {}  (SOFTWARE RENDERER numbers)", e.adapter_description());
        let dabs = frame_dabs(0, w, h);
        // Warm-up: pipelines, buffers, staging.
        e.upload(&layer);
        stamp(&mut e, &dabs);
        e.readback(&mut out);

        let mut before = Vec::new();
        for _ in 0..ITER {
            let t = Instant::now();
            assert!(e.upload(&layer));
            stamp(&mut e, &dabs);
            assert!(e.readback(&mut out));
            before.push(t.elapsed());
        }

        let mut miss = Vec::new();
        let mut hit = Vec::new();
        for i in 0..ITER {
            let gen = 1000 + i as u64;
            let t = Instant::now();
            let session = e.upload_layer(KEY, gen, &layer);
            assert_ne!(session, 0);
            stamp(&mut e, &dabs);
            assert!(e.readback_rect(&mut out).is_some());
            miss.push(t.elapsed());
            // Commit the stroke's GPU result (the desktop's commit) as a new generation, then time
            // the next stroke's start, which binds without any upload.
            let committed = gen + 1_000_000;
            assert!(e.commit_layer(KEY, session, committed));
            let t = Instant::now();
            assert_ne!(e.bind_layer(KEY, committed), 0);
            stamp(&mut e, &dabs);
            assert!(e.readback_rect(&mut out).is_some());
            hit.push(t.elapsed());
        }

        // Per-frame cost inside a stroke.
        let row_band = |d: &[GpuDab]| {
            let top = d.iter().map(|d| (d.y - d.radius).floor() as i32).min().unwrap().max(0);
            let bottom = d.iter().map(|d| (d.y + d.radius).ceil() as i32 + 1).max().unwrap().min(h);
            (top, bottom - top)
        };
        let mut band = vec![0u8; (w * h * 4) as usize];
        let mut frame_before = Vec::new();
        let mut frame_after = Vec::new();
        assert_ne!(e.upload_layer(KEY, 9_999, &layer), 0);
        for i in 0..ITER {
            let d = frame_dabs(i + 1, w, h);
            let t = Instant::now();
            stamp(&mut e, &d);
            let (y, rows) = row_band(&d);
            assert!(e.read_region(0, y, w, rows, &mut band));
            frame_before.push(t.elapsed());
            e.readback_rect(&mut out); // keep the dirty rect from accumulating

            let d = frame_dabs(i + 1 + ITER, w, h);
            let t = Instant::now();
            stamp(&mut e, &d);
            assert!(e.readback_rect(&mut out).is_some());
            frame_after.push(t.elapsed());
        }

        println!(
            "[{name}] first dab   before (upload + full readback): {:8.2}",
            median(before)
        );
        println!("[{name}] first dab   after, resident hit            : {:8.2}", median(hit));
        println!("[{name}] first dab   after, miss (upload_layer)     : {:8.2}", median(miss));
        println!(
            "[{name}] per frame   before (full-width row band)   : {:8.2}",
            median(frame_before)
        );
        println!(
            "[{name}] per frame   after  (rectangle readback)    : {:8.2}",
            median(frame_after)
        );
    }
}

