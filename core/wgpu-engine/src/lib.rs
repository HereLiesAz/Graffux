//! Graffux's wgpu stamp engine -- the long-term single brush engine for the Android app and the
//! desktop app. See ARCHITECTURE.md ("GPU stamp engines") for why wgpu.
//!
//! * [`engine`] -- the engine, mirroring `StampEngine.h` (core/nativebridge).
//! * [`capi`] -- C ABI (`include/graffux_wgpu.h`) for the C++ `WgpuStampEngine` adapter.
//! * [`jni_api`] -- JNI for the Kotlin `WgpuNative`/`WgpuStampEngine` in core:engine.

pub mod capi;
pub mod draft;
pub mod engine;
pub mod jni_api;
pub mod progress;
pub mod reference;
pub mod resident;
pub mod scheduler;
pub mod timing;

pub use wgpu;

pub use engine::{
    BackendChoice, BenchmarkInfo, ColorSmudgeDab, Engine, EngineOptions, GpuDab, GpuSecondaryDab,
    MaskedParams, MultipassConfig, MultipassStats, SubstrateParams, DEFAULT_STAMP_TILE,
};
pub use timing::PassKind;
