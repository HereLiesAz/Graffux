// Multipass draft of stamp.wgsl (round tips): the same dab, the same coverage function and substrate
// deposition, rendered at 1/scale of the layer resolution into the draft buffer, over transparent.
// Nothing here touches the layer; the final pass (stamp.wgsl itself) does, later.
//
// Each draft texel stores the stroke's premultiplied contribution (packed RGBA8) and a "feather key":
// how far out through the feather zone the winning coverage sits (0 = core, 1 = outer edge), from
// the real falloff (see draft.rs). The display reveals the draft only up to a key that grows from
// the trimmed edge (f, default 0.4) to the full feather as the final result approaches.

struct Dab {
    geometry: vec4<f32>, // x, y, radius, alpha
    paint0: vec4<f32>,   // angleDeg, colorR, colorG, colorB
    paint1: vec4<f32>,   // colorA, flow, resolved (always 1 here), hardness
    material: vec4<f32>, // contactDepth, reservoirLoad, depositionRate, substrateResponse
};

struct Params {
    dab_count: u32,
    origin_x: i32,       // draft texels
    origin_y: i32,
    build_up: f32,
    scale: f32,          // layer pixels per draft texel
    has_substrate: f32,
    has_paint_height: f32,
    substrate_base_height: f32,
    substrate_height_scale: f32,
    substrate_texture_scale: f32,
    substrate_offset_x: f32,
    substrate_offset_y: f32,
    draft_width: i32,
    draft_height: i32,
    _pad0: u32,
    _pad1: u32,
};

@group(0) @binding(0) var<storage, read> dabs: array<Dab>;
@group(0) @binding(1) var<storage, read_write> draft_px: array<vec2<u32>>;
@group(0) @binding(2) var substrate_tex: texture_2d<f32>;
@group(0) @binding(3) var paint_height_tex: texture_2d<f32>;
@group(0) @binding(4) var<uniform> pc: Params;

fn stamp_coverage(dist_from_center: f32, radius: f32, hardness: f32) -> f32 {
    if (radius <= 0.0) { return 0.0; }
    let t = dist_from_center / radius;
    if (t <= hardness) { return 1.0; }
    if (t >= 1.0) { return 0.0; }
    if (hardness >= 0.999) { return 1.0 - (t - hardness) / 0.001; }
    return 1.0 - (t - hardness) / (1.0 - hardness);
}

// 0 inside the core, 1 at the outer edge (draft.rs FeatherEdge::position).
fn feather_key(t: f32, hardness: f32) -> f32 {
    let h = clamp(hardness, 0.0, 1.0);
    let outer = select(1.0, min(h + 0.001, 1.0), h >= 0.999);
    if (t <= h) { return 0.0; }
    if (outer <= h) { return 1.0; }
    return clamp((t - h) / (outer - h), 0.0, 1.0);
}

// draft.rs trim_weight: 0 for soft tips (full falloff shown), 1 for hard ones.
fn trim_weight(hardness: f32) -> f32 {
    return smoothstep(0.5, 0.9, clamp(hardness, 0.0, 1.0));
}

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
    let texel = vec2<i32>(gid.xy) + vec2<i32>(pc.origin_x, pc.origin_y);
    if (texel.x < 0 || texel.y < 0 || texel.x >= pc.draft_width || texel.y >= pc.draft_height) { return; }
    let idx = texel.y * pc.draft_width + texel.x;
    let stored = draft_px[idx];
    var dst = unpack4x8unorm(stored.x);
    var key = select(1.0, bitcast<f32>(stored.y), dst.a > 0.0);
    // The texel's center in layer pixels.
    let p = (vec2<f32>(texel) + vec2<f32>(0.5)) * pc.scale;
    let sequential = pc.build_up > 0.5;

    for (var i = 0u; i < pc.dab_count; i++) {
        let d = dabs[i];
        let center = d.geometry.xy;
        let radius = max(d.geometry.z, 0.5);
        if (abs(p.x - center.x) > radius || abs(p.y - center.y) > radius) { continue; }
        let hardness = d.paint1.w;
        let dist = distance(p, center);
        var coverage = stamp_coverage(dist, radius, hardness);
        if (coverage <= 0.0) { continue; }
        coverage *= substrate_deposition(d, p);
        if (coverage <= 0.0) { continue; }
        let src_a = d.paint1.x * d.geometry.w * max(d.paint1.y, 0.0) * coverage;
        if (src_a <= 0.0) { continue; }
        // The trim only ever hides the thin, blurry rim of a hard tip. A soft tip's feather IS the
        // stamp: trimming it turns the draft into a flat, hard-edged disc (the stroke-start "solid
        // circle"), so the key fades to 0 (never trimmed) as hardness drops (draft.rs trim_weight).
        let k = feather_key(dist / radius, hardness) * trim_weight(hardness);
        if (sequential) {
            dst = vec4<f32>(d.paint0.yzw * src_a + dst.rgb * (1.0 - src_a), src_a + dst.a * (1.0 - src_a));
            // Build-up: never hide more than one dab's worth of feather. Where repeated dabs (a held
            // pointer) have piled alpha up past what the key promises, reveal it (draft.rs).
            key = min(min(key, k), 1.0 - dst.a);
        } else if (src_a > dst.a) {
            // Strongest dab wins, as in the final pass (max-combine / strokeMax).
            dst = vec4<f32>(d.paint0.yzw * src_a, src_a);
            key = k;
        }
    }
    draft_px[idx] = vec2<u32>(pack4x8unorm(dst), bitcast<u32>(key));
}
