//! Direct display (direct.rs) on the GPU, through an offscreen target in place of the Android
//! window (Mesa lavapipe = Vulkan, llvmpipe = GL; skipped per backend when no adapter exists).
//!
//! * The present pass shows exactly the stroke's contribution (`stroke_contribution`, the port of
//!   live_overlay.comp) of the layer over the stroke-start snapshot.
//! * The on-screen affine is honoured (target pixel -> layer pixel).
//! * The committed layer is byte-identical with direct display on or off, with multipass on or off.
//! * With multipass on, the first present after a stamp call already shows the draft (no
//!   refinement ran), and after a flush it shows the contribution of the final layer.

use graffux_wgpu::engine::direct::{caps, stroke_contribution};
use graffux_wgpu::wgpu::{Backends, TextureFormat};
use graffux_wgpu::{Engine, GpuDab, MultipassConfig, SubstrateParams};

const W: i32 = 96;
const H: i32 = 72;
const IDENTITY: [f32; 6] = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0];

fn backends() -> Vec<(&'static str, Backends)> {
    let mut out = Vec::new();
    for (name, b) in [("vulkan", Backends::VULKAN), ("gl", Backends::GL)] {
        match Engine::with_backends(8, 8, b) {
            Some(e) if e.direct_capabilities() & caps::ADAPTER != 0 => out.push((name, b)),
            Some(_) => eprintln!("[{name}] no fragment storage -- skipped"),
            None => eprintln!("[{name}] no adapter -- skipped"),
        }
    }
    out
}

fn seed(w: i32, h: i32) -> Vec<u8> {
    // Opaque, translucent and empty areas, so every branch of the contribution math runs.
    let mut v = vec![0u8; (w * h * 4) as usize];
    for y in 0..h {
        for x in 0..w {
            let i = ((y * w + x) * 4) as usize;
            let a: u8 = if x < w / 3 {
                255
            } else if x < 2 * w / 3 {
                110
            } else {
                0
            };
            let c = |k: i32| ((((x * 7 + y * 3 + k * 50) % 256) as u32 * a as u32) / 255) as u8;
            v[i] = c(0);
            v[i + 1] = c(1);
            v[i + 2] = c(2);
            v[i + 3] = a;
        }
    }
    v
}

fn dabs(k: usize) -> Vec<GpuDab> {
    (0..6)
        .map(|i| {
            let t = (k * 6 + i) as f32;
            GpuDab::legacy(8.0 + t * 3.1, 20.0 + (t * 0.7).sin() * 18.0, 7.0, 0.55, 0.0)
        })
        .collect()
}

fn stamp(e: &mut Engine, k: usize) {
    let color = [0xFF20_80E0u32, 0xFFE0_4020, 0xC030_C040][k % 3];
    assert!(e.stamp_dabs(
        &dabs(k),
        color,
        0.4,
        false,
        SubstrateParams::default(),
        true
    ));
}

/// `stroke_contribution` of the layer over `base`, as the RGBA8 the target stores.
fn expected(layer: &[u8], base: &[u8]) -> Vec<u8> {
    let mut out = vec![0u8; layer.len()];
    for i in (0..layer.len()).step_by(4) {
        let p = [layer[i], layer[i + 1], layer[i + 2], layer[i + 3]];
        let b = [base[i], base[i + 1], base[i + 2], base[i + 3]];
        let s = stroke_contribution(p, b);
        for c in 0..4 {
            out[i + c] = (s[c] * 255.0).round().clamp(0.0, 255.0) as u8;
        }
    }
    out
}

fn max_diff(a: &[u8], b: &[u8]) -> u8 {
    a.iter()
        .zip(b)
        .map(|(x, y)| x.abs_diff(*y))
        .max()
        .unwrap_or(0)
}

#[test]
fn present_shows_exactly_the_strokes_contribution() {
    for (name, b) in backends() {
        let mut e = Engine::with_backends(W, H, b).expect("adapter");
        let base = seed(W, H);
        assert!(e.upload(&base));
        assert!(
            e.direct_attach_offscreen(W, H, TextureFormat::Rgba8Unorm),
            "[{name}] attach"
        );
        assert!(e.direct_begin_stroke());
        let caps_now = e.direct_capabilities();
        assert!(
            caps_now & caps::ATTACHED != 0 && caps_now & caps::STROKE != 0,
            "[{name}] {caps_now:b}"
        );
        assert!(caps_now & caps::SURFACE == 0);
        // Transparent before the first present.
        assert!(
            e.direct_read_offscreen().unwrap().iter().all(|&v| v == 0),
            "[{name}] not cleared"
        );
        for k in 0..4 {
            stamp(&mut e, k);
            assert!(
                e.direct_present(Some(IDENTITY), true),
                "[{name}] present {k}"
            );
        }
        let shown = e.direct_read_offscreen().unwrap();
        let layer = e.read_all().unwrap();
        let want = expected(&layer, &base);
        let d = max_diff(&shown, &want);
        assert!(d <= 1, "[{name}] contribution differs by {d}");
        assert!(
            shown.chunks_exact(4).any(|p| p[3] > 0),
            "[{name}] nothing shown"
        );
        assert_eq!(e.direct_present_count(), 4);
        // Stroke end clears the target.
        assert!(e.direct_end_stroke());
        assert!(
            e.direct_read_offscreen().unwrap().iter().all(|&v| v == 0),
            "[{name}] not cleared at end"
        );
        assert!(
            !e.direct_present(None, false),
            "[{name}] present outside a stroke must fail"
        );
    }
}

