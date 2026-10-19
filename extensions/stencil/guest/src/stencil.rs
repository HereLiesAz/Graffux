//! The stencil algorithm: a design in, three tonal sheets out. Pure Rust, no host calls, so it is
//! unit-tested natively and the WASM entry point in `lib.rs` is a thin shell around it.
//!
//! It is GraffitiXR's stencil tool without OpenCV. The value channel is simplified with a 9x9 median
//! blur and clustered into three tones; whether the image is dark or light (by area, after dimming
//! it and pushing the contrast) picks the BASE sheet, which covers the whole subject: black for a
//! dark image, white for a light one. The other two sheets are laid over it, sequentially, as paint
//! is. Each sheet's edge is closed 5x5 so the cuts are smooth. Transparent pixels stay transparent.
//!
//! Work is done at no more than [`WORK_DIM`] pixels on the long side, because the sandbox that runs
//! this is an interpreter with a hard time limit. The WASM shell asks the host for the layer already
//! at that size and the host scales the result back up, so `stencil` itself only resamples when it
//! is handed something larger (native use and tests).

pub const BLACK: u8 = 0;
pub const GREY: u8 = 1;
pub const WHITE: u8 = 2;
const NONE: u8 = 255;

/// Longest side, in pixels, that the heavy filters run at.
pub const WORK_DIM: usize = 256;

const MEDIAN_RADIUS: usize = 4; // 9x9
const CLOSE_RADIUS: usize = 2; // 5x5

/// Brightness taken off before the contrast push, in luminance levels (0..255).
const DARK_CALL_BRIGHTNESS: f64 = -40.0;
/// Contrast gain about mid-grey for the dark-or-light call.
const DARK_CALL_CONTRAST: f64 = 2.0;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum View {
    All,
    Base,
    Midtone,
    Secondary,
}

impl View {
    pub fn parse(s: &str) -> View {
        match s {
            "base" => View::Base,
            "midtone" => View::Midtone,
            "secondary" => View::Secondary,
            _ => View::All,
        }
    }
}

/// Which colour is the base sheet. `Auto` lets the image decide.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BaseChoice {
    Auto,
    Dark,
    Light,
}

impl BaseChoice {
    pub fn parse(s: &str) -> BaseChoice {
        match s {
            "dark" => BaseChoice::Dark,
            "light" => BaseChoice::Light,
            _ => BaseChoice::Auto,
        }
    }
}

/// Two thresholds splitting a 256-bin value histogram into three tones: 1-D K-means, K = 3, started
/// at the 1/6, 3/6, 5/6 quantiles so the same image always gives the same split. A value is black up
/// to and including the first threshold, grey up to and including the second, white above. `None`
/// when the histogram is empty.
pub fn thresholds(histogram: &[u32; 256]) -> Option<(i32, i32)> {
    let total: u64 = histogram.iter().map(|&n| n as u64).sum();
    if total == 0 {
        return None;
    }
    let quantile = |fraction: f64| -> f64 {
        let target = fraction * total as f64;
        let mut seen: u64 = 0;
        for (v, &n) in histogram.iter().enumerate() {
            seen += n as u64;
            if seen as f64 >= target {
                return v as f64;
            }
        }
        255.0
    };
    let mut centres = [quantile(1.0 / 6.0), quantile(3.0 / 6.0), quantile(5.0 / 6.0)];
    for _ in 0..50 {
        let mut sum = [0.0f64; 3];
        let mut count = [0u64; 3];
        for (v, &n) in histogram.iter().enumerate() {
            if n == 0 {
                continue;
            }
            let mut best = 0;
            for k in 1..3 {
                if (v as f64 - centres[k]).abs() < (v as f64 - centres[best]).abs() {
                    best = k;
                }
            }
            sum[best] += v as f64 * n as f64;
            count[best] += n as u64;
        }
        let mut moved = false;
        for k in 0..3 {
            if count[k] == 0 {
                continue;
            }
            let next = sum[k] / count[k] as f64;
            if (next - centres[k]).abs() > 1e-6 {
                moved = true;
            }
            centres[k] = next;
        }
        if !moved {
            break;
        }
    }
    centres.sort_by(|a, b| a.total_cmp(b));
    // Boundaries sit halfway between neighbouring centres, clamped so the thresholds stay ordered.
    let lo = (((centres[0] + centres[1]) / 2.0) as i32).clamp(0, 254);
    let hi = (((centres[1] + centres[2]) / 2.0) as i32).clamp(lo + 1, 255);
    Some((lo, hi))
}

