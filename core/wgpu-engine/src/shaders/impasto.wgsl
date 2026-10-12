// Impasto v1 on the GPU: the CPU reference is ImpastoEngine.deposit (height) and
// ImpastoRegionShader.shade (relief), and both entry points follow them step for step.
//
// deposit: one invocation per pixel of the dabs' footprint walks the dabs in order, so the
// order-dependent build-up composes exactly as the CPU loop does. shade: reads the finished height
// and rewrites the layer pixel in place with the relief multiplier applied to its straight RGB.

struct HeightDab {
    x: f32,
    y: f32,
    radius: f32,
    // alpha.clamp(0, 1) * flowMultiplier.max(0): the CPU increment's per-dab factor.
    strength: f32,
};

struct Params {
    dab_count: u32,
    hardness: f32,
    thickness: f32,
    origin_x: i32,
    origin_y: i32,
    region_w: i32,
    region_h: i32,
    width: i32,
    height: i32,
    light_x: f32,
    light_y: f32,
    light_z: f32,
    strength: f32,
    _pad0: f32,
    _pad1: f32,
    _pad2: f32,
};

@group(0) @binding(0) var<storage, read> dabs: array<HeightDab>;
@group(0) @binding(1) var<storage, read_write> heights: array<f32>;
@group(0) @binding(2) var<storage, read_write> layer_px: array<u32>;
@group(0) @binding(3) var<uniform> p: Params;

// BrushStamps.stampCoverage.
fn coverage(r_norm: f32, hardness: f32) -> f32 {
    if (r_norm <= 0.0) { return 1.0; }
    if (r_norm >= 1.0) { return 0.0; }
    let h = clamp(hardness, 0.0, 1.0);
    if (r_norm <= h) { return 1.0; }
    let denom = 1.0 - h;
    if (denom <= 1e-4) { return 1.0; }
    return clamp((1.0 - r_norm) / denom, 0.0, 1.0);
}

fn pixel(gid: vec3<u32>) -> vec2<i32> {
    if (i32(gid.x) >= p.region_w || i32(gid.y) >= p.region_h) { return vec2<i32>(-1, -1); }
    let x = p.origin_x + i32(gid.x);
    let y = p.origin_y + i32(gid.y);
    if (x < 0 || y < 0 || x >= p.width || y >= p.height) { return vec2<i32>(-1, -1); }
    return vec2<i32>(x, y);
}

@compute @workgroup_size(8, 8)
fn deposit(@builtin(global_invocation_id) gid: vec3<u32>) {
    let xy = pixel(gid);
    if (xy.x < 0) { return; }
    let idx = u32(xy.y * p.width + xy.x);
    let fx = f32(xy.x);
    let fy = f32(xy.y);
    var h = heights[idx];
    for (var i = 0u; i < p.dab_count; i = i + 1u) {
        let d = dabs[i];
        if (d.radius <= 0.0) { continue; }
        // The CPU's per-dab pixel box, floor(c - r)..ceil(c + r) inclusive.
        if (fx < floor(d.x - d.radius) || fx > ceil(d.x + d.radius) ||
            fy < floor(d.y - d.radius) || fy > ceil(d.y + d.radius)) { continue; }
        let dx = fx + 0.5 - d.x;
        let dy = fy + 0.5 - d.y;
        let r_norm = clamp(sqrt(dx * dx + dy * dy) / d.radius, 0.0, 1.0);
        let c = coverage(r_norm, p.hardness);
        if (c <= 0.0) { continue; }
        // BrushStamps.buildUp.
        let cur = clamp(h, 0.0, 1.0);
        let inc = clamp(p.thickness * c * d.strength, 0.0, 1.0);
        h = clamp(cur + inc * (1.0 - cur), 0.0, 1.0);
    }
    heights[idx] = h;
}

fn height_at(x: i32, y: i32) -> f32 {
    let cx = clamp(x, 0, p.width - 1);
    let cy = clamp(y, 0, p.height - 1);
    return heights[u32(cy * p.width + cx)];
}

@compute @workgroup_size(8, 8)
fn shade(@builtin(global_invocation_id) gid: vec3<u32>) {
    let xy = pixel(gid);
    if (xy.x < 0) { return; }
    let x = xy.x;
    let y = xy.y;
    let dhdx = (height_at(x + 1, y) - height_at(x - 1, y)) / 2.0;
    let dhdy = (height_at(x, y + 1) - height_at(x, y - 1)) / 2.0;
    if (dhdx == 0.0 && dhdy == 0.0) { return; }
    let nx = -dhdx;
    let ny = -dhdy;
    let inv_len = 1.0 / sqrt(nx * nx + ny * ny + 1.0);
    let diffuse = nx * inv_len * p.light_x + ny * inv_len * p.light_y + inv_len * p.light_z;
    let m = clamp(1.0 + p.strength * (diffuse - p.light_z), 0.0, 3.0);
    if (m == 1.0) { return; }
    let idx = u32(y * p.width + x);
    // The layer is premultiplied; the CPU scales straight RGB (truncating, as scaleRgb does).
    let c = unpack4x8unorm(layer_px[idx]) * 255.0;
    let a = round(c.a);
    if (a <= 0.0) { return; }
    let straight = min(round(c.rgb * 255.0 / a), vec3<f32>(255.0));
    let scaled = clamp(floor(straight * m), vec3<f32>(0.0), vec3<f32>(255.0));
    let pre = round(scaled * a / 255.0);
    layer_px[idx] = pack4x8unorm(vec4<f32>(pre / 255.0, a / 255.0));
}
