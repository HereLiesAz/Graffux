//! A readback waits for the GPU for a bounded time. It used to wait forever (`wait_indefinitely`), so a GPU
//! that never finished hung the calling thread, and with it the Kotlin engine's lock. A timeout now fails the
//! call and the engine instead.
//!
//! A software rasteriser runs the work inside `queue.submit`, so a genuine stall cannot be staged on it; the
//! tests stand in for the GPU's silence with a wait that reports a timeout (`map_read_with`,
//! `Engine::simulate_readback_stall`) and check what the code does about it.

mod common;

use std::time::{Duration, Instant};

use graffux_wgpu::gpu_wait::{map_read, map_read_with, ReadFailure};
use graffux_wgpu::wgpu::{self, Backends};
use graffux_wgpu::Engine;

fn device(backends: Backends) -> Option<(wgpu::Device, wgpu::Queue)> {
    let mut desc = wgpu::InstanceDescriptor::new_without_display_handle();
    desc.backends = backends;
    let instance = wgpu::Instance::new(desc);
    let adapter = pollster::block_on(instance.enumerate_adapters(backends))
        .into_iter()
        .find(|a| {
            a.get_downlevel_capabilities()
                .flags
                .contains(wgpu::DownlevelFlags::COMPUTE_SHADERS)
        })?;
    pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor::default())).ok()
}

fn filled_staging(device: &wgpu::Device, queue: &wgpu::Queue, bytes: &[u8]) -> wgpu::Buffer {
    let src = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("src"),
        size: bytes.len() as u64,
        usage: wgpu::BufferUsages::COPY_SRC | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    queue.write_buffer(&src, 0, bytes);
    let staging = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("staging"),
        size: bytes.len() as u64,
        usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    let mut encoder = device.create_command_encoder(&Default::default());
    encoder.copy_buffer_to_buffer(&src, 0, &staging, 0, bytes.len() as u64);
    queue.submit([encoder.finish()]);
    staging
}

#[test]
fn a_wait_that_times_out_is_reported_and_leaves_the_buffer_mappable() {
    for (name, b) in [("vulkan", Backends::VULKAN), ("gl", Backends::GL)] {
        let Some((device, queue)) = device(b) else {
            common::no_adapter(name);
            continue;
        };
        let staging = filled_staging(&device, &queue, &[7u8; 64]);
        let stalled = map_read_with(
            &device,
            &staging,
            0..64,
            |_| Err(wgpu::PollError::Timeout),
            |_| (),
        );
        assert_eq!(stalled, Err(ReadFailure::TimedOut), "[{name}]");
        // The pending map was cancelled: mapping the same buffer again is not a validation error.
        let again = map_read(&device, &staging, 0..64, Duration::from_secs(5), |v| v.to_vec());
        assert_eq!(again, Ok(vec![7u8; 64]), "[{name}] the buffer must be usable after a timeout");
    }
}

#[test]
fn a_wait_that_fails_for_another_reason_is_not_called_a_timeout() {
    for (name, b) in [("vulkan", Backends::VULKAN), ("gl", Backends::GL)] {
        let Some((device, queue)) = device(b) else {
            common::no_adapter(name);
            continue;
        };
        let staging = filled_staging(&device, &queue, &[1u8; 16]);
        let failed = map_read_with(
            &device,
            &staging,
            0..16,
            |_| Err(wgpu::PollError::WrongSubmissionIndex(0, 0)),
            |_| (),
        );
        assert_eq!(failed, Err(ReadFailure::Failed), "[{name}]");
    }
}

#[test]
fn a_bounded_read_returns_the_bytes() {
    for (name, b) in [("vulkan", Backends::VULKAN), ("gl", Backends::GL)] {
        let Some((device, queue)) = device(b) else {
            common::no_adapter(name);
            continue;
        };
        let staging = filled_staging(&device, &queue, &[9u8; 32]);
        let got = map_read(&device, &staging, 0..32, Duration::from_secs(5), |v| v.to_vec());
        assert_eq!(got, Ok(vec![9u8; 32]), "[{name}]");
    }
}

#[test]
fn a_stalled_readback_fails_the_engine_at_once_instead_of_every_call_waiting_again() {
    for (name, b) in [("vulkan", Backends::VULKAN), ("gl", Backends::GL)] {
        let Some(mut e) = Engine::with_backends(64, 64, b) else {
            common::no_adapter(name);
            continue;
        };
        assert!(e.upload(&[200u8; 64 * 64 * 4]));
        let mut out = vec![0u8; 64 * 64 * 4];
        e.simulate_readback_stall();
        let t = Instant::now();
        assert!(!e.readback(&mut out), "[{name}] a readback the GPU never answers must fail");
        // The engine is failed now, like after an uncaptured device error: later calls fail fast.
        let t2 = Instant::now();
        assert!(e.read_all().is_none(), "[{name}] a stalled engine must fail every later call");
        assert!(!e.upload(&[0u8; 64 * 64 * 4]), "[{name}] writes fail too");
        assert!(t.elapsed() < Duration::from_secs(2), "[{name}] the failure must not wait");
        assert!(t2.elapsed() < Duration::from_millis(500), "[{name}] later calls answered slowly");
    }
}

#[test]
fn a_healthy_readback_is_unaffected_and_leaves_nothing_mapped() {
    for (name, b) in [("vulkan", Backends::VULKAN), ("gl", Backends::GL)] {
        let Some(mut e) = Engine::with_backends(64, 64, b) else {
            common::no_adapter(name);
            continue;
        };
        assert!(e.upload(&[200u8; 64 * 64 * 4]));
        let all = e.read_all().expect("read_all within the default timeout");
        assert!(all.iter().all(|&v| v == 200), "[{name}] pixels must survive the bounded read");
        assert!(e.read_all().is_some(), "[{name}] reading again");
    }
}
