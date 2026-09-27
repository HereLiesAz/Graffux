// WGSL port of core/nativebridge/src/main/cpp/shaders/stamp.comp -- identical math, statement for
// statement. Two deliberate representation changes, both copied from the GLES port
// (shaders/gles/stamp.comp) rather than invented here:
//  * the layer is a storage buffer of packed RGBA8 words (unpack4x8unorm/pack4x8unorm) instead of
//    an rgba8 storage image, because read+write rgba8 storage textures are not portable across
//    wgpu's backends (GL/WebGPU in particular);
//  * the push constants are a uniform buffer (push constants are a native-only wgpu feature),
//    extended with the layer size the buffer addressing needs.
// Keep the two in lock-step: a change to stamp.comp's math must land here too.

struct Dab {
    geometry: vec4<f32>, // x, y, radius, alpha
    paint0: vec4<f32>,   // angleDeg, colorR, colorG, colorB
    paint1: vec4<f32>,   // colorA, flow, resolved, hardness (resolved dabs only)
    material: vec4<f32>, // contactDepth, reservoirLoad, depositionRate, substrateResponse
};

struct Params {
    dab_count: u32,
    hardness: f32,
    color_r: f32,
    color_g: f32,
    color_b: f32,
    base_alpha: f32,
    origin_x: i32,
    origin_y: i32,
    // >0.5 = sequential SRC_OVER build-up; otherwise the pixel's strongest dab wins (stamp.comp).
    build_up: f32,
    has_substrate: f32,
    has_paint_height: f32,
    substrate_base_height: f32,
    substrate_height_scale: f32,
    substrate_texture_scale: f32,
    substrate_offset_x: f32,
    substrate_offset_y: f32,
    // >0.5 = max-combine across every call since the last upload()/clear() (stamp.comp).
    stroke_max: f32,
    layer_width: i32,
    layer_height: i32,
    _pad: u32,
};

@group(0) @binding(0) var<storage, read> dabs: array<Dab>;
@group(0) @binding(1) var<storage, read_write> layer_px: array<u32>;
@group(0) @binding(2) var substrate_tex: texture_2d<f32>;
@group(0) @binding(3) var paint_height_tex: texture_2d<f32>;
// x = pre-stroke base (pack4x8unorm), y = bits of the strongest alpha so far this stroke (0 = untouched).
@group(0) @binding(4) var<storage, read_write> stroke_state: array<vec2<u32>>;
@group(0) @binding(5) var<uniform> pc: Params;

fn layer_load(p: vec2<i32>) -> vec4<f32> {
    return unpack4x8unorm(layer_px[p.y * pc.layer_width + p.x]);
}

fn layer_store(p: vec2<i32>, c: vec4<f32>) {
    layer_px[p.y * pc.layer_width + p.x] = pack4x8unorm(c);
}

fn stamp_coverage(dist_from_center: f32, radius: f32, hardness: f32) -> f32 {
    if (radius <= 0.0) { return 0.0; }
    let t = dist_from_center / radius;
    if (t <= hardness) { return 1.0; }
    if (t >= 1.0) { return 0.0; }
    if (hardness >= 0.999) { return 1.0 - (t - hardness) / 0.001; }
    return 1.0 - (t - hardness) / (1.0 - hardness);
}

// The GLSL's ((c % s) + s) % s, with only non-negative operands. A remainder with a negative
// operand is undefined in GLSL, and naga's GL backend passes `%` straight through: on Mesa llvmpipe
// that put negative texture offsets on the wrong substrate texel (a 38-level diff against the
// GLES engine in tools/stamp-engine-diff). Same result as the original wherever it is defined.
fn wrap(c: i32, s: i32) -> i32 {
    if (c >= 0) { return c % s; }
    return s - 1 - ((-c - 1) % s);
}

