//! Photo adjustment layers on the GPU (`Adjuster`, shaders/adjust.wgsl) against a CPU port of the
//! same formulas (core/engine's PhotoAdjustMath.kt is the reference both follow), plus the mask,
//! the curves table and the Detail blur. Runs once per backend with an adapter.

mod common;

use graffux_wgpu::adjust::{identity_curves, Adjuster, CURVE_FLOATS, PARAM_FLOATS};
use graffux_wgpu::wgpu::Backends;

const W: i32 = 40;
const H: i32 = 30;
const N: usize = (W * H) as usize;

fn adjusters() -> Vec<(&'static str, Adjuster)> {
    let mut out = Vec::new();
    for (name, b) in [("vulkan", Backends::VULKAN), ("gl", Backends::GL)] {
        match Adjuster::with_backends(W, H, b) {
            Some(a) => out.push((name, a)),
            None => common::no_adapter(name),
        }
    }
    out
}

/// A colour ramp across x, a brightness ramp down y, a few transparent and half-alpha pixels.
fn source() -> Vec<u8> {
    (0..N)
        .flat_map(|i| {
            let x = (i % W as usize) as f32 / (W - 1) as f32;
            let y = (i / W as usize) as f32 / (H - 1) as f32;
            let a: f32 = match i % 17 {
                0 => 0.0,
                1 => 0.5,
                _ => 1.0,
            };
            let r = (x * (1.0 - y) + 0.1 * y).min(1.0);
            let g = ((1.0 - x) * 0.8 * (1.0 - 0.5 * y)).min(1.0);
            let b = (0.2 + 0.7 * y * x).min(1.0);
            // Premultiplied, as the editor uploads it.
            [
                (r * a * 255.0).round() as u8,
                (g * a * 255.0).round() as u8,
                (b * a * 255.0).round() as u8,
                (a * 255.0).round() as u8,
            ]
        })
        .collect()
}

fn neutral() -> [f32; PARAM_FLOATS] {
    let mut p = [0f32; PARAM_FLOATS];
    p[8] = 1.0; // wb r
    p[9] = 1.0; // wb g
    p[10] = 1.0; // wb b
    p[13] = 1.0; // levels gamma
    p[14] = 1.0; // levels white
    p[21] = 0.5; // vignette midpoint
    p[22] = 0.5; // vignette feather
    p[24] = 1.0; // grain size
    p
}

// ── CPU port of adjust.wgsl ──────────────────────────────────────────────────────────────────

fn to_linear(c: f32) -> f32 {
    if c <= 0.04045 {
        c / 12.92
    } else {
        ((c + 0.055) / 1.055).powf(2.4)
    }
}

fn to_srgb(c: f32) -> f32 {
    if c <= 0.0031308 {
        c * 12.92
    } else {
        1.055 * c.max(0.0).powf(1.0 / 2.4) - 0.055
    }
}

fn luma(c: [f32; 3]) -> f32 {
    0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
}

fn tone(p: &[f32], x0: f32) -> f32 {
    let bk = -p[5] * 0.15;
    let wh = 1.0 - p[4] * 0.15;
    let mut x = ((x0 - bk) / (wh - bk)).clamp(0.0, 1.0);
    let inv = 1.0 - x;
    x = x + 0.9 * p[3] * x * inv * inv + 0.9 * p[2] * x * x * inv;
    x = x.clamp(0.0, 1.0);
    let s = x * x * (3.0 - 2.0 * x);
    (x + p[1] * (s - x)).clamp(0.0, 1.0)
}

