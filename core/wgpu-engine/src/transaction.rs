//! The paint transaction on the GPU (`shaders/transaction.wgsl`), spec/painterly.md phase P5.
//!
//! A WGSL port of Kotlin `PaintTransaction.run`. [`run_on_gpu`] is a standalone entry (its own device)
//! used by the parity test against `tests/fixtures/transaction_golden.txt`; wiring it to the resident
//! layer, the stamp pipeline and the editor is the remaining part of phase P5.

use crate::gpu_wait::{wait_submission, ReadFailure, READBACK_TIMEOUT};
use crate::shared_gpu;

pub const TRANSACTION_WGSL: &str = include_str!("shaders/transaction.wgsl");

/// Channels per texel (12 tubes + 4 basis).
pub const CH: usize = 16;
/// Floats per dab: x y radius hardness lane draw lift mixWet dispose + 3 pad.
pub const DAB_FLOATS: usize = 12;

/// Estimated work per texel a dab covers, in arbitrary units. Read off the shader, not measured: the lift pass visits
/// each texel to take, to scale, 16 times for the per-channel sums and once for the total (about 20 visits); the draw
/// pass visits it to total the demand and to deposit, where the deposit mixes and quantises 16 channels (about 20
/// more). So a texel is worth roughly 40 visits.
pub const COST_PER_TEXEL: u64 = 40;
/// Work one dispatch may carry, in the units above (200,000 texels). One workgroup of 64 threads walks every dab of a
/// dispatch alone, so an unbounded stroke is one unbounded dispatch: a long watchdog window that a driver answers by
/// resetting the device. UNMEASURED on real hardware: a conservative guess to be tuned from device timings.
pub const MAX_CHUNK_COST: u64 = 200_000 * COST_PER_TEXEL;
/// Work one dab may carry. A dab cannot be split (its lift, scale and draw need the whole footprint), so a chunk is
/// only as bounded as its biggest dab: a dab over this is refused (about 500,000 texels, a radius near 350) rather
/// than run as one unbounded dispatch. Also unmeasured.
pub const MAX_DAB_COST: u64 = 20_000_000;
/// Dabs one dispatch may carry however small they are (each dab costs a dozen barriers).
pub const MAX_CHUNK_DABS: usize = 512;

/// Why a transaction run produced nothing.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TransactionError {
    /// The input is malformed.
    Invalid,
    /// No adapter, or the input does not fit its limits.
    Unavailable,
    /// A single dab is over [`MAX_DAB_COST`] and cannot be bounded by chunking.
    DabTooLarge,
    /// The GPU did not finish a chunk in time; the shared device is replaced (work may still be in flight on it).
    TimedOut,
    /// A device error, a lost device or a refused map.
    Failed,
}

/// The estimated cost of one dab: its footprint box, clipped to the canvas, times [`COST_PER_TEXEL`].
pub fn dab_cost(dab: &[f32], width: i32, height: i32) -> u64 {
    let (x, y, r) = (dab[0], dab[1], dab[2]);
    if !(r > 0.0) {
        return 1;
    }
    let w = ((x + r).ceil().min((width - 1) as f32) - (x - r).floor().max(0.0) + 1.0).max(0.0);
    let h = ((y + r).ceil().min((height - 1) as f32) - (y - r).floor().max(0.0) + 1.0).max(0.0);
    ((w as u64) * (h as u64)).saturating_mul(COST_PER_TEXEL) + 1
}

/// Splits the dabs into consecutive ranges, each within `max_cost` and `max_dabs`; every dab is in exactly one
/// range, in order, so chunked runs compose exactly as one dispatch would. A dab alone over `max_dab_cost` is
/// refused (`DabTooLarge`); one over `max_cost` but within `max_dab_cost` gets a range of its own.
pub fn plan_chunks(
    dabs: &[f32],
    width: i32,
    height: i32,
    max_cost: u64,
    max_dab_cost: u64,
    max_dabs: usize,
) -> Result<Vec<(std::ops::Range<usize>, u64)>, TransactionError> {
    let count = dabs.len() / DAB_FLOATS;
    let mut out = Vec::new();
    let (mut start, mut total) = (0usize, 0u64);
    for i in 0..count {
        let cost = dab_cost(&dabs[i * DAB_FLOATS..], width, height);
        if cost > max_dab_cost {
            return Err(TransactionError::DabTooLarge);
        }
        if i > start && (total + cost > max_cost || i - start >= max_dabs.max(1)) {
            out.push((start..i, total));
            start = i;
            total = 0;
        }
        total += cost;
    }
    if count > start {
        out.push((start..count, total));
    }
    Ok(out)
}

