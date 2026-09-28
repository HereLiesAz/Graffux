//! C ABI, declared in include/graffux_wgpu.h. Used by the C++ `WgpuStampEngine` adapter
//! (core/nativebridge, which puts this engine behind the existing `StampEngine` interface on
//! Android) and by tools/stamp-engine-diff. Every entry point catches panics: a Rust panic must
//! never unwind into C++ or the JVM, it just reports failure.

use std::ffi::c_char;
use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::engine::{
    BackendChoice, ColorSmudgeDab, Engine, GpuDab, GpuSecondaryDab, MaskedParams, MultipassConfig,
    MultipassStats, SubstrateParams,
    EngineOptions,
};

/// Mirrors `GfxWgpuSubstrate` in the header.
#[repr(C)]
pub struct GfxWgpuSubstrate {
    pub enabled: i32,
    pub has_paint_height: i32,
    pub base_height: f32,
    pub height_scale: f32,
    pub texture_scale: f32,
    pub texture_offset_x: f32,
    pub texture_offset_y: f32,
}

fn substrate(p: *const GfxWgpuSubstrate) -> SubstrateParams {
    if p.is_null() {
        return SubstrateParams::default();
    }
    // SAFETY: caller passes a valid pointer or null.
    let s = unsafe { &*p };
    SubstrateParams {
        enabled: s.enabled != 0,
        has_paint_height: s.has_paint_height != 0,
        base_height: s.base_height,
        height_scale: s.height_scale,
        texture_scale: s.texture_scale,
        texture_offset_x: s.texture_offset_x,
        texture_offset_y: s.texture_offset_y,
    }
}

unsafe fn slice<'a, T>(p: *const T, n: usize) -> &'a [T] {
    if p.is_null() || n == 0 {
        &[]
    } else {
        std::slice::from_raw_parts(p, n)
    }
}

fn guard<R>(fallback: R, f: impl FnOnce() -> R) -> R {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(fallback)
}

unsafe fn engine<'a>(e: *mut Engine) -> Option<&'a mut Engine> {
    e.as_mut()
}

/// Null when no adapter with compute support is found (the caller falls back to the CPU path).
#[no_mangle]
pub extern "C" fn gfx_wgpu_create(width: i32, height: i32, backend: i32) -> *mut Engine {
    guard(std::ptr::null_mut(), || {
        match Engine::new(width, height, BackendChoice::from_id(backend)) {
            Some(e) => Box::into_raw(Box::new(e)),
            None => std::ptr::null_mut(),
        }
    })
}

/// `gfx_wgpu_create` with per-device tuning: `stamp_tile` 8 or 16 (other values mean 16) and
/// whether to time passes with timestamp queries when the adapter supports them.
#[no_mangle]
pub extern "C" fn gfx_wgpu_create_tuned(
    width: i32,
    height: i32,
    backend: i32,
    stamp_tile: i32,
    timestamps: bool,
) -> *mut Engine {
    guard(std::ptr::null_mut(), || {
        let options = EngineOptions {
            stamp_tile: stamp_tile.max(0) as u32,
            timestamps,
        };
        match Engine::new_with_options(width, height, BackendChoice::from_id(backend), options) {
            Some(e) => Box::into_raw(Box::new(e)),
            None => std::ptr::null_mut(),
        }
    })
}

/// Writes NUL-terminated `key=value` lines describing the adapter (see `Engine::gpu_info`);
/// returns the full length.
///
/// # Safety
/// `out` must point to `capacity` writable bytes (or be null with capacity 0).
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_gpu_info(e: *mut Engine, out: *mut c_char, capacity: usize) -> usize {
    let Some(e) = engine(e) else { return 0 };
    let text = e.gpu_info();
    if !out.is_null() && capacity > 0 {
        let n = text.len().min(capacity - 1);
        std::ptr::copy_nonoverlapping(text.as_ptr(), out as *mut u8, n);
        *out.add(n) = 0;
    }
    text.len()
}

/// Drains GPU pass timings into `out` as `{kind, nanoseconds}` pairs, at most `capacity_pairs`
/// (the rest are dropped); returns the pairs written. 0 without timestamp support.
///
/// # Safety
/// `out` must point to `2 * capacity_pairs` writable u64s (or be null with capacity 0).
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_take_pass_timings(
    e: *mut Engine,
    out: *mut u64,
    capacity_pairs: usize,
) -> usize {
    let Some(e) = engine(e) else { return 0 };
    guard(0, || {
        let samples = e.take_pass_timings();
        let n = samples.len().min(capacity_pairs);
        if out.is_null() {
            return 0;
        }
        for (i, (kind, ns)) in samples.iter().take(n).enumerate() {
            *out.add(i * 2) = *kind as u64;
            *out.add(i * 2 + 1) = *ns;
        }
        n
    })
}

