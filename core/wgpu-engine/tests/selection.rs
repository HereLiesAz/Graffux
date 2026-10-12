//! Selection at commit (selection.rs) against a CPU reference of the editor's commit: the hard clip
//! (`lerp(shadow, painted, m)`, SRC through an antialiased clip) and the feathered composite
//! (`painted * m` SRC_OVER the shadow). Runs once per backend with an adapter.

use graffux_wgpu::wgpu::Backends;
use graffux_wgpu::{Engine, GpuDab, SubstrateParams};

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

fn base() -> Vec<u8> {
    (0..N * 4)
        .map(|i| match i % 4 {
            3 => 200,
            c => ((i / 4) as u32 * (c as u32 + 3) % 200) as u8,
        })
        .collect()
}

fn mask() -> Vec<u8> {
    (0..N)
        .map(|i| {
            let x = (i % W as usize) as i32;
            ((x - 10).clamp(0, 25) * 255 / 25) as u8
        })
        .collect()
}

fn unpack(p: &[u8]) -> [f32; 4] {
    [p[0] as f32 / 255.0, p[1] as f32 / 255.0, p[2] as f32 / 255.0, p[3] as f32 / 255.0]
}

fn paint(e: &mut Engine) {
    let mut d = GpuDab::legacy(24.0, 20.0, 18.0, 1.0, 0.0);
    d.resolved = 0.0;
    assert!(e.stamp_dabs(&[d], 0xCC3366FF, 0.5, false, SubstrateParams::default(), true));
}

#[test]
fn confine_matches_the_cpu_commit_and_stays_in_the_stroke() {
    for (name, mut e) in engines() {
        for feather in [false, true] {
            let before = base();
            assert!(e.upload(&before));
            assert!(e.set_selection(&mask()));
            assert!(e.take_stroke_shadow());
            paint(&mut e);
            // The stroke's rectangle first: a readback resets it.
            let r = e.stroke_rect();
            assert!(!r.is_empty());
            let painted = e.read_all().unwrap();
            assert!(e.confine_stroke(r.x, r.y, r.w, r.h, feather));
            let out = e.read_all().unwrap();
            let m = mask();
            let mut worst = 0i32;
            for i in 0..N {
                let (x, y) = ((i % W as usize) as i32, (i / W as usize) as i32);
                let px = &out[i * 4..i * 4 + 4];
                let inside = x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h;
                if !inside {
                    assert_eq!(px, &painted[i * 4..i * 4 + 4], "[{name}] outside the stroke changed");
                    continue;
                }
                let (pa, ba, k) = (unpack(&painted[i * 4..]), unpack(&before[i * 4..]), m[i] as f32 / 255.0);
                let want: Vec<i32> = (0..4)
                    .map(|c| {
                        let v = if feather {
                            pa[c] * k + ba[c] * (1.0 - pa[3] * k)
                        } else if k >= 1.0 {
                            pa[c]
                        } else {
                            ba[c] + (pa[c] - ba[c]) * k
                        };
                        (v.clamp(0.0, 1.0) * 255.0).round() as i32
                    })
                    .collect();
                for c in 0..4 {
                    worst = worst.max((px[c] as i32 - want[c]).abs());
                }
            }
            assert!(worst <= 1, "[{name}] feather={feather}: worst channel difference {worst}");
            // Fully outside the selection the stroke is gone: the pre-stroke pixel comes back.
            let i = (20 * W + 5) as usize;
            assert_eq!(&out[i * 4..i * 4 + 4], &before[i * 4..i * 4 + 4], "[{name}] outside the selection");
        }
    }
}
