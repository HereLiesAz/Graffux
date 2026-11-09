//! One device per backend set for the standalone compute entries (`transaction::run_on_gpu`,
//! `pigment::mix_on_gpu`, `watercolour::run_on_gpu`).
//!
//! Each of those used to build a wgpu `Instance`, enumerate adapters, request a device and compile its shader and
//! pipeline on every call: tens to hundreds of milliseconds of setup around microseconds of work. They now share
//! a [`SharedGpu`] per backend set, created on first use, with their pipelines cached on it by name. A device
//! that was lost is dropped from the cache and made again on the next call.
//!
//! Every call also runs inside an error scope ([`SharedGpu::scoped`]): a validation or out-of-memory error fails
//! that call with `None` instead of reaching wgpu's default handler, which panics the process. The scopes are popped
//! by drop guards, so they stay paired even if the body panics.

use std::any::Any;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

/// Devices created by [`shared`] since the process started (tests, telemetry).
pub static DEVICES_CREATED: AtomicU64 = AtomicU64::new(0);
/// Pipelines (or pipeline sets) built through [`SharedGpu::cached`] since the process started.
pub static PIPELINES_BUILT: AtomicU64 = AtomicU64::new(0);

pub struct SharedGpu {
    // Kept so the instance outlives everything made from it.
    _instance: wgpu::Instance,
    pub device: wgpu::Device,
    pub queue: wgpu::Queue,
    pub limits: wgpu::Limits,
    lost: Arc<AtomicBool>,
    cache: Mutex<HashMap<&'static str, Box<dyn Any + Send + Sync>>>,
}

/// How long a failed attempt to get a device is believed before trying again. A missing adapter or a refused device
/// is sometimes transient (a driver still starting, a device just reset), so a failure is not kept for the life of the
/// process; but every call retrying would repeat an instance and adapter enumeration that costs tens of milliseconds.
pub const RETRY_AFTER: std::time::Duration = std::time::Duration::from_secs(2);

enum Slot<T> {
    Ready(Arc<T>),
    Failed(std::time::Instant),
}

/// The per-key cache behind [`shared`], generic so its retry and replacement rules are tested without a GPU.
pub(crate) struct Registry<T> {
    slots: Vec<(u32, Slot<T>)>,
}

impl<T> Registry<T> {
    pub(crate) const fn new() -> Self {
        Registry { slots: Vec::new() }
    }

    /// The value for `key`: the cached one while `is_dead` says it lives; a recent failure is answered with None
    /// until [`RETRY_AFTER`] has passed; otherwise `create` runs.
    pub(crate) fn get(
        &mut self,
        key: u32,
        clock: impl Fn() -> std::time::Instant,
        is_dead: impl Fn(&T) -> bool,
        create: impl FnOnce() -> Option<T>,
    ) -> Option<Arc<T>> {
        let now = clock();
        match self.slots.iter().find(|(k, _)| *k == key).map(|(_, s)| s) {
            Some(Slot::Ready(v)) if !is_dead(v) => return Some(v.clone()),
            Some(Slot::Failed(at)) if now.duration_since(*at) < RETRY_AFTER => return None,
            _ => {}
        }
        let made = create().map(Arc::new);
        self.slots.retain(|(k, _)| *k != key);
        self.slots.push((
            key,
            match &made {
                Some(v) => Slot::Ready(v.clone()),
                // Stamped after `create` returns, so a slow attempt is not already stale when it fails.
                None => Slot::Failed(clock()),
            },
        ));
        made
    }
}

static DEVICES: Mutex<Registry<SharedGpu>> = Mutex::new(Registry::new());

/// The shared device for `backends` (the best adapter with compute shaders), or None when there is none. A lost
/// device is replaced; a failure to get one is retried after [`RETRY_AFTER`].
pub fn shared(backends: wgpu::Backends) -> Option<Arc<SharedGpu>> {
    let mut devices = DEVICES.lock().unwrap_or_else(|e| e.into_inner());
    devices.get(
        backends.bits(),
        std::time::Instant::now,
        |gpu| gpu.is_lost(),
        || SharedGpu::create(backends),
    )
}

