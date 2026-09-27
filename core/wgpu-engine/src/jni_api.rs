//! JNI surface for `com.hereliesaz.graffitixr.common.azphalt.wgpu.WgpuNative` (core:engine's
//! shared JVM source set -- the same Kotlin class on Android and desktop). Handles are
//! `Box<Engine>` pointers; 0 means "no engine". Panics are caught and reported as failure.

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JByteArray, JClass, JFloatArray, ReleaseMode};
use jni::sys::{jboolean, jfloat, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use crate::engine::{
    BackendChoice, ColorSmudgeDab, Engine, GpuDab, GpuSecondaryDab, MaskedParams, SubstrateParams,
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

fn floats(env: &mut JNIEnv, array: &JFloatArray) -> Option<Vec<f32>> {
    if array.is_null() {
        return None;
    }
    let len = env.get_array_length(array).ok()? as usize;
    let mut out = vec![0f32; len];
    env.get_float_array_region(array, 0, &mut out).ok()?;
    Some(out)
}

fn bytes(env: &mut JNIEnv, array: &JByteArray) -> Option<Vec<u8>> {
    if array.is_null() {
        return None;
    }
    env.convert_byte_array(array).ok()
}

fn records<T: bytemuck::Pod>(data: &[f32]) -> Vec<T> {
    let per = std::mem::size_of::<T>() / 4;
    data.chunks_exact(per)
        .map(|c| bytemuck::pod_read_unaligned(bytemuck::cast_slice(c)))
        .collect()
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
    _env: JNIEnv,
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
    _env: JNIEnv,
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
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    guard(|| unsafe { engine(handle) }.is_some_and(|e| e.clear()))
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeUpload(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    rgba: JByteArray,
) -> jboolean {
    guard(|| {
        let Some(e) = (unsafe { engine(handle) }) else {
            return false;
        };
        if rgba.is_null() {
            return false;
        }
        // SAFETY: no other JNI call happens while the elements are borrowed.
        let Ok(elements) = (unsafe { env.get_array_elements(&rgba, ReleaseMode::NoCopyBack) })
        else {
            return false;
        };
        e.upload(bytemuck::cast_slice(&elements))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeUploadSubstrateHeight(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    width: jint,
    height: jint,
) -> jboolean {
    guard(|| {
        let Some(e) = (unsafe { engine(handle) }) else {
            return false;
        };
        let Some(d) = bytes(&mut env, &data) else {
            return false;
        };
        e.upload_substrate_height(&d, width, height)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeUploadPaintHeight(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JFloatArray,
    width: jint,
    height: jint,
) -> jboolean {
    guard(|| {
        let Some(e) = (unsafe { engine(handle) }) else {
            return false;
        };
        let Some(d) = floats(&mut env, &data) else {
            return false;
        };
        e.upload_paint_height(&d, width, height)
    })
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeStampDabs(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    dabs: JFloatArray,
    color_argb: jint,
    hardness: jfloat,
    build_up: jboolean,
    substrate_params: JFloatArray,
    stroke_max: jboolean,
) -> jboolean {
    guard(|| {
        let Some(e) = (unsafe { engine(handle) }) else {
            return false;
        };
        let Some(d) = floats(&mut env, &dabs) else {
            return false;
        };
        let sub = substrate(floats(&mut env, &substrate_params));
        let dabs: Vec<GpuDab> = records(&d);
        e.stamp_dabs(
            &dabs,
            color_argb as u32,
            hardness,
            build_up != 0,
            sub,
            stroke_max != 0,
        )
    })
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeStampMaskedDabs(
    mut env: JNIEnv,
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
    guard(|| {
        let Some(e) = (unsafe { engine(handle) }) else {
            return false;
        };
        let Some(d) = floats(&mut env, &dabs) else {
            return false;
        };
        let Some(m) = bytes(&mut env, &mask) else {
            return false;
        };
        if mask_width <= 0 || mask_height <= 0 {
            return false;
        }
        let g = bytes(&mut env, &grain);
        let sd: Vec<GpuSecondaryDab> = floats(&mut env, &secondary_dabs)
            .map(|s| records(&s))
            .unwrap_or_default();
        let sm = bytes(&mut env, &secondary_mask);
        let sub = substrate(floats(&mut env, &substrate_params));
        let dabs: Vec<GpuDab> = records(&d);
        let params = MaskedParams {
            mask: &m,
            mask_width: mask_width as u32,
            mask_height: mask_height as u32,
            grain: g
                .as_deref()
                .filter(|_| grain_width > 0 && grain_height > 0)
                .map(|g| (g, grain_width as u32, grain_height as u32)),
            grain_canvas_locked: grain_canvas_locked != 0,
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
    })
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeColorSmudge(
    mut env: JNIEnv,
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
    guard(|| {
        let Some(e) = (unsafe { engine(handle) }) else {
            return false;
        };
        let Some(d) = floats(&mut env, &dabs) else {
            return false;
        };
        let dabs: Vec<ColorSmudgeDab> = records(&d);
        let src = bytes(&mut env, &sample_source);
        let source = src.as_deref().map(|s| (s, sample_width, sample_height));
        e.color_smudge(
            &dabs,
            mode,
            radius_px,
            feathering,
            smear_alpha != 0,
            paint_color_argb as u32,
            dilution,
            source,
        )
    })
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeReadback(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    out: JByteArray,
) -> jboolean {
    guard(|| {
        let Some(e) = (unsafe { engine(handle) }) else {
            return false;
        };
        if out.is_null() {
            return false;
        }
        // CopyBack: readback writes only the dirty rectangle, the rest of `out` must survive.
        // SAFETY: no other JNI call happens while the elements are borrowed.
        let Ok(mut elements) = (unsafe { env.get_array_elements(&out, ReleaseMode::CopyBack) })
        else {
            return false;
        };
        e.readback(bytemuck::cast_slice_mut(&mut elements))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_hereliesaz_graffitixr_common_azphalt_wgpu_WgpuNative_nativeAdapterDescription(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let text = unsafe { engine(handle) }
        .map(|e| e.adapter_description())
        .unwrap_or_default();
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
