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
    height_region, BackendChoice, BenchmarkInfo, ColorSmudgeDab, Engine, EngineOptions, GpuDab,
    GpuSecondaryDab, HeightDab, MaskedParams, MaterialDab, MultipassConfig, MultipassStats,
    SubstrateParams, DEFAULT_STAMP_TILE, IMPASTO_WGSL, WET_FLAG_HEIGHT, WET_FLAG_WETNESS,
    WET_MEDIA_FLOATS, WET_WGSL,
};
pub use timing::PassKind;
