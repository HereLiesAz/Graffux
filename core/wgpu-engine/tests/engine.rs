//! Engine tests. Shader validation runs everywhere; the GPU tests run once per backend that has
//! an adapter on this machine (Vulkan, e.g. Mesa lavapipe; GL, e.g. Mesa llvmpipe) and are skipped
//! with a note when none exists, so `cargo test` passes on a GPU-less CI runner too.

use graffux_wgpu::reference::{stamp, StrokeState};
use graffux_wgpu::wgpu::Backends;
use graffux_wgpu::{ColorSmudgeDab, Engine, GpuDab, MaskedParams, SubstrateParams};

const W: i32 = 97;
const H: i32 = 71;

/// Tiny deterministic LCG so the tests need no rand crate.
struct Rng(u64);
impl Rng {
    fn f(&mut self) -> f32 {
        self.0 = self
            .0
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        ((self.0 >> 40) as f32) / ((1u64 << 24) as f32)
    }
    fn byte(&mut self) -> u8 {
        (self.f() * 256.0) as u8
    }
}

fn engines() -> Vec<(&'static str, Engine)> {
    let mut out = Vec::new();
    for (name, b) in [("vulkan", Backends::VULKAN), ("gl", Backends::GL)] {
        match Engine::with_backends(W, H, b) {
            Some(e) => {
                eprintln!("[{name}] {}", e.adapter_description());
                out.push((name, e));
            }
            None => eprintln!("[{name}] no adapter -- skipped"),
        }
    }
    out
}

fn seed(rng: &mut Rng) -> Vec<u8> {
    let mut v = vec![0u8; (W * H * 4) as usize];
    for px in v.chunks_exact_mut(4) {
        let a = rng.byte();
        for c in 0..3 {
            px[c] = ((rng.byte() as u32 * a as u32) / 255) as u8;
        }
        px[3] = a;
    }
    v
}

fn dabs(rng: &mut Rng, n: usize, resolved: bool) -> Vec<GpuDab> {
    (0..n)
        .map(|i| {
            let mut d = GpuDab::legacy(
                rng.f() * W as f32,
                rng.f() * H as f32,
                1.0 + rng.f() * 14.0,
                0.2 + rng.f() * 0.8,
                rng.f() * 360.0,
            );
            if resolved || i % 3 == 0 {
                d.color_r = rng.f();
                d.color_g = rng.f();
                d.color_b = rng.f();
                d.color_a = 0.3 + rng.f() * 0.7;
                d.flow = 0.2 + rng.f();
                d.resolved = 1.0;
                d.tip_ratio = rng.f();
            }
            d
        })
        .collect()
}

fn words(bytes: &[u8]) -> Vec<u32> {
    bytes
        .chunks_exact(4)
        .map(|c| u32::from_le_bytes([c[0], c[1], c[2], c[3]]))
        .collect()
}

/// (bytes that differ, max abs difference)
fn diff(a: &[u8], b: &[u8]) -> (usize, u8) {
    a.iter().zip(b).fold((0, 0), |(n, m), (x, y)| {
        let d = x.abs_diff(*y);
        (n + (d != 0) as usize, m.max(d))
    })
}

#[test]
fn shaders_validate_with_naga() {
    for (name, src) in [
        ("stamp", graffux_wgpu::engine::STAMP_WGSL),
        ("stamp_masked", graffux_wgpu::engine::STAMP_MASKED_WGSL),
        ("color_smudge", graffux_wgpu::engine::COLOR_SMUDGE_WGSL),
        ("draft_stamp", graffux_wgpu::engine::DRAFT_STAMP_WGSL),
        ("draft_masked", graffux_wgpu::engine::DRAFT_MASKED_WGSL),
        ("display", graffux_wgpu::engine::DISPLAY_WGSL),
    ] {
        let module = naga::front::wgsl::parse_str(src)
            .unwrap_or_else(|e| panic!("{name}: {}", e.emit_to_string(src)));
        naga::valid::Validator::new(
            naga::valid::ValidationFlags::all(),
            naga::valid::Capabilities::empty(),
        )
        .validate(&module)
        .unwrap_or_else(|e| panic!("{name}: {e:?}"));
    }
}

