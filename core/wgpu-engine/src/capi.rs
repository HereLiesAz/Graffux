//! C ABI, declared in include/graffux_wgpu.h. Used by the C++ `WgpuStampEngine` adapter
//! (core/nativebridge, which puts this engine behind the existing `StampEngine` interface on
//! Android) and by tools/stamp-engine-diff. Every entry point catches panics: a Rust panic must
//! never unwind into C++ or the JVM, it just reports failure.

use std::ffi::c_char;
use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::engine::{
    BackendChoice, ColorSmudgeDab, Engine, EngineOptions, GpuDab, GpuSecondaryDab, HeightDab,
    MaskedParams, MultipassConfig, MultipassStats, SubstrateParams,
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

/// Null when no adapter with compute support is found (desktop then uses its CPU path; Android has none and cannot draw).
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
pub unsafe extern "C" fn gfx_wgpu_gpu_info(
    e: *mut Engine,
    out: *mut c_char,
    capacity: usize,
) -> usize {
    guard(0, || {
        let Some(e) = engine(e) else { return 0 };
        let text = e.gpu_info();
        if !out.is_null() && capacity > 0 {
            let n = text.len().min(capacity - 1);
            std::ptr::copy_nonoverlapping(text.as_ptr(), out as *mut u8, n);
            *out.add(n) = 0;
        }
        text.len()
    })
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
    guard(0, || {
        let Some(e) = engine(e) else { return 0 };
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
    guard(0, || engine(e).map_or(0, |e| e.width()))
}

/// # Safety
/// `e` must be a live engine.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_height(e: *mut Engine) -> i32 {
    guard(0, || engine(e).map_or(0, |e| e.height()))
}

/// # Safety
/// `e` must be a live engine.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_clear(e: *mut Engine) -> bool {
    guard(false, || engine(e).is_some_and(|e| e.clear()))
}

/// [`Engine::retouch`]: `kind` (0 Blur, 1 Sharpen, 2 Clone), `factor`, `amount`, the clone offset
/// (`ox`, `oy`) and wrap, over `count` dabs of `[x, y, radius, hardness]` at `dabs`.
///
/// # Safety
/// `dabs` must point to `count * 4` floats.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn gfx_wgpu_retouch(
    e: *mut Engine,
    kind: u32,
    factor: i32,
    amount: f32,
    ox: f32,
    oy: f32,
    wrap: bool,
    dabs: *const f32,
    count: i32,
) -> bool {
    gfx_wgpu_retouch_frame(e, kind, factor, amount, ox, oy, wrap, dabs, count, 0, 0, 0, 0)
}

/// Extended ABI: the logical wrap frame inside an expanded bitmap. The original export stays
/// unchanged so older native consumers continue to load the same binary safely.
///
/// # Safety
/// `dabs` points to `count * 4` float values.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn gfx_wgpu_retouch_frame(
    e: *mut Engine,
    kind: u32,
    factor: i32,
    amount: f32,
    ox: f32,
    oy: f32,
    wrap: bool,
    dabs: *const f32,
    count: i32,
    frame_x: i32,
    frame_y: i32,
    frame_w: i32,
    frame_h: i32,
) -> bool {
    guard(false, || {
        let flat = slice(dabs, count.max(0) as usize * 4);
        let quads: Vec<[f32; 4]> = flat
            .chunks_exact(4)
            .map(|c| [c[0], c[1], c[2], c[3]])
            .collect();
        let tool = crate::Retouch {
            kind,
            factor,
            amount,
            offset: (ox, oy),
            wrap,
            wrap_frame: (frame_x, frame_y, frame_w, frame_h),
        };
        engine(e).is_some_and(|e| e.retouch(tool, &quads))
    })
}

/// [`Engine::set_stamp_mode`]: 0 paint over, 1 erase, 2 alpha lock, 3 darken, 4 lighten.
///
/// # Safety
/// `e` must be a live engine or null.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_set_stamp_mode(e: *mut Engine, mode: u32) -> bool {
    guard(false, || engine(e).is_some_and(|e| e.set_stamp_mode(mode)))
}