impl SharedGpu {
    fn create(backends: wgpu::Backends) -> Option<SharedGpu> {
        let mut desc = wgpu::InstanceDescriptor::new_without_display_handle();
        desc.backends = backends;
        let instance = wgpu::Instance::new(desc);
        let adapters = pollster::block_on(instance.enumerate_adapters(backends));
        let adapter = adapters.into_iter().find(|a| {
            a.get_downlevel_capabilities()
                .flags
                .contains(wgpu::DownlevelFlags::COMPUTE_SHADERS)
        })?;
        let limits = adapter.limits();
        let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
            label: Some("graffux-standalone-compute"),
            required_limits: limits.clone(),
            ..Default::default()
        }))
        .ok()?;
        let lost = Arc::new(AtomicBool::new(false));
        {
            let lost = lost.clone();
            device.set_device_lost_callback(move |reason, message| {
                log::error!("graffux-wgpu: standalone compute device lost ({reason:?}): {message}");
                lost.store(true, Ordering::SeqCst);
            });
        }
        DEVICES_CREATED.fetch_add(1, Ordering::SeqCst);
        Some(SharedGpu {
            _instance: instance,
            device,
            queue,
            limits,
            lost,
            cache: Mutex::new(HashMap::new()),
        })
    }

    /// The GPU stopped answering on this device (a wait timed out): [`shared`] stops handing it out and makes a new one.
    /// Work already submitted may still be running on it; it ends when the last user drops the device.
    pub fn mark_stalled(&self) {
        self.lost.store(true, Ordering::SeqCst);
    }

    pub fn is_lost(&self) -> bool {
        let _ = self.device.poll(wgpu::PollType::Poll);
        self.lost.load(Ordering::SeqCst)
    }

    /// The value cached under `key` (a pipeline, or the layouts and pipelines of one shader), built by `build` on
    /// first use. `T` must be a cheap clone (wgpu handles are reference counted).
    pub fn cached<T: Clone + Send + Sync + 'static>(&self, key: &'static str, build: impl FnOnce() -> T) -> T {
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(v) = cache.get(key).and_then(|v| v.downcast_ref::<T>()) {
            return v.clone();
        }
        let v = build();
        PIPELINES_BUILT.fetch_add(1, Ordering::SeqCst);
        cache.insert(key, Box::new(v.clone()));
        v
    }

    /// Reads `range` of the staging `buffer` with the bounded wait. A timeout means the GPU stopped answering on this
    /// device, so it is marked stalled and [`shared`] replaces it: otherwise every later call would reuse a hung
    /// device and wait out the timeout again.
    pub fn read<R>(
        &self,
        buffer: &wgpu::Buffer,
        range: std::ops::Range<u64>,
        read: impl FnOnce(&[u8]) -> R,
    ) -> Result<R, crate::gpu_wait::ReadFailure> {
        self.read_with(
            buffer,
            range,
            |d| {
                d.poll(wgpu::PollType::Wait {
                    submission_index: None,
                    timeout: Some(crate::gpu_wait::READBACK_TIMEOUT),
                })
            },
            read,
        )
    }

    /// [`SharedGpu::read`] with the wait supplied (tests stand in for a GPU that never answers).
    pub fn read_with<R>(
        &self,
        buffer: &wgpu::Buffer,
        range: std::ops::Range<u64>,
        wait: impl FnOnce(&wgpu::Device) -> Result<wgpu::PollStatus, wgpu::PollError>,
        read: impl FnOnce(&[u8]) -> R,
    ) -> Result<R, crate::gpu_wait::ReadFailure> {
        let r = crate::gpu_wait::map_read_with(&self.device, buffer, range, wait, read);
        if r.as_ref().err() == Some(&crate::gpu_wait::ReadFailure::TimedOut) {
            self.mark_stalled();
        }
        r
    }

    /// Runs `body` inside validation and out-of-memory error scopes. Returns its value, or None when it returned
    /// None or either scope caught an error (logged); the device stays usable.
    pub fn scoped<R>(&self, label: &str, body: impl FnOnce() -> Option<R>) -> Option<R> {
        // Guards: dropped without `pop`, wgpu still pops the scope, so a panic in `body` cannot leave stale scopes
        // on this thread's stack of the shared device (locals drop in reverse order, matching the pushes).
        let oom = self.device.push_error_scope(wgpu::ErrorFilter::OutOfMemory);
        let validation = self.device.push_error_scope(wgpu::ErrorFilter::Validation);
        let out = body();
        let validation = pollster::block_on(validation.pop());
        let oom = pollster::block_on(oom.pop());
        if let Some(err) = validation.or(oom) {
            log::error!("graffux-wgpu {label}: {err}");
            return None;
        }
        out
    }
}