/// The dark-or-light call: the subject's luminance is dimmed and its contrast pushed up about
/// mid-grey, which leaves each pixel clearly dark or clearly light, and the call goes to whichever
/// covers more area. A tie, and an empty subject, are dark.
pub fn is_dark_by_area(luma_histogram: &[u32; 256]) -> bool {
    let mut dark: u64 = 0;
    let mut light: u64 = 0;
    for (level, &n) in luma_histogram.iter().enumerate() {
        if n == 0 {
            continue;
        }
        let adjusted = (level as f64 + DARK_CALL_BRIGHTNESS - 128.0) * DARK_CALL_CONTRAST + 128.0;
        if adjusted >= 128.0 {
            light += n as u64;
        } else {
            dark += n as u64;
        }
    }
    dark >= light
}

/// Luminance of an RGB pixel in 0..1, with the weights the old stencil tool used.
pub fn luma(r: u8, g: u8, b: u8) -> f64 {
    (0.299 * r as f64 + 0.587 * g as f64 + 0.114 * b as f64) / 255.0
}

/// A tone for every pixel from the three masks. The BASE is the complete silhouette: every pixel
/// of `silhouette` gets at least the base tone and none outside it is covered; a silhouette pixel
/// that was not `observed` (a hole in the cut-out, filled in) takes the base tone and nothing else.
/// The MIDTONE covers at least the secondary: a pixel on `top` is also in the midtone.
pub fn ranks(
    silhouette: &[bool],
    observed: &[bool],
    middle: &[bool],
    top: &[bool],
    dark: bool,
) -> Vec<u8> {
    let base = if dark { BLACK } else { WHITE };
    let secondary = if dark { WHITE } else { BLACK };
    (0..silhouette.len())
        .map(|i| {
            if !silhouette[i] {
                NONE
            } else if !observed[i] {
                base
            } else if top[i] {
                secondary
            } else if middle[i] {
                GREY
            } else {
                base
            }
        })
        .collect()
}

fn pixel(rank: u8, view: View, dark: bool) -> [u8; 4] {
    const BLACK_PX: [u8; 4] = [0, 0, 0, 255];
    const GREY_PX: [u8; 4] = [128, 128, 128, 255];
    const WHITE_PX: [u8; 4] = [255, 255, 255, 255];
    const CLEAR: [u8; 4] = [0, 0, 0, 0];
    if rank == NONE {
        return CLEAR;
    }
    let base_px = if dark { BLACK_PX } else { WHITE_PX };
    let secondary_px = if dark { WHITE_PX } else { BLACK_PX };
    let secondary_rank = if dark { WHITE } else { BLACK };
    match view {
        View::All => match rank {
            BLACK => BLACK_PX,
            GREY => GREY_PX,
            _ => WHITE_PX,
        },
        View::Base => base_px,
        View::Midtone => {
            let in_midtone = if dark { rank >= GREY } else { rank <= GREY };
            if in_midtone {
                GREY_PX
            } else {
                CLEAR
            }
        }
        View::Secondary => {
            if rank == secondary_rank {
                secondary_px
            } else {
                CLEAR
            }
        }
    }
}

/// A single-channel image.
struct Plane {
    w: usize,
    h: usize,
    v: Vec<u8>,
}