/// [`Engine::begin_stroke`].
///
/// # Safety
/// `e` must be a live engine or null.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_begin_stroke(e: *mut Engine) -> bool {
    guard(false, || engine(e).is_some_and(|e| e.begin_stroke()))
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
            e.upload_substrate_height(slice(data, (width as usize) * (height as usize)), width, height)
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
            e.upload_paint_height(slice(data, (width as usize) * (height as usize)), width, height)
        })
    })
}

/// Impasto height: `data` must point to `width*height` floats (the layer's size).
///
/// # Safety
/// `data` must point to `width*height` floats.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_upload_height(
    e: *mut Engine,
    data: *const f32,
    count: i32,
) -> bool {
    guard(false, || {
        if data.is_null() || count <= 0 {
            return false;
        }
        engine(e).is_some_and(|e| e.upload_height(slice(data, count as usize)))
    })
}

/// # Safety
/// `out` must point to `count` writable floats, at least the layer's pixel count.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_read_height(e: *mut Engine, out: *mut f32, count: i32) -> bool {
    guard(false, || {
        if out.is_null() || count <= 0 {
            return false;
        }
        // SAFETY: the caller passes `count` writable floats.
        let out = unsafe { std::slice::from_raw_parts_mut(out, count as usize) };
        engine(e).is_some_and(|e| e.read_height(out))
    })
}

/// # Safety
/// `dabs` must point to `count` 16-byte `HeightDab` records (x, y, radius, strength).
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_deposit_height(
    e: *mut Engine,
    dabs: *const HeightDab,
    count: i32,
    hardness: f32,
    thickness: f32,
) -> bool {
    guard(false, || {
        if count < 0 {
            return false;
        }
        engine(e)
            .is_some_and(|e| e.deposit_height(slice(dabs, count as usize), hardness, thickness))
    })
}

/// # Safety
/// `e` must be a live engine or null.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn gfx_wgpu_shade_relief(
    e: *mut Engine,
    x: i32,
    y: i32,
    w: i32,
    h: i32,
    light_azimuth_deg: f32,
    light_elevation_deg: f32,
    strength: f32,
) -> bool {
    guard(false, || {
        engine(e).is_some_and(|e| {
            e.shade_relief(x, y, w, h, light_azimuth_deg, light_elevation_deg, strength)
        })
    })
}

// ---- Selection at commit (selection.rs) --------------------------------------------------

/// [`Engine::set_selection`] from `len` coverage bytes, or [`Engine::clear_selection`] when
/// `coverage` is null.
///
/// # Safety
/// `coverage` must point to `len` readable bytes, or be null.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_set_selection(
    e: *mut Engine,
    coverage: *const u8,
    len: usize,
) -> bool {
    guard(false, || {
        engine(e).is_some_and(|e| {
            if coverage.is_null() {
                e.clear_selection();
                true
            } else {
                e.set_selection(slice(coverage, len))
            }
        })
    })
}

/// `op` 0: [`Engine::take_stroke_shadow`]; 1: [`Engine::confine_stroke`] over the stroke's own
/// rectangle with the hard clip; 2: the same, feathered.
///
/// # Safety
/// `e` must be a live engine or null.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_selection_op(e: *mut Engine, op: i32) -> bool {
    guard(false, || {
        engine(e).is_some_and(|e| match op {
            0 => e.take_stroke_shadow(),
            1 | 2 => {
                let r = e.stroke_rect();
                e.confine_stroke(r.x, r.y, r.w, r.h, op == 2)
            }
            3 => e.restore_stroke_shadow(),
            _ => false,
        })
    })
}

/// [`Engine::stroke_rect`]: writes `{x, y, w, h}` into `out`.
///
/// # Safety
/// `out` must point to four writable ints.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_stroke_rect(e: *mut Engine, out: *mut i32) -> bool {
    guard(false, || {
        if out.is_null() {
            return false;
        }
        let Some(r) = engine(e).map(|e| e.stroke_rect()) else {
            return false;
        };
        // SAFETY: the caller passes four writable ints.
        unsafe { std::slice::from_raw_parts_mut(out, 4) }.copy_from_slice(&[r.x, r.y, r.w, r.h]);
        true
    })
}

