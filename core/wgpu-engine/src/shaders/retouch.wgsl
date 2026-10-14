// The retouch tools that read their neighbourhood (Blur, Sharpen, Clone) on the GPU. The CPU
// reference is ImageProcessor.applyToolToBitmap. Every pixel is computed from the stroke shadow
// (the layer as the stroke found it, selection.rs), so the stroke never reads its own output and
// re-running it from the shadow gives the same pixels.
//
// Coverage is the stroke's round dabs (constant radius along its polyline), each pixel taking its
// strongest dab -- the brush-shaped mask the CPU drew with a round-capped stroke.
//
//   0 Blur     out = blurred * cov SRC_OVER shadow; blurred = Bitmap.createScaledBitmap down by
//              `factor` and back up, both bilinear (ImageProcessor.cheapBlur), in premultiplied space
//   1 Sharpen  straight rgb += amount * cov * (rgb - blur2(rgb)), alpha kept (unsharp mask)
//   2 Clone    out = shadow(p + offset) * cov SRC_OVER shadow (wrap-around optional)

struct Params {
    kind: u32,
    dab_count: u32,
    rx: i32,
    ry: i32,
    rw: i32,
    rh: i32,
    width: i32,
    height: i32,
    factor: i32,
    wrap: u32,
    amount: f32,
    offset_x: f32,
    offset_y: f32,
    frame_x: i32,
    frame_y: i32,
    frame_w: i32,
    frame_h: i32,
    _p0: u32,
    _p1: u32,
    _p2: u32,
};

@group(0) @binding(0) var<uniform> p: Params;
@group(0) @binding(1) var<storage, read_write> layer_px: array<u32>;
@group(0) @binding(2) var<storage, read> shadow_px: array<u32>;
// x, y, radius, hardness.
@group(0) @binding(3) var<storage, read> dabs: array<vec4<f32>>;

fn wrap_coord(v: i32, start: i32, size: i32) -> i32 {
    return start + ((v - start) % size + size) % size;
}

fn texel(x: i32, y: i32) -> vec4<f32> {
    var cx = clamp(x, 0, p.width - 1);
    var cy = clamp(y, 0, p.height - 1);
    if (p.wrap != 0u) {
        cx = wrap_coord(x, p.frame_x, p.frame_w);
        cy = wrap_coord(y, p.frame_y, p.frame_h);
    }
    return unpack4x8unorm(shadow_px[cy * p.width + cx]);
}

fn bilinear(fx: f32, fy: f32) -> vec4<f32> {
    let x0 = i32(floor(fx));
    let y0 = i32(floor(fy));
    let tx = fx - f32(x0);
    let ty = fy - f32(y0);
    let top = mix(texel(x0, y0), texel(x0 + 1, y0), tx);
    let bottom = mix(texel(x0, y0 + 1), texel(x0 + 1, y0 + 1), tx);
    return mix(top, bottom, ty);
}

// One texel of the downscaled image, sampled bilinearly from the shadow at its centre.
fn small_texel(i: i32, j: i32, sw: i32, sh: i32) -> vec4<f32> {
    var ci = clamp(i, 0, sw - 1);
    var cj = clamp(j, 0, sh - 1);
    var left = 0;
    var top = 0;
    var width = p.width;
    var height = p.height;
    if (p.wrap != 0u) {
        ci = ((i % sw) + sw) % sw;
        cj = ((j % sh) + sh) % sh;
        left = p.frame_x;
        top = p.frame_y;
        width = p.frame_w;
        height = p.frame_h;
    }
    let sx = f32(width) / f32(sw);
    let sy = f32(height) / f32(sh);
    return bilinear(f32(left) + (f32(ci) + 0.5) * sx - 0.5,
                    f32(top) + (f32(cj) + 0.5) * sy - 0.5);
}

// ImageProcessor.cheapBlur at pixel (x, y): down by `factor`, back up, both bilinear.
fn blurred(x: i32, y: i32, factor: i32) -> vec4<f32> {
    var width = p.width;
    var height = p.height;
    var left = 0;
    var top = 0;
    if (p.wrap != 0u) {
        width = p.frame_w;
        height = p.frame_h;
        left = p.frame_x;
        top = p.frame_y;
    }
    let sw = max(width / factor, 1);
    let sh = max(height / factor, 1);
    let u = (f32(x - left) + 0.5) * f32(sw) / f32(width) - 0.5;
    let v = (f32(y - top) + 0.5) * f32(sh) / f32(height) - 0.5;
    let i0 = i32(floor(u));
    let j0 = i32(floor(v));
    let tx = u - f32(i0);
    let ty = v - f32(j0);
    let top = mix(small_texel(i0, j0, sw, sh), small_texel(i0 + 1, j0, sw, sh), tx);
    let bottom = mix(small_texel(i0, j0 + 1, sw, sh), small_texel(i0 + 1, j0 + 1, sw, sh), tx);
    return mix(top, bottom, ty);
}

fn coverage(px: vec2<f32>) -> f32 {
    var best = 0.0;
    for (var i = 0u; i < p.dab_count; i++) {
        let d = dabs[i];
        let r = max(d.z, 0.5);
        let t = distance(px, d.xy) / r;
        if (t >= 1.0) { continue; }
        let h = clamp(d.w, 0.0, 1.0);
        var c = 1.0;
        if (t > h) { c = 1.0 - (t - h) / max(1.0 - h, 1e-3); }
        best = max(best, c);
    }
    return best;
}

fn straight(c: vec4<f32>) -> vec3<f32> {
    if (c.a <= 0.0) { return vec3<f32>(0.0); }
    return c.rgb / c.a;
}

@compute @workgroup_size(8, 8)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    if (i32(gid.x) >= p.rw || i32(gid.y) >= p.rh) { return; }
    let x = p.rx + i32(gid.x);
    let y = p.ry + i32(gid.y);
    if (x < 0 || y < 0 || x >= p.width || y >= p.height) { return; }
    let cov = coverage(vec2<f32>(f32(x) + 0.5, f32(y) + 0.5));
    if (cov <= 0.0) { return; }
    let idx = y * p.width + x;
    let dst = unpack4x8unorm(shadow_px[idx]);
    var out: vec4<f32>;
    if (p.kind == 0u) {
        let b = blurred(x, y, p.factor) * cov;
        out = b + dst * (1.0 - b.a);
    } else if (p.kind == 1u) {
        let k = p.amount * cov;
        let rgb = straight(dst) * 255.0;
        let q = straight(blurred(x, y, 2)) * 255.0;
        let sharp = clamp(round(rgb + k * (rgb - q)), vec3<f32>(0.0), vec3<f32>(255.0)) / 255.0;
        out = vec4<f32>(sharp * dst.a, dst.a);
    } else {
        var sx = i32(floor(f32(x) + p.offset_x + 0.5));
        var sy = i32(floor(f32(y) + p.offset_y + 0.5));
        var src = vec4<f32>(0.0);
        if (p.wrap != 0u) {
            sx = wrap_coord(sx, p.frame_x, p.frame_w);
            sy = wrap_coord(sy, p.frame_y, p.frame_h);
            src = unpack4x8unorm(shadow_px[sy * p.width + sx]);
        } else if (sx >= 0 && sy >= 0 && sx < p.width && sy < p.height) {
            src = unpack4x8unorm(shadow_px[sy * p.width + sx]);
        }
        let s = src * cov;
        out = s + dst * (1.0 - s.a);
    }
    layer_px[idx] = pack4x8unorm(out);
}