fn rgb_to_hsl(c: [f32; 3]) -> [f32; 3] {
    let mx = c[0].max(c[1]).max(c[2]);
    let mn = c[0].min(c[1]).min(c[2]);
    let l = (mx + mn) * 0.5;
    let d = mx - mn;
    if d < 1e-6 {
        return [0.0, 0.0, l];
    }
    let s = d / (1.0 - (2.0 * l - 1.0).abs()).max(1e-6);
    let mut h = if mx == c[0] {
        let h = (c[1] - c[2]) / d;
        if h < 0.0 { h + 6.0 } else { h }
    } else if mx == c[1] {
        (c[2] - c[0]) / d + 2.0
    } else {
        (c[0] - c[1]) / d + 4.0
    };
    h *= 60.0;
    [h, s.clamp(0.0, 1.0), l]
}

fn hsl_to_rgb(hsl: [f32; 3]) -> [f32; 3] {
    let a = hsl[1] * hsl[2].min(1.0 - hsl[2]);
    let f = |n: f32| {
        let k = (n + hsl[0] / 30.0) % 12.0;
        hsl[2] - a * (k - 3.0).min(9.0 - k).clamp(-1.0, 1.0)
    };
    [f(0.0), f(8.0), f(4.0)]
}

const CENTERS: [f32; 8] = [0.0, 30.0, 60.0, 120.0, 180.0, 240.0, 270.0, 300.0];

fn range_weight(h: f32, i: usize) -> f32 {
    let c = CENTERS[i];
    let prev = CENTERS[(i + 7) % 8];
    let next = CENTERS[(i + 1) % 8];
    let mut d = h - c;
    if d > 180.0 {
        d -= 360.0;
    }
    if d < -180.0 {
        d += 360.0;
    }
    let mut span = if d >= 0.0 { next - c } else { c - prev };
    if span <= 0.0 {
        span += 360.0;
    }
    (1.0 - d.abs() / span).max(0.0)
}

fn reference(p: &[f32], curves: &[f32], mask: Option<&[u8]>, src: &[u8]) -> Vec<u8> {
    let fx = if p[62] > 0.5 { Some(effect_planes(src, p)) } else { None };
    let mut out = vec![0u8; N * 4];
    for i in 0..N {
        let px: Vec<f32> = src[i * 4..i * 4 + 4].iter().map(|&v| v as f32 / 255.0).collect();
        let a0 = px[3];
        if a0 <= 0.0 {
            continue;
        }
        let mut c = [px[0] / a0, px[1] / a0, px[2] / a0];
        for k in 0..3 {
            let lin = (to_linear(c[k]) * p[8 + k] * p[0].exp2()).clamp(0.0, 1.0);
            c[k] = tone(p, to_srgb(lin));
        }
        if p[18] != 0.0 {
            let k = p[18] * 0.12;
            for v in &mut c {
                *v = ((*v - k) / (1.0 - k)).clamp(0.0, 1.0);
            }
        }
        if p[15] > 0.5 {
            for (k, v) in c.iter_mut().enumerate() {
                let m = curves[(v.clamp(0.0, 1.0) * 255.0).round() as usize];
                *v = curves[256 * (k + 1) + (m.clamp(0.0, 1.0) * 255.0).round() as usize];
            }
        }
        for v in &mut c {
            *v = ((*v - p[12]) / (p[14] - p[12]).max(1e-4))
                .clamp(0.0, 1.0)
                .powf(1.0 / p[13].max(0.01));
        }
        let mut hsl = rgb_to_hsl(c);
        if hsl[1] >= 1e-4 {
            let (mut dh, mut ds, mut dl) = (0.0, 0.0, 0.0);
            for r in 0..8 {
                let w = range_weight(hsl[0], r);
                if w > 0.0 {
                    dh += w * p[32 + 3 * r];
                    ds += w * p[33 + 3 * r];
                    dl += w * p[34 + 3 * r];
                }
            }
            if dh != 0.0 || ds != 0.0 || dl != 0.0 {
                hsl[0] = (hsl[0] + dh * 30.0 + 360.0) % 360.0;
                let sat = hsl[1];
                hsl[1] = (hsl[1] * (1.0 + ds)).clamp(0.0, 1.0);
                hsl[2] = (hsl[2] + dl * 0.25 * sat).clamp(0.0, 1.0);
                c = hsl_to_rgb(hsl);
            }
        }
        let y = luma(c);
        let sat = c[0].max(c[1]).max(c[2]) - c[0].min(c[1]).min(c[2]);
        let f = (1.0 + p[6] * (1.0 - sat)) * (1.0 + p[7]) * (1.0 + p[18].max(0.0) * 0.2);
        for v in &mut c {
            *v = (y + (*v - y) * f).clamp(0.0, 1.0);
        }
        if let Some((lum, small, large, lines)) = &fx {
            if p[59] > 0.0 {
                let b = small[i] + (large[i] - small[i]) * p[60];
                let s = (lum[i] / b.max(1e-3)).clamp(0.0, 1.0).powf(1.0 + 4.0 * p[61]);
                for v in &mut c {
                    *v += (s - *v) * p[59];
                }
            }
            if p[56] > 0.0 {
                for v in &mut c {
                    *v += (1.0 - *v) * (p[58] * p[56]);
                    *v *= 1.0 - p[56] * lines[i];
                }
            }
        }
        let mut m = 1.0;
        if p[11] > 0.5 {
            m = mask.map_or(1.0, |mk| mk[i] as f32 / 255.0);
            if p[30] > 0.5 {
                m = 1.0 - m;
            }
        }
        let a = a0 * m;
        for k in 0..3 {
            out[i * 4 + k] = ((c[k] * a).clamp(0.0, 1.0) * 255.0).round() as u8;
        }
        out[i * 4 + 3] = (a.clamp(0.0, 1.0) * 255.0).round() as u8;
    }
    out
}