/// 9x9 median with replicated borders. A column histogram slides along each row, with a 16-bin
/// coarse histogram on top so finding the median never scans more than 32 bins.
fn median_blur(src: &Plane) -> Plane {
    let (w, h) = (src.w, src.h);
    let r = MEDIAN_RADIUS as isize;
    let kth = ((2 * MEDIAN_RADIUS + 1).pow(2) / 2 + 1) as u32; // 41st smallest of 81
    let mut out = vec![0u8; w * h];
    let clamp = |i: isize, n: usize| i.clamp(0, n as isize - 1) as usize;
    let mut fine = [0u32; 256];
    let mut coarse = [0u32; 16];
    for y in 0..h {
        fine.fill(0);
        coarse.fill(0);
        let rows: Vec<usize> = (-r..=r).map(|dy| clamp(y as isize + dy, h)).collect();
        let add = |fine: &mut [u32; 256], coarse: &mut [u32; 16], x: usize, sign: i32| {
            for &ry in &rows {
                let value = src.v[ry * w + x] as usize;
                if sign > 0 {
                    fine[value] += 1;
                    coarse[value >> 4] += 1;
                } else {
                    fine[value] -= 1;
                    coarse[value >> 4] -= 1;
                }
            }
        };
        for dx in -r..=r {
            add(&mut fine, &mut coarse, clamp(dx, w), 1);
        }
        for x in 0..w {
            if x > 0 {
                add(&mut fine, &mut coarse, clamp(x as isize - r - 1, w), -1);
                add(&mut fine, &mut coarse, clamp(x as isize + r, w), 1);
            }
            let mut seen = 0u32;
            let mut c = 0usize;
            while seen + coarse[c] < kth {
                seen += coarse[c];
                c += 1;
            }
            let mut v = c << 4;
            while seen + fine[v] < kth {
                seen += fine[v];
                v += 1;
            }
            out[y * w + x] = v as u8;
        }
    }
    Plane { w, h, v: out }
}

/// Morphological closing (dilate, then erode) with a (2r+1) square, borders ignored.
fn close(mask: &[bool], w: usize, h: usize) -> Vec<bool> {
    let dilated = rect_filter(mask, w, h, true);
    rect_filter(&dilated, w, h, false)
}

/// Separable square max (`dilate`) or min filter; pixels outside the image do not count.
fn rect_filter(src: &[bool], w: usize, h: usize, dilate: bool) -> Vec<bool> {
    let r = CLOSE_RADIUS;
    let mut tmp = vec![false; w * h];
    for y in 0..h {
        let row = &src[y * w..(y + 1) * w];
        for x in 0..w {
            let lo = x.saturating_sub(r);
            let hi = (x + r).min(w - 1);
            let window = &row[lo..=hi];
            tmp[y * w + x] = if dilate { window.iter().any(|&b| b) } else { window.iter().all(|&b| b) };
        }
    }
    let mut out = vec![false; w * h];
    for x in 0..w {
        for y in 0..h {
            let lo = y.saturating_sub(r);
            let hi = (y + r).min(h - 1);
            let mut acc = !dilate;
            for yy in lo..=hi {
                let b = tmp[yy * w + x];
                if dilate {
                    acc |= b;
                } else {
                    acc &= b;
                }
            }
            out[y * w + x] = acc;
        }
    }
    out
}

/// `observed` (the pixels the cut-out kept) with its gaps closed and its enclosed holes filled: the
/// subject's complete silhouette. Holes are what a flood from the frame's edge cannot reach.
fn filled_silhouette(observed: &[bool], w: usize, h: usize) -> Vec<bool> {
    let closed = close(observed, w, h);
    // Flood the background from the border: the image sits in a one-pixel frame of background.
    let (pw, ph) = (w + 2, h + 2);
    let mut reached = vec![false; pw * ph];
    let is_bg = |px: usize, py: usize| -> bool {
        px == 0 || py == 0 || px == pw - 1 || py == ph - 1 || !closed[(py - 1) * w + (px - 1)]
    };
    let mut stack: Vec<u32> = vec![0];
    reached[0] = true;
    while let Some(idx) = stack.pop() {
        let (px, py) = ((idx as usize) % pw, (idx as usize) / pw);
        let mut visit = |nx: usize, ny: usize| {
            let n = ny * pw + nx;
            if !reached[n] && is_bg(nx, ny) {
                reached[n] = true;
                stack.push(n as u32);
            }
        };
        if px > 0 {
            visit(px - 1, py);
        }
        if px + 1 < pw {
            visit(px + 1, py);
        }
        if py > 0 {
            visit(px, py - 1);
        }
        if py + 1 < ph {
            visit(px, py + 1);
        }
    }
    (0..w * h)
        .map(|i| {
            let (x, y) = (i % w, i / w);
            // Not reached by the flood from outside = inside the silhouette.
            closed[i] || !reached[(y + 1) * pw + (x + 1)]
        })
        .collect()
}

