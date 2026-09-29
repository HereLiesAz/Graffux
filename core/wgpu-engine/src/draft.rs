//! Draft-pass geometry, shared by the engine and its tests. Pure math, no wgpu.
//!
//! The draft is a low-resolution rendering of the *same* stamp (same tip, mask, orientation,
//! colour, grain), not a stand-in shape. What differs:
//!
//! * it is rendered into a buffer at `1 / scale` of the layer's resolution and upsampled
//!   bilinearly for display;
//! * the tip mask is sampled from a lower mip level;
//! * its soft falloff is *trimmed*: the visible edge sits a fraction `f` of the way out through the
//!   feather zone (see [`FeatherEdge`]), cut with the real falloff function rather than a disc. The
//!   display then reveals the feather outward toward its full width as the final result
//!   approaches (`progress.rs`).
//!
//! # The feather rule
//!
//! For a round tip, `stamp.wgsl`'s coverage at normalized distance `t = dist / radius` is
//!
//! ~~~text
//! coverage(t) = 1                          t <= h
//!             = 1 - (t - h) / (1 - h)      h < t < 1       (h < 0.999)
//!             = 1 - (t - h) / 0.001        h < t           (h >= 0.999, i.e. a hard tip)
//!             = 0                          t >= 1
//! ~~~
//!
//! so the full-opacity core ends at `core = h` and the feather reaches zero at `outer = 1` (or
//! `h + 0.001` for a hard tip). The draft's edge is
//!
//! ~~~text
//! edge = core + f * (outer - core),   f in [1/3, 1/2], default 0.4
//! ~~~
//!
//! applied per axis for elliptical tips. Inside the edge the draft keeps the real coverage profile.
//! Because the falloff is linear in the feather, coverage at the edge is `1 - f`; for masked tips
//! (whose falloff lives in the mask image) the same fraction is the mask threshold `1 - f`.

use crate::engine::GpuDab;

pub const EDGE_FRACTION_MIN: f32 = 1.0 / 3.0;
pub const EDGE_FRACTION_MAX: f32 = 0.5;
pub const EDGE_FRACTION_DEFAULT: f32 = 0.4;

pub fn clamp_edge_fraction(f: f32) -> f32 {
    if f.is_finite() {
        f.clamp(EDGE_FRACTION_MIN, EDGE_FRACTION_MAX)
    } else {
        EDGE_FRACTION_DEFAULT
    }
}

/// `stamp.wgsl`'s `stamp_coverage`, in Rust (the draft must use the same function).
pub fn stamp_coverage(dist: f32, radius: f32, hardness: f32) -> f32 {
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

/// Where a round tip of hardness `h` stops being fully opaque and where it reaches zero, as
/// fractions of its radius, and the draft's trimmed edge between them.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct FeatherEdge {
    pub core: f32,
    pub outer: f32,
    pub edge: f32,
}

impl FeatherEdge {
    pub fn for_hardness(hardness: f32, f: f32) -> FeatherEdge {
        let h = hardness.clamp(0.0, 1.0);
        let outer = if h >= 0.999 { (h + 0.001).min(1.0) } else { 1.0 };
        let core = h.min(outer);
        let f = clamp_edge_fraction(f);
        FeatherEdge {
            core,
            outer,
            edge: core + f * (outer - core),
        }
    }

    /// Normalized position inside the feather: 0 in the core, 1 at the outer edge.
    pub fn position(&self, t: f32) -> f32 {
        if t <= self.core {
            0.0
        } else if self.outer <= self.core {
            1.0
        } else {
            ((t - self.core) / (self.outer - self.core)).clamp(0.0, 1.0)
        }
    }
}

/// The draft's radius for a round dab: `radius * edge`.
pub fn coarse_radius(radius: f32, hardness: f32, f: f32) -> f32 {
    radius * FeatherEdge::for_hardness(hardness, f).edge
}