/// Whether buffers of these byte sizes can each be bound as storage, and whether `storage_buffers` storage
/// bindings, one uniform binding, and a compute workgroup of `workgroup_invocations` fit the adapter's limits.
/// Checked before any buffer is created: an oversized buffer is a validation error, not a clean failure.
pub fn fits(
    limits: &wgpu::Limits,
    buffer_bytes: &[u64],
    storage_buffers: u32,
    workgroup_invocations: u32,
) -> bool {
    buffer_bytes.iter().all(|&b| {
        b <= limits.max_storage_buffer_binding_size as u64 && b <= limits.max_buffer_size
    }) && storage_buffers <= limits.max_storage_buffers_per_shader_stage
        && limits.max_uniform_buffers_per_shader_stage >= 1
        && workgroup_invocations <= limits.max_compute_invocations_per_workgroup
        && workgroup_invocations <= limits.max_compute_workgroup_size_x
}

/// Whether a 1D dispatch of `groups` workgroups is within the adapter's per-dimension limit.
pub fn groups_fit(limits: &wgpu::Limits, groups: u64) -> bool {
    groups <= limits.max_compute_workgroups_per_dimension as u64
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, Instant};

    #[test]
    fn a_failure_is_remembered_briefly_then_retried_and_recovers() {
        let mut r: Registry<u32> = Registry::new();
        let t0 = Instant::now();
        let at = |d: Duration| move || t0 + d;
        let mut calls = 0;
        assert!(r.get(1, at(Duration::ZERO), |_| false, || { calls += 1; None }).is_none());
        // Inside the window the failure is answered without trying again.
        assert!(r.get(1, at(Duration::from_millis(500)), |_| false, || { calls += 1; Some(7) }).is_none());
        assert_eq!(calls, 1);
        // After it, the factory runs again and a transient failure is gone for good.
        let got = r.get(1, at(RETRY_AFTER), |_| false, || { calls += 1; Some(7) });
        assert_eq!((got.map(|v| *v), calls), (Some(7), 2));
        assert!(r.get(1, at(RETRY_AFTER), |_| false, || { calls += 1; None }).is_some());
        assert_eq!(calls, 2, "a live device is not rebuilt");
    }

    #[test]
    fn a_dead_value_is_replaced_and_keys_are_independent() {
        let mut r: Registry<u32> = Registry::new();
        let t0 = Instant::now();
        let c = move || t0;
        assert_eq!(r.get(1, c, |_| false, || Some(1)).map(|v| *v), Some(1));
        assert_eq!(r.get(2, c, |_| false, || Some(2)).map(|v| *v), Some(2));
        assert_eq!(r.get(1, c, |v| *v == 1, || Some(10)).map(|v| *v), Some(10));
        assert_eq!(r.get(2, c, |_| false, || Some(99)).map(|v| *v), Some(2));
    }

    #[test]
    fn a_slow_failed_attempt_is_stamped_when_it_ends() {
        let mut r: Registry<u32> = Registry::new();
        let t0 = Instant::now();
        let now = std::cell::Cell::new(t0);
        let clock = || now.get();
        // create() takes longer than RETRY_AFTER and fails: the failure must still be fresh afterwards.
        assert!(r.get(1, clock, |_| false, || { now.set(t0 + RETRY_AFTER * 2); None }).is_none());
        let mut retried = false;
        assert!(r.get(1, clock, |_| false, || { retried = true; Some(1) }).is_none());
        assert!(!retried, "retried immediately although the failure had just happened");
    }
}
