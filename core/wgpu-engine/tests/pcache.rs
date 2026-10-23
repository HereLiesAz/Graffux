//! The persisted pipeline cache (pcache.rs): with a directory set, an engine on a backend that
//! supports caching (Vulkan) leaves its compiled pipelines there, and the next engine starts from
//! them. Its own test binary: the directory is process-wide.

mod common;

use graffux_wgpu::engine::pcache;
use graffux_wgpu::wgpu::Backends;
use graffux_wgpu::Engine;

#[test]
fn engines_persist_and_reuse_the_pipeline_cache() {
    let dir = std::env::temp_dir().join(format!("graffux-pcache-test-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    pcache::set_dir(Some(dir.clone()));
    let Some(e) = Engine::with_backends(16, 16, Backends::VULKAN) else {
        common::no_adapter("vulkan");
        pcache::set_dir(None);
        return;
    };
    drop(e);
    let files: Vec<_> = std::fs::read_dir(&dir)
        .map(|d| d.filter_map(Result::ok).map(|f| f.path()).collect())
        .unwrap_or_default();
    // A driver may not support caching (wgpu then exposes no PIPELINE_CACHE): nothing to check.
    if !files.is_empty() {
        assert_eq!(
            files.len(),
            1,
            "one file per adapter, no temp file left: {files:?}"
        );
        let name = files[0].file_name().unwrap().to_string_lossy().into_owned();
        assert!(
            name.starts_with("wgpu_pipeline_cache_vulkan_") && name.ends_with(".bin"),
            "{name}"
        );
        // A second engine starts from the file and still works.
        let mut again = Engine::with_backends(16, 16, Backends::VULKAN).expect("second engine");
        assert!(again.clear());
    }
    pcache::set_dir(None);
    let _ = std::fs::remove_dir_all(&dir);
}
