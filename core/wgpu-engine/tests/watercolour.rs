//! Watercolour field passes: the WGSL port (`shaders/watercolour.wgsl`) against the Kotlin reference
//! (`WatercolourField`) through `tests/fixtures/watercolour_golden.txt`, which Kotlin's
//! `WatercolourGoldenFixtureTest` generates and pins. Runs on every backend with an adapter.

mod common;

use graffux_wgpu::watercolour::{run_on_gpu, WatercolourInput};

fn floats(it: std::str::SplitWhitespace) -> Vec<f32> {
    it.map(|v| v.parse::<f32>().unwrap()).collect()
}

#[test]
fn wgsl_watercolour_matches_the_kotlin_reference() {
    let text = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/watercolour_golden.txt"))
        .expect("watercolour_golden.txt (regenerate with Kotlin WatercolourGoldenFixtureTest)");
    let mut input = WatercolourInput::default();
    let (mut want_wet, mut want_pig) = (vec![], vec![]);
    for line in text.lines() {
        let mut it = line.split_whitespace();
        match it.next() {
            Some("S") => {
                input.width = it.next().unwrap().parse().unwrap();
                input.height = it.next().unwrap().parse().unwrap();
            }
            Some("P") => {
                let v = floats(it);
                (input.wick, input.bleed, input.edge, input.dry, input.granulation) = (v[0], v[1], v[2], v[3], v[4]);
                input.ticks = v[5] as u32;
            }
            Some("W") => input.wet = floats(it),
            Some("G") => input.pigment = floats(it),
            Some("A") => input.absorbency = floats(it),
            Some("T") => input.tooth = floats(it),
            Some("DA") => input.deposit_amount = floats(it),
            Some("DW") => input.deposit_water = floats(it),
            Some("OW") => want_wet = floats(it),
            Some("OG") => want_pig = floats(it),
            _ => {}
        }
    }
    for (name, backends) in [("vulkan", wgpu::Backends::VULKAN), ("gl", wgpu::Backends::GL)] {
        let Some(out) = run_on_gpu(backends, &input) else {
            common::no_adapter(name);
            continue;
        };
        let worst = |a: &[f32], b: &[f32]| a.iter().zip(b).map(|(x, y)| (x - y).abs()).fold(0f32, f32::max);
        let (ww, wp) = (worst(&out.wet, &want_wet), worst(&out.pigment, &want_pig));
        eprintln!("[{name}] watercolour: wet {ww:e} pigment {wp:e}");
        assert!(ww <= 1e-5, "[{name}] wetness differs by {ww}");
        assert!(wp <= 1e-5, "[{name}] pigment differs by {wp}");
    }
}
