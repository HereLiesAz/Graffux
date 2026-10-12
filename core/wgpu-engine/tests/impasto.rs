//! Impasto v1 on the GPU against a CPU reference written line for line from the editor's
//! `ImpastoEngine.deposit` and `ImpastoRegionShader.shade` (Kotlin, f32 throughout). Runs once per
//! backend with an adapter; skipped with a note when there is none.

use graffux_wgpu::wgpu::Backends;
use graffux_wgpu::{height_region, Engine, HeightDab};

const W: i32 = 83;
const H: i32 = 61;

struct Rng(u64);
impl Rng {
    fn f(&mut self) -> f32 {
        self.0 = self
            .0
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        ((self.0 >> 40) as f32) / ((1u64 << 24) as f32)
    }
}

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

/// `BrushStamps.stampCoverage`.
fn coverage(r_norm: f32, hardness: f32) -> f32 {
    if r_norm <= 0.0 {
        return 1.0;
    }
    if r_norm >= 1.0 {
        return 0.0;
    }
    let h = hardness.clamp(0.0, 1.0);
    if r_norm <= h {
        return 1.0;
    }
    let denom = 1.0 - h;
    if denom <= 1e-4 {
        return 1.0;
    }
    ((1.0 - r_norm) / denom).clamp(0.0, 1.0)
}

/// `ImpastoEngine.deposit` over every dab, in order.
fn cpu_deposit(height: &mut [f32], dabs: &[HeightDab], hardness: f32, thickness: f32) {
    for d in dabs {
        let r = d.radius;
        if thickness <= 0.0 || r <= 0.0 {
            continue;
        }
        let min_x = ((d.x - r).floor() as i32).max(0);
        let max_x = ((d.x + r).ceil() as i32).min(W - 1);
        let min_y = ((d.y - r).floor() as i32).max(0);
        let max_y = ((d.y + r).ceil() as i32).min(H - 1);
        for y in min_y..=max_y {
            for x in min_x..=max_x {
                let dx = x as f32 + 0.5 - d.x;
                let dy = y as f32 + 0.5 - d.y;
                let r_norm = ((dx * dx + dy * dy).sqrt() / r).clamp(0.0, 1.0);
                let c = coverage(r_norm, hardness);
                if c <= 0.0 {
                    continue;
                }
                let i = (y * W + x) as usize;
                let cur = height[i].clamp(0.0, 1.0);
                let inc = (thickness * c * d.strength).clamp(0.0, 1.0);
                height[i] = (cur + inc * (1.0 - cur)).clamp(0.0, 1.0);
            }
        }
    }
}

/// `ImpastoRegionShader.shade` on premultiplied RGBA8 (the engine's layer), through straight RGB.
#[allow(clippy::too_many_arguments)]
fn cpu_shade(
    px: &mut [u8],
    height: &[f32],
    x0: i32,
    y0: i32,
    w: i32,
    h: i32,
    az: f32,
    el: f32,
    strength: f32,
) {
    let (az, el) = (az.to_radians(), el.to_radians());
    let (lx, ly, lz) = (az.cos() * el.cos(), az.sin() * el.cos(), el.sin());
    let at = |x: i32, y: i32| height[(y.clamp(0, H - 1) * W + x.clamp(0, W - 1)) as usize];
    for y in y0..y0 + h {
        for x in x0..x0 + w {
            let dhdx = (at(x + 1, y) - at(x - 1, y)) / 2.0;
            let dhdy = (at(x, y + 1) - at(x, y - 1)) / 2.0;
            if dhdx == 0.0 && dhdy == 0.0 {
                continue;
            }
            let (nx, ny) = (-dhdx, -dhdy);
            let inv = 1.0 / (nx * nx + ny * ny + 1.0).sqrt();
            let diffuse = nx * inv * lx + ny * inv * ly + inv * lz;
            let m = (1.0 + strength * (diffuse - lz)).clamp(0.0, 3.0);
            if m == 1.0 {
                continue;
            }
            let i = ((y * W + x) * 4) as usize;
            let a = px[i + 3] as f32;
            if a <= 0.0 {
                continue;
            }
            for c in 0..3 {
                let straight = (px[i + c] as f32 * 255.0 / a).round().min(255.0);
                let scaled = (straight * m).floor().clamp(0.0, 255.0);
                px[i + c] = (scaled * a / 255.0).round() as u8;
            }
        }
    }
}