fn src_luma(src: &[u8]) -> Vec<f32> {
    (0..N)
        .map(|i| {
            let al = src[i * 4 + 3] as f32 / 255.0;
            if al <= 0.0 {
                0.0
            } else {
                let c: Vec<f32> = (0..3).map(|k| src[i * 4 + k] as f32 / 255.0 / al).collect();
                luma([c[0], c[1], c[2]])
            }
        })
        .collect()
}

fn box_blur(plane: &[f32], r: i32) -> Vec<f32> {
    let pass = |plane: &[f32], horizontal: bool| -> Vec<f32> {
        (0..N)
            .map(|i| {
                let x = (i % W as usize) as i32;
                let y = (i / W as usize) as i32;
                let (mut s, mut n) = (0.0, 0.0);
                for k in -r..=r {
                    let (xx, yy) = if horizontal { (x + k, y) } else { (x, y + k) };
                    if xx >= 0 && xx < W && yy >= 0 && yy < H {
                        s += plane[(yy * W + xx) as usize];
                        n += 1.0;
                    }
                }
                s / n
            })
            .collect()
    };
    pass(&pass(plane, true), false)
}

/// PhotoAdjustMath.edgeCode: Sobel of the small plane, direction + magnitude.
fn edge_code(small: &[f32], r: i32, x: i32, y: i32) -> f32 {
    let at = |xx: i32, yy: i32| small[(yy.clamp(0, H - 1) * W + xx.clamp(0, W - 1)) as usize];
    let gx = (at(x + 1, y - 1) + 2.0 * at(x + 1, y) + at(x + 1, y + 1))
        - (at(x - 1, y - 1) + 2.0 * at(x - 1, y) + at(x - 1, y + 1));
    let gy = (at(x - 1, y + 1) + 2.0 * at(x, y + 1) + at(x + 1, y + 1))
        - (at(x - 1, y - 1) + 2.0 * at(x, y - 1) + at(x + 1, y - 1));
    let mag = ((gx * gx + gy * gy).sqrt() * (2 * r + 1) as f32 / 8.0).min(0.999);
    let (ax, ay) = (gx.abs(), gy.abs());
    let dir = if ay <= ax * 0.41421356 {
        0.0
    } else if ax <= ay * 0.41421356 {
        2.0
    } else if (gx > 0.0) == (gy > 0.0) {
        1.0
    } else {
        3.0
    };
    dir + mag
}

