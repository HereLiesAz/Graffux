//! The paint transaction on the GPU (`shaders/transaction.wgsl`), spec/painterly.md phase P5.
//!
//! A WGSL port of Kotlin `PaintTransaction.run`. [`run_on_gpu`] is a standalone entry (its own device)
//! used by the parity test against `tests/fixtures/transaction_golden.txt`; wiring it to the resident
//! layer, the stamp pipeline and the editor is the remaining part of phase P5.

use crate::gpu_wait::{map_read, READBACK_TIMEOUT};
use crate::shared_gpu;

pub const TRANSACTION_WGSL: &str = include_str!("shaders/transaction.wgsl");

/// Channels per texel (12 tubes + 4 basis).
pub const CH: usize = 16;
/// Floats per dab: x y radius hardness lane draw lift mixWet dispose + 3 pad.
pub const DAB_FLOATS: usize = 12;

#[derive(Clone, Debug, Default)]
pub struct TransactionInput {
    pub width: i32,
    pub height: i32,
    /// `width * height * CH` concentrations as q/255.
    pub conc: Vec<f32>,
    pub amount: Vec<f32>,
    pub lanes: u32,
    pub capacity: f32,
    pub load_amount: Vec<f32>,
    pub load_conc: Vec<f32>,
    /// `DAB_FLOATS` per dab.
    pub dabs: Vec<f32>,
}

#[derive(Clone, Debug, Default)]
pub struct TransactionOutput {
    pub conc: Vec<f32>,
    pub amount: Vec<f32>,
    pub avail: Vec<f32>,
    pub load_amount: Vec<f32>,
    pub load_conc: Vec<f32>,
}

pub fn run_on_gpu(backends: wgpu::Backends, input: &TransactionInput) -> Option<TransactionOutput> {
    let px = (input.width.max(0) * input.height.max(0)) as usize;
    if px == 0
        || input.conc.len() != px * CH
        || input.amount.len() != px
        || input.load_amount.len() != input.lanes as usize
        || input.load_conc.len() != input.lanes as usize * CH
        || input.dabs.len() % DAB_FLOATS != 0
    {
        return None;
    }
    let dab_count = input.dabs.len() / DAB_FLOATS;
    let gpu = shared_gpu::shared(backends)?;
    let (device, queue) = (&gpu.device, &gpu.queue);
    let f = 4u64;
    let (conc_b, px_b) = (input.conc.len() as u64 * f, px as u64 * f);
    if !shared_gpu::fits(
        &gpu.limits,
        &[conc_b, px_b, (input.load_conc.len().max(1) as u64) * f, (input.dabs.len().max(1) as u64) * f],
        8,
        64,
    ) {
        log::warn!("graffux-wgpu transaction: {}x{} with {} dabs exceeds this adapter's limits", input.width, input.height, dab_count);
        return None;
    }
    gpu.scoped("transaction", || {
    let pipeline = gpu.cached("transaction run", || {
        let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("transaction"),
            source: wgpu::ShaderSource::Wgsl(TRANSACTION_WGSL.into()),
        });
        device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("transaction run"),
            layout: None,
            module: &module,
            entry_point: Some("run"),
            compilation_options: Default::default(),
            cache: None,
        })
    });
    let usage = wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::COPY_SRC;
    let make = |label: &str, data: &[f32]| {
        let buf = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some(label),
            size: (data.len().max(1) * 4) as u64,
            usage,
            mapped_at_creation: false,
        });
        if !data.is_empty() {
            queue.write_buffer(&buf, 0, bytemuck::cast_slice(data));
        }
        buf
    };
    let conc = make("conc", &input.conc);
    let amount = make("amount", &input.amount);
    let avail = make("avail", &input.amount); // avail starts as the paint present at touchdown
    let snap = make("snap", &input.conc);
    let take = make("take", &vec![0.0; px]);
    let load_amount = make("load_amount", &input.load_amount);
    let load_conc = make("load_conc", &input.load_conc);
    let dabs = make("dabs", &input.dabs);
    let params: [u32; 8] = [
        input.width as u32,
        input.height as u32,
        dab_count as u32,
        input.lanes,
        input.capacity.to_bits(),
        0,
        0,
        0,
    ];
    let uniform = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("params"),
        size: 32,
        usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    queue.write_buffer(&uniform, 0, bytemuck::cast_slice(&params));
    let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: Some("transaction bind"),
        layout: &pipeline.get_bind_group_layout(0),
        entries: &[
            wgpu::BindGroupEntry { binding: 0, resource: conc.as_entire_binding() },
            wgpu::BindGroupEntry { binding: 1, resource: amount.as_entire_binding() },
            wgpu::BindGroupEntry { binding: 2, resource: avail.as_entire_binding() },
            wgpu::BindGroupEntry { binding: 3, resource: snap.as_entire_binding() },
            wgpu::BindGroupEntry { binding: 4, resource: take.as_entire_binding() },
            wgpu::BindGroupEntry { binding: 5, resource: load_amount.as_entire_binding() },
            wgpu::BindGroupEntry { binding: 6, resource: load_conc.as_entire_binding() },
            wgpu::BindGroupEntry { binding: 7, resource: dabs.as_entire_binding() },
            wgpu::BindGroupEntry { binding: 8, resource: uniform.as_entire_binding() },
        ],
    });
    let mut encoder = device.create_command_encoder(&Default::default());
    {
        let mut pass = encoder.begin_compute_pass(&Default::default());
        pass.set_pipeline(&pipeline);
        pass.set_bind_group(0, &bind, &[]);
        pass.dispatch_workgroups(1, 1, 1);
    }
    let outputs = [&conc, &amount, &avail, &load_amount, &load_conc];
    let sizes = [input.conc.len(), px, px, input.lanes as usize, input.load_conc.len()];
    let mut stagings = Vec::new();
    for (buf, len) in outputs.iter().zip(sizes) {
        let bytes = (len.max(1) * 4) as u64;
        let staging = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("staging"),
            size: bytes,
            usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        encoder.copy_buffer_to_buffer(buf, 0, &staging, 0, bytes);
        stagings.push((staging, len));
    }
    queue.submit([encoder.finish()]);
    let mut results: Vec<Vec<f32>> = Vec::new();
    for (staging, len) in &stagings {
        let v = map_read(device, staging, 0..((*len).max(1) * 4) as u64, READBACK_TIMEOUT, |b| {
            bytemuck::cast_slice::<u8, f32>(b)[..*len].to_vec()
        })
        .ok()?;
        results.push(v);
    }
    let mut it = results.into_iter();
    Some(TransactionOutput {
        conc: it.next()?,
        amount: it.next()?,
        avail: it.next()?,
        load_amount: it.next()?,
        load_conc: it.next()?,
    })
    })
}
