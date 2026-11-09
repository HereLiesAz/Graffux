//! The standalone compute entries share one device and their pipelines, and refuse inputs the adapter cannot hold
//! instead of panicking. Before: every call built an Instance, a device and its pipelines (pigment ~52 ms/call,
//! transaction ~160 ms/call on software Vulkan, debug build, tiny inputs).

mod common;

use std::sync::atomic::Ordering;
use std::sync::Mutex;
use std::time::Instant;

use graffux_wgpu::pigment::{mix_on_gpu, PIGMENT_TABLE_FLOATS};
use graffux_wgpu::shared_gpu::{fits, groups_fit, DEVICES_CREATED, PIPELINES_BUILT};
use graffux_wgpu::transaction::{run_on_gpu, TransactionInput, CH, DAB_FLOATS};
use graffux_wgpu::watercolour::{self, WatercolourInput};
use graffux_wgpu::wgpu::{Backends, Limits};

/// The device and pipeline counters are process-wide; tests that read them take turns.
static SERIAL: Mutex<()> = Mutex::new(());

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
    let _turn = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
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
    let _turn = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    // 2048 x 2048 texels need 65,536 workgroups of 64: one more than the per-dimension limit of 65,535.
    let n = 2048 * 2048;
    let input = WatercolourInput {
        width: 2048, height: 2048, wet: vec![0.0; n], pigment: vec![0.0; n], absorbency: vec![0.0; n],
        tooth: vec![0.0; n], deposit_amount: vec![0.0; n], deposit_water: vec![0.0; n], ticks: 1, ..Default::default()
    };
    let Some(gpu) = graffux_wgpu::shared_gpu::shared(Backends::VULKAN) else {
        common::no_adapter("vulkan");
        return;
    };
    let out = watercolour::run_on_gpu(Backends::VULKAN, &input);
    if groups_fit(&gpu.limits, (n as u64).div_ceil(64)) {
        assert!(out.is_some(), "this adapter allows that many workgroups, so the run must succeed");
    } else {
        assert!(out.is_none(), "one workgroup over the limit must be refused, not dispatched");
    }
}

/// Counts errors that reach the device's uncaptured-error handler (instead of panicking the test).
fn count_uncaptured(device: &graffux_wgpu::wgpu::Device) -> std::sync::Arc<std::sync::atomic::AtomicUsize> {
    let n = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let c = n.clone();
    device.on_uncaptured_error(std::sync::Arc::new(move |_| {
        c.fetch_add(1, Ordering::SeqCst);
    }));
    n
}

fn bad_buffer(device: &graffux_wgpu::wgpu::Device) {
    // No usage at all: a validation error.
    let _ = device.create_buffer(&graffux_wgpu::wgpu::BufferDescriptor {
        label: Some("invalid"),
        size: 16,
        usage: graffux_wgpu::wgpu::BufferUsages::empty(),
        mapped_at_creation: false,
    });
}

#[test]
fn a_panic_inside_a_scoped_call_leaves_its_error_scopes_paired() {
    let _turn = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    let Some(gpu) = graffux_wgpu::shared_gpu::shared(Backends::VULKAN) else {
        common::no_adapter("vulkan");
        return;
    };
    let seen = count_uncaptured(&gpu.device);
    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        gpu.scoped("panics", || -> Option<()> { panic!("body panicked") })
    }));
    assert!(r.is_err());
    // A stale scope left on the stack would swallow this error; paired scopes let it reach the handler.
    bad_buffer(&gpu.device);
    assert_eq!(seen.load(Ordering::SeqCst), 1, "an error outside any scope must not be swallowed");
    // A later scoped call still captures its own error.
    let caught = gpu.scoped("later", || {
        bad_buffer(&gpu.device);
        Some(())
    });
    assert!(caught.is_none(), "the call's own scope must capture its error");
    assert_eq!(seen.load(Ordering::SeqCst), 1, "and it must not leak to the handler");
}

