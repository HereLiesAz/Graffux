//! One device per backend set for the standalone compute entries (`transaction::run_on_gpu`,
//! `pigment::mix_on_gpu`, `watercolour::run_on_gpu`).
//!
//! Each of those used to build a wgpu `Instance`, enumerate adapters, request a device and compile its shader and
//! pipeline on every call: tens to hundreds of milliseconds of setup around microseconds of work. They now share
//! a [`SharedGpu`] per backend set, created on first use, with their pipelines cached on it by name. A device
//! that was lost is dropped from the cache and made again on the next call.
//!
//! Every call also runs inside an error scope ([`SharedGpu::scoped`]): a validation or out-of-memory error fails
//! that call with `None` instead of reaching wgpu's default handler, which panics the process, and it cannot
//! poison the shared device for the next caller.

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

static DEVICES: Mutex<Vec<(u32, Option<Arc<SharedGpu>>)>> = Mutex::new(Vec::new());

/// The shared device for `backends` (the best adapter with compute shaders), or None when there is none. The
/// answer, including "none", is kept for the life of the process, except that a lost device is replaced.
pub fn shared(backends: wgpu::Backends) -> Option<Arc<SharedGpu>> {
    let mut devices = DEVICES.lock().unwrap_or_else(|e| e.into_inner());
    let key = backends.bits();
    if let Some((_, slot)) = devices.iter().find(|(k, _)| *k == key) {
        match slot {
            Some(gpu) if !gpu.is_lost() => return Some(gpu.clone()),
            None => return None,
            Some(_) => {} // lost: made again below
        }
    }
    let made = SharedGpu::create(backends).map(Arc::new);
    devices.retain(|(k, _)| *k != key);
    devices.push((key, made.clone()));
    made
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

    /// Runs `body` inside validation and out-of-memory error scopes. Returns its value, or None when it returned
    /// None or either scope caught an error (logged); the device stays usable.
    pub fn scoped<R>(&self, label: &str, body: impl FnOnce() -> Option<R>) -> Option<R> {
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
