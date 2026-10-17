//! JNI surface for `com.hereliesaz.graffitixr.common.azphalt.wgpu.WgpuNative` (core:engine's
//! shared JVM source set -- the same Kotlin class on Android and desktop). Handles are
//! `Box<Engine>` pointers; 0 means "no engine". Panics are caught and reported as failure.

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::errors::LogErrorAndDefault;
use jni::objects::{JByteArray, JClass, JFloatArray, JIntArray, JLongArray, ReleaseMode};
use jni::sys::{
    jboolean, jdoubleArray, jfloat, jint, jlong, jlongArray, jstring, JNI_FALSE, JNI_TRUE,
};
use jni::{Env, EnvUnowned};

use crate::engine::{
    BackendChoice, ColorSmudgeDab, Engine, GpuDab, GpuSecondaryDab, MaskedParams, MultipassConfig,
    SubstrateParams,
};

fn guard(f: impl FnOnce() -> bool) -> jboolean {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(true) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

unsafe fn engine<'a>(handle: jlong) -> Option<&'a mut Engine> {
    (handle as *mut Engine).as_mut()
}

fn floats(env: &Env, array: &JFloatArray) -> Option<Vec<f32>> {
    if array.is_null() {
        return None;
    }
    let len = array.len(env).ok()?;
    let mut out = vec![0f32; len];
    array.get_region(env, 0, &mut out).ok()?;
    Some(out)
}

fn bytes(env: &Env, array: &JByteArray) -> Option<Vec<u8>> {
    if array.is_null() {
        return None;
    }
    env.convert_byte_array(array).ok()
}

/// Splits packed floats into records. `None` when the length is not a whole number of records,
/// so a packer stride error fails the call instead of silently dropping a trailing partial dab.
fn records<T: bytemuck::Pod>(data: &[f32]) -> Option<Vec<T>> {
    let per = std::mem::size_of::<T>() / 4;
    if per == 0 || !data.len().is_multiple_of(per) {
        return None;
    }
    Some(
        data.chunks_exact(per)
            .map(|c| bytemuck::pod_read_unaligned(bytemuck::cast_slice(c)))
            .collect(),
    )
}

/// [enabled, hasPaintHeight, baseHeight, heightScale, textureScale, offsetX, offsetY]
fn substrate(data: Option<Vec<f32>>) -> SubstrateParams {
    match data {
        Some(s) if s.len() >= 7 => SubstrateParams {
            enabled: s[0] > 0.5,
            has_paint_height: s[1] > 0.5,
            base_height: s[2],
            height_scale: s[3],
            texture_scale: s[4],
            texture_offset_x: s[5],
            texture_offset_y: s[6],
        },
        _ => SubstrateParams::default(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeCreate(
    _env: EnvUnowned,
    _class: JClass,
    width: jint,
    height: jint,
    backend: jint,
) -> jlong {
    catch_unwind(
        || match Engine::new(width, height, BackendChoice::from_id(backend)) {
            Some(e) => Box::into_raw(Box::new(e)) as jlong,
            None => 0,
        },
    )
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeDestroy(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: the Kotlin wrapper hands each handle to nativeDestroy exactly once.
        let _ = catch_unwind(|| drop(unsafe { Box::from_raw(handle as *mut Engine) }));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeClear(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    guard(|| unsafe { engine(handle) }.is_some_and(|e| e.clear()))
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeUpload(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    rgba: JByteArray,
) -> jboolean {
    env.with_env(|env| {
        Ok::<_, jni::errors::Error>(guard(|| {
            let Some(e) = (unsafe { engine(handle) }) else {
                return false;
            };
            if rgba.is_null() {
                return false;
            }
            // SAFETY: no other JNI call happens while the elements are borrowed.
            let Ok(elements) = (unsafe { rgba.get_elements(env, ReleaseMode::NoCopyBack) }) else {
                return false;
            };
            e.upload(bytemuck::cast_slice(&elements))
        }))
    })
    .resolve::<LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeUploadRows(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    rgba: JByteArray,
    y: jint,
    rows: jint,
) -> jboolean {
    env.with_env(|env| {
        Ok::<_, jni::errors::Error>(guard(|| {
            let Some(e) = (unsafe { engine(handle) }) else {
                return false;
            };
            if rgba.is_null() {
                return false;
            }
            // SAFETY: no other JNI call happens while the elements are borrowed.
            let Ok(elements) = (unsafe { rgba.get_elements(env, ReleaseMode::NoCopyBack) }) else {
                return false;
            };
            e.upload_rows(bytemuck::cast_slice(&elements), y, rows)
        }))
    })
    .resolve::<LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeUploadSubstrateHeight(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    width: jint,
    height: jint,
) -> jboolean {
    env.with_env(|env| {
        Ok::<_, jni::errors::Error>(guard(|| {
            let Some(e) = (unsafe { engine(handle) }) else {
                return false;
            };
            let Some(d) = bytes(env, &data) else {
                return false;
            };
            e.upload_substrate_height(&d, width, height)
        }))
    })
    .resolve::<LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeUploadPaintHeight(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    data: JFloatArray,
    width: jint,
    height: jint,
) -> jboolean {
    env.with_env(|env| {
        Ok::<_, jni::errors::Error>(guard(|| {
            let Some(e) = (unsafe { engine(handle) }) else {
                return false;
            };
            let Some(d) = floats(env, &data) else {
                return false;
            };
            e.upload_paint_height(&d, width, height)
        }))
    })
    .resolve::<LogErrorAndDefault>()
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeStampDabs(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    dabs: JFloatArray,
    color_argb: jint,
    hardness: jfloat,
    build_up: jboolean,
    substrate_params: JFloatArray,
    stroke_max: jboolean,
) -> jboolean {
    env.with_env(|env| {
        Ok::<_, jni::errors::Error>(guard(|| {
            let Some(e) = (unsafe { engine(handle) }) else {
                return false;
            };
            let Some(d) = floats(env, &dabs) else {
                return false;
            };
            let sub = substrate(floats(env, &substrate_params));
            let Some(dabs) = records::<GpuDab>(&d) else {
                return false;
            };
            e.stamp_dabs(
                &dabs,
                color_argb as u32,
                hardness,
                build_up,
                sub,
                stroke_max,
            )
        }))
    })
    .resolve::<LogErrorAndDefault>()
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeStampMaskedDabs(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    dabs: JFloatArray,
    color_argb: jint,
    hardness: jfloat,
    mask: JByteArray,
    mask_width: jint,
    mask_height: jint,
    grain: JByteArray,
    grain_width: jint,
    grain_height: jint,
    grain_canvas_locked: jboolean,
    grain_scale: jfloat,
    grain_phase_x: jfloat,
    grain_phase_y: jfloat,
    secondary_dabs: JFloatArray,
    secondary_mask: JByteArray,
    secondary_width: jint,
    secondary_height: jint,
    substrate_params: JFloatArray,
) -> jboolean {
    env.with_env(|env| {
        Ok::<_, jni::errors::Error>(guard(|| {
            let Some(e) = (unsafe { engine(handle) }) else {
                return false;
            };
            let Some(d) = floats(env, &dabs) else {
                return false;
            };
            let Some(m) = bytes(env, &mask) else {
                return false;
            };
            if mask_width <= 0 || mask_height <= 0 {
                return false;
            }
            let g = bytes(env, &grain);
            let sd: Vec<GpuSecondaryDab> = match floats(env, &secondary_dabs) {
                Some(s) => match records(&s) {
                    Some(r) => r,
                    None => return false,
                },
                None => Vec::new(),
            };
            let sm = bytes(env, &secondary_mask);
            let sub = substrate(floats(env, &substrate_params));
            let Some(dabs) = records::<GpuDab>(&d) else {
                return false;
            };
            let params = MaskedParams {
                mask: &m,
                mask_width: mask_width as u32,
                mask_height: mask_height as u32,
                grain: g
                    .as_deref()
                    .filter(|_| grain_width > 0 && grain_height > 0)
                    .map(|g| (g, grain_width as u32, grain_height as u32)),
                grain_canvas_locked,
                grain_scale,
                grain_phase_x,
                grain_phase_y,
                secondary_dabs: &sd,
                secondary_mask: sm
                    .as_deref()
                    .filter(|_| secondary_width > 0 && secondary_height > 0)
                    .map(|m| (m, secondary_width as u32, secondary_height as u32)),
                substrate: sub,
            };
            e.stamp_masked_dabs(&dabs, color_argb as u32, hardness, &params)
        }))
    })
    .resolve::<LogErrorAndDefault>()
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeColorSmudge(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    dabs: JFloatArray,
    mode: jint,
    radius_px: jfloat,
    feathering: jfloat,
    smear_alpha: jboolean,
    paint_color_argb: jint,
    dilution: jfloat,
    sample_source: JByteArray,
    sample_width: jint,
    sample_height: jint,
) -> jboolean {
    env.with_env(|env| {
        Ok::<_, jni::errors::Error>(guard(|| {
            let Some(e) = (unsafe { engine(handle) }) else {
                return false;
            };
            let Some(d) = floats(env, &dabs) else {
                return false;
            };
            let Some(dabs) = records::<ColorSmudgeDab>(&d) else {
                return false;
            };
            let src = bytes(env, &sample_source);
            let source = src.as_deref().map(|s| (s, sample_width, sample_height));
            e.color_smudge(
                &dabs,
                mode,
                radius_px,
                feathering,
                smear_alpha,
                paint_color_argb as u32,
                dilution,
                source,
            )
        }))
    })
    .resolve::<LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeReadback(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    out: JByteArray,
) -> jboolean {
    env.with_env(|env| {
        Ok::<_, jni::errors::Error>(guard(|| {
            let Some(e) = (unsafe { engine(handle) }) else {
                return false;
            };
            if out.is_null() {
                return false;
            }
            // CopyBack: readback writes only the dirty rectangle, the rest of `out` must survive.
            // SAFETY: no other JNI call happens while the elements are borrowed.
            let Ok(mut elements) = (unsafe { out.get_elements(env, ReleaseMode::CopyBack) }) else {
                return false;
            };
            e.readback(bytemuck::cast_slice_mut(&mut elements))
        }))
    })
    .resolve::<LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeAdapterDescription(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let text = catch_unwind(AssertUnwindSafe(|| {
        unsafe { engine(handle) }
            .map(|e| e.adapter_description())
            .unwrap_or_default()
    }))
    .unwrap_or_default();
    env.with_env(|env| Ok::<_, jni::errors::Error>(env.new_string(text)?.into_raw()))
        .resolve::<LogErrorAndDefault>()
}

// ---- Dirty-rect readback and resident layers ---------------------------------------------

/// Runs `f` with `array` borrowed as bytes (no copy back).
fn with_bytes<R>(env: &mut Env, array: &JByteArray, fallback: R, f: impl FnOnce(&[u8]) -> R) -> R {
    if array.is_null() {
        return fallback;
    }
    // SAFETY: no other JNI call happens while the elements are borrowed.
    match unsafe { array.get_elements(env, ReleaseMode::NoCopyBack) } {
        Ok(elements) => f(bytemuck::cast_slice(&elements)),
        Err(_) => fallback,
    }
}

fn session(f: impl FnOnce() -> u64) -> jlong {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(0) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeReadbackRect(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    out: JByteArray,
    rect: JIntArray,
) -> jboolean {
    env.with_env(|env| {
        Ok::<_, jni::errors::Error>(guard(|| {
            let Some(e) = (unsafe { engine(handle) }) else {
                return false;
            };
            if out.is_null() {
                return false;
            }
            let result = {
                // SAFETY: no other JNI call happens while the elements are borrowed.
                let Ok(mut elements) = (unsafe { out.get_elements(env, ReleaseMode::CopyBack) })
                else {
                    return false;
                };
                e.readback_rect(bytemuck::cast_slice_mut(&mut elements))
            };
            let Some((x, y, w, h)) = result else {
                return false;
            };
            if !rect.is_null() {
                let _ = rect.set_region(env, 0, &[x, y, w, h]);
            }
            true
        }))
    })
    .resolve::<LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeReadRegion(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    x: jint,
    y: jint,
    w: jint,
    h: jint,
    out: JByteArray,
) -> jboolean {
    env.with_env(|env| {
        Ok::<_, jni::errors::Error>(guard(|| {
            let Some(e) = (unsafe { engine(handle) }) else {
                return false;
            };
            if out.is_null() {
                return false;
            }
            // SAFETY: no other JNI call happens while the elements are borrowed.
            let Ok(mut elements) = (unsafe { out.get_elements(env, ReleaseMode::CopyBack) }) else {
                return false;
            };
            e.read_region(x, y, w, h, bytemuck::cast_slice_mut(&mut elements))
        }))
    })
    .resolve::<LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeBindLayer(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    key: jlong,
    generation: jlong,
) -> jlong {
    session(|| unsafe { engine(handle) }.map_or(0, |e| e.bind_layer(key as u64, generation as u64)))
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeUploadLayer(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    key: jlong,
    generation: jlong,
    rgba: JByteArray,
) -> jlong {
    env.with_env(|env| {
        Ok::<_, jni::errors::Error>(session(|| {
            let Some(e) = (unsafe { engine(handle) }) else {
                return 0;
            };
            with_bytes(env, &rgba, 0, |b| {
                e.upload_layer(key as u64, generation as u64, b)
            })
        }))
    })
    .resolve::<LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeCommitLayer(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    key: jlong,
    session: jlong,
    generation: jlong,
) -> jboolean {
    guard(|| {
        unsafe { engine(handle) }
            .is_some_and(|e| e.commit_layer(key as u64, session as u64, generation as u64))
    })
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeRefreshLayer(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    key: jlong,
    session: jlong,
    generation: jlong,
    rgba: JByteArray,
    x: jint,
    y: jint,
    w: jint,
    h: jint,
) -> jboolean {
    env.with_env(|env| {
        Ok::<_, jni::errors::Error>(guard(|| {
            let Some(e) = (unsafe { engine(handle) }) else {
                return false;
            };
            with_bytes(env, &rgba, false, |b| {
                e.refresh_layer(
                    key as u64,
                    session as u64,
                    generation as u64,
                    b,
                    (x, y, w, h),
                )
            })
        }))
    })
    .resolve::<LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeInvalidateLayer(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    key: jlong,
) -> jboolean {
    guard(|| unsafe { engine(handle) }.is_some_and(|e| e.invalidate_layer(key as u64)))
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeInvalidateAllLayers(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Some(e) = unsafe { engine(handle) } {
            e.invalidate_all_layers();
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeSetResidentBudget(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    bytes: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Some(e) = unsafe { engine(handle) } {
            e.set_resident_budget(bytes.max(0) as u64);
        }
    }));
}

/// {resident layer count, bytes held}.
#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeResidentStats(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jlongArray {
    let (n, bytes) = catch_unwind(AssertUnwindSafe(|| {
        unsafe { engine(handle) }.map_or((0, 0), |e| e.resident_stats())
    }))
    .unwrap_or((0, 0));
    env.with_env(|env| {
        let array: JLongArray = env.new_long_array(2)?;
        array.set_region(env, 0, &[n as i64, bytes as i64])?;
        Ok::<_, jni::errors::Error>(array.into_raw())
    })
    .resolve::<LogErrorAndDefault>()
}

// ---- Multipass rendering (experimental; see multipass.rs) ------------------------------------

/// `params` = MultipassConfig floats; null or short takes defaults (null = off).
#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeSetMultipass(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    params: JFloatArray,
) -> jboolean {
    env.with_env(|env| {
        Ok::<_, jni::errors::Error>(guard(|| {
            let values = floats(env, &params).unwrap_or_default();
            unsafe { engine(handle) }
                .is_some_and(|e| e.set_multipass(MultipassConfig::from_floats(&values)))
        }))
    })
    .resolve::<LogErrorAndDefault>()
}

/// 1 = work or animation remains, 0 = idle, -1 = failure.
#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeRefine(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    budget_ms: jfloat,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        unsafe { engine(handle) }.map_or(-1, |e| e.refine(budget_ms))
    }))
    .unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeFlush(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    guard(|| unsafe { engine(handle) }.is_some_and(|e| e.flush()))
}

/// MultipassStats::to_array().
#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeMultipassStats(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jdoubleArray {
    let stats = catch_unwind(AssertUnwindSafe(|| {
        unsafe { engine(handle) }.map(|e| e.multipass_stats().to_array())
    }))
    .ok()
    .flatten();
    let Some(stats) = stats else {
        return std::ptr::null_mut();
    };
    env.with_env(|env| {
        let array = env.new_double_array(stats.len())?;
        array.set_region(env, 0, &stats)?;
        Ok::<_, jni::errors::Error>(array.into_raw())
    })
    .resolve::<LogErrorAndDefault>()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn records_rejects_a_trailing_partial_record() {
        let per = std::mem::size_of::<GpuDab>() / 4;
        assert_eq!(
            records::<GpuDab>(&vec![0.0; per * 2]).map(|r| r.len()),
            Some(2)
        );
        assert!(records::<GpuDab>(&vec![0.0; per * 2 + 1]).is_none());
        assert!(records::<GpuDab>(&[]).is_some_and(|r| r.is_empty()));
    }
}