/// [`Engine::undo_open`]: GPU undo shadows on a cache-mode engine.
///
/// # Safety
/// `e` must be a live engine or null.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_undo_open(e: *mut Engine) -> bool {
    guard(false, || engine(e).is_some_and(|e| e.undo_open()))
}

// ---- Document store (docstore.rs): canonical GPU layers ------------------------------------

/// [`Engine::doc_open`].
///
/// # Safety
/// `e` must be a live engine or null.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_doc_open(e: *mut Engine) -> bool {
    guard(false, || engine(e).is_some_and(|e| e.doc_open()))
}

/// One document-layer call returning a generation (0 = failure): `op` 0 put (whole layer from
/// `rgba`, `len` bytes), 1 clear, 2 write `rect` from `rgba`, 3 copy layer `other` into `key`,
/// 4 current generation.
///
/// # Safety
/// `rgba` must point to `len` readable bytes (may be null for ops that take none); `rect` to four
/// ints for op 2.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn gfx_wgpu_doc_layer(
    e: *mut Engine,
    op: i32,
    key: u64,
    other: u64,
    rect: *const i32,
    rgba: *const u8,
    len: usize,
) -> u64 {
    guard(0, || {
        let Some(e) = engine(e) else { return 0 };
        let data = slice(rgba, len);
        match op {
            0 => e.doc_layer_put(key, data),
            1 => e.doc_layer_clear(key),
            2 => {
                let r = slice(rect, 4);
                if r.len() < 4 {
                    return 0;
                }
                e.doc_layer_write(key, r[0], r[1], r[2], r[3], data)
            }
            3 => e.doc_layer_copy(other, key),
            4 => e.doc_layer_gen(key),
            _ => 0,
        }
    })
}

/// [`Engine::doc_layer_drop`].
///
/// # Safety
/// `e` must be a live engine or null.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_doc_layer_drop(e: *mut Engine, key: u64) -> bool {
    guard(false, || engine(e).is_some_and(|e| e.doc_layer_drop(key)))
}

/// [`Engine::doc_layer_bind`]: the stroke session, 0 on failure.
///
/// # Safety
/// `e` must be a live engine or null.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_doc_layer_bind(e: *mut Engine, key: u64) -> u64 {
    guard(0, || engine(e).map_or(0, |e| e.doc_layer_bind(key)))
}

/// [`Engine::doc_layer_commit`]: writes `{x, y, w, h}` into `out_rect` and returns the new
/// generation, 0 when the session is no longer the layer's stroke.
///
/// # Safety
/// `out_rect` must point to four writable ints.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_doc_layer_commit(
    e: *mut Engine,
    key: u64,
    session: u64,
    out_rect: *mut i32,
) -> u64 {
    guard(0, || {
        if out_rect.is_null() {
            return 0;
        }
        let Some((generation, r)) = engine(e).and_then(|e| e.doc_layer_commit(key, session)) else {
            return 0;
        };
        // SAFETY: the caller passes four writable ints.
        let out = unsafe { std::slice::from_raw_parts_mut(out_rect, 4) };
        out.copy_from_slice(&[r.x, r.y, r.w, r.h]);
        generation
    })
}

/// Undo snapshots (undo.rs). `op` 0: [`Engine::doc_snapshot_stroke`] of layer `a`, bind session
/// `b`, over `rect` (`{x, y, w, h}`, read), returning the snapshot id; 1:
/// [`Engine::doc_snapshot_swap`] of snapshot `a` expecting generation `b` and tagging `c`, writing
/// the rectangle into `rect` and returning the layer's generation; 2:
/// [`Engine::doc_snapshot_drop`] of snapshot `a`, returning 1 if it existed. 0 on failure.
///
/// # Safety
/// `rect` must point to four ints (writable for op 1), or be null for op 2.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_doc_snapshot(
    e: *mut Engine,
    op: i32,
    a: u64,
    b: u64,
    c: u64,
    rect: *mut i32,
) -> u64 {
    guard(0, || {
        let Some(e) = engine(e) else { return 0 };
        if op == 2 {
            return u64::from(e.doc_snapshot_drop(a));
        }
        if rect.is_null() {
            return 0;
        }
        // SAFETY: the caller passes four ints.
        let r = unsafe { std::slice::from_raw_parts_mut(rect, 4) };
        match op {
            0 => e.doc_snapshot_stroke(a, b, r[0], r[1], r[2], r[3]),
            1 => e.doc_snapshot_swap(a, b, c).map_or(0, |(generation, s)| {
                r.copy_from_slice(&[s.x, s.y, s.w, s.h]);
                generation
            }),
            _ => 0,
        }
    })
}

