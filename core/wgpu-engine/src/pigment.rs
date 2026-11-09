//! Kubelka-Munk pigment mixing on the GPU (`shaders/pigment.wgsl`), spec/painterly.md phase P3.
//!
//! This is the WGSL port of Kotlin `PigmentMixer.mixLinearRgb`. [`mix_on_gpu`] is a standalone
//! entry (its own device) used by the parity test against `tests/fixtures/pigment_golden.txt`; the
//! per-pixel paint transaction (phase P5) reuses the same shader function.

use crate::gpu_wait::{map_read, READBACK_TIMEOUT};
use crate::shared_gpu;

pub const PIGMENT_WGSL: &str = include_str!("shaders/pigment.wgsl");

/// Floats per pigment in the tables buffer: K0..K6 then S0..S6.
pub const PIGMENT_TABLE_FLOATS: usize = 14;

/// Mixes `mixtures` (`m` rows of `n` concentrations) of the `n` pigments in `tables`
/// (`n * PIGMENT_TABLE_FLOATS` floats) on the best adapter of `backends`; `m * 3` linear RGB floats,
/// or `None` when there is no adapter or the dispatch failed.
pub fn mix_on_gpu(
    backends: wgpu::Backends,
    tables: &[f32],
    n: usize,
    mixtures: &[f32],
) -> Option<Vec<f32>> {
    if n == 0 || tables.len() != n * PIGMENT_TABLE_FLOATS || mixtures.len() % n != 0 {
        return None;
    }
    let m = mixtures.len() / n;
    if m == 0 {
        return Some(Vec::new());
    }
    let gpu = shared_gpu::shared(backends)?;
    let (device, queue) = (&gpu.device, &gpu.queue);
    let out_bytes = (m * 3 * 4) as u64;
    if !shared_gpu::fits(
        &gpu.limits,
        &[(tables.len() * 4) as u64, (mixtures.len() * 4) as u64, out_bytes],
        3,
        64,
    ) || !shared_gpu::groups_fit(&gpu.limits, m.div_ceil(64) as u64)
    {
        log::warn!("graffux-wgpu pigment: {m} mixtures of {n} pigments exceed this adapter's limits");
        return None;
    }
    gpu.scoped("pigment", || {
    let pipeline = gpu.cached("pigment km_mix", || {
        let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("pigment"),
            source: wgpu::ShaderSource::Wgsl(PIGMENT_WGSL.into()),
        });
        device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("pigment km_mix"),
            layout: None,
            module: &module,
            entry_point: Some("km_mix"),
            compilation_options: Default::default(),
            cache: None,
        })
    });
    let storage = |label: &str, bytes: &[u8], extra: wgpu::BufferUsages| {
        let buf = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some(label),
            size: bytes.len().max(4) as u64,
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST | extra,
            mapped_at_creation: false,
        });
        queue.write_buffer(&buf, 0, bytes);
        buf
    };
    let tables_buf = storage("tables", bytemuck::cast_slice(tables), wgpu::BufferUsages::empty());
    let mix_buf = storage("mixtures", bytemuck::cast_slice(mixtures), wgpu::BufferUsages::empty());
    let out_buf = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("out"),
        size: out_bytes,
        usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
        mapped_at_creation: false,
    });
    let staging = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("staging"),
        size: out_bytes,
        usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    let params: [u32; 4] = [n as u32, m as u32, 0, 0];
    let uniform = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("params"),
        size: 16,
        usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    queue.write_buffer(&uniform, 0, bytemuck::cast_slice(&params));
    let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: Some("pigment bind"),
        layout: &pipeline.get_bind_group_layout(0),
        entries: &[
            wgpu::BindGroupEntry { binding: 0, resource: tables_buf.as_entire_binding() },
            wgpu::BindGroupEntry { binding: 1, resource: mix_buf.as_entire_binding() },
            wgpu::BindGroupEntry { binding: 2, resource: out_buf.as_entire_binding() },
            wgpu::BindGroupEntry { binding: 3, resource: uniform.as_entire_binding() },
        ],
    });
    let mut encoder = device.create_command_encoder(&Default::default());
    {
        let mut pass = encoder.begin_compute_pass(&Default::default());
        pass.set_pipeline(&pipeline);
        pass.set_bind_group(0, &bind, &[]);
        pass.dispatch_workgroups(((m + 63) / 64) as u32, 1, 1);
    }
    encoder.copy_buffer_to_buffer(&out_buf, 0, &staging, 0, out_bytes);
    queue.submit([encoder.finish()]);
    map_read(device, &staging, 0..out_bytes, READBACK_TIMEOUT, |v| {
        bytemuck::cast_slice::<u8, f32>(v).to_vec()
    })
    .ok()
    })
}
