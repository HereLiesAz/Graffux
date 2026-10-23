//! The persisted pipeline cache: compiled compute pipelines kept on disk between engines and
//! between runs, so a new engine (every layer-size or tuning change recreates one) does not pay
//! the driver's shader compile again.
//!
//! wgpu supports this on Vulkan only ([`wgpu::Features::PIPELINE_CACHE`],
//! [`wgpu::util::pipeline_cache_key`]); elsewhere, or with no directory set, pipelines are
//! compiled as before. The directory comes from [`set_dir`] (Android: the app's cache dir, through
//! `gfx_wgpu_set_pipeline_cache_dir`) or the `GRAFFUX_WGPU_PIPELINE_CACHE_DIR` environment variable.
//! The file name carries the adapter's vendor and device; wgpu and the driver validate the data
//! (driver version included) and fall back to an empty cache when it does not match.
//!
//! Why not share one device instead: every engine registers its own uncaptured-error and
//! device-lost callbacks (one per device) and fails closed on its own error flag. A shared device
//! would make one engine's validation error fail every engine and needs that state reworked per
//! engine; the disk cache removes the recompile cost without changing those semantics.

use std::path::PathBuf;
use std::sync::Mutex;

static DIR: Mutex<Option<PathBuf>> = Mutex::new(None);

/// Sets (or with `None` clears) the directory pipeline caches are kept in. Engines created after
/// the call use it.
pub fn set_dir(dir: Option<PathBuf>) {
    *DIR.lock().unwrap_or_else(|e| e.into_inner()) = dir;
}

fn dir() -> Option<PathBuf> {
    DIR.lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
        .or_else(|| std::env::var_os("GRAFFUX_WGPU_PIPELINE_CACHE_DIR").map(PathBuf::from))
}

/// Where `adapter`'s cache lives, when caching applies to it at all.
fn path_for(adapter: &wgpu::Adapter) -> Option<PathBuf> {
    if !adapter.features().contains(wgpu::Features::PIPELINE_CACHE) {
        return None;
    }
    let key = wgpu::util::pipeline_cache_key(&adapter.get_info())?;
    Some(dir()?.join(format!("{key}.bin")))
}

/// The device feature to request so [`DiskCache::open`] can work (empty when it cannot).
pub(crate) fn feature(adapter: &wgpu::Adapter) -> wgpu::Features {
    if path_for(adapter).is_some() {
        wgpu::Features::PIPELINE_CACHE
    } else {
        wgpu::Features::empty()
    }
}

/// A pipeline cache backed by a file.
pub(crate) struct DiskCache {
    pub(crate) cache: wgpu::PipelineCache,
    path: PathBuf,
    saved_len: usize,
}

impl DiskCache {
    /// The cache for `device` (created from `adapter` with [`feature`] requested), seeded from its
    /// file when one exists. None when caching does not apply.
    pub(crate) fn open(adapter: &wgpu::Adapter, device: &wgpu::Device) -> Option<DiskCache> {
        if !device.features().contains(wgpu::Features::PIPELINE_CACHE) {
            return None;
        }
        let path = path_for(adapter)?;
        let data = std::fs::read(&path).ok();
        // SAFETY: `data` is only ever what `PipelineCache::get_data` returned for an adapter with
        // this same pipeline_cache_key (the file name), written whole via a rename; wgpu and the
        // driver validate it and `fallback` turns anything unusable into an empty cache.
        let cache = unsafe {
            device.create_pipeline_cache(&wgpu::PipelineCacheDescriptor {
                label: Some("graffux-wgpu pipelines"),
                data: data.as_deref(),
                fallback: true,
            })
        };
        Some(DiskCache {
            cache,
            saved_len: data.map_or(0, |d| d.len()),
            path,
        })
    }

    /// Writes the cache back when it grew (new pipelines were compiled into it). Failures are
    /// logged and otherwise ignored: the cache is an optimisation.
    pub(crate) fn save(&mut self) {
        let Some(data) = self.cache.get_data() else {
            return;
        };
        if data.len() == self.saved_len {
            return;
        }
        // A name of its own per write: engines on several threads may save the same file at once,
        // and each rename must move a complete file.
        static SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let seq = SEQ.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let tmp = self
            .path
            .with_extension(format!("{}.{seq}.tmp", std::process::id()));
        let written = self
            .path
            .parent()
            .map_or(Ok(()), std::fs::create_dir_all)
            .and_then(|_| std::fs::write(&tmp, &data))
            .and_then(|_| std::fs::rename(&tmp, &self.path));
        match written {
            Ok(()) => self.saved_len = data.len(),
            Err(e) => log::warn!("graffux-wgpu: pipeline cache not saved: {e}"),
        }
    }
}
