//! Per-pass GPU timings from wgpu timestamp queries (`Features::TIMESTAMP_QUERY`).
//!
//! Each timed pass gets a begin/end query pair. Queries are resolved lazily, in one batch, when the
//! caller drains ([`GpuTimer::take`]) or when every pair is in use, so a stamp call never waits on
//! the GPU just to be timed. Adapters without timestamp support get no timer at all; the caller
//! (Kotlin `GpuPassTimings`) then falls back to CPU wall time and labels it so.

/// Pass kinds, shared with the C ABI / JNI and Kotlin `GpuPassKind`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u32)]
pub enum PassKind {
    Stamp = 0,
    Readback = 1,
    Composite = 2,
    Smudge = 3,
    /// Reserved for the multipass draft/clarity scheduler.
    Multipass = 4,
}

/// Query pairs held before a forced resolve.
const PAIRS: u32 = 128;
/// Samples kept between drains; older ones are dropped first.
const MAX_SAMPLES: usize = 1024;

pub struct GpuTimer {
    set: wgpu::QuerySet,
    resolve: wgpu::Buffer,
    read: wgpu::Buffer,
    period_ns: f64,
    /// Whether copies (readback) can be timed: `TIMESTAMP_QUERY_INSIDE_ENCODERS`.
    pub inside_encoders: bool,
    pending: Vec<PassKind>,
    samples: Vec<(PassKind, u64)>,
}

impl GpuTimer {
    /// `None` unless `device` was created with `TIMESTAMP_QUERY`.
    pub fn new(device: &wgpu::Device, queue: &wgpu::Queue) -> Option<GpuTimer> {
        let features = device.features();
        if !features.contains(wgpu::Features::TIMESTAMP_QUERY) {
            return None;
        }
        let period_ns = queue.get_timestamp_period() as f64;
        if period_ns <= 0.0 {
            return None;
        }
        let set = device.create_query_set(&wgpu::QuerySetDescriptor {
            label: Some("pass timestamps"),
            ty: wgpu::QueryType::Timestamp,
            count: PAIRS * 2,
        });
        let bytes = PAIRS as u64 * 2 * 8;
        let resolve = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("timestamp resolve"),
            size: bytes,
            usage: wgpu::BufferUsages::QUERY_RESOLVE | wgpu::BufferUsages::COPY_SRC,
            mapped_at_creation: false,
        });
        let read = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("timestamp read"),
            size: bytes,
            usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        Some(GpuTimer {
            set,
            resolve,
            read,
            period_ns,
            inside_encoders: features.contains(wgpu::Features::TIMESTAMP_QUERY_INSIDE_ENCODERS),
            pending: Vec::new(),
            samples: Vec::new(),
        })
    }

    /// Reserves the next pair for a pass of `kind`, resolving first when all are in use.
    /// Returns the begin index (end = begin + 1).
    pub fn reserve(&mut self, device: &wgpu::Device, queue: &wgpu::Queue, kind: PassKind) -> u32 {
        if self.pending.len() as u32 >= PAIRS {
            self.resolve_pending(device, queue);
        }
        let index = self.pending.len() as u32 * 2;
        self.pending.push(kind);
        index
    }

    /// Compute-pass timestamp writes for the pair starting at `begin`.
    pub fn pass_writes(&self, begin: u32) -> wgpu::ComputePassTimestampWrites<'_> {
        wgpu::ComputePassTimestampWrites {
            query_set: &self.set,
            beginning_of_pass_write_index: Some(begin),
            end_of_pass_write_index: Some(begin + 1),
        }
    }

    pub fn query_set(&self) -> &wgpu::QuerySet {
        &self.set
    }

    /// Resolves every reserved pair into samples. Waits for the GPU once.
    fn resolve_pending(&mut self, device: &wgpu::Device, queue: &wgpu::Queue) {
        let n = self.pending.len() as u32;
        if n == 0 {
            return;
        }
        let bytes = n as u64 * 2 * 8;
        let mut encoder = device.create_command_encoder(&Default::default());
        encoder.resolve_query_set(&self.set, 0..n * 2, &self.resolve, 0);
        encoder.copy_buffer_to_buffer(&self.resolve, 0, &self.read, 0, bytes);
        queue.submit([encoder.finish()]);
        let slice = self.read.slice(0..bytes);
        let (tx, rx) = std::sync::mpsc::channel();
        slice.map_async(wgpu::MapMode::Read, move |res| {
            let _ = tx.send(res.is_ok());
        });
        let kinds = std::mem::take(&mut self.pending);
        if device.poll(wgpu::PollType::wait_indefinitely()).is_err() || !matches!(rx.recv(), Ok(true)) {
            return;
        }
        if let Ok(mapped) = slice.get_mapped_range() {
            let ticks: &[u64] = bytemuck::cast_slice(&mapped);
            for (i, kind) in kinds.iter().enumerate() {
                let (begin, end) = (ticks[i * 2], ticks[i * 2 + 1]);
                // A pass the driver skipped, or a wrapped counter, reports end <= begin: drop it.
                if end > begin {
                    let ns = ((end - begin) as f64 * self.period_ns) as u64;
                    self.samples.push((*kind, ns));
                }
            }
        }
        self.read.unmap();
        if self.samples.len() > MAX_SAMPLES {
            let excess = self.samples.len() - MAX_SAMPLES;
            self.samples.drain(0..excess);
        }
    }

    /// Resolves what is pending and hands over every sample since the last call.
    pub fn take(&mut self, device: &wgpu::Device, queue: &wgpu::Queue) -> Vec<(PassKind, u64)> {
        self.resolve_pending(device, queue);
        std::mem::take(&mut self.samples)
    }
}
