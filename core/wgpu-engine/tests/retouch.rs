//! The retouch tools on the GPU: Burn/Dodge (the round shader's DARKEN/LIGHTEN) against Android's
//! premultiplied formulas, and Blur/Sharpen/Clone (`Engine::retouch`) against CPU references of
//! the same arithmetic. Runs once per backend with an adapter.

use graffux_wgpu::wgpu::Backends;
use graffux_wgpu::{
    Engine, GpuDab, Retouch, SubstrateParams, RETOUCH_BLUR, RETOUCH_CLONE, RETOUCH_SHARPEN,
    STAMP_DARKEN, STAMP_LIGHTEN, STAMP_OVER,
};

const W: i32 = 48;
const H: i32 = 40;
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

/// Opaque stripes, so blur, sharpen and clone all have edges to work on.
fn stripes() -> Vec<u8> {
    (0..N)
        .flat_map(|i| {
            let x = (i % W as usize) as u32;
            let y = (i / W as usize) as u32;
            if (x / 4 + y / 6).is_multiple_of(2) {
                [200, 60, 30, 255]
            } else {
                [20, 90, 210, 255]
            }
        })
        .collect()
}

fn px(img: &[u8], x: i32, y: i32) -> [f32; 4] {
    let i = ((y.clamp(0, H - 1) * W + x.clamp(0, W - 1)) * 4) as usize;
    [0, 1, 2, 3].map(|c| img[i + c] as f32 / 255.0)
}

fn lerp(a: [f32; 4], b: [f32; 4], t: f32) -> [f32; 4] {
    [0, 1, 2, 3].map(|c| a[c] + (b[c] - a[c]) * t)
}

fn bilinear(img: &[u8], fx: f32, fy: f32) -> [f32; 4] {
    let (x0, y0) = (fx.floor() as i32, fy.floor() as i32);
    let (tx, ty) = (fx - x0 as f32, fy - y0 as f32);
    lerp(
        lerp(px(img, x0, y0), px(img, x0 + 1, y0), tx),
        lerp(px(img, x0, y0 + 1), px(img, x0 + 1, y0 + 1), tx),
        ty,
    )
}

/// The reference for retouch.wgsl's `blurred`.
fn blurred(img: &[u8], x: i32, y: i32, factor: i32) -> [f32; 4] {
    let (sw, sh) = ((W / factor).max(1), (H / factor).max(1));
    let small = |i: i32, j: i32| {
        let (ci, cj) = (i.clamp(0, sw - 1), j.clamp(0, sh - 1));
        bilinear(
            img,
            (ci as f32 + 0.5) * W as f32 / sw as f32 - 0.5,
            (cj as f32 + 0.5) * H as f32 / sh as f32 - 0.5,
        )
    };
    let u = (x as f32 + 0.5) * sw as f32 / W as f32 - 0.5;
    let v = (y as f32 + 0.5) * sh as f32 / H as f32 - 0.5;
    let (i0, j0) = (u.floor() as i32, v.floor() as i32);
    let (tx, ty) = (u - i0 as f32, v - j0 as f32);
    lerp(
        lerp(small(i0, j0), small(i0 + 1, j0), tx),
        lerp(small(i0, j0 + 1), small(i0 + 1, j0 + 1), tx),
        ty,
    )
}

/// One hard dab covering the middle of the layer.
const DAB: [f32; 4] = [24.0, 20.0, 12.0, 1.0];

fn inside(x: i32, y: i32) -> bool {
    let (dx, dy) = (x as f32 + 0.5 - DAB[0], y as f32 + 0.5 - DAB[1]);
    (dx * dx + dy * dy).sqrt() < DAB[2] - 1.0
}

fn run(e: &mut Engine, tool: Retouch) -> Vec<u8> {
    assert!(e.upload(&stripes()));
    assert!(e.take_stroke_shadow());
    assert!(e.retouch(tool, &[DAB]));
    e.read_all().unwrap()
}

fn tool(kind: u32) -> Retouch {
    Retouch {
        kind,
        factor: 4,
        amount: 1.2,
        offset: (5.0, -3.0),
        wrap: false,
        wrap_frame: (0, 0, 0, 0),
    }
}