/// [`Engine::doc_layer_read`] of `[x, x + w) x [y, y + h)` into `out` (`w*h*4` bytes).
///
/// # Safety
/// `out` must point to `len` writable bytes.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn gfx_wgpu_doc_layer_read(
    e: *mut Engine,
    key: u64,
    x: i32,
    y: i32,
    w: i32,
    h: i32,
    out: *mut u8,
    len: usize,
) -> bool {
    guard(false, || {
        if out.is_null() {
            return false;
        }
        // SAFETY: the caller passes `len` writable bytes.
        let out = unsafe { std::slice::from_raw_parts_mut(out, len) };
        engine(e).is_some_and(|e| e.doc_layer_read(key, x, y, w, h, out))
    })
}

// ---- Impasto v2: the wet material simulation (wet.rs) -------------------------------------

/// [`Engine::wet_begin`]: `count` pixels of straight-ARGB `raw`, `height` and `wetness`; `tiles`
/// active flags; `media_count` media floats.
///
/// # Safety
/// Every pointer must cover its stated count.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn gfx_wgpu_wet_begin(
    e: *mut Engine,
    raw: *const u32,
    height: *const f32,
    wetness: *const f32,
    count: i32,
    active: *const u32,
    tiles: i32,
    tile_size: i32,
    media: *const f32,
    media_count: i32,
) -> bool {
    guard(false, || {
        if raw.is_null()
            || height.is_null()
            || wetness.is_null()
            || active.is_null()
            || media.is_null()
            || count <= 0
            || tiles <= 0
            || media_count <= 0
        {
            return false;
        }
        let n = count as usize;
        engine(e).is_some_and(|e| {
            e.wet_begin(
                slice(raw, n),
                slice(height, n),
                slice(wetness, n),
                slice(active, tiles as usize),
                tile_size,
                slice(media, media_count as usize),
            )
        })
    })
}

/// Replaces one simulation input: `which` 0 = the media table, 1 = the selection coverage (null =
/// no selection), 2 = the dabs (`MaterialDab`, 8 floats each).
///
/// # Safety
/// `data` must point to `count` floats, or be null.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_wet_buffer(
    e: *mut Engine,
    which: i32,
    data: *const f32,
    count: i32,
) -> bool {
    guard(false, || {
        let floats = slice(data, count.max(0) as usize);
        engine(e).is_some_and(|e| match which {
            0 => e.wet_set_media(floats),
            1 => e.wet_set_coverage((!data.is_null()).then_some(floats)),
            2 => e.wet_set_dabs(bytemuck::cast_slice(&floats[..floats.len() / 8 * 8])),
            _ => false,
        })
    })
}

