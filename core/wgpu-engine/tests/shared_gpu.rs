//! The standalone compute entries share one device and their pipelines, and refuse inputs the adapter cannot hold
//! instead of panicking. Before: every call built an Instance, a device and its pipelines (pigment ~52 ms/call,
//! transaction ~160 ms/call on software Vulkan, debug build, tiny inputs).

mod common;

use std::sync::atomic::Ordering;
use std::time::Instant;

use graffux_wgpu::pigment::{mix_on_gpu, PIGMENT_TABLE_FLOATS};
use graffux_wgpu::shared_gpu::{fits, groups_fit, DEVICES_CREATED, PIPELINES_BUILT};
use graffux_wgpu::transaction::{run_on_gpu, TransactionInput, CH, DAB_FLOATS};
use graffux_wgpu::watercolour::{self, WatercolourInput};
use graffux_wgpu::wgpu::{Backends, Limits};

fn tx_input() -> TransactionInput {
    let (w, h) = (16, 16);
    let px = (w * h) as usize;
    let mut d = vec![0.0; DAB_FLOATS];
    d[0] = 8.0;
    d[1] = 8.0;
    d[2] = 5.0;
    d[3] = 0.5;
    d[6] = 0.5;
    TransactionInput {
        width: w, height: h, conc: vec![0.0625; px * CH], amount: vec![1.0; px], lanes: 1, capacity: 1.0,
        load_amount: vec![0.0], load_conc: vec![0.0; CH], dabs: d,
    }
}

#[test]
fn repeated_calls_share_one_device_and_build_each_pipeline_once() {
    let b = Backends::VULKAN;
    let tables = vec![0.5f32; 3 * PIGMENT_TABLE_FLOATS];
    let mix = vec![0.3f32; 3 * 8];
    if mix_on_gpu(b, &tables, 3, &mix).is_none() {
        common::no_adapter("vulkan");
        return;
    }
    let (devices, pipes) = (DEVICES_CREATED.load(Ordering::SeqCst), PIPELINES_BUILT.load(Ordering::SeqCst));
    let n = 20;
    let t = Instant::now();
    for _ in 0..n {
        assert!(mix_on_gpu(b, &tables, 3, &mix).is_some());
        assert!(run_on_gpu(b, &tx_input()).is_some());
    }
    let per = t.elapsed() / (2 * n);
    eprintln!("pigment + transaction: {per:?}/call over {} calls (before: ~52 ms and ~160 ms)", 2 * n);
    // The transaction pipeline is the one new thing the loop built; nothing else was rebuilt.
    assert_eq!(DEVICES_CREATED.load(Ordering::SeqCst), devices, "a call made another device");
    assert_eq!(PIPELINES_BUILT.load(Ordering::SeqCst) - pipes, 1, "pipelines were rebuilt");
}

#[test]
fn an_input_the_adapter_cannot_dispatch_is_refused_not_a_panic() {
    // 2048 x 2048 texels need 65,536 workgroups of 64: one more than the per-dimension limit of 65,535.
    let n = 2048 * 2048;
    let input = WatercolourInput {
        width: 2048, height: 2048, wet: vec![0.0; n], pigment: vec![0.0; n], absorbency: vec![0.0; n],
        tooth: vec![0.0; n], deposit_amount: vec![0.0; n], deposit_water: vec![0.0; n], ticks: 1, ..Default::default()
    };
    match watercolour::run_on_gpu(Backends::VULKAN, &input) {
        None => {}
        Some(_) => eprintln!("this adapter allows that many workgroups"),
    }
}

#[test]
fn limits_are_checked_before_any_buffer_exists() {
    let small = Limits { max_storage_buffer_binding_size: 1024, max_buffer_size: 4096, ..Limits::default() };
    assert!(fits(&small, &[1024, 512], 3, 64));
    assert!(!fits(&small, &[1025], 3, 64), "binding size");
    let tiny_buffer = Limits { max_buffer_size: 100, ..Limits::default() };
    assert!(!fits(&tiny_buffer, &[101], 3, 64), "buffer size");
    let few = Limits { max_storage_buffers_per_shader_stage: 4, ..Limits::default() };
    assert!(!fits(&few, &[4], 8, 64), "storage buffer count");
    let narrow = Limits { max_compute_invocations_per_workgroup: 32, ..Limits::default() };
    assert!(!fits(&narrow, &[4], 3, 64), "workgroup size");
    let groups = Limits { max_compute_workgroups_per_dimension: 10, ..Limits::default() };
    assert!(groups_fit(&groups, 10) && !groups_fit(&groups, 11));
}
