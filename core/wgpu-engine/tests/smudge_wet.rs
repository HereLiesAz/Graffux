//! Color Smudge on the wet simulation's canvas wetness (`Engine::set_smudge_wetness`): the canvas
//! wetness gates how much the carrier picks up (ColorSmudgeEngine.wetnessMobility), and every
//! smudged pixel deposits `dilution * mask * opacity` wetness, activating its tile, as
//! PersistentWetnessField.addWetness does. Runs once per backend with an adapter.

use graffux_wgpu::wgpu::Backends;
use graffux_wgpu::{ColorSmudgeDab, Engine, WET_MEDIA_FLOATS};

const W: i32 = 64;
const H: i32 = 32;
const N: usize = (W * H) as usize;
const TILE: i32 = 16;
const TILES: usize = ((W / TILE) * (H / TILE)) as usize;

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

/// Opaque red on the left half, opaque blue on the right: premultiplied layer and straight raw.
fn image() -> (Vec<u8>, Vec<u32>) {
    let mut layer = Vec::with_capacity(N * 4);
    let mut raw = Vec::with_capacity(N);
    for i in 0..N {
        let left = (i % W as usize) < W as usize / 2;
        if left {
            layer.extend_from_slice(&[220, 30, 30, 255]);
            raw.push(0xFFDC1E1E);
        } else {
            layer.extend_from_slice(&[30, 30, 220, 255]);
            raw.push(0xFF1E1EDC);
        }
    }
    (layer, raw)
}

fn dabs() -> Vec<ColorSmudgeDab> {
    (0..12)
        .map(|i| ColorSmudgeDab {
            x: 20.0 + i as f32 * 2.0,
            y: 16.0,
            smudge_rate: 0.8,
            color_rate: 0.0,
            opacity: 0.9,
            smudge_radius: 1.0,
            color_rate_multiplier: 1.0,
            distance_delta_px: 2.0,
            base_color_rate: 0.0,
            charge_decay_rate: 0.0,
            pickup_rate: 0.0,
        })
        .collect()
}

/// Smudges with the canvas at uniform `wetness` and the brush at `dilution`; returns the layer
/// and the wetness after.
fn run(e: &mut Engine, wetness: f32, dilution: f32) -> (Vec<u8>, Vec<f32>) {
    let (layer, raw) = image();
    assert!(e.upload(&layer));
    let active = vec![u32::from(wetness > 0.0); TILES];
    assert!(e.wet_begin(
        &raw,
        &vec![0.0; N],
        &vec![wetness; N],
        &active,
        TILE,
        &vec![0.0; TILES * WET_MEDIA_FLOATS]
    ));
    assert!(e.set_smudge_wetness(true));
    assert!(e.color_smudge(&dabs(), 0, 4.0, 0.3, true, 0xFF000000, dilution, None));
    assert!(e.set_smudge_wetness(false));
    let out = e.read_all().unwrap();
    let (mut r, mut h, mut w) = (vec![0u32; N], vec![0f32; N], vec![0f32; N]);
    assert!(e.wet_read(&mut r, &mut h, &mut w));
    (out, w)
}

#[test]
fn canvas_wetness_gates_pickup_and_the_smudge_deposits_wetness() {
    for (name, mut e) in engines() {
        let dry = run(&mut e, 0.0, 0.0);
        let wet = run(&mut e, 1.0, 0.0);
        let at = |px: &[u8], x: i32| px[((16 * W + x) * 4) as usize..][..4].to_vec();
        // A dry canvas and a dry brush: nothing is mobile, so the carrier is only ever the pixel
        // under it and nothing moves; a wet canvas lets it carry the red across the edge.
        assert_eq!(
            at(&dry.0, 40),
            vec![30, 30, 220, 255],
            "[{name}] a dry smear moves nothing"
        );
        assert!(
            at(&wet.0, 40)[0] > at(&dry.0, 40)[0],
            "[{name}] the wet canvas should carry the red"
        );
        assert_eq!(
            dry.1.iter().copied().fold(0.0, f32::max),
            0.0,
            "[{name}] no dilution, no deposit"
        );

        // A diluted brush wets the canvas where it smudges, and only there.
        let (_, w) = run(&mut e, 0.0, 0.5);
        assert!(
            w[(16 * W + 30) as usize] > 0.0,
            "[{name}] deposit under the stroke"
        );
        assert_eq!(w[(2 * W + 2) as usize], 0.0, "[{name}] nothing far from it");
        assert!(
            w.iter().all(|&v| (0.0..=1.0).contains(&v)),
            "[{name}] clamped"
        );
        // And its own dilution makes the paint mobile on a dry canvas.
        assert_ne!(run(&mut e, 0.0, 0.5).0, dry.0, "[{name}] dilution mobility");
    }
}