/// `rgba` reduced to at most [`WORK_DIM`] on the long side: colour is the alpha-weighted mean of
/// each block and alpha is its maximum, so a block with any opaque pixel still counts as observed.
fn downscale(rgba: &[u8], w: usize, h: usize, ww: usize, wh: usize) -> Vec<u8> {
    let mut out = vec![0u8; ww * wh * 4];
    for wy in 0..wh {
        let (y0, y1) = (wy * h / wh, ((wy + 1) * h / wh).max(wy * h / wh + 1));
        for wx in 0..ww {
            let (x0, x1) = (wx * w / ww, ((wx + 1) * w / ww).max(wx * w / ww + 1));
            let (mut sr, mut sg, mut sb, mut sa, mut max_a) = (0u64, 0u64, 0u64, 0u64, 0u8);
            for y in y0..y1 {
                for x in x0..x1 {
                    let p = (y * w + x) * 4;
                    let a = rgba[p + 3];
                    sr += rgba[p] as u64 * a as u64;
                    sg += rgba[p + 1] as u64 * a as u64;
                    sb += rgba[p + 2] as u64 * a as u64;
                    sa += a as u64;
                    max_a = max_a.max(a);
                }
            }
            let o = (wy * ww + wx) * 4;
            if sa > 0 {
                out[o] = (sr / sa) as u8;
                out[o + 1] = (sg / sa) as u8;
                out[o + 2] = (sb / sa) as u8;
            }
            out[o + 3] = max_a;
        }
    }
    out
}

/// A mask at `(sw, sh)` sampled up to `(w, h)`: bilinear on the 0/1 values, then cut at one half,
/// which keeps the edge smooth rather than blocky.
fn upscale_mask(src: &[bool], sw: usize, sh: usize, w: usize, h: usize) -> Vec<bool> {
    if sw == w && sh == h {
        return src.to_vec();
    }
    let axis = |n: usize, sn: usize| -> Vec<(usize, usize, f32)> {
        (0..n)
            .map(|i| {
                let f = ((i as f32 + 0.5) * sn as f32 / n as f32 - 0.5).clamp(0.0, (sn - 1) as f32);
                let i0 = f.floor() as usize;
                (i0, (i0 + 1).min(sn - 1), f - i0 as f32)
            })
            .collect()
    };
    let xs = axis(w, sw);
    let ys = axis(h, sh);
    let mut out = vec![false; w * h];
    for (y, &(y0, y1, fy)) in ys.iter().enumerate() {
        for (x, &(x0, x1, fx)) in xs.iter().enumerate() {
            let at = |xx: usize, yy: usize| if src[yy * sw + xx] { 1.0f32 } else { 0.0 };
            let top = at(x0, y0) * (1.0 - fx) + at(x1, y0) * fx;
            let bottom = at(x0, y1) * (1.0 - fx) + at(x1, y1) * fx;
            out[y * w + x] = top * (1.0 - fy) + bottom * fy >= 0.5;
        }
    }
    out
}

/// What [`stencil`] produced: RGBA pixels the size of the input, and whether the image was judged
/// dark (black base) or light (white base).
pub struct StencilOutput {
    pub rgba: Vec<u8>,
    pub dark: bool,
}

