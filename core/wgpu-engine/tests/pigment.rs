//! Kubelka-Munk pigment mixing: the WGSL port (`shaders/pigment.wgsl`) against the Kotlin reference
//! (`PigmentMixer.mixLinearRgb`) through `tests/fixtures/pigment_golden.txt`, which Kotlin's
//! `PigmentGoldenFixtureTest` generates and pins. Runs on every backend that has an adapter.

mod common;

use graffux_wgpu::pigment::{mix_on_gpu, PIGMENT_TABLE_FLOATS};

struct Fixture {
    n: usize,
    tables: Vec<f32>,
    mixtures: Vec<f32>,
    want: Vec<f32>,
}

fn load() -> Fixture {
    let text = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/pigment_golden.txt"))
        .expect("pigment_golden.txt (regenerate with Kotlin PigmentGoldenFixtureTest)");
    let (mut tables, mut mixtures, mut want) = (Vec::new(), Vec::new(), Vec::new());
    let mut n = 0;
    for line in text.lines() {
        let mut it = line.split_whitespace();
        match it.next() {
            Some("P") => {
                it.next(); // id
                tables.extend(it.map(|v| v.parse::<f32>().unwrap()));
                n += 1;
            }
            Some("M") => {
                let rest: Vec<&str> = it.collect();
                let bar = rest.iter().position(|s| *s == "|").unwrap();
                mixtures.extend(rest[..bar].iter().map(|v| v.parse::<f32>().unwrap()));
                want.extend(rest[bar + 1..].iter().map(|v| v.parse::<f32>().unwrap()));
            }
            _ => {}
        }
    }
    assert_eq!(tables.len(), n * PIGMENT_TABLE_FLOATS);
    assert_eq!(mixtures.len() / n * 3, want.len());
    Fixture { n, tables, mixtures, want }
}

#[test]
fn wgsl_mixer_matches_the_kotlin_reference() {
    let f = load();
    for (name, backends) in [
        ("vulkan", wgpu::Backends::VULKAN),
        ("gl", wgpu::Backends::GL),
    ] {
        let Some(got) = mix_on_gpu(backends, &f.tables, f.n, &f.mixtures) else {
            common::no_adapter(name);
            continue;
        };
        assert_eq!(got.len(), f.want.len(), "[{name}] row count");
        let worst = got
            .iter()
            .zip(&f.want)
            .map(|(g, w)| (g - w).abs())
            .fold(0f32, f32::max);
        eprintln!("[{name}] pigment WGSL vs Kotlin worst linear difference {worst:e}");
        // Linear 0..1: 2e-5 is well under one 8-bit sRGB level anywhere in the range.
        assert!(worst <= 2e-5, "[{name}] WGSL vs Kotlin worst linear difference {worst}");
    }
}