#[test]
fn no_adapter_fails_gracefully() {
    // Metal never exists on Linux/Windows/Android; an empty set never has an adapter anywhere.
    assert!(Engine::with_backends(W, H, Backends::empty()).is_none());
    #[cfg(not(target_vendor = "apple"))]
    assert!(Engine::with_backends(W, H, Backends::METAL).is_none());
    assert!(Engine::with_backends(0, H, Backends::all()).is_none());
}

#[test]
fn upload_readback_roundtrip_and_clear() {
    for (name, mut e) in engines() {
        let s = seed(&mut Rng(7));
        assert!(e.upload(&s));
        assert_eq!(e.read_all().unwrap(), s, "{name}");
        assert!(e.clear());
        assert!(e.read_all().unwrap().iter().all(|b| *b == 0), "{name}");
    }
}

/// Max-combine, build-up and stroke-max (across batches) against the scalar reference of
/// stamp.comp. Tolerance: 1 level per byte (float evaluation order / half-way rounding).
#[test]
fn stamp_matches_cpu_reference() {
    for (name, mut e) in engines() {
        let mut rng = Rng(42);
        let s = seed(&mut rng);
        for (label, build_up, batches) in [
            ("max", false, 1),
            ("buildup", true, 1),
            ("strokemax", false, 3),
        ] {
            let all = dabs(&mut rng, 45, false);
            let color = 0xC0FF8040u32;
            let hardness = 0.35;
            assert!(e.upload(&s));
            let mut expected = words(&s);
            let mut state = StrokeState(vec![None; (W * H) as usize]);
            for part in all.chunks(all.len() / batches) {
                assert!(e.stamp_dabs(
                    part,
                    color,
                    hardness,
                    build_up,
                    SubstrateParams::default(),
                    batches > 1
                ));
                stamp(
                    &mut expected,
                    W as usize,
                    part,
                    color,
                    hardness,
                    build_up,
                    (batches > 1).then_some(&mut state),
                );
            }
            let got = e.read_all().unwrap();
            let want: Vec<u8> = expected.iter().flat_map(|w| w.to_le_bytes()).collect();
            let (n, max) = diff(&got, &want);
            eprintln!(
                "[{name}] {label}: {n}/{} bytes differ, max {max}",
                got.len()
            );
            assert!(max <= 1, "[{name}] {label}: max diff {max}");
        }
    }
}

/// Readback copies only the rectangle dirtied since the previous readback, like the C++ engines.
#[test]
fn readback_is_dirty_rect_only() {
    for (name, mut e) in engines() {
        let mut buf = vec![0u8; (W * H * 4) as usize];
        assert!(e.readback(&mut buf)); // initial full-layer dirty
        let mut d = GpuDab::legacy(10.0, 10.0, 4.0, 1.0, 0.0);
        d.resolved = 0.0;
        assert!(e.stamp_dabs(
            &[d],
            0xFFFFFFFF,
            1.0,
            false,
            SubstrateParams::default(),
            false
        ));
        buf.iter_mut().for_each(|b| *b = 0x5A);
        assert!(e.readback(&mut buf));
        let px = |x: i32, y: i32| &buf[((y * W + x) * 4) as usize..((y * W + x) * 4 + 4) as usize];
        assert_eq!(px(10, 10), &[255, 255, 255, 255], "{name}");
        assert_eq!(
            px(60, 60),
            &[0x5A; 4],
            "{name}: outside the dirty rect must be untouched"
        );
    }
}