#[test]
fn a_refused_map_raises_no_second_error_from_an_unmap() {
    use graffux_wgpu::gpu_wait::{map_read, ReadFailure};
    use graffux_wgpu::wgpu;
    // Its own device with a counting handler and no error scope: a scope keeps only the first error, which here
    // is the refusal itself, so a second one (unmapping an idle buffer) would be invisible inside a scope.
    let mut desc = wgpu::InstanceDescriptor::new_without_display_handle();
    desc.backends = Backends::VULKAN;
    let instance = wgpu::Instance::new(desc);
    let Some(adapter) = pollster::block_on(instance.enumerate_adapters(Backends::VULKAN)).into_iter().next() else {
        common::no_adapter("vulkan");
        return;
    };
    let (device, _queue) = pollster::block_on(adapter.request_device(&Default::default())).unwrap();
    let seen = count_uncaptured(&device);
    let wrong = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("no map usage"),
        size: 16,
        usage: wgpu::BufferUsages::STORAGE,
        mapped_at_creation: false,
    });
    let r = map_read(&device, &wrong, 0..16, std::time::Duration::from_secs(5), |_| ());
    assert_eq!(r, Err(ReadFailure::Failed));
    assert_eq!(seen.load(Ordering::SeqCst), 1, "only the refusal itself may raise an error");
}

#[test]
fn a_timed_out_read_retires_the_shared_device_and_the_next_call_gets_a_fresh_one() {
    use graffux_wgpu::gpu_wait::ReadFailure;
    use graffux_wgpu::wgpu;
    let _turn = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    let Some(gpu) = graffux_wgpu::shared_gpu::shared(Backends::VULKAN) else {
        common::no_adapter("vulkan");
        return;
    };
    let staging = gpu.device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("staging"),
        size: 16,
        usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    let before = DEVICES_CREATED.load(Ordering::SeqCst);
    let r = gpu.read_with(&staging, 0..16, |_| Err(wgpu::PollError::Timeout), |_| ());
    assert_eq!(r, Err(ReadFailure::TimedOut));
    let fresh = graffux_wgpu::shared_gpu::shared(Backends::VULKAN).expect("a replacement device");
    assert!(!std::sync::Arc::ptr_eq(&gpu, &fresh), "the hung device was handed out again");
    assert_eq!(DEVICES_CREATED.load(Ordering::SeqCst), before + 1);
    // A refusal that is not a timeout does not retire it.
    let r = fresh.read_with(&staging, 0..16, |_| Err(wgpu::PollError::WrongSubmissionIndex(0, 0)), |_| ());
    assert_eq!(r, Err(ReadFailure::Failed));
    assert!(std::sync::Arc::ptr_eq(&fresh, &graffux_wgpu::shared_gpu::shared(Backends::VULKAN).unwrap()));
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

#[test]
fn a_read_waits_for_its_own_submission_not_the_devices_latest() {
    use graffux_wgpu::gpu_wait::{wait_poll, READBACK_TIMEOUT};
    use graffux_wgpu::wgpu;
    let _turn = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    let Some(gpu) = graffux_wgpu::shared_gpu::shared(Backends::VULKAN) else {
        common::no_adapter("vulkan");
        return;
    };
    let mine = gpu.queue.submit([gpu.device.create_command_encoder(&Default::default()).finish()]);
    // Another thread's later submission moves "the latest" on; the wait must still name mine.
    let _later = gpu.queue.submit([gpu.device.create_command_encoder(&Default::default()).finish()]);
    match wait_poll(Some(mine.clone()), READBACK_TIMEOUT) {
        wgpu::PollType::Wait { submission_index: Some(i), timeout: Some(t) } => {
            assert_eq!(format!("{i:?}"), format!("{mine:?}"), "the index named is the caller's own");
            assert_eq!(t, READBACK_TIMEOUT);
        }
        other => panic!("not a wait on a specific submission: {other:?}"),
    }
    assert!(matches!(
        wait_poll(None, READBACK_TIMEOUT),
        wgpu::PollType::Wait { submission_index: None, .. }
    ));
}