/// How much of the feather trim applies to a round tip of this hardness (`draft_stamp.wgsl`
/// `trim_weight`): the stored feather key is multiplied by it. 0 for soft tips (hardness <= 0.5), so
/// the draft shows the whole real falloff; 1 for hard ones (>= 0.9), where the trim only hides the
/// thin rim that low resolution would blur. Trimming a soft falloff at `f` cuts through paint that
/// still carries most of the dab's alpha and turns the draft into a flat, hard-edged disc, which
/// the draft must never be.
pub fn trim_weight(hardness: f32) -> f32 {
    let t = ((hardness.clamp(0.0, 1.0) - 0.5) / 0.4).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

/// Smallest draft radius, in layer pixels, at draft `scale`: a dab smaller than one draft texel
/// would fall between texel centers and vanish, so it is widened to this and its alpha lowered by
/// the area ratio (bristle-size dabs stay present, with the same total paint).
pub fn min_draft_radius(scale: u32) -> f32 {
    0.75 * scale as f32
}

/// A dab as the draft shaders take it: always "resolved" (its own colour, alpha, flow and
/// hardness), widened to [`min_draft_radius`] with its alpha scaled down to keep the paint total.
/// `tip_ratio_slot` carries hardness for round tips and the tip ratio for masked ones.
pub fn draft_dab(d: &GpuDab, call_color_argb: u32, call_hardness: f32, scale: u32, masked: bool) -> GpuDab {
    let mut out = *d;
    if d.resolved < 0.5 {
        out.color_r = ((call_color_argb >> 16) & 0xFF) as f32 / 255.0;
        out.color_g = ((call_color_argb >> 8) & 0xFF) as f32 / 255.0;
        out.color_b = (call_color_argb & 0xFF) as f32 / 255.0;
        out.color_a = ((call_color_argb >> 24) & 0xFF) as f32 / 255.0;
        out.flow = 1.0;
        out.resolved = 1.0;
        if !masked {
            out.tip_ratio = call_hardness;
        }
    }
    let r = d.radius.max(0.5);
    let min_r = min_draft_radius(scale);
    if r < min_r {
        let ratio = r / min_r;
        out.radius = min_r;
        out.alpha = d.alpha * ratio * ratio;
    }
    out
}

/// Box-filtered mip chain of an R8 image, level 0 first, down to 1x1.
pub fn mip_chain(pixels: &[u8], width: u32, height: u32) -> Vec<(Vec<u8>, u32, u32)> {
    let mut out = vec![(pixels[..(width * height) as usize].to_vec(), width, height)];
    loop {
        let (prev, w, h) = out.last().unwrap();
        if *w <= 1 && *h <= 1 {
            break;
        }
        let (nw, nh) = ((*w / 2).max(1), (*h / 2).max(1));
        let mut next = vec![0u8; (nw * nh) as usize];
        for y in 0..nh {
            for x in 0..nw {
                let mut sum = 0u32;
                let mut n = 0u32;
                for (dx, dy) in [(0, 0), (1, 0), (0, 1), (1, 1)] {
                    let sx = (x * 2 + dx).min(w - 1);
                    let sy = (y * 2 + dy).min(h - 1);
                    sum += prev[(sy * w + sx) as usize] as u32;
                    n += 1;
                }
                next[(y * nw + x) as usize] = ((sum + n / 2) / n) as u8;
            }
        }
        out.push((next, nw, nh));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn soft_tips_are_never_trimmed_and_hard_tips_fully() {
        for h in [0.0, 0.05, 0.2, 0.35, 0.5] {
            assert_eq!(trim_weight(h), 0.0, "hardness {h}");
        }
        assert_eq!(trim_weight(0.9), 1.0);
        assert_eq!(trim_weight(1.0), 1.0);
        let mut prev = 0.0;
        for i in 0..=100 {
            let w = trim_weight(i as f32 / 100.0);
            assert!(w >= prev);
            prev = w;
        }
    }

    /// Core and outer found numerically from `stamp_coverage` itself, then the trim rule checked.
    #[test]
    fn coarse_radius_sits_one_third_to_one_half_out_through_the_feather() {
        let radius = 100.0;
        for i in 0..=40 {
            let h = i as f32 / 40.0;
            // Scan the real falloff in 1/10000ths of the radius.
            let mut core = 0.0f32;
            let mut outer = radius;
            let mut found_outer = false;
            for s in 0..=10_000 {
                let d = radius * s as f32 / 10_000.0;
                let c = stamp_coverage(d, radius, h);
                if c >= 1.0 {
                    core = d;
                }
                if c <= 0.0 && !found_outer {
                    outer = d;
                    found_outer = true;
                }
            }
            let feather = outer - core;
            for f in [EDGE_FRACTION_MIN, EDGE_FRACTION_DEFAULT, EDGE_FRACTION_MAX, 0.0, 0.9] {
                let rc = coarse_radius(radius, h, f);
                let tol = radius / 10_000.0 * 2.0;
                assert!(
                    rc >= core + feather / 3.0 - tol && rc <= core + feather / 2.0 + tol,
                    "h={h} f={f}: coarse {rc} outside [{} , {}]",
                    core + feather / 3.0,
                    core + feather / 2.0
                );
            }
            // The default sits at 0.4 of the feather.
            let rc = coarse_radius(radius, h, EDGE_FRACTION_DEFAULT);
            assert!((rc - (core + 0.4 * feather)).abs() <= radius / 5000.0, "h={h}");
        }
    }

    #[test]
    fn coverage_at_the_trimmed_edge_is_one_minus_f() {
        for h in [0.0, 0.25, 0.5, 0.8] {
            let e = FeatherEdge::for_hardness(h, 0.4);
            let c = stamp_coverage(e.edge * 50.0, 50.0, h);
            assert!((c - 0.6).abs() < 1e-4, "h={h}: {c}");
            assert!((e.position(e.edge) - 0.4).abs() < 1e-6);
        }
    }

    #[test]
    fn tiny_dabs_are_widened_with_the_same_paint() {
        let d = GpuDab::legacy(10.0, 10.0, 0.5, 0.8, 0.0);
        let out = draft_dab(&d, 0xFF102030, 0.5, 4, false);
        assert_eq!(out.radius, 3.0);
        let area_in = 0.5f32 * 0.5 * 0.8;
        let area_out = out.radius * out.radius * out.alpha;
        assert!((area_in - area_out).abs() < 1e-6);
        assert_eq!(out.resolved, 1.0);
        assert!((out.tip_ratio - 0.5).abs() < 1e-6);
        assert!((out.color_a - 1.0).abs() < 1e-6);
    }

    #[test]
    fn mip_chain_halves_to_one_pixel() {
        let img: Vec<u8> = (0..64).map(|i| (i * 4) as u8).collect();
        let chain = mip_chain(&img, 8, 8);
        assert_eq!(chain.iter().map(|c| (c.1, c.2)).collect::<Vec<_>>(), vec![(8, 8), (4, 4), (2, 2), (1, 1)]);
        let avg: u32 = img.iter().map(|&v| v as u32).sum::<u32>() / 64;
        assert!((chain[3].0[0] as i32 - avg as i32).abs() <= 2);
    }
}
