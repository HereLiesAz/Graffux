//! Scalar CPU reference of stamp.comp (round tips: max-combine, build-up, stroke-max), used by the
//! unit tests to check the WGSL port against the GLSL math independently of any GPU backend.
//! Substrate deposition is omitted (covered by the cross-engine diff in tools/stamp-engine-diff).

use crate::engine::GpuDab;

fn unpack(px: u32) -> [f32; 4] {
    let b = px.to_le_bytes();
    [
        b[0] as f32 / 255.0,
        b[1] as f32 / 255.0,
        b[2] as f32 / 255.0,
        b[3] as f32 / 255.0,
    ]
}

fn pack(c: [f32; 4]) -> u32 {
    // pack4x8unorm: round(clamp(c, 0, 1) * 255)
    let q = |v: f32| (v.clamp(0.0, 1.0) * 255.0).round_ties_even() as u8;
    u32::from_le_bytes([q(c[0]), q(c[1]), q(c[2]), q(c[3])])
}

fn q8(c: [f32; 4]) -> [f32; 4] {
    c.map(|v| (v * 255.0).round_ties_even() / 255.0)
}

fn coverage(dist: f32, radius: f32, hardness: f32) -> f32 {
    if radius <= 0.0 {
        return 0.0;
    }
    let t = dist / radius;
    if t <= hardness {
        return 1.0;
    }
    if t >= 1.0 {
        return 0.0;
    }
    if hardness >= 0.999 {
        return 1.0 - (t - hardness) / 0.001;
    }
    1.0 - (t - hardness) / (1.0 - hardness)
}

fn over(src_rgb: [f32; 3], src_a: f32, dst: [f32; 4]) -> [f32; 4] {
    q8([
        src_rgb[0] * src_a + dst[0] * (1.0 - src_a),
        src_rgb[1] * src_a + dst[1] * (1.0 - src_a),
        src_rgb[2] * src_a + dst[2] * (1.0 - src_a),
        src_a + dst[3] * (1.0 - src_a),
    ])
}

/// Per-stroke state for [`stamp`]'s stroke-max mode: (pre-stroke base, best alpha) per pixel.
pub struct StrokeState(pub Vec<Option<(u32, f32)>>);

/// Applies one `stampDabs` call to `layer` (packed RGBA8 words, `width` wide) on the CPU.
pub fn stamp(
    layer: &mut [u32],
    width: usize,
    dabs: &[GpuDab],
    color_argb: u32,
    hardness: f32,
    build_up: bool,
    stroke: Option<&mut StrokeState>,
) {
    let height = layer.len() / width;
    let pc_rgb = [
        ((color_argb >> 16) & 0xFF) as f32 / 255.0,
        ((color_argb >> 8) & 0xFF) as f32 / 255.0,
        (color_argb & 0xFF) as f32 / 255.0,
    ];
    let pc_a = ((color_argb >> 24) & 0xFF) as f32 / 255.0;
    let mut stroke = stroke;
    for py in 0..height {
        for px in 0..width {
            let idx = py * width + px;
            let mut dst = unpack(layer[idx]);
            let p = (px as f32 + 0.5, py as f32 + 0.5);
            let mut best_a = 0.0f32;
            let mut best_rgb = [0.0f32; 3];
            for d in dabs {
                let radius = d.radius.max(0.5);
                if (p.0 - d.x).abs() > radius || (p.1 - d.y).abs() > radius {
                    continue;
                }
                let resolved = d.resolved >= 0.5;
                let h = if resolved { d.tip_ratio } else { hardness };
                let dist = ((p.0 - d.x).powi(2) + (p.1 - d.y).powi(2)).sqrt();
                let c = coverage(dist, radius, h);
                if c <= 0.0 {
                    continue;
                }
                let rgb = if resolved {
                    [d.color_r, d.color_g, d.color_b]
                } else {
                    pc_rgb
                };
                let base_a = if resolved { d.color_a } else { pc_a };
                let flow = if resolved { d.flow.max(0.0) } else { 1.0 };
                let a = base_a * d.alpha * flow * c;
                if a <= 0.0 {
                    continue;
                }
                if build_up {
                    dst = over(rgb, a, dst);
                } else if a > best_a {
                    best_a = a;
                    best_rgb = rgb;
                }
            }
            if !build_up {
                if let Some(state) = stroke.as_deref_mut() {
                    if best_a <= 0.0 {
                        continue;
                    }
                    let (base, prev) = match state.0[idx] {
                        Some((b, a)) => (unpack(b), a),
                        None => (dst, 0.0),
                    };
                    if best_a <= prev {
                        continue;
                    }
                    state.0[idx] = Some((pack(base), best_a));
                    layer[idx] = pack(over(best_rgb, best_a, base));
                    continue;
                }
                if best_a > 0.0 {
                    dst = over(best_rgb, best_a, dst);
                }
            }
            layer[idx] = pack(dst);
        }
    }
}
