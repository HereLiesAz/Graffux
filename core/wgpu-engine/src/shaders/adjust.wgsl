// Photo adjustment layers (core/wgpu-engine/src/adjust.rs): one pass over the composite below an
// adjustment layer, writing the adjusted image times the layer's mask. Pixels are premultiplied
// RGBA8 packed in a u32 (R in the low byte), the same memory as an Android ARGB_8888 bitmap.
//
// The formulas are the reference ones in core/engine's PhotoAdjustMath.kt, step for step; a change
// here is a change there (adjust.rs's tests compare the two through a CPU port).

struct Params {
    // [exposure EV, contrast, highlights, shadows]
    tone0: vec4<f32>,
    // [whites, blacks, vibrance, saturation]
    tone1: vec4<f32>,
    // [white-balance R, G, B multipliers, mask on (0/1)]
    wb: vec4<f32>,
    // [levels black, levels gamma, levels white, curves on (0/1)]
    levels: vec4<f32>,
    // [width, height, dehaze, grain seed]
    dims: vec4<f32>,
    // [vignette amount, midpoint, feather, grain amount]
    fx: vec4<f32>,
    // [grain size, clarity, texture, sharpen]
    detail0: vec4<f32>,
    // [noise reduction, detail on (0/1), invert mask (0/1), unused]
    detail1: vec4<f32>,
    // HSL: eight ranges x (hue shift, saturation, luminance), two ranges per pair of vec4s.
    hsl: array<vec4<f32>, 6>,
};

@group(0) @binding(0) var<uniform> p: Params;
@group(0) @binding(1) var<storage, read> src: array<u32>;
@group(0) @binding(2) var<storage, read> mask: array<u32>;
@group(0) @binding(3) var<storage, read> curves: array<f32>;
@group(0) @binding(4) var<storage, read_write> dst: array<u32>;
// Two blurred copies of the source's luma for the Detail tab (small and large radius), or empty.
@group(0) @binding(5) var<storage, read> blur: array<f32>;

fn unpack(v: u32) -> vec4<f32> {
    return vec4<f32>(
        f32(v & 0xffu),
        f32((v >> 8u) & 0xffu),
        f32((v >> 16u) & 0xffu),
        f32((v >> 24u) & 0xffu),
    ) / 255.0;
}

fn pack(c: vec4<f32>) -> u32 {
    let q = vec4<u32>(round(clamp(c, vec4<f32>(0.0), vec4<f32>(1.0)) * 255.0));
    return q.x | (q.y << 8u) | (q.z << 16u) | (q.w << 24u);
}

fn to_linear(c: vec3<f32>) -> vec3<f32> {
    let lo = c / 12.92;
    let hi = pow((c + 0.055) / 1.055, vec3<f32>(2.4));
    return select(hi, lo, c <= vec3<f32>(0.04045));
}

fn to_srgb(c: vec3<f32>) -> vec3<f32> {
    let lo = c * 12.92;
    let hi = 1.055 * pow(max(c, vec3<f32>(0.0)), vec3<f32>(1.0 / 2.4)) - 0.055;
    return select(hi, lo, c <= vec3<f32>(0.0031308));
}

fn luma(c: vec3<f32>) -> f32 {
    return dot(c, vec3<f32>(0.2126, 0.7152, 0.0722));
}

// Blacks/whites move the end points, shadows/highlights lift or pull the lower and upper thirds,
// contrast blends toward (or away from) a smoothstep S-curve. Per channel, like a tone curve.
fn tone(x0: vec3<f32>) -> vec3<f32> {
    let bk = -p.tone1.y * 0.15;
    let wh = 1.0 - p.tone1.x * 0.15;
    var x = clamp((x0 - bk) / (wh - bk), vec3<f32>(0.0), vec3<f32>(1.0));
    let inv = vec3<f32>(1.0) - x;
    x = x + 0.9 * p.tone0.w * x * inv * inv + 0.9 * p.tone0.z * x * x * inv;
    x = clamp(x, vec3<f32>(0.0), vec3<f32>(1.0));
    let s = x * x * (3.0 - 2.0 * x);
    return clamp(x + p.tone0.y * (s - x), vec3<f32>(0.0), vec3<f32>(1.0));
}

