//! A failed engine creation leaves its reason behind (diag::last_report) instead of a bare `None`.
//! Run with RUST_TEST_THREADS=1: the report is process-wide, like the engines' device state.

mod common;

use graffux_wgpu::wgpu::Backends;
use graffux_wgpu::{diag, Engine};

#[test]
fn no_backend_reports_that_no_adapter_was_enumerated() {
    assert!(Engine::with_backends(16, 16, Backends::empty()).is_none());
    let r = diag::last_report();
    assert!(r.contains("requested backends="), "{r}");
    assert!(r.contains("enumerate_adapters returned no adapters"), "{r}");
    assert!(r.contains("FAILED: NoAdapter"), "{r}");
    assert!(!r.contains("ALLOW_UNDERLYING"), "no Vulkan requested, so no retry: {r}");
}

#[test]
fn a_layer_over_the_buffer_limits_names_the_limit() {
    // 40000x40000x4x2 bytes is over any adapter's storage buffer binding size.
    if Engine::with_backends(16, 16, Backends::VULKAN | Backends::GL).is_none() {
        common::no_adapter("any");
        return;
    }
    assert!(Engine::with_backends(40000, 40000, Backends::VULKAN | Backends::GL).is_none());
    let r = diag::last_report();
    assert!(r.contains("max_storage_buffer_binding_size="), "{r}");
    assert!(r.contains("does not fit the adapter's storage buffer limits"), "{r}");
    assert!(r.contains("adapter[0]"), "{r}");
}

#[test]
fn a_successful_creation_reports_the_adapter_it_chose() {
    let Some(e) = Engine::with_backends(16, 16, Backends::VULKAN | Backends::GL) else {
        common::no_adapter("any");
        return;
    };
    let r = diag::last_report();
    assert!(r.contains(&format!("ok: {}", e.adapter_description())), "{r}");
    assert!(r.contains("compute_shaders=yes"), "{r}");
}