/// PhotoAdjustMath.cannyOutline.
fn canny(edges: &[f32], high: f32) -> Vec<f32> {
    let mag = |x: i32, y: i32| {
        let e = edges[(y.clamp(0, H - 1) * W + x.clamp(0, W - 1)) as usize];
        e - e.floor()
    };
    let low = high * 0.5;
    (0..N)
        .map(|i| {
            let x = (i % W as usize) as i32;
            let y = (i / W as usize) as i32;
            let m = mag(x, y);
            let (ox, oy) = match edges[i].floor() as i32 {
                1 => (1, 1),
                2 => (0, 1),
                3 => (-1, 1),
                _ => (1, 0),
            };
            if m < low || m <= 0.0 || m < mag(x + ox, y + oy) || m < mag(x - ox, y - oy) {
                return 0.0;
            }
            if m >= high {
                return 1.0;
            }
            for dy in -1..=1 {
                for dx in -1..=1 {
                    if (dx != 0 || dy != 0) && mag(x + dx, y + dy) >= high {
                        return 1.0;
                    }
                }
            }
            0.0
        })
        .collect()
}

type Planes = (Vec<f32>, Vec<f32>, Vec<f32>, Vec<f32>);

fn effect_planes(src: &[u8], p: &[f32]) -> Planes {
    let long = W.max(H);
    let small_r = (long / 400).clamp(1, 8);
    let lum = src_luma(src);
    let small = box_blur(&lum, small_r);
    let large = box_blur(&lum, (long / 40).clamp(4, 64));
    let edges: Vec<f32> =
        (0..N).map(|i| edge_code(&small, small_r, (i % W as usize) as i32, (i / W as usize) as i32)).collect();
    let lines = canny(&edges, p[57]);
    (lum, small, large, lines)
}

fn max_diff(a: &[u8], b: &[u8]) -> (u8, usize) {
    let mut worst = (0u8, 0usize);
    for (i, (x, y)) in a.iter().zip(b).enumerate() {
        let d = x.abs_diff(*y);
        if d > worst.0 {
            worst = (d, i);
        }
    }
    worst
}

fn check(name: &str, case: &str, a: &mut Adjuster, p: &[f32], curves: &[f32], mask: Option<&[u8]>) {
    let src = source();
    assert!(a.set_source(&src));
    assert!(a.set_curves(curves));
    if let Some(m) = mask {
        assert!(a.set_mask(m));
    }
    let mut out = vec![0u8; N * 4];
    assert!(a.render(p, &mut out), "{name} {case}: render failed");
    let want = reference(p, curves, mask, &src);
    let (d, at) = max_diff(&out, &want);
    // One or two levels: pow/exp2 differ slightly between drivers and the CPU.
    assert!(d <= 2, "{name} {case}: byte {at} differs by {d} (gpu {} cpu {})", out[at], want[at]);
}

#[test]
fn neutral_parameters_leave_the_pixels_alone() {
    for (name, mut a) in adjusters() {
        let src = source();
        a.set_source(&src);
        let mut out = vec![0u8; N * 4];
        assert!(a.render(&neutral(), &mut out));
        let (d, at) = max_diff(&out, &src);
        assert!(d <= 1, "{name}: byte {at} moved by {d}");
    }
}

#[test]
fn light_and_color_match_the_cpu_reference() {
    for (name, mut a) in adjusters() {
        let curves = identity_curves();
        let mut p = neutral();
        p[0] = 0.35; // exposure
        p[1] = 0.12; // contrast
        p[2] = -0.38; // highlights
        p[3] = 0.41; // shadows
        p[4] = 0.08; // whites
        p[5] = -0.14; // blacks
        check(name, "light", &mut a, &p, &curves, None);
        let mut p = neutral();
        p[8] = 1.08;
        p[9] = 0.98;
        p[10] = 0.9;
        p[6] = 0.18; // vibrance
        p[7] = -0.06; // saturation
        check(name, "color", &mut a, &p, &curves, None);
        let mut p = neutral();
        p[0] = -1.5;
        p[1] = -0.8;
        p[7] = 1.0;
        check(name, "extremes", &mut a, &p, &curves, None);
    }
}