fn curve(c: vec3<f32>) -> vec3<f32> {
    // Master (row 0) first, then each channel's own row.
    let m = vec3<u32>(round(clamp(c, vec3<f32>(0.0), vec3<f32>(1.0)) * 255.0));
    let mm = vec3<f32>(curves[m.x], curves[m.y], curves[m.z]);
    let q = vec3<u32>(round(clamp(mm, vec3<f32>(0.0), vec3<f32>(1.0)) * 255.0));
    return vec3<f32>(curves[256u + q.x], curves[512u + q.y], curves[768u + q.z]);
}

fn rgb_to_hsl(c: vec3<f32>) -> vec3<f32> {
    let mx = max(c.r, max(c.g, c.b));
    let mn = min(c.r, min(c.g, c.b));
    let l = (mx + mn) * 0.5;
    let d = mx - mn;
    if (d < 1e-6) {
        return vec3<f32>(0.0, 0.0, l);
    }
    let s = d / max(1.0 - abs(2.0 * l - 1.0), 1e-6);
    var h: f32;
    if (mx == c.r) {
        h = (c.g - c.b) / d;
        if (h < 0.0) { h = h + 6.0; }
    } else if (mx == c.g) {
        h = (c.b - c.r) / d + 2.0;
    } else {
        h = (c.r - c.g) / d + 4.0;
    }
    return vec3<f32>(h * 60.0, clamp(s, 0.0, 1.0), l);
}

// f(n) = l - a * max(-1, min(k - 3, 9 - k, 1)), k = (n + h / 30) mod 12, a = s * min(l, 1 - l).
fn hsl_to_rgb(hsl: vec3<f32>) -> vec3<f32> {
    let a = hsl.y * min(hsl.z, 1.0 - hsl.z);
    let k = (vec3<f32>(0.0, 8.0, 4.0) + hsl.x / 30.0) % 12.0;
    let t = clamp(min(k - 3.0, 9.0 - k), vec3<f32>(-1.0), vec3<f32>(1.0));
    return hsl.z - a * t;
}

const CENTERS = array<f32, 8>(0.0, 30.0, 60.0, 120.0, 180.0, 240.0, 270.0, 300.0);

fn hsl_param(i: u32) -> vec3<f32> {
    // Range i's (hue, sat, lum) sit at floats 3i..3i+2 of the 24-float block.
    let f0 = 3u * i;
    var out = vec3<f32>(0.0);
    for (var k = 0u; k < 3u; k = k + 1u) {
        let f = f0 + k;
        out[k] = p.hsl[f / 4u][f % 4u];
    }
    return out;
}

fn range_weight(h: f32, i: u32) -> f32 {
    let c = CENTERS[i];
    let prev = CENTERS[(i + 7u) % 8u];
    let next = CENTERS[(i + 1u) % 8u];
    var d = h - c;
    if (d > 180.0) { d = d - 360.0; }
    if (d < -180.0) { d = d + 360.0; }
    var span: f32;
    if (d >= 0.0) {
        span = next - c;
        if (span <= 0.0) { span = span + 360.0; }
    } else {
        span = c - prev;
        if (span <= 0.0) { span = span + 360.0; }
    }
    return max(0.0, 1.0 - abs(d) / span);
}

fn hsl_adjust(c: vec3<f32>) -> vec3<f32> {
    var hsl = rgb_to_hsl(c);
    if (hsl.y < 1e-4) {
        return c;
    }
    var dh = 0.0;
    var ds = 0.0;
    var dl = 0.0;
    for (var i = 0u; i < 8u; i = i + 1u) {
        let w = range_weight(hsl.x, i);
        if (w > 0.0) {
            let q = hsl_param(i);
            dh = dh + w * q.x;
            ds = ds + w * q.y;
            dl = dl + w * q.z;
        }
    }
    if (dh == 0.0 && ds == 0.0 && dl == 0.0) {
        return c;
    }
    hsl.x = (hsl.x + dh * 30.0 + 360.0) % 360.0;
    let sat = hsl.y;
    hsl.y = clamp(hsl.y * (1.0 + ds), 0.0, 1.0);
    hsl.z = clamp(hsl.z + dl * 0.25 * sat, 0.0, 1.0);
    return hsl_to_rgb(hsl);
}