/// Renders `rgba` (straight RGBA8, `w * h * 4` bytes) as `view`. `None` when there is nothing to
/// stencil: no opaque pixels, or an image smaller than 2x2.
pub fn stencil(rgba: &[u8], w: usize, h: usize, view: View, base: BaseChoice) -> Option<StencilOutput> {
    if w < 2 || h < 2 || rgba.len() != w * h * 4 {
        return None;
    }
    // Working size.
    let long = w.max(h);
    let (ww, wh, work) = if long <= WORK_DIM {
        (w, h, None)
    } else {
        let ww = ((w * WORK_DIM).div_ceil(long)).max(2);
        let wh = ((h * WORK_DIM).div_ceil(long)).max(2);
        (ww, wh, Some(downscale(rgba, w, h, ww, wh)))
    };
    let small: &[u8] = work.as_deref().unwrap_or(rgba);

    // V of HSV is the brightest channel; it is simplified with the median blur.
    let value = Plane {
        w: ww,
        h: wh,
        v: (0..ww * wh).map(|i| small[i * 4].max(small[i * 4 + 1]).max(small[i * 4 + 2])).collect(),
    };
    let value = median_blur(&value);
    let observed: Vec<bool> = (0..ww * wh).map(|i| small[i * 4 + 3] > 0).collect();

    let mut histogram = [0u32; 256];
    let mut luma_histogram = [0u32; 256];
    for i in 0..ww * wh {
        if observed[i] {
            histogram[value.v[i] as usize] += 1;
            let l = luma(small[i * 4], small[i * 4 + 1], small[i * 4 + 2]);
            luma_histogram[((l * 255.0) as usize).min(255)] += 1;
        }
    }
    let (lo, hi) = thresholds(&histogram)?;
    let dark = match base {
        BaseChoice::Auto => is_dark_by_area(&luma_histogram),
        BaseChoice::Dark => true,
        BaseChoice::Light => false,
    };

    // The two sheets laid over the base, closed so their edges are smooth. A dark image grows
    // outwards from black (grey-or-lighter, then the lightest); a light one from white.
    let (middle, top): (Vec<bool>, Vec<bool>) = if dark {
        (
            value.v.iter().map(|&v| v as i32 > lo).collect(),
            value.v.iter().map(|&v| v as i32 > hi).collect(),
        )
    } else {
        (
            value.v.iter().map(|&v| (v as i32) <= hi).collect(),
            value.v.iter().map(|&v| (v as i32) <= lo).collect(),
        )
    };
    let middle = close(&middle, ww, wh);
    let top = close(&top, ww, wh);
    let silhouette = filled_silhouette(&observed, ww, wh);

    // Back to full size. The observed mask comes from the real alpha, not the scaled one.
    let silhouette = upscale_mask(&silhouette, ww, wh, w, h);
    let middle = upscale_mask(&middle, ww, wh, w, h);
    let top = upscale_mask(&top, ww, wh, w, h);
    let observed_full: Vec<bool> = (0..w * h).map(|i| rgba[i * 4 + 3] > 0).collect();
    let tones = ranks(&silhouette, &observed_full, &middle, &top, dark);

    let mut out = vec![0u8; w * h * 4];
    for (i, &rank) in tones.iter().enumerate() {
        out[i * 4..i * 4 + 4].copy_from_slice(&pixel(rank, view, dark));
    }
    Some(StencilOutput { rgba: out, dark })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn histogram_of(values: &[(usize, u32)]) -> [u32; 256] {
        let mut h = [0u32; 256];
        for &(v, n) in values {
            h[v] += n;
        }
        h
    }

    #[test]
    fn three_clear_tones_split_between_their_centres() {
        let (lo, hi) = thresholds(&histogram_of(&[(20, 100), (128, 100), (235, 100)])).unwrap();
        assert!((20..=127).contains(&lo), "lo {lo}");
        assert!((128..=234).contains(&hi), "hi {hi}");
    }

    #[test]
    fn thresholds_are_ordered_even_for_a_one_tone_image() {
        let (lo, hi) = thresholds(&histogram_of(&[(90, 500)])).unwrap();
        assert!(lo < hi);
    }

    #[test]
    fn an_empty_histogram_has_no_thresholds() {
        assert!(thresholds(&[0u32; 256]).is_none());
    }

    #[test]
    fn the_same_histogram_always_splits_the_same_way() {
        let h = histogram_of(&[(10, 40), (60, 70), (140, 90), (200, 30), (250, 60)]);
        assert_eq!(thresholds(&h), thresholds(&h));
    }

    #[test]
    fn the_call_goes_to_whichever_of_dark_and_light_covers_more_area() {
        assert!(is_dark_by_area(&histogram_of(&[(20, 700), (240, 300)])));
        assert!(!is_dark_by_area(&histogram_of(&[(20, 300), (240, 700)])));
    }

    #[test]
    fn a_tie_and_an_empty_subject_are_dark() {
        assert!(is_dark_by_area(&histogram_of(&[(20, 500), (240, 500)])));
        assert!(is_dark_by_area(&[0u32; 256]));
    }

    #[test]
    fn dimming_and_pushing_contrast_makes_a_mid_bright_image_dark() {
        assert!(is_dark_by_area(&histogram_of(&[(150, 1000)])));
        assert!(!is_dark_by_area(&histogram_of(&[(230, 1000)])));
    }

    #[test]
    fn luminance_uses_the_old_weights() {
        assert_eq!(luma(0, 0, 0), 0.0);
        assert!((luma(255, 255, 255) - 1.0).abs() < 1e-9);
        assert!(luma(0, 255, 0) > luma(0, 0, 255));
    }

    const RANKS: [u8; 4] = [NONE, BLACK, GREY, WHITE];

    fn render(view: View, dark: bool) -> Vec<[u8; 4]> {
        RANKS.iter().map(|&r| pixel(r, view, dark)).collect()
    }

    const CLEAR: [u8; 4] = [0, 0, 0, 0];
    const K: [u8; 4] = [0, 0, 0, 255];
    const G: [u8; 4] = [128, 128, 128, 255];
    const W: [u8; 4] = [255, 255, 255, 255];

    #[test]
    fn all_stacks_every_tone_and_leaves_cut_outs_transparent_either_way() {
        assert_eq!(render(View::All, true), vec![CLEAR, K, G, W]);
        assert_eq!(render(View::All, false), vec![CLEAR, K, G, W]);
    }

    #[test]
    fn a_dark_image_goes_black_base_grey_midtone_white_secondary() {
        assert_eq!(render(View::Base, true), vec![CLEAR, K, K, K]);
        assert_eq!(render(View::Midtone, true), vec![CLEAR, CLEAR, G, G]);
        assert_eq!(render(View::Secondary, true), vec![CLEAR, CLEAR, CLEAR, W]);
    }

    #[test]
    fn a_light_image_goes_white_base_grey_midtone_black_secondary() {
        assert_eq!(render(View::Base, false), vec![CLEAR, W, W, W]);
        assert_eq!(render(View::Midtone, false), vec![CLEAR, G, G, CLEAR]);
        assert_eq!(render(View::Secondary, false), vec![CLEAR, K, CLEAR, CLEAR]);
    }

    #[test]
    fn the_base_is_the_complete_silhouette_holes_included_and_nothing_outside_it() {
        let silhouette = [false, true, true, true];
        let observed = [false, false, true, true];
        let none = [false; 4];
        for dark in [true, false] {
            let r = ranks(&silhouette, &observed, &none, &none, dark);
            let base = if dark { BLACK } else { WHITE };
            assert_eq!(r, vec![NONE, base, base, base]);
        }
    }

    #[test]
    fn the_midtone_covers_at_least_as_much_as_the_secondary() {
        let all = [true; 4];
        let middle = [false, true, true, false];
        let top = [false, false, true, true];
        for dark in [true, false] {
            let r = ranks(&all, &all, &middle, &top, dark);
            let mid: Vec<bool> = r.iter().map(|&k| pixel(k, View::Midtone, dark)[3] != 0).collect();
            let sec: Vec<bool> = r.iter().map(|&k| pixel(k, View::Secondary, dark)[3] != 0).collect();
            for i in 0..4 {
                assert!(!sec[i] || mid[i], "pixel {i} dark={dark}");
            }
        }
    }

    #[test]
    fn the_median_of_a_constant_plane_is_the_constant_and_a_speck_is_removed() {
        let mut p = Plane { w: 20, h: 20, v: vec![100; 400] };
        assert!(median_blur(&p).v.iter().all(|&v| v == 100));
        p.v[10 * 20 + 10] = 255;
        assert_eq!(median_blur(&p).v[10 * 20 + 10], 100);
    }

    #[test]
    fn closing_fills_a_one_pixel_gap_and_leaves_the_outside_alone() {
        let (w, h) = (15, 15);
        let mut m = vec![false; w * h];
        for y in 5..10 {
            for x in 5..10 {
                m[y * w + x] = true;
            }
        }
        m[7 * w + 7] = false;
        let c = close(&m, w, h);
        assert!(c[7 * w + 7]);
        assert!(!c[0]);
    }

    #[test]
    fn the_silhouette_fills_an_enclosed_hole_but_not_the_background() {
        let (w, h) = (15, 15);
        let mut observed = vec![false; w * h];
        for y in 3..12 {
            for x in 3..12 {
                observed[y * w + x] = true;
            }
        }
        for y in 6..9 {
            for x in 6..9 {
                observed[y * w + x] = false; // a 3x3 hole
            }
        }
        let s = filled_silhouette(&observed, w, h);
        assert!(s[7 * w + 7], "hole is inside the silhouette");
        assert!(!s[0], "corner is background");
    }

    /// A dark image with one bright patch, fully opaque.
    fn dark_with_patch(w: usize, h: usize) -> Vec<u8> {
        let mut px = vec![0u8; w * h * 4];
        for y in 0..h {
            for x in 0..w {
                let bright = (x > w / 3 && x < 2 * w / 3) && (y > h / 3 && y < 2 * h / 3);
                let v = if bright { 240 } else { 20 };
                let i = (y * w + x) * 4;
                px[i..i + 4].copy_from_slice(&[v, v, v, 255]);
            }
        }
        px
    }

    #[test]
    fn a_dark_image_with_a_bright_patch_stacks_black_and_white() {
        let (w, h) = (60, 60);
        let out = stencil(&dark_with_patch(w, h), w, h, View::All, BaseChoice::Auto).unwrap();
        assert!(out.dark);
        let at = |x: usize, y: usize| &out.rgba[(y * w + x) * 4..(y * w + x) * 4 + 4];
        assert_eq!(at(2, 2), &K, "the dark field is the black base");
        assert_eq!(at(30, 30), &W, "the bright patch is the white sheet");
    }

    #[test]
    fn the_base_choice_overrides_the_image() {
        let (w, h) = (40, 40);
        let img = dark_with_patch(w, h);
        assert!(!stencil(&img, w, h, View::Base, BaseChoice::Light).unwrap().dark);
        let base = stencil(&img, w, h, View::Base, BaseChoice::Light).unwrap();
        assert_eq!(&base.rgba[0..4], &W);
    }

    #[test]
    fn transparent_pixels_stay_transparent_and_an_all_clear_image_is_refused() {
        let (w, h) = (30, 30);
        let mut img = dark_with_patch(w, h);
        // Cut a clear margin round the subject.
        for y in 0..h {
            for x in 0..w {
                if x < 5 || y < 5 || x >= w - 5 || y >= h - 5 {
                    img[(y * w + x) * 4 + 3] = 0;
                }
            }
        }
        let out = stencil(&img, w, h, View::All, BaseChoice::Auto).unwrap();
        assert_eq!(out.rgba[3], 0, "the cut-out corner is transparent");
        let clear = vec![0u8; w * h * 4];
        assert!(stencil(&clear, w, h, View::All, BaseChoice::Auto).is_none());
    }

    #[test]
    fn a_large_image_is_worked_at_a_reduced_size_and_comes_back_full_size() {
        let (w, h) = (WORK_DIM * 2, 60);
        let out = stencil(&dark_with_patch(w, h), w, h, View::All, BaseChoice::Auto).unwrap();
        assert_eq!(out.rgba.len(), w * h * 4);
        assert_eq!(&out.rgba[0..4], &K);
        let mid = (30 * w + w / 2) * 4;
        assert_eq!(&out.rgba[mid..mid + 4], &W);
    }


    #[test]
    fn nonsense_input_is_refused() {
        assert!(stencil(&[0u8; 4], 1, 1, View::All, BaseChoice::Auto).is_none());
        assert!(stencil(&[0u8; 7], 2, 2, View::All, BaseChoice::Auto).is_none());
    }
}