#[test]
fn hsl_curves_and_levels_match_the_cpu_reference() {
    for (name, mut a) in adjusters() {
        let mut p = neutral();
        // Orange: hue +6, saturation +24, luminance -12 (the mockup's values).
        p[32 + 3] = 0.2; // +6 of the slider's ±30 degrees
        p[33 + 3] = 0.24;
        p[34 + 3] = -0.12;
        p[32 + 15] = -0.5; // blue hue
        check(name, "hsl", &mut a, &p, &identity_curves(), None);
        let mut curves = identity_curves();
        for i in 0..256 {
            let x = i as f32 / 255.0;
            curves[i] = x.powf(0.8); // master lifts the mids
            curves[512 + i] = (x * 0.9).min(1.0); // green pulled down
        }
        let mut p = neutral();
        p[15] = 1.0;
        p[12] = 12.0 / 255.0;
        p[13] = 1.1;
        p[14] = 241.0 / 255.0;
        check(name, "curves+levels", &mut a, &p, &curves, None);
    }
}

#[test]
fn the_mask_scales_the_layer_and_inverts() {
    for (name, mut a) in adjusters() {
        let mask: Vec<u8> = (0..N).map(|i| ((i * 7) % 256) as u8).collect();
        let mut p = neutral();
        p[0] = 1.0;
        p[11] = 1.0;
        check(name, "mask", &mut a, &p, &identity_curves(), Some(&mask));
        p[30] = 1.0;
        check(name, "inverted mask", &mut a, &p, &identity_curves(), Some(&mask));
    }
}

#[test]
fn the_detail_blur_is_a_box_blur_of_the_luma() {
    for (name, mut a) in adjusters() {
        let src = source();
        a.set_source(&src);
        let mut p = neutral();
        p[29] = 1.0;
        let mut out = vec![0u8; N * 4];
        assert!(a.render(&p, &mut out));
        let (small, _) = a.blur_radii();
        let blur = a.read_blur().expect("blur readback");
        let lum: Vec<f32> = (0..N)
            .map(|i| {
                let al = src[i * 4 + 3] as f32 / 255.0;
                if al <= 0.0 {
                    0.0
                } else {
                    let c: Vec<f32> = (0..3).map(|k| src[i * 4 + k] as f32 / 255.0 / al).collect();
                    luma([c[0], c[1], c[2]])
                }
            })
            .collect();
        let r = small as i32;
        let blur1 = |plane: &[f32], horizontal: bool| -> Vec<f32> {
            (0..N)
                .map(|i| {
                    let x = (i % W as usize) as i32;
                    let y = (i / W as usize) as i32;
                    let (mut s, mut n) = (0.0, 0.0);
                    for k in -r..=r {
                        let (xx, yy) = if horizontal { (x + k, y) } else { (x, y + k) };
                        if xx >= 0 && xx < W && yy >= 0 && yy < H {
                            s += plane[(yy * W + xx) as usize];
                            n += 1.0;
                        }
                    }
                    s / n
                })
                .collect()
        };
        let want = blur1(&blur1(&lum, true), false);
        for i in 0..N {
            assert!((blur[i] - want[i]).abs() < 1e-4, "{name}: blur {i} {} vs {}", blur[i], want[i]);
        }
        assert_eq!(CURVE_FLOATS, 1024);
    }
}

#[test]
fn a_render_reports_its_time() {
    for (_, mut a) in adjusters() {
        a.set_source(&source());
        let mut out = vec![0u8; N * 4];
        assert!(a.render(&neutral(), &mut out));
        assert!(a.last_nanos() > 0);
    }
}