#[test]
fn present_follows_the_on_screen_affine() {
    for (name, b) in backends() {
        let mut e = Engine::with_backends(W, H, b).expect("adapter");
        let base = seed(W, H);
        assert!(e.upload(&base));
        // A target twice the layer's size, shifted by (5, 3) layer pixels.
        let (tw, th) = (W * 2, H * 2);
        assert!(e.direct_attach_offscreen(tw, th, TextureFormat::Bgra8Unorm));
        assert!(e.direct_begin_stroke());
        stamp(&mut e, 0);
        stamp(&mut e, 1);
        let m = [0.5, 0.0, 5.0, 0.0, 0.5, 3.0];
        assert!(e.direct_present(Some(m), true));
        let shown = e.direct_read_offscreen().unwrap();
        let want_layer = expected(&e.read_all().unwrap(), &base);
        let mut worst = 0u8;
        for y in 0..th {
            for x in 0..tw {
                let lx = ((x as f32 + 0.5) * 0.5 + 5.0).floor() as i32;
                let ly = ((y as f32 + 0.5) * 0.5 + 3.0).floor() as i32;
                let got = &shown[((y * tw + x) * 4) as usize..][..4];
                let want: [u8; 4] = if lx < W && ly < H {
                    want_layer[((ly * W + lx) * 4) as usize..][..4]
                        .try_into()
                        .unwrap()
                } else {
                    [0; 4]
                };
                worst = worst.max(max_diff(got, &want));
            }
        }
        assert!(worst <= 1, "[{name}] affine mapping off by {worst}");
        // A singular matrix is refused rather than drawn.
        assert!(!e.direct_present(Some([0.0; 6]), true));
    }
}

fn session(b: Backends, direct: bool, multipass: bool) -> Vec<u8> {
    let mut e = Engine::with_backends(W, H, b).expect("adapter");
    if multipass {
        assert!(e.set_multipass(MultipassConfig {
            enabled: true,
            draft_scale: 2,
            ..MultipassConfig::default()
        }));
    }
    let mut out = vec![0u8; (W * H * 4) as usize];
    for stroke in 0..3 {
        assert!(e.upload(&seed(W, H)));
        if direct {
            assert!(e.direct_attach_offscreen(W, H, TextureFormat::Rgba8Unorm));
            assert!(e.direct_begin_stroke());
        }
        for k in 0..5 {
            stamp(&mut e, stroke * 5 + k);
            if direct {
                assert!(e.direct_present(Some(IDENTITY), true));
                if multipass {
                    let _ = e.refine(0.05);
                    assert!(e.direct_present(None, false));
                }
            } else {
                assert!(e.readback(&mut out));
                if multipass {
                    let _ = e.refine(0.05);
                }
            }
        }
        if direct {
            assert!(e.direct_end_stroke());
        }
    }
    e.read_all().unwrap()
}

#[test]
fn committed_layer_is_byte_identical_with_direct_display() {
    for (name, b) in backends() {
        for multipass in [false, true] {
            let plain = session(b, false, multipass);
            let direct = session(b, true, multipass);
            assert!(
                plain == direct,
                "[{name}] multipass={multipass}: layer changed by direct display"
            );
        }
        assert!(
            session(b, false, false) == session(b, true, true),
            "[{name}] multipass+direct vs plain"
        );
    }
}

#[test]
fn multipass_draft_shows_on_the_first_present() {
    for (name, b) in backends() {
        let mut e = Engine::with_backends(W, H, b).expect("adapter");
        assert!(e.set_multipass(MultipassConfig {
            enabled: true,
            draft_scale: 2,
            ..MultipassConfig::default()
        }));
        e.set_multipass_clock(Some(0.0));
        let base = vec![0u8; (W * H * 4) as usize];
        assert!(e.upload(&base));
        assert!(e.direct_attach_offscreen(W, H, TextureFormat::Rgba8Unorm));
        assert!(e.direct_begin_stroke());
        stamp(&mut e, 0);
        // No refine call: whatever shows is the draft.
        assert!(e.direct_present(Some(IDENTITY), true));
        let draft = e.direct_read_offscreen().unwrap();
        let covered = draft.chunks_exact(4).filter(|p| p[3] > 0).count();
        assert!(
            covered > 50,
            "[{name}] draft not shown on the first present ({covered} px)"
        );
        // Landing everything: the display converges to exactly the layer.
        assert!(e.flush());
        assert!(e.direct_present(None, false));
        let shown = e.direct_read_offscreen().unwrap();
        let want = expected(&e.read_all().unwrap(), &base);
        let d = max_diff(&shown, &want);
        assert!(
            d <= 1,
            "[{name}] settled display differs from the layer's contribution by {d}"
        );
    }
}

#[test]
fn detach_and_capabilities() {
    for (name, b) in backends() {
        let mut e = Engine::with_backends(W, H, b).expect("adapter");
        assert_eq!(e.direct_capabilities() & (caps::ATTACHED | caps::STROKE), 0);
        // No window support on a host build; attaching a (null) window is refused.
        #[cfg(not(target_os = "android"))]
        {
            assert_eq!(e.direct_capabilities() & caps::WINDOW, 0);
            assert!(!unsafe { e.direct_attach_window(std::ptr::null_mut(), W, H) });
        }
        assert!(!e.direct_begin_stroke(), "[{name}] begin without a target");
        assert!(e.direct_attach_offscreen(W, H, TextureFormat::Rgba8UnormSrgb));
        assert!(e.direct_begin_stroke());
        e.direct_detach();
        assert_eq!(e.direct_capabilities() & caps::ATTACHED, 0);
        assert!(!e.direct_present(Some(IDENTITY), true));
        // Unusable formats are refused.
        assert!(!e.direct_attach_offscreen(W, H, TextureFormat::Rgba16Float));
    }
}
