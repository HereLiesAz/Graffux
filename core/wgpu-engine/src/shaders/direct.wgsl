// Direct display (docs/Native Rendering Engine Design.md §3, "wgpu direct display"): the WGSL
// counterpart of live_overlay.comp. Draws the live stroke's own contribution into a presentable
// surface layered over the canvas, straight from the engine's GPU buffers -- no readback.
//
// The surface holds only the stroke's contribution. SRC_OVER is associative, so
//   stroke over (base over below) == the correct result
// and the canvas underneath keeps showing the pre-stroke layer (`base`). Both buffers are packed
// premultiplied RGBA8 (byte order R,G,B,A). For a pixel painted `out` over its pre-stroke `base`:
//   out = S + base * (1 - Sa)   ->   Sa = 1 - (1 - out.a) / (1 - base.a),  S = out - base * (1 - Sa)
// An opaque base hides everything beneath it, so there the surface shows `out` wherever the stroke
// changed it. Unchanged pixels come out fully transparent. `src` is the layer, or with multipass on
// the multipass display buffer (draft + refinement ease), which converges to exactly the layer.
//
// Every frame redraws the whole surface: swapchain images are not persistent, and one full-screen
// fragment pass is cheap next to the Compose frame + bitmap upload + readback it replaces.

struct Params {
    // Affine map from a surface pixel centre to layer pixel coordinates:
    //   layer = (row0.x * x + row0.y * y + row0.z, row1.x * x + row1.y * y + row1.z)
    row0: vec4<f32>,
    row1: vec4<f32>,
    layer_width: i32,
    layer_height: i32,
    mode: i32,      // 0 = the stroke's contribution, 1 = transparent (stroke start / end)
    flags: i32,     // bit 0: target is sRGB (store the bytes as-is), bit 1: straight alpha
};

@group(0) @binding(0) var<storage, read> src: array<u32>;
@group(0) @binding(1) var<storage, read> base: array<u32>;
@group(0) @binding(2) var<uniform> params: Params;

@vertex
fn vs(@builtin(vertex_index) i: u32) -> @builtin(position) vec4<f32> {
    // One triangle covering the viewport.
    let x = f32(i32(i & 1u) * 4 - 1);
    let y = f32(i32(i >> 1u) * 4 - 1);
    return vec4<f32>(x, y, 0.0, 1.0);
}

fn contribution(painted: vec4<f32>, under: vec4<f32>) -> vec4<f32> {
    if (all(painted == under)) {
        return vec4<f32>(0.0);
    }
    if (under.a >= 0.999) {
        return vec4<f32>(painted.rgb, 1.0);
    }
    let sa = clamp(1.0 - (1.0 - painted.a) / (1.0 - under.a), 0.0, 1.0);
    let s = clamp(painted.rgb - under.rgb * (1.0 - sa), vec3<f32>(0.0), vec3<f32>(sa));
    return vec4<f32>(s, sa);
}

fn srgb_to_linear(c: vec3<f32>) -> vec3<f32> {
    let lo = c / 12.92;
    let hi = pow((c + 0.055) / 1.055, vec3<f32>(2.4));
    return select(hi, lo, c <= vec3<f32>(0.04045));
}

@fragment
fn fs(@builtin(position) pos: vec4<f32>) -> @location(0) vec4<f32> {
    if (params.mode != 0) {
        return vec4<f32>(0.0);
    }
    // `pos.xy` is already the pixel centre (x + 0.5, y + 0.5), like live_overlay.comp's `c`.
    let c = vec3<f32>(pos.xy, 1.0);
    let q = vec2<f32>(dot(params.row0.xyz, c), dot(params.row1.xyz, c));
    let lp = vec2<i32>(floor(q));
    var out = vec4<f32>(0.0);
    if (lp.x >= 0 && lp.y >= 0 && lp.x < params.layer_width && lp.y < params.layer_height) {
        let i = u32(lp.y) * u32(params.layer_width) + u32(lp.x);
        out = contribution(unpack4x8unorm(src[i]), unpack4x8unorm(base[i]));
    }
    if ((params.flags & 2) != 0 && out.a > 0.0) {
        out = vec4<f32>(out.rgb / out.a, out.a);
    }
    if ((params.flags & 1) != 0) {
        // An sRGB target encodes on store; decode first so the stored bytes are ours unchanged.
        out = vec4<f32>(srgb_to_linear(out.rgb), out.a);
    }
    return out;
}