/// # Safety
/// `e` must come from `gfx_wgpu_create` and not be used afterwards.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_destroy(e: *mut Engine) {
    if !e.is_null() {
        guard((), || drop(Box::from_raw(e)));
    }
}

/// # Safety
/// `e` must be a live engine.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_width(e: *mut Engine) -> i32 {
    engine(e).map_or(0, |e| e.width())
}

/// # Safety
/// `e` must be a live engine.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_height(e: *mut Engine) -> i32 {
    engine(e).map_or(0, |e| e.height())
}

/// # Safety
/// `e` must be a live engine.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_clear(e: *mut Engine) -> bool {
    guard(false, || engine(e).is_some_and(|e| e.clear()))
}

/// # Safety
/// `rgba` must point to `len` readable bytes.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_upload(e: *mut Engine, rgba: *const u8, len: usize) -> bool {
    guard(false, || {
        !rgba.is_null() && engine(e).is_some_and(|e| e.upload(slice(rgba, len)))
    })
}

/// # Safety
/// `data` must point to `width*height` bytes.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_upload_substrate_height(
    e: *mut Engine,
    data: *const u8,
    width: i32,
    height: i32,
) -> bool {
    guard(false, || {
        if data.is_null() || width <= 0 || height <= 0 {
            return false;
        }
        engine(e).is_some_and(|e| {
            e.upload_substrate_height(slice(data, (width * height) as usize), width, height)
        })
    })
}

/// # Safety
/// `data` must point to `width*height` floats.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_upload_paint_height(
    e: *mut Engine,
    data: *const f32,
    width: i32,
    height: i32,
) -> bool {
    guard(false, || {
        if data.is_null() || width <= 0 || height <= 0 {
            return false;
        }
        engine(e).is_some_and(|e| {
            e.upload_paint_height(slice(data, (width * height) as usize), width, height)
        })
    })
}

/// # Safety
/// `dabs` must point to `count` 64-byte `GpuDab` records; `substrate` may be null.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_stamp_dabs(
    e: *mut Engine,
    dabs: *const GpuDab,
    count: usize,
    color_argb: u32,
    hardness: f32,
    build_up: bool,
    sub: *const GfxWgpuSubstrate,
    stroke_max: bool,
) -> bool {
    guard(false, || {
        engine(e).is_some_and(|e| {
            e.stamp_dabs(
                slice(dabs, count),
                color_argb,
                hardness,
                build_up,
                substrate(sub),
                stroke_max,
            )
        })
    })
}

/// # Safety
/// Every pointer must cover the stated sizes; optional inputs may be null / zero-sized.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_stamp_masked_dabs(
    e: *mut Engine,
    dabs: *const GpuDab,
    count: usize,
    color_argb: u32,
    hardness: f32,
    mask: *const u8,
    mask_width: i32,
    mask_height: i32,
    grain: *const u8,
    grain_width: i32,
    grain_height: i32,
    grain_canvas_locked: bool,
    grain_scale: f32,
    grain_phase_x: f32,
    grain_phase_y: f32,
    secondary_dabs: *const GpuSecondaryDab,
    secondary_count: usize,
    secondary_mask: *const u8,
    secondary_width: i32,
    secondary_height: i32,
    sub: *const GfxWgpuSubstrate,
) -> bool {
    guard(false, || {
        if mask.is_null() || mask_width <= 0 || mask_height <= 0 {
            return false;
        }
        let grain = (!grain.is_null() && grain_width > 0 && grain_height > 0).then(|| {
            (
                slice(grain, (grain_width * grain_height) as usize),
                grain_width as u32,
                grain_height as u32,
            )
        });
        let secondary_mask =
            (!secondary_mask.is_null() && secondary_width > 0 && secondary_height > 0).then(|| {
                (
                    slice(
                        secondary_mask,
                        (secondary_width * secondary_height) as usize,
                    ),
                    secondary_width as u32,
                    secondary_height as u32,
                )
            });
        let params = MaskedParams {
            mask: slice(mask, (mask_width * mask_height) as usize),
            mask_width: mask_width as u32,
            mask_height: mask_height as u32,
            grain,
            grain_canvas_locked,
            grain_scale,
            grain_phase_x,
            grain_phase_y,
            secondary_dabs: slice(secondary_dabs, secondary_count),
            secondary_mask,
            substrate: substrate(sub),
        };
        engine(e)
            .is_some_and(|e| e.stamp_masked_dabs(slice(dabs, count), color_argb, hardness, &params))
    })
}

