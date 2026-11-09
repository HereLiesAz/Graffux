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

fn bits(v: &[f32]) -> Vec<u32> {
    v.iter().map(|x| x.to_bits()).collect()
}

fn dab(x: f32, y: f32, r: f32) -> Vec<f32> {
    let mut d = vec![0.0; DAB_FLOATS];
    d[0] = x;
    d[1] = y;
    d[2] = r;
    d
}

#[test]
fn the_chunk_plan_covers_every_dab_once_within_budget() {
    use graffux_wgpu::transaction::{plan_chunks, MAX_CHUNK_COST, MAX_CHUNK_DABS, MAX_DAB_COST};
    let f = load();
    let n = f.input.dabs.len() / DAB_FLOATS;
    let (w, h) = (f.input.width, f.input.height);
    // Many small dabs: bounded by the dab count only.
    let mut heavy = Vec::new();
    for _ in 0..201 {
        heavy.extend_from_slice(&f.input.dabs);
    }
    let total = 201 * n;
    let plan = plan_chunks(&heavy, w, h, MAX_CHUNK_COST, MAX_DAB_COST, MAX_CHUNK_DABS).unwrap();
    assert_eq!(plan.len(), total.div_ceil(MAX_CHUNK_DABS), "{total} dabs");
    assert_eq!(plan[0].0.start, 0);
    assert_eq!(plan.last().unwrap().0.end, total);
    assert!(plan.windows(2).all(|p| p[0].0.end == p[1].0.start));
    assert!(plan.iter().all(|(r, c)| r.len() <= MAX_CHUNK_DABS && *c <= MAX_CHUNK_COST));
    // Big dabs: each over half the chunk budget, so each gets its own chunk.
    let big: Vec<f32> = (0..5).flat_map(|i| dab(512.0, 512.0 + i as f32, 250.0)).collect();
    let plan = plan_chunks(&big, 1024, 1024, MAX_CHUNK_COST, MAX_DAB_COST, MAX_CHUNK_DABS).unwrap();
    assert_eq!(plan.len(), 5);
    assert!(plan.iter().all(|(r, _)| r.len() == 1));
}

#[test]
fn a_dab_that_cannot_be_split_is_refused_not_run_unbounded() {
    use graffux_wgpu::transaction::{
        chunk_timeout, plan_chunks, try_run_chunked, TransactionError, MAX_CHUNK_COST, MAX_CHUNK_DABS,
        MAX_CHUNK_WAIT, MAX_DAB_COST,
    };
    // A whole-canvas dab on a big canvas is over the per-dab limit.
    let whole = dab(2048.0, 2048.0, 3000.0);
    assert_eq!(
        plan_chunks(&whole, 4096, 4096, MAX_CHUNK_COST, MAX_DAB_COST, MAX_CHUNK_DABS),
        Err(TransactionError::DabTooLarge)
    );
    // The run refuses before touching the GPU (a tiny per-dab limit stands in for the huge canvas).
    let f = load();
    assert_eq!(
        try_run_chunked(wgpu::Backends::VULKAN, &f.input, MAX_CHUNK_COST, 10, MAX_CHUNK_DABS).unwrap_err(),
        TransactionError::DabTooLarge
    );
    // A chunk's wait grows with its estimated work, never below the base timeout.
    assert!(chunk_timeout(0) >= graffux_wgpu::gpu_wait::READBACK_TIMEOUT);
    assert!(chunk_timeout(MAX_CHUNK_COST) > chunk_timeout(0));
    // Capped, and the largest legitimate dab is not what hits the cap.
    assert!(chunk_timeout(u64::MAX) <= MAX_CHUNK_WAIT);
    // The uncapped allowance for the largest legitimate dab fits under the cap, so the cap never clips it.
    assert!(graffux_wgpu::gpu_wait::READBACK_TIMEOUT + std::time::Duration::from_micros(MAX_DAB_COST / 4) <= MAX_CHUNK_WAIT);
    assert!(chunk_timeout(MAX_DAB_COST) > chunk_timeout(MAX_CHUNK_COST) || MAX_DAB_COST <= MAX_CHUNK_COST);
}

#[test]
fn production_chunks_give_bit_identical_results_to_one_dispatch() {
    use graffux_wgpu::transaction::{
        try_run_chunked, TransactionError, MAX_CHUNK_COST, MAX_CHUNK_DABS, MAX_DAB_COST,
    };
    let f = load();
    let n = f.input.dabs.len() / DAB_FLOATS;
    let mut input = f.input.clone();
    for _ in 0..40 {
        input.dabs.extend_from_slice(&f.input.dabs);
    }
    let total = 41 * n;
    for (name, backends) in [("vulkan", wgpu::Backends::VULKAN), ("gl", wgpu::Backends::GL)] {
        // With GRAFFUX_REQUIRE_GPU naming the backend a missing adapter panics here rather than passing.
        let whole = match try_run_chunked(backends, &input, u64::MAX, u64::MAX, usize::MAX) {
            Ok(w) => w,
            // Only "no adapter / does not fit" is a skip; any other failure of the baseline is a failure.
            Err(TransactionError::Unavailable) => {
                common::no_adapter(name);
                continue;
            }
            Err(other) => panic!("[{name}] the unchunked baseline failed: {other:?}"),
        };
        assert_eq!(whole.dispatches, 1);
        // The production plan: 656 dabs, so 2 dispatches (512 + 144); the second starts at dab 512.
        let prod = try_run_chunked(backends, &input, MAX_CHUNK_COST, MAX_DAB_COST, MAX_CHUNK_DABS).unwrap();
        assert_eq!(prod.dispatches, total.div_ceil(MAX_CHUNK_DABS), "[{name}]");
        assert_eq!(prod.dispatches, 2);
        // Several multi-dab chunks (150 dabs each, 5 dispatches, all but the first starting past dab 0).
        let several = try_run_chunked(backends, &input, MAX_CHUNK_COST, MAX_DAB_COST, 150).unwrap();
        assert_eq!(several.dispatches, total.div_ceil(150), "[{name}]");
        assert!(several.dispatches >= 4);
        assert_eq!(bits(&whole.conc), bits(&several.conc), "[{name}] 150-dab chunks conc");
        assert_eq!(bits(&whole.amount), bits(&several.amount), "[{name}] 150-dab chunks amount");
        assert_eq!(bits(&whole.avail), bits(&several.avail), "[{name}] 150-dab chunks avail");
        assert_eq!(bits(&whole.load_conc), bits(&several.load_conc), "[{name}] 150-dab chunks load conc");
        // And one dab per dispatch.
        let split = try_run_chunked(backends, &f.input, 1, u64::MAX, 1).unwrap();
        let one = try_run_chunked(backends, &f.input, u64::MAX, u64::MAX, usize::MAX).unwrap();
        assert_eq!(split.dispatches, n);
        for (label, a, b) in [("production", &whole, &prod), ("per-dab", &one, &split)] {
            assert_eq!(bits(&a.conc), bits(&b.conc), "[{name}] {label} conc");
            assert_eq!(bits(&a.amount), bits(&b.amount), "[{name}] {label} amount");
            assert_eq!(bits(&a.avail), bits(&b.avail), "[{name}] {label} avail");
            assert_eq!(bits(&a.load_amount), bits(&b.load_amount), "[{name}] {label} load");
            assert_eq!(bits(&a.load_conc), bits(&b.load_conc), "[{name}] {label} load conc");
        }
    }
}
