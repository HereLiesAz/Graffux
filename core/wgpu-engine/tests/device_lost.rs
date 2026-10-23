//! Device loss: the engine reports it, and every later call fails instead of panicking.

mod common;

use graffux_wgpu::wgpu::Backends;
use graffux_wgpu::Engine;

#[test]
fn a_lost_device_is_reported_and_fails_closed() {
    for (name, b) in [("vulkan", Backends::VULKAN), ("gl", Backends::GL)] {
        let Some(mut e) = Engine::with_backends(16, 16, b) else {
            common::no_adapter(name);
            continue;
        };
        assert!(!e.device_lost(), "[{name}] fresh engine");
        assert!(e.upload(&[255u8; 16 * 16 * 4]));
        e.simulate_device_loss();
        assert!(e.device_lost(), "[{name}] loss not reported");
        assert!(
            !e.upload(&[0u8; 16 * 16 * 4]),
            "[{name}] call after loss succeeded"
        );
    }
}