/// # Safety
/// `dabs` must point to `count` 44-byte `ColorSmudgeDab` records; `sample_source` may be null.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_color_smudge(
    e: *mut Engine,
    dabs: *const ColorSmudgeDab,
    count: usize,
    mode: i32,
    radius_px: f32,
    feathering: f32,
    smear_alpha: bool,
    paint_color_argb: u32,
    dilution: f32,
    sample_source: *const u8,
    sample_width: i32,
    sample_height: i32,
) -> bool {
    guard(false, || {
        let source =
            (!sample_source.is_null() && sample_width > 0 && sample_height > 0).then(|| {
                (
                    slice(sample_source, (sample_width * sample_height * 4) as usize),
                    sample_width,
                    sample_height,
                )
            });
        engine(e).is_some_and(|e| {
            e.color_smudge(
                slice(dabs, count),
                mode,
                radius_px,
                feathering,
                smear_alpha,
                paint_color_argb,
                dilution,
                source,
            )
        })
    })
}

/// Writes vendorId, deviceId, selectedTileSize into `ids` and nanos8, nanos16 into `nanos`.
///
/// # Safety
/// `ids` must hold 3 u32s and `nanos` 2 u64s.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_benchmark_info(e: *mut Engine, ids: *mut u32, nanos: *mut u64) {
    let Some(e) = engine(e) else { return };
    let b = e.color_smudge_benchmark_info();
    if !ids.is_null() {
        *ids = b.vendor_id;
        *ids.add(1) = b.device_id;
        *ids.add(2) = b.selected_tile_size;
    }
    if !nanos.is_null() {
        *nanos = b.nanos8;
        *nanos.add(1) = b.nanos16;
    }
}

/// # Safety
/// `out` must point to `capacity` writable bytes.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_readback(e: *mut Engine, out: *mut u8, capacity: usize) -> bool {
    guard(false, || {
        if out.is_null() {
            return false;
        }
        engine(e).is_some_and(|e| e.readback(std::slice::from_raw_parts_mut(out, capacity)))
    })
}

/// Writes a NUL-terminated "Backend: adapter (driver)" string; returns its full length.
///
/// # Safety
/// `out` must point to `capacity` writable bytes (or be null with capacity 0).
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_adapter_description(
    e: *mut Engine,
    out: *mut c_char,
    capacity: usize,
) -> usize {
    let Some(e) = engine(e) else { return 0 };
    let text = e.adapter_description();
    if !out.is_null() && capacity > 0 {
        let n = text.len().min(capacity - 1);
        std::ptr::copy_nonoverlapping(text.as_ptr(), out as *mut u8, n);
        *out.add(n) = 0;
    }
    text.len()
}

// ---- Dirty-rect readback and resident layers (see resident.rs and Engine's docs) ----------

/// `gfx_wgpu_readback` that also writes the rectangle it copied to `rect` as {x, y, w, h} (all
/// zero when nothing was dirty).
///
/// # Safety
/// `out` must point to `capacity` writable bytes, `rect` to 4 writable i32s (or be null).
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_readback_rect(
    e: *mut Engine,
    out: *mut u8,
    capacity: usize,
    rect: *mut i32,
) -> bool {
    guard(false, || {
        if out.is_null() {
            return false;
        }
        let Some(e) = engine(e) else { return false };
        match e.readback_rect(std::slice::from_raw_parts_mut(out, capacity)) {
            Some((x, y, w, h)) => {
                if !rect.is_null() {
                    *rect = x;
                    *rect.add(1) = y;
                    *rect.add(2) = w;
                    *rect.add(3) = h;
                }
                true
            }
            None => false,
        }
    })
}

/// Reads rectangle {x, y, w, h} of the layer into `out`, tightly packed.
///
/// # Safety
/// `out` must point to `capacity` writable bytes.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_read_region(
    e: *mut Engine,
    x: i32,
    y: i32,
    w: i32,
    h: i32,
    out: *mut u8,
    capacity: usize,
) -> bool {
    guard(false, || {
        !out.is_null()
            && engine(e).is_some_and(|e| {
                e.read_region(x, y, w, h, std::slice::from_raw_parts_mut(out, capacity))
            })
    })
}

