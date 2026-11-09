//! The paint transaction: the WGSL port (`shaders/transaction.wgsl`) against the Kotlin reference
//! (`PaintTransaction.run`) through `tests/fixtures/transaction_golden.txt`, which Kotlin's
//! `PaintTransactionGoldenFixtureTest` generates and pins. Runs on every backend with an adapter.

mod common;

use graffux_wgpu::transaction::{run_on_gpu, TransactionInput, CH, DAB_FLOATS};

struct Fixture {
    input: TransactionInput,
    want_amount: Vec<f32>,
    want_conc_q: Vec<i32>,
    want_avail: Vec<f32>,
    want_load: Vec<f32>,
    want_load_conc: Vec<f32>,
}

fn floats(it: std::str::SplitWhitespace) -> Vec<f32> {
    it.map(|v| v.parse::<f32>().unwrap()).collect()
}

fn load() -> Fixture {
    let text = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/transaction_golden.txt"))
        .expect("transaction_golden.txt (regenerate with Kotlin PaintTransactionGoldenFixtureTest)");
    let mut input = TransactionInput::default();
    let (mut want_amount, mut want_conc_q, mut want_avail, mut want_load, mut want_load_conc) =
        (vec![], vec![], vec![], vec![], vec![]);
    for line in text.lines() {
        let mut it = line.split_whitespace();
        match it.next() {
            Some("S") => {
                input.width = it.next().unwrap().parse().unwrap();
                input.height = it.next().unwrap().parse().unwrap();
            }
            Some("A") => input.amount = floats(it),
            Some("C") => input.conc = floats(it).into_iter().map(|q| q / 255.0).collect(),
            Some("L") => {
                input.lanes = it.next().unwrap().parse().unwrap();
                input.capacity = it.next().unwrap().parse().unwrap();
                input.load_amount = vec![0.0; input.lanes as usize];
                input.load_conc = vec![0.0; input.lanes as usize * CH];
            }
            Some("D") => {
                let mut d = floats(it);
                d.resize(DAB_FLOATS, 0.0);
                input.dabs.extend(d);
            }
            Some("OA") => want_amount = floats(it),
            Some("OC") => want_conc_q = floats(it).into_iter().map(|v| v as i32).collect(),
            Some("OV") => want_avail = floats(it),
            Some("OL") => want_load = floats(it),
            Some("OLC") => want_load_conc = floats(it),
            _ => {}
        }
    }
    Fixture { input, want_amount, want_conc_q, want_avail, want_load, want_load_conc }
}

fn worst(a: &[f32], b: &[f32]) -> f32 {
    assert_eq!(a.len(), b.len());
    a.iter().zip(b).map(|(x, y)| (x - y).abs()).fold(0.0, f32::max)
}

#[test]
fn wgsl_transaction_matches_the_kotlin_reference() {
    let f = load();
    for (name, backends) in [("vulkan", wgpu::Backends::VULKAN), ("gl", wgpu::Backends::GL)] {
        let Some(out) = run_on_gpu(backends, &f.input) else {
            common::no_adapter(name);
            continue;
        };
        let w_amount = worst(&out.amount, &f.want_amount);
        let w_avail = worst(&out.avail, &f.want_avail);
        let w_load = worst(&out.load_amount, &f.want_load);
        let w_loadc = worst(&out.load_conc, &f.want_load_conc);
        // Quantised concentrations: count texels whose byte differs from the reference.
        let got_q: Vec<i32> = out.conc.iter().map(|c| (c * 255.0).round() as i32).collect();
        let off = got_q.iter().zip(&f.want_conc_q).filter(|(g, w)| g != w).count();
        let off_by_more = got_q.iter().zip(&f.want_conc_q).filter(|(g, w)| (*g - *w).abs() > 1).count();
        eprintln!(
            "[{name}] transaction: amount {w_amount:e} avail {w_avail:e} load {w_load:e} loadconc {w_loadc:e}; conc bytes differing {off} of {}, by more than 1: {off_by_more}",
            got_q.len()
        );
        assert!(w_amount <= 1e-4, "[{name}] surface amount differs by {w_amount}");
        assert!(w_avail <= 1e-4, "[{name}] avail differs by {w_avail}");
        assert!(w_load <= 1e-4, "[{name}] load amount differs by {w_load}");
        assert!(w_loadc <= 1e-4, "[{name}] load recipe differs by {w_loadc}");
        assert_eq!(off_by_more, 0, "[{name}] concentration bytes differ by more than one step");
        assert!(off * 100 <= got_q.len(), "[{name}] too many concentration bytes differ: {off}");
        // Determinism: the same dispatch twice gives bit-identical results on one device.
        let again = run_on_gpu(backends, &f.input).unwrap();
        assert_eq!(out.conc, again.conc, "[{name}] repeat run differs");
        assert_eq!(out.amount, again.amount, "[{name}] repeat run differs");
    }
}