/// One simulation step, its arguments as floats (integers exactly): 0 raw -> layer, 1 layer ->
/// raw, 2 save raw, 3 blend selection, 4 transfer (hardness, rate, height response, deposition,
/// pickup, substrate response, initial load), 5 wetness deposit (hardness, rate), 6 pigment
/// transport (dt, rate, iterations), 7 wetness advance (dt, drying, transport, tile drying 0/1),
/// 8 levelling (dt, iterations, x, y, w, h), 9 shade (x, y, w, h, azimuth, elevation, relief,
/// use wetness 0/1, optionally into the display buffer 0/1), 10 Color Smudge on the canvas
/// wetness (on 0/1, [`Engine::set_smudge_wetness`]), 11 save the wet checkpoint, 12 restore it.
///
/// # Safety
/// `args` must point to `count` floats, or be null with `count` 0.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_wet_op(
    e: *mut Engine,
    op: i32,
    args: *const f32,
    count: i32,
) -> bool {
    guard(false, || {
        let a = slice(args, count.max(0) as usize);
        let need = [0, 0, 0, 0, 7, 2, 3, 4, 6, 8, 1, 0, 0];
        if op < 0 || op as usize >= need.len() || a.len() < need[op as usize] {
            return false;
        }
        let i = |k: usize| a[k] as i32;
        engine(e).is_some_and(|e| match op {
            0 => e.wet_raw_to_layer(),
            1 => e.wet_layer_to_raw(),
            2 => e.wet_save_raw_before(),
            3 => e.wet_blend_selection(),
            4 => e.wet_transfer(a[0], a[1], a[2], a[3], a[4], a[5], a[6]),
            5 => e.wet_deposit(a[0], a[1]),
            6 => e.wet_pigment(a[0], a[1], i(2)),
            7 => e.wet_advance(a[0], a[1], a[2], a[3] != 0.0),
            8 => e.wet_level(a[0], i(1), i(2), i(3), i(4), i(5)),
            10 => e.set_smudge_wetness(a[0] != 0.0),
            11 => e.wet_checkpoint_save(),
            12 => e.wet_checkpoint_restore(),
            _ => e.wet_shade(
                i(0),
                i(1),
                i(2),
                i(3),
                a[4],
                a[5],
                a[6],
                a[7] != 0.0,
                a.get(8).is_some_and(|&v| v != 0.0),
            ),
        })
    })
}

/// [`Engine::wet_read`] into `count`-pixel arrays.
///
/// # Safety
/// Every pointer must cover `count` writable values.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_wet_read(
    e: *mut Engine,
    raw: *mut u32,
    height: *mut f32,
    wetness: *mut f32,
    count: i32,
) -> bool {
    guard(false, || {
        if raw.is_null() || height.is_null() || wetness.is_null() || count <= 0 {
            return false;
        }
        let n = count as usize;
        // SAFETY: the caller passes `count` writable values in each array.
        let (raw, height, wetness) = unsafe {
            (
                std::slice::from_raw_parts_mut(raw, n),
                std::slice::from_raw_parts_mut(height, n),
                std::slice::from_raw_parts_mut(wetness, n),
            )
        };
        engine(e).is_some_and(|e| e.wet_read(raw, height, wetness))
    })
}

/// [`Engine::wet_read_ctl`]: tile active flags, as many zero words, then the dab flags.
///
/// # Safety
/// `out` must point to `count` writable words.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_wet_read_flags(
    e: *mut Engine,
    out: *mut u32,
    count: i32,
) -> bool {
    guard(false, || {
        if out.is_null() || count <= 0 {
            return false;
        }
        // SAFETY: the caller passes `count` writable words.
        let out = unsafe { std::slice::from_raw_parts_mut(out, count as usize) };
        engine(e).is_some_and(|e| e.wet_read_ctl(out))
    })
}

/// [`Engine::shade_relief_to_display`].
///
/// # Safety
/// `e` must be a live engine or null.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn gfx_wgpu_shade_relief_display(
    e: *mut Engine,
    x: i32,
    y: i32,
    w: i32,
    h: i32,
    light_azimuth_deg: f32,
    light_elevation_deg: f32,
    strength: f32,
) -> bool {
    guard(false, || {
        engine(e).is_some_and(|e| {
            e.shade_relief_to_display(x, y, w, h, light_azimuth_deg, light_elevation_deg, strength)
        })
    })
}