#[test]
fn masked_and_smudge_run_and_are_deterministic() {
    for (name, mut e) in engines() {
        let mut rng = Rng(3);
        let s = seed(&mut rng);
        let d = dabs(&mut rng, 20, false);
        let mask: Vec<u8> = (0..16 * 16).map(|_| rng.byte()).collect();
        let params = MaskedParams {
            mask: &mask,
            mask_width: 16,
            mask_height: 16,
            grain: None,
            grain_canvas_locked: false,
            grain_scale: 1.0,
            grain_phase_x: 0.0,
            grain_phase_y: 0.0,
            secondary_dabs: &[],
            secondary_mask: None,
            substrate: SubstrateParams::default(),
        };
        let smudge: Vec<ColorSmudgeDab> = (0..10)
            .map(|i| ColorSmudgeDab {
                x: 10.0 + i as f32 * 6.0,
                y: 30.0,
                smudge_rate: 0.6,
                color_rate: 0.2,
                opacity: 0.9,
                smudge_radius: 0.8,
                color_rate_multiplier: 1.0,
                distance_delta_px: 6.0,
                base_color_rate: 0.3,
                charge_decay_rate: 0.02,
                pickup_rate: 0.4,
            })
            .collect();
        let run = |e: &mut Engine| {
            assert!(e.upload(&s));
            assert!(e.stamp_masked_dabs(&d, 0xFF4488CC, 0.0, &params));
            for mode in 0..4 {
                assert!(e.color_smudge(
                    &smudge,
                    mode,
                    7.0,
                    0.4,
                    mode % 2 == 0,
                    0xCC3399FF,
                    0.25,
                    None
                ));
            }
            e.read_all().unwrap()
        };
        let a = run(&mut e);
        let b = run(&mut e);
        assert_ne!(a, s, "[{name}] nothing painted");
        assert_eq!(a, b, "[{name}] not deterministic");
    }
}

/// Regression: substrate wrapping with a negative texture offset on a non-power-of-two tile. A
/// plain `%` there hit GLSL's undefined negative remainder on wgpu's GL backend (Mesa llvmpipe).
#[test]
fn substrate_wrap_agrees_across_backends() {
    let engines = engines();
    if engines.len() < 2 {
        eprintln!("needs both backends -- skipped");
        return;
    }
    let mut outs = Vec::new();
    for (_, mut e) in engines {
        let tooth: Vec<u8> = (0..29 * 31).map(|i| ((i * 37 + 11) % 256) as u8).collect();
        assert!(e.upload_substrate_height(&tooth, 29, 31));
        let sp = SubstrateParams {
            enabled: true,
            height_scale: 1.0,
            texture_scale: 1.3,
            texture_offset_x: -13.3,
            texture_offset_y: -40.2,
            ..SubstrateParams::default()
        };
        let mut d = GpuDab::legacy(40.0, 30.0, 45.0, 1.0, 0.0);
        d.contact_depth = 0.5;
        d.substrate_response = 1.0;
        assert!(e.stamp_dabs(&[d], 0xFFFFFFFF, 1.0, false, sp, false));
        outs.push(e.read_all().unwrap());
    }
    assert_eq!(diff(&outs[0], &outs[1]), (0, 0));
}

/// upload_rows restores only the given rows and leaves the rest of the layer (and the stroke) alone.
#[test]
fn upload_rows_restores_a_band() {
    for (name, mut e) in engines() {
        let base = seed(&mut Rng(11));
        assert!(e.upload(&base));
        let d = GpuDab::legacy(40.0, 35.0, 30.0, 1.0, 0.0);
        assert!(e.stamp_dabs(
            &[d],
            0xFF00FF00,
            1.0,
            false,
            SubstrateParams::default(),
            false
        ));
        let painted = e.read_all().unwrap();
        assert!(e.upload_rows(&base, 20, 10));
        let got = e.read_all().unwrap();
        let row = (W * 4) as usize;
        assert_eq!(
            &got[20 * row..30 * row],
            &base[20 * row..30 * row],
            "{name}"
        );
        assert_eq!(&got[..20 * row], &painted[..20 * row], "{name}");
        assert_eq!(&got[30 * row..], &painted[30 * row..], "{name}");
    }
}