fn hash(x: u32) -> u32 {
    var v = x * 747796405u + 2891336453u;
    let w = ((v >> ((v >> 28u) + 4u)) ^ v) * 277803737u;
    return (w >> 22u) ^ w;
}

@compute @workgroup_size(8, 8, 1)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
    let w = u32(p.dims.x);
    let h = u32(p.dims.y);
    if (id.x >= w || id.y >= h) {
        return;
    }
    let i = id.y * w + id.x;
    let px = unpack(src[i]);
    if (px.a <= 0.0) {
        dst[i] = 0u;
        return;
    }
    var c = px.rgb / px.a;

    // White balance and exposure, in linear light.
    var lin = to_linear(c) * p.wb.xyz * exp2(p.tone0.x);
    c = to_srgb(clamp(lin, vec3<f32>(0.0), vec3<f32>(1.0)));

    c = tone(c);

    // Detail: local contrast of the source's own luma against its blurred copies (small radius at
    // blur[i], large at blur[n + i]), computed once per source, so a slider drag stays one pass.
    if (p.detail1.y > 0.5) {
        let n = w * h;
        let y = luma(px.rgb / px.a);
        let small = blur[i];
        let large = blur[n + i];
        var dy = 0.0;
        dy = dy + p.detail0.y * 0.6 * (y - large);      // clarity
        dy = dy + p.detail0.z * 0.8 * (y - small);      // texture
        dy = dy + p.detail0.w * 1.5 * (y - small);      // sharpen
        dy = dy + p.detail1.x * (small - y);            // noise reduction
        c = clamp(c + vec3<f32>(dy), vec3<f32>(0.0), vec3<f32>(1.0));
    }

    // Dehaze: subtract a share of the airlight and stretch back, plus a little saturation.
    if (p.dims.z != 0.0) {
        let k = p.dims.z * 0.12;
        c = clamp((c - k) / (1.0 - k), vec3<f32>(0.0), vec3<f32>(1.0));
    }

    if (p.levels.w > 0.5) {
        c = curve(c);
    }
    let lb = p.levels.x;
    let lw = p.levels.z;
    c = pow(clamp((c - lb) / max(lw - lb, 1e-4), vec3<f32>(0.0), vec3<f32>(1.0)), vec3<f32>(1.0 / max(p.levels.y, 0.01)));

    c = hsl_adjust(c);

    // Vibrance (weighted toward muted colours) and saturation, around the luma.
    let y = luma(c);
    let sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    let f = (1.0 + p.tone1.z * (1.0 - sat)) * (1.0 + p.tone1.w) * (1.0 + max(p.dims.z, 0.0) * 0.2);
    c = clamp(vec3<f32>(y) + (c - vec3<f32>(y)) * f, vec3<f32>(0.0), vec3<f32>(1.0));

    // Vignette: amount < 0 darkens the corners, > 0 lightens them.
    if (p.fx.x != 0.0) {
        let uv = (vec2<f32>(f32(id.x), f32(id.y)) + 0.5) / vec2<f32>(f32(w), f32(h)) * 2.0 - 1.0;
        let r = length(uv) / 1.41421356;
        let mid = p.fx.y;
        let edge = mid + max(p.fx.z, 0.01) * (1.0 - mid);
        let t = smoothstep(mid, edge, r);
        c = clamp(c * (1.0 + p.fx.x * t), vec3<f32>(0.0), vec3<f32>(1.0));
    }

    // Grain: one value per grain-size cell, the same on every device.
    if (p.fx.w > 0.0) {
        let size = max(u32(p.detail0.x), 1u);
        let cx = id.x / size;
        let cy = id.y / size;
        let seed = u32(p.dims.w);
        let n = f32(hash((cx * 73856093u) ^ (cy * 19349663u) ^ seed) & 0xffffu) / 65535.0 - 0.5;
        c = clamp(c + vec3<f32>(n * p.fx.w * 0.25), vec3<f32>(0.0), vec3<f32>(1.0));
    }

    var m = 1.0;
    if (p.wb.w > 0.5) {
        let word = mask[i / 4u];
        m = f32((word >> ((i % 4u) * 8u)) & 0xffu) / 255.0;
        if (p.detail1.z > 0.5) {
            m = 1.0 - m;
        }
    }
    let a = px.a * m;
    dst[i] = pack(vec4<f32>(c * a, a));
}
