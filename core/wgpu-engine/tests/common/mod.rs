//! Shared test support: what to do when a backend has no adapter.
//!
//! Without `GRAFFUX_REQUIRE_GPU` a missing adapter skips that backend, so a machine without Mesa
//! still runs the CPU-only tests. On a GPU CI host a skip would let every GPU test pass vacuously,
//! so there `GRAFFUX_REQUIRE_GPU` names the backends that must exist (`vulkan`, `gl`, a comma list
//! of them, or `all`/`1` for every backend) and a missing one fails the test instead.

#![allow(dead_code)]

/// Whether `backend` must have an adapter on this host. `"any"` asks whether any backend is required.
pub fn required(backend: &str) -> bool {
    let Ok(v) = std::env::var("GRAFFUX_REQUIRE_GPU") else { return false };
    let v = v.trim().to_ascii_lowercase();
    match v.as_str() {
        "" | "0" | "false" | "no" => false,
        "1" | "true" | "yes" | "all" => true,
        list => backend == "any" || list.split(',').any(|b| b.trim() == backend),
    }
}

/// Called where `backend` has no adapter: panics if it is required, otherwise logs the skip.
pub fn no_adapter(backend: &str) {
    if required(backend) {
        panic!("[{backend}] no adapter, but GRAFFUX_REQUIRE_GPU requires one");
    }
    eprintln!("[{backend}] no adapter -- skipped");
}