fn dabs(rng: &mut Rng, n: usize) -> Vec<HeightDab> {
    (0..n)
        .map(|_| HeightDab {
            x: rng.f() * W as f32,
            y: rng.f() * H as f32,
            radius: 1.0 + rng.f() * 12.0,
            strength: rng.f(),
        })
        .collect()
}

#[test]
fn deposit_matches_the_cpu_reference() {
    for (name, mut e) in engines() {
        let mut rng = Rng(11);
        let mut cpu: Vec<f32> = (0..W * H)
            .map(|i| if i % 3 == 0 { 0.0 } else { rng.f() * 0.5 })
            .collect();
        assert!(e.upload_height(&cpu), "[{name}] upload");
        for (hardness, thickness) in [(0.0, 0.3), (0.6, 0.15), (1.0, 0.5)] {
            let batch = dabs(&mut rng, 40);
            assert!(
                e.deposit_height(&batch, hardness, thickness),
                "[{name}] deposit"
            );
            cpu_deposit(&mut cpu, &batch, hardness, thickness);
        }
        let mut gpu = vec![0f32; (W * H) as usize];
        assert!(e.read_height(&mut gpu), "[{name}] read");
        let worst = cpu
            .iter()
            .zip(&gpu)
            .map(|(a, b)| (a - b).abs())
            .fold(0f32, f32::max);
        assert!(
            worst < 1e-5,
            "[{name}] height differs from the CPU by {worst}"
        );
    }
}

#[test]
fn shade_matches_the_cpu_reference_and_only_touches_its_region() {
    for (name, mut e) in engines() {
        let mut rng = Rng(5);
        let mut layer = vec![0u8; (W * H * 4) as usize];
        for p in layer.chunks_exact_mut(4) {
            let a = (rng.f() * 256.0) as u32;
            for c in 0..3 {
                p[c] = (((rng.f() * 256.0) as u32 * a) / 255) as u8;
            }
            p[3] = a as u8;
        }
        let mut height = vec![0f32; (W * H) as usize];
        let batch = dabs(&mut rng, 30);
        cpu_deposit(&mut height, &batch, 0.4, 0.4);
        assert!(
            e.upload(&layer) && e.upload_height(&height),
            "[{name}] upload"
        );
        let (x, y, w, h) = (7, 5, 50, 40);
        assert!(
            e.shade_relief(x, y, w, h, 135.0, 45.0, 1.0),
            "[{name}] shade"
        );
        let gpu = e.read_all().expect("read layer");
        cpu_shade(&mut layer, &height, x, y, w, h, 135.0, 45.0, 1.0);
        let mut worst = 0i32;
        for (i, (a, b)) in layer.iter().zip(&gpu).enumerate() {
            let p = (i / 4) as i32;
            let (px, py) = (p % W, p / W);
            let inside = px >= x && px < x + w && py >= y && py < y + h;
            if !inside {
                assert_eq!(
                    a, b,
                    "[{name}] pixel ({px},{py}) outside the region changed"
                );
            }
            worst = worst.max((*a as i32 - *b as i32).abs());
        }
        assert!(
            worst <= 1,
            "[{name}] shade differs from the CPU by {worst} levels"
        );
    }
}

#[test]
fn height_region_is_the_union_of_the_cpu_boxes_clamped() {
    let r = height_region(
        &[
            HeightDab {
                x: 10.2,
                y: 10.7,
                radius: 3.0,
                strength: 1.0,
            },
            HeightDab {
                x: -5.0,
                y: 2.0,
                radius: 2.0,
                strength: 1.0,
            },
        ],
        W,
        H,
    );
    // x: -7..=14 and y: 0..=14 inclusive, clamped to the layer.
    assert_eq!((r.x, r.y, r.w, r.h), (0, 0, 15, 15));
}