/// A disc on a ramp: strong edges at its rim, a soft gradient elsewhere.
fn disc_source() -> Vec<u8> {
    (0..N)
        .flat_map(|i| {
            let x = (i % W as usize) as f32;
            let y = (i / W as usize) as f32;
            let d = ((x - 20.0).powi(2) + (y - 15.0).powi(2)).sqrt();
            let v = if d < 9.0 { 0.9 } else { 0.15 + 0.2 * x / W as f32 };
            let q = (v * 255.0).round() as u8;
            [q, (q as f32 * 0.8) as u8, q / 2, 255]
        })
        .collect()
}

fn check_effects(name: &str, case: &str, a: &mut Adjuster, p: &[f32]) {
    let src = disc_source();
    let curves = identity_curves();
    assert!(a.set_source(&src));
    assert!(a.set_curves(&curves));
    let mut out = vec![0u8; N * 4];
    assert!(a.render(p, &mut out), "{name} {case}: render failed");
    let want = reference(p, &curves, None, &src);
    // Lines are all-or-nothing: count the pixels that differ by more than rounding.
    let wrong = out.iter().zip(&want).filter(|(x, y)| x.abs_diff(**y) > 3).count();
    assert!(wrong <= N / 200, "{name} {case}: {wrong} bytes differ");
}

#[test]
fn outline_and_sketch_match_the_cpu_reference() {
    for (name, mut a) in adjusters() {
        let mut p = neutral();
        p[62] = 1.0;
        p[56] = 1.0; // outline amount
        p[57] = 0.2; // threshold
        p[58] = 0.6; // paper
        check_effects(name, "outline", &mut a, &p);
        a.use_edge_plane(false);
        check_effects(name, "outline inline", &mut a, &p);
        a.use_edge_plane(true);
        let mut p = neutral();
        p[62] = 1.0;
        p[59] = 0.8; // sketch
        p[60] = 0.3;
        p[61] = 0.25;
        check_effects(name, "sketch", &mut a, &p);
    }
}

#[test]
fn the_outline_draws_the_rim_of_a_disc() {
    for (name, mut a) in adjusters() {
        let mut p = neutral();
        p[62] = 1.0;
        p[56] = 1.0;
        p[57] = 0.2;
        p[58] = 1.0; // white paper: only the lines stay dark
        a.set_source(&disc_source());
        let mut out = vec![0u8; N * 4];
        assert!(a.render(&p, &mut out));
        let dark = |x: usize, y: usize| out[(y * W as usize + x) * 4] < 64;
        // On the rim (radius 9 from (20, 15)), not at the centre or a corner.
        assert!((0..4).any(|k| dark(20 - 9 + k, 15) || dark(20 + 9 - k, 15)), "{name}: no line on the rim");
        assert!(!dark(20, 15), "{name}: line at the centre");
        assert!(!dark(1, 1), "{name}: line in the corner");
    }
}

#[test]
fn a_vignette_at_the_midpoint_maximum_is_defined() {
    // Audit round 2, #7: midpoint 100 used to evaluate smoothstep(1, 1, r), undefined in WGSL.
    // Every pixel centre has r < 1, so the vignette must leave the image untouched.
    for (name, mut a) in adjusters() {
        let src = source();
        assert!(a.set_source(&src));
        for feather in [0.0f32, 0.5, 1.0] {
            let mut p = neutral();
            p[20] = -1.0; // vignette amount: darken
            p[21] = 1.0; // midpoint at its maximum
            p[22] = feather;
            let mut out = vec![0u8; N * 4];
            assert!(a.render(&p, &mut out), "{name}: render failed");
            let (d, at) = max_diff(&out, &src);
            assert!(d <= 1, "{name} feather {feather}: byte {at} moved by {d}");
        }
    }
}
