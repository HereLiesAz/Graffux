//! The paint transaction on the GPU (`shaders/transaction.wgsl`), spec/painterly.md phase P5.
//!
//! A WGSL port of Kotlin `PaintTransaction.run`. [`run_on_gpu`] is a standalone entry (its own device)
//! used by the parity test against `tests/fixtures/transaction_golden.txt`; wiring it to the resident
//! layer, the stamp pipeline and the editor is the remaining part of phase P5.

use crate::gpu_wait::{map_read, wait_bounded, READBACK_TIMEOUT};

pub const TRANSACTION_WGSL: &str = include_str!("shaders/transaction.wgsl");

/// Channels per texel (12 tubes + 4 basis).
pub const CH: usize = 16;
/// Floats per dab: x y radius hardness lane draw lift mixWet dispose + 3 pad.
pub const DAB_FLOATS: usize = 12;

/// Work one dispatch may carry, in texel visits (a dab's clipped bounding box). One workgroup walks every dab of a
/// dispatch alone, so an unbounded stroke is one unbounded dispatch: a long watchdog window that a mobile or desktop
/// driver answers by resetting the device. A conservative guess, not measured on real hardware.
pub const MAX_CHUNK_TEXELS: u64 = 200_000;
/// Dabs one dispatch may carry however small they are (each dab costs a dozen barriers).
pub const MAX_CHUNK_DABS: usize = 512;

/// Splits the dabs into consecutive ranges, each within the budget (a single dab over it gets a range of its own).
/// Every dab is in exactly one range, in order, so chunked runs compose exactly as one dispatch would.
pub fn plan_chunks(
    dabs: &[f32],
    width: i32,
    height: i32,
    max_texels: u64,
    max_dabs: usize,
) -> Vec<std::ops::Range<usize>> {
    let count = dabs.len() / DAB_FLOATS;
    let mut out = Vec::new();
    let (mut start, mut texels) = (0usize, 0u64);
    for i in 0..count {
        let d = &dabs[i * DAB_FLOATS..];
        let r = d[2];
        let cost = if r > 0.0 {
            let w = ((d[0] + r).ceil().min((width - 1) as f32) - (d[0] - r).floor().max(0.0) + 1.0).max(0.0);
            let h = ((d[1] + r).ceil().min((height - 1) as f32) - (d[1] - r).floor().max(0.0) + 1.0).max(0.0);
            (w as u64) * (h as u64) + 1
        } else {
            1
        };
        if i > start && (texels + cost > max_texels || i - start >= max_dabs.max(1)) {
            out.push(start..i);
            start = i;
            texels = 0;
        }
        texels += cost;
    }
    if count > start {
        out.push(start..count);
    }
    out
}

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
    /// Dispatches (submissions) the run took: one per [`plan_chunks`] range.
    pub dispatches: usize,
    pub conc: Vec<f32>,
    pub amount: Vec<f32>,
    pub avail: Vec<f32>,
    pub load_amount: Vec<f32>,
    pub load_conc: Vec<f32>,
}

pub fn run_on_gpu(backends: wgpu::Backends, input: &TransactionInput) -> Option<TransactionOutput> {
    run_chunked(backends, input, MAX_CHUNK_TEXELS, MAX_CHUNK_DABS)
}

/// [`run_on_gpu`] with explicit chunk budgets (tests shrink them to force many dispatches).
pub fn run_chunked(
    backends: wgpu::Backends,
    input: &TransactionInput,
    max_texels: u64,
    max_dabs: usize,
) -> Option<TransactionOutput> {
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
    let mut desc = wgpu::InstanceDescriptor::new_without_display_handle();
    desc.backends = backends;
    let instance = wgpu::Instance::new(desc);
    let adapters = pollster::block_on(instance.enumerate_adapters(backends));
    let adapter = adapters.into_iter().find(|a| {
        a.get_downlevel_capabilities()
            .flags
            .contains(wgpu::DownlevelFlags::COMPUTE_SHADERS)
    })?;
    let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
        label: Some("graffux-transaction"),
        required_limits: adapter.limits(),
        ..Default::default()
    }))
    .ok()?;
    let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some("transaction"),
        source: wgpu::ShaderSource::Wgsl(TRANSACTION_WGSL.into()),
    });
    let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
        label: Some("transaction run"),
        layout: None,
        module: &module,
        entry_point: Some("run"),
        compilation_options: Default::default(),
        cache: None,
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
    let chunks = plan_chunks(&input.dabs, input.width, input.height, max_texels, max_dabs);
    let mut dispatches = 0;
    // An empty stroke still takes one (empty) dispatch, as before.
    for range in if chunks.is_empty() { vec![0..0] } else { chunks } {
        let params: [u32; 8] = [
            input.width as u32,
            input.height as u32,
            range.len() as u32,
            input.lanes,
            input.capacity.to_bits(),
            range.start as u32,
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
        queue.submit([encoder.finish()]);
        // Each dispatch is its own bounded submission: a stroke never holds the GPU for longer than one chunk.
        if !wait_bounded(&device, READBACK_TIMEOUT) {
            return None;
        }
        dispatches += 1;
    }
    let mut encoder = device.create_command_encoder(&Default::default());
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
        let v = map_read(&device, staging, 0..((*len).max(1) * 4) as u64, READBACK_TIMEOUT, |b| {
            bytemuck::cast_slice::<u8, f32>(b)[..*len].to_vec()
        })
        .ok()?;
        results.push(v);
    }
    let mut it = results.into_iter();
    Some(TransactionOutput {
        dispatches,
        conc: it.next()?,
        amount: it.next()?,
        avail: it.next()?,
        load_amount: it.next()?,
        load_conc: it.next()?,
    })
}