/// [`Engine::read_display`] of `[x, x + w) x [y, y + h)` into `out` (`w*h*4` bytes).
///
/// # Safety
/// `out` must point to `len` writable bytes.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn gfx_wgpu_read_display(
    e: *mut Engine,
    x: i32,
    y: i32,
    w: i32,
    h: i32,
    out: *mut u8,
    len: usize,
) -> bool {
    guard(false, || {
        if out.is_null() {
            return false;
        }
        // SAFETY: the caller passes `len` writable bytes.
        let out = unsafe { std::slice::from_raw_parts_mut(out, len) };
        engine(e).is_some_and(|e| e.read_display(x, y, w, h, out))
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
    guard((), || {
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
    })
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
    guard(0, || {
        let Some(e) = engine(e) else { return 0 };
        let text = e.adapter_description();
        if !out.is_null() && capacity > 0 {
            let n = text.len().min(capacity - 1);
            std::ptr::copy_nonoverlapping(text.as_ptr(), out as *mut u8, n);
            *out.add(n) = 0;
        }
        text.len()
    })
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

/// Whether the engine's device was lost: every later call fails and the engine must be replaced.
///
/// # Safety
/// `e` must be a live engine.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_device_lost(e: *mut Engine) -> bool {
    guard(false, || engine(e).is_some_and(|e| e.device_lost()))
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
    guard((), || {
        let Some(e) = engine(e) else { return };
        if !out.is_null() {
            let (n, bytes) = e.resident_stats();
            *out = n as u64;
            *out.add(1) = bytes;
        }
    })
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
pub unsafe extern "C" fn gfx_wgpu_set_multipass(
    e: *mut Engine,
    params: *const f32,
    n: usize,
) -> bool {
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
pub unsafe extern "C" fn gfx_wgpu_multipass_stats(
    e: *mut Engine,
    out: *mut f64,
    n: usize,
) -> usize {
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

// ---- Direct display (direct.rs; docs/Native Rendering Engine Design.md §3) ---------------------

/// Direct-display capability bits (`direct::caps`): 1 window support built in, 2 adapter can run
/// the present pass, 4 attached, 8 stroke showing, 16 attached to a real swapchain.
///
/// # Safety
/// `e` must be a live engine or null.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_direct_capabilities(e: *mut Engine) -> u32 {
    guard(0, || engine(e).map_or(0, |e| e.direct_capabilities()))
}

/// Attaches direct display to an `ANativeWindow*` of `width`x`height` pixels. False = unsupported
/// here; the caller keeps the readback display.
///
/// # Safety
/// `e` must be a live engine; `window` a valid `ANativeWindow*` the caller keeps a reference to
/// until `gfx_wgpu_direct_detach` or `gfx_wgpu_destroy`. One engine per window at a time.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_direct_attach(
    e: *mut Engine,
    window: *mut std::ffi::c_void,
    width: i32,
    height: i32,
) -> bool {
    guard(false, || {
        engine(e).is_some_and(|e| e.direct_attach_window(window, width, height))
    })
}

/// Drops the surface (disconnects from the window). Safe to call when not attached.
///
/// # Safety
/// `e` must be a live engine or null.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_direct_detach(e: *mut Engine) {
    guard((), || {
        if let Some(e) = engine(e) {
            e.direct_detach()
        }
    })
}

/// Stroke start: snapshots the (already seeded) layer as the stroke's base, clears the surface.
///
/// # Safety
/// `e` must be a live engine or null.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_direct_begin_stroke(e: *mut Engine) -> bool {
    guard(false, || engine(e).is_some_and(|e| e.direct_begin_stroke()))
}

/// Presents the stroke so far. `matrix`: 6 floats mapping a surface pixel to layer pixels
/// (`m0*x + m1*y + m2, m3*x + m4*y + m5`), or null to reuse the previous one. `new_batch`: a stamp
/// call preceded it (false for a re-present of the multipass ease).
///
/// # Safety
/// `matrix` must be null or point to 6 readable floats.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_direct_present(
    e: *mut Engine,
    matrix: *const f32,
    new_batch: bool,
) -> bool {
    guard(false, || {
        let m = slice(matrix, 6);
        let m = (m.len() == 6).then(|| [m[0], m[1], m[2], m[3], m[4], m[5]]);
        engine(e).is_some_and(|e| e.direct_present(m, new_batch))
    })
}

/// Stroke end: clears the surface.
///
/// # Safety
/// `e` must be a live engine or null.
#[no_mangle]
pub unsafe extern "C" fn gfx_wgpu_direct_end_stroke(e: *mut Engine) -> bool {
    guard(false, || engine(e).is_some_and(|e| e.direct_end_stroke()))
}