fn substrate_deposition(d: Dab, canvas_point: vec2<f32>) -> f32 {
    if (pc.has_substrate <= 0.5) { return 1.0; }
    let size = vec2<i32>(textureDimensions(substrate_tex, 0));
    if (size.x <= 0 || size.y <= 0) { return 1.0; }
    let scale = max(pc.substrate_texture_scale, 0.05);
    var cell = vec2<i32>(floor(canvas_point / scale + vec2<f32>(pc.substrate_offset_x, pc.substrate_offset_y)));
    cell.x = wrap(cell.x, size.x);
    cell.y = wrap(cell.y, size.y);
    let tooth = textureLoad(substrate_tex, cell, 0).r;
    let substrate_height = clamp(
        clamp(pc.substrate_base_height, 0.0, 1.0) + tooth * clamp(pc.substrate_height_scale, 0.0, 1.0),
        0.0, 1.0);
    var local_paint_height = 0.0;
    if (pc.has_paint_height > 0.5) {
        let paint_size = vec2<i32>(textureDimensions(paint_height_tex, 0));
        let paint_pixel = vec2<i32>(floor(canvas_point));
        if (paint_pixel.x >= 0 && paint_pixel.y >= 0 && paint_pixel.x < paint_size.x && paint_pixel.y < paint_size.y) {
            local_paint_height = max(textureLoad(paint_height_tex, paint_pixel, 0).r, 0.0);
        }
    }
    let barrier = clamp(substrate_height - local_paint_height, 0.0, 1.0);
    let contact_depth = clamp(d.material.x, 0.0, 1.0);
    let response = clamp(d.material.w, 0.0, 1.0);
    let penetrates = select(0.0, 1.0, contact_depth >= barrier);
    let gate = clamp((1.0 - response) + penetrates * response, 0.0, 1.0);
    return clamp(d.material.y, 0.0, 1.0) * clamp(d.material.z, 0.0, 1.0) * gate;
}

@compute @workgroup_size(16, 16)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    let pixel = vec2<i32>(gid.xy) + vec2<i32>(pc.origin_x, pc.origin_y);
    let size = vec2<i32>(pc.layer_width, pc.layer_height);
    if (pixel.x < 0 || pixel.y < 0 || pixel.x >= size.x || pixel.y >= size.y) { return; }

    var dst = layer_load(pixel);
    let p = vec2<f32>(pixel) + vec2<f32>(0.5);
    let sequential = pc.build_up > 0.5;

    var best_a = 0.0;
    var best_rgb = vec3<f32>(0.0);

    for (var i = 0u; i < pc.dab_count; i++) {
        let d = dabs[i];
        let center = d.geometry.xy;
        let radius = max(d.geometry.z, 0.5);
        let dab_alpha = d.geometry.w;
        if (abs(p.x - center.x) > radius || abs(p.y - center.y) > radius) { continue; }
        let has_resolved_paint = d.paint1.z >= 0.5;
        let dab_hardness = select(pc.hardness, d.paint1.w, has_resolved_paint);
        var coverage = stamp_coverage(distance(p, center), radius, dab_hardness);
        if (coverage <= 0.0) { continue; }
        coverage *= substrate_deposition(d, p);
        if (coverage <= 0.0) { continue; }

        let src_rgb = select(vec3<f32>(pc.color_r, pc.color_g, pc.color_b), d.paint0.yzw, has_resolved_paint);
        let base_alpha = select(pc.base_alpha, d.paint1.x, has_resolved_paint);
        let dab_flow = select(1.0, max(d.paint1.y, 0.0), has_resolved_paint);
        let src_a = base_alpha * dab_alpha * dab_flow * coverage;
        if (src_a <= 0.0) { continue; }

        if (sequential) {
            dst = vec4<f32>(src_rgb * src_a + dst.rgb * (1.0 - src_a), src_a + dst.a * (1.0 - src_a));
            dst = round(dst * 255.0) / 255.0;
        } else if (src_a > best_a) {
            best_a = src_a;
            best_rgb = src_rgb;
        }
    }
    if (!sequential && pc.stroke_max > 0.5) {
        if (best_a <= 0.0) { return; }
        let idx = u32(pixel.y) * u32(size.x) + u32(pixel.x);
        let state = stroke_state[idx];
        let base = select(unpack4x8unorm(state.x), dst, state.y == 0u);
        let prev_a = bitcast<f32>(state.y);
        if (best_a <= prev_a) { return; }
        var out_px = vec4<f32>(best_rgb * best_a + base.rgb * (1.0 - best_a), best_a + base.a * (1.0 - best_a));
        out_px = round(out_px * 255.0) / 255.0;
        stroke_state[idx] = vec2<u32>(pack4x8unorm(base), bitcast<u32>(best_a));
        layer_store(pixel, out_px);
        return;
    }
    if (!sequential && best_a > 0.0) {
        dst = vec4<f32>(best_rgb * best_a + dst.rgb * (1.0 - best_a), best_a + dst.a * (1.0 - best_a));
        dst = round(dst * 255.0) / 255.0;
    }
    layer_store(pixel, dst);
}