/// Bind session (> 0) when layer `key` is resident at `generation`; 0 = miss, upload instead.
///
/// # Safety
/// `e` must be a live engine.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_bind_layer(e: *mut Engine, key: u64, generation: u64) -> u64 {
    guard(0, || engine(e).map_or(0, |e| e.bind_layer(key, generation)))
}

/// Uploads a full layer image as resident layer `key` at `generation`; returns the bind session.
///
/// # Safety
/// `rgba` must point to `len` readable bytes.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_upload_layer(
    e: *mut Engine,
    key: u64,
    generation: u64,
    rgba: *const u8,
    len: usize,
) -> u64 {
    guard(0, || {
        if rgba.is_null() {
            return 0;
        }
        engine(e).map_or(0, |e| e.upload_layer(key, generation, slice(rgba, len)))
    })
}

/// # Safety
/// `e` must be a live engine.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_commit_layer(
    e: *mut Engine,
    key: u64,
    session: u64,
    generation: u64,
) -> bool {
    guard(false, || {
        engine(e).is_some_and(|e| e.commit_layer(key, session, generation))
    })
}

/// # Safety
/// `rgba` must point to `len` readable bytes.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn gfx_wgpu_refresh_layer(
    e: *mut Engine,
    key: u64,
    session: u64,
    generation: u64,
    rgba: *const u8,
    len: usize,
    x: i32,
    y: i32,
    w: i32,
    h: i32,
) -> bool {
    guard(false, || {
        !rgba.is_null()
            && engine(e).is_some_and(|e| {
                e.refresh_layer(key, session, generation, slice(rgba, len), (x, y, w, h))
            })
    })
}

/// # Safety
/// `e` must be a live engine.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_invalidate_layer(e: *mut Engine, key: u64) -> bool {
    guard(false, || engine(e).is_some_and(|e| e.invalidate_layer(key)))
}

/// # Safety
/// `e` must be a live engine.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_invalidate_all_layers(e: *mut Engine) {
    guard((), || {
        if let Some(e) = engine(e) {
            e.invalidate_all_layers();
        }
    })
}

/// # Safety
/// `e` must be a live engine.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_set_resident_budget(e: *mut Engine, bytes: u64) {
    guard((), || {
        if let Some(e) = engine(e) {
            e.set_resident_budget(bytes);
        }
    })
}

/// Writes {resident layer count, bytes held} to `out`.
///
/// # Safety
/// `out` must point to 2 writable u64s.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_resident_stats(e: *mut Engine, out: *mut u64) {
    let Some(e) = engine(e) else { return };
    if !out.is_null() {
        let (n, bytes) = e.resident_stats();
        *out = n as u64;
        *out.add(1) = bytes;
    }
}

// ---- Multipass rendering (experimental; see multipass.rs) ------------------------------------

/// Sets multipass rendering: `params` = `MultipassConfig` floats ([enabled, passes, edge_fraction,
/// transition_ms, overtake_ms, draft_scale, refine_ballast, frame_ms, refine_fraction,
/// max_chunk_px]; missing trailing values take
/// their defaults). Off (the default) is the pre-multipass path exactly.
///
/// # Safety
/// `params` must point to `n` readable floats (or be null with n = 0).
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_set_multipass(e: *mut Engine, params: *const f32, n: usize) -> bool {
    guard(false, || {
        engine(e).is_some_and(|e| e.set_multipass(MultipassConfig::from_floats(slice(params, n))))
    })
}

/// Refinement for up to `budget_ms` (<= 0: the rest of the current frame). 1 = work or display
/// animation remains, 0 = idle (or multipass off), -1 = failure.
///
/// # Safety
/// `e` must be a live engine.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_refine(e: *mut Engine, budget_ms: f32) -> i32 {
    guard(-1, || engine(e).map_or(-1, |e| e.refine(budget_ms)))
}

/// Lands all queued multipass work and finishes every display ease (blocks).
///
/// # Safety
/// `e` must be a live engine.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_flush(e: *mut Engine) -> bool {
    guard(false, || engine(e).is_some_and(|e| e.flush()))
}

/// Writes up to `n` doubles of `MultipassStats` (see its `to_array`); returns how many.
///
/// # Safety
/// `out` must point to `n` writable doubles.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_multipass_stats(e: *mut Engine, out: *mut f64, n: usize) -> usize {
    guard(0, || {
        let Some(e) = engine(e) else { return 0 };
        if out.is_null() {
            return 0;
        }
        let stats = e.multipass_stats().to_array();
        let k = n.min(MultipassStats::DOUBLES);
        std::slice::from_raw_parts_mut(out, k).copy_from_slice(&stats[..k]);
        k
    })
}