#[test]
fn blur_and_clone_match_their_references_and_stay_in_the_stroke() {
    for (name, mut e) in engines() {
        let src = stripes();
        let blur = run(&mut e, tool(RETOUCH_BLUR));
        let clone = run(&mut e, tool(RETOUCH_CLONE));
        let mut worst = 0i32;
        for y in 0..H {
            for x in 0..W {
                let i = ((y * W + x) * 4) as usize;
                let far = {
                    let (dx, dy) = (x as f32 + 0.5 - DAB[0], y as f32 + 0.5 - DAB[1]);
                    (dx * dx + dy * dy).sqrt() >= DAB[2]
                };
                if far {
                    assert_eq!(
                        &blur[i..i + 4],
                        &src[i..i + 4],
                        "[{name}] blur outside the stroke"
                    );
                    assert_eq!(
                        &clone[i..i + 4],
                        &src[i..i + 4],
                        "[{name}] clone outside the stroke"
                    );
                    continue;
                }
                if !inside(x, y) {
                    continue;
                }
                let want = blurred(&src, x, y, 4);
                for c in 0..4 {
                    worst =
                        worst.max((blur[i + c] as i32 - (want[c] * 255.0).round() as i32).abs());
                }
                let from = px(&src, x + 5, y - 3);
                for c in 0..4 {
                    assert_eq!(
                        clone[i + c],
                        (from[c] * 255.0).round() as u8,
                        "[{name}] clone at {x},{y}"
                    );
                }
            }
        }
        assert!(worst <= 2, "[{name}] blur worst channel difference {worst}");
    }
}

#[test]
fn sharpen_keeps_flat_colour_and_alpha_and_raises_edge_contrast() {
    for (name, mut e) in engines() {
        let src = stripes();
        let out = run(&mut e, tool(RETOUCH_SHARPEN));
        let mut raised = false;
        for y in 0..H {
            for x in 0..W {
                if !inside(x, y) {
                    continue;
                }
                let i = ((y * W + x) * 4) as usize;
                assert_eq!(out[i + 3], 255, "[{name}] alpha kept");
                let d = (out[i] as i32 - src[i] as i32).abs();
                raised |= d > 4;
            }
        }
        assert!(raised, "[{name}] sharpen changed nothing at the edges");
        // A flat layer has nothing to sharpen.
        assert!(e.upload(&vec![128u8; N * 4]));
        assert!(e.take_stroke_shadow());
        assert!(e.retouch(tool(RETOUCH_SHARPEN), &[DAB]));
        assert_eq!(
            e.read_all().unwrap(),
            vec![128u8; N * 4],
            "[{name}] flat stays flat"
        );
    }
}

#[test]
fn burn_and_dodge_match_android_darken_and_lighten() {
    for (name, mut e) in engines() {
        for (mode, color) in [
            (STAMP_DARKEN, 0xFF000000u32),
            (STAMP_LIGHTEN, 0xFFFFFFFFu32),
        ] {
            let src = stripes();
            assert!(e.upload(&src));
            assert!(e.set_stamp_mode(mode));
            let mut d = GpuDab::legacy(DAB[0], DAB[1], DAB[2], 0.4, 0.0);
            d.resolved = 0.0;
            assert!(e.stamp_dabs(&[d], color, 1.0, false, SubstrateParams::default(), true));
            assert!(e.set_stamp_mode(STAMP_OVER));
            let out = e.read_all().unwrap();
            let s = if mode == STAMP_DARKEN { 0.0 } else { 0.4 };
            let mut worst = 0i32;
            for y in 0..H {
                for x in 0..W {
                    if !inside(x, y) {
                        continue;
                    }
                    let i = ((y * W + x) * 4) as usize;
                    let dst = px(&src, x, y);
                    for c in 0..3 {
                        let v = if mode == STAMP_DARKEN {
                            s + dst[c] - (s * dst[3]).max(dst[c] * 0.4)
                        } else {
                            s + dst[c] - (s * dst[3]).min(dst[c] * 0.4)
                        };
                        worst = worst.max((out[i + c] as i32 - (v * 255.0).round() as i32).abs());
                    }
                }
            }
            assert!(worst <= 1, "[{name}] mode {mode}: worst {worst}");
        }
    }
}

#[test]
fn clone_wrap_samples_the_logical_frame_not_the_padded_texture() {
    for (name, mut engine) in engines() {
        let original = stripes();
        assert!(engine.upload(&original));
        assert!(engine.take_stroke_shadow());
        let dab = [9.0, 15.0, 2.0, 1.0];
        let wrapped = Retouch {
            kind: RETOUCH_CLONE,
            factor: 2,
            amount: 0.0,
            offset: (-5.0, 0.0),
            wrap: true,
            wrap_frame: (8, 6, 24, 20),
        };
        assert!(engine.retouch(wrapped, &[dab]));
        let result = engine.read_all().unwrap();
        let ix = ((15 * W + 9) * 4) as usize;
        let source = ((15 * W + 28) * 4) as usize; // 9 - 5 wraps to 28 within 8..32.
        assert_eq!(&result[ix..ix + 4], &original[source..source + 4],
            "[{name}] clone samples opposite logical edge");
    }
}