/// The longest a chunk is ever waited for: a chosen cap, not an Android guarantee. The wait runs on the caller's
/// thread (which may hold a lock), so callers must keep it off the UI and input threads. The cost limits above are
/// chosen so that this cap is not what a legitimate chunk runs into (a test pins the uncapped sum at the largest
/// dab). No app code calls the standalone entries yet.
pub const MAX_CHUNK_WAIT: std::time::Duration = std::time::Duration::from_secs(10);

/// How long to wait for a chunk of estimated `cost`: the base timeout plus a quarter of a microsecond per cost unit
/// (7 s at [`MAX_CHUNK_COST`], 10 s at [`MAX_DAB_COST`]), capped at [`MAX_CHUNK_WAIT`]. Unmeasured.
///
/// The wait names this chunk's own submission, so work queued AFTER it never counts. Work queued BEFORE it by
/// other users of the shared device does, because the device finishes submissions in order: a chunk stuck behind
/// another thread's long job can time out and have a healthy device retired. That costs a device replacement, not a
/// wrong result, and is accepted: the standalone entries have no concurrent production callers, the allowance is
/// generous, and telling "slow behind others" from "hung" would need a progress probe wgpu does not offer cheaply.
pub fn chunk_timeout(cost: u64) -> std::time::Duration {
    (READBACK_TIMEOUT + std::time::Duration::from_micros(cost / 4)).min(MAX_CHUNK_WAIT)
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
    try_run_on_gpu(backends, input).ok()
}

/// [`run_on_gpu`] that says why it failed.
pub fn try_run_on_gpu(
    backends: wgpu::Backends,
    input: &TransactionInput,
) -> Result<TransactionOutput, TransactionError> {
    try_run_chunked(backends, input, MAX_CHUNK_COST, MAX_DAB_COST, MAX_CHUNK_DABS)
}

/// [`try_run_on_gpu`] with explicit chunk budgets (tests shrink them to force many dispatches).
pub fn try_run_chunked(
    backends: wgpu::Backends,
    input: &TransactionInput,
    max_cost: u64,
    max_dab_cost: u64,
    max_dabs: usize,
) -> Result<TransactionOutput, TransactionError> {
    let px = (input.width.max(0) * input.height.max(0)) as usize;
    if px == 0
        || input.conc.len() != px * CH
        || input.amount.len() != px
        || input.load_amount.len() != input.lanes as usize
        || input.load_conc.len() != input.lanes as usize * CH
        || input.dabs.len() % DAB_FLOATS != 0
    {
        return Err(TransactionError::Invalid);
    }
    let dab_count = input.dabs.len() / DAB_FLOATS;
    // Planned before any GPU work: a dab that cannot be bounded is refused up front.
    let plan = plan_chunks(&input.dabs, input.width, input.height, max_cost, max_dab_cost, max_dabs)?;
    let gpu = shared_gpu::shared(backends).ok_or(TransactionError::Unavailable)?;
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
        return Err(TransactionError::Unavailable);
    }
    let failure = std::cell::Cell::new(TransactionError::Failed);
    let out = gpu.scoped("transaction", || {
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
    let mut dispatches = 0;
    // An empty stroke still takes one (empty) dispatch, as before.
    for (range, cost) in if plan.is_empty() { vec![(0..0, 0)] } else { plan } {
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
        let submission = queue.submit([encoder.finish()]);
        // Each chunk is its own submission, waited for by its own index: work queued after it does not count (work
        // queued before it by other users does; see `chunk_timeout`).
        if let Err(why) = wait_submission(device, submission, chunk_timeout(cost)) {
            if why == ReadFailure::TimedOut {
                // Work may still be running on the shared device: stop handing it out.
                gpu.mark_stalled();
                failure.set(TransactionError::TimedOut);
            }
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
    let submission = queue.submit([encoder.finish()]);
    let mut results: Vec<Vec<f32>> = Vec::new();
    for (staging, len) in &stagings {
        let v = match gpu.read(staging, 0..((*len).max(1) * 4) as u64, submission.clone(), |b| {
            bytemuck::cast_slice::<u8, f32>(b)[..*len].to_vec()
        }) {
            Ok(v) => v,
            Err(why) => {
                if why == ReadFailure::TimedOut {
                    gpu.mark_stalled();
                    failure.set(TransactionError::TimedOut);
                }
                return None;
            }
        };
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
    });
    out.ok_or_else(|| failure.get())
}
