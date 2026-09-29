// Multipass draft of stamp_masked.wgsl: the same shaped tip, orientation, grain, dual-brush
// secondary tip and substrate, rendered at 1/scale of the layer resolution into the draft buffer,
// over transparent, sequential SRC_OVER like the final. The tip mask is sampled from the mip level
// matching the draft's texel footprint (a lower-detail version of the same mask). The feather key is
// 1 - mask coverage: the mask carries the tip's falloff, so the display's trim at key f is the mask
// threshold 1 - f (draft.rs).

struct Dab {
    geometry: vec4<f32>, // x, y, radius, alpha
    paint0: vec4<f32>,   // angleDeg, colorR, colorG, colorB
    paint1: vec4<f32>,   // colorA, flow, resolved (always 1 here), tipRatio
    material: vec4<f32>, // contactDepth, reservoirLoad, depositionRate, substrateResponse
};

struct SecondaryDab {
    geometry: vec4<f32>, // x, y, radius, tipRatio
    paint: vec4<f32>,    // alpha, angleDeg, flowMultiplier, keepInside
};

struct Params {
    dab_count: u32,
    origin_x: i32,
    origin_y: i32,
    scale: f32,
    grain_canvas_locked: f32,
    grain_scale: f32,
    grain_phase_x: f32,
    grain_phase_y: f32,
    has_secondary: f32,
    has_substrate: f32,
    has_paint_height: f32,
    substrate_base_height: f32,
    substrate_height_scale: f32,
    substrate_texture_scale: f32,
    substrate_offset_x: f32,
    substrate_offset_y: f32,
    draft_width: i32,
    draft_height: i32,
    mask_width: f32,
    mask_levels: f32,
};

@group(0) @binding(0) var<storage, read> dabs: array<Dab>;
@group(0) @binding(1) var<storage, read_write> draft_px: array<vec2<u32>>;
@group(0) @binding(2) var tip_mask: texture_2d<f32>;
@group(0) @binding(3) var grain_tex: texture_2d<f32>;
@group(0) @binding(4) var secondary_mask: texture_2d<f32>;
@group(0) @binding(5) var<storage, read> secondary_dabs: array<SecondaryDab>;
@group(0) @binding(6) var substrate_tex: texture_2d<f32>;
@group(0) @binding(7) var paint_height_tex: texture_2d<f32>;
@group(0) @binding(8) var<uniform> pc: Params;
@group(0) @binding(9) var linear_mip_clamp: sampler;
@group(0) @binding(10) var nearest_repeat: sampler;
@group(0) @binding(11) var linear_clamp: sampler;

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
    let p = (vec2<f32>(texel) + vec2<f32>(0.5)) * pc.scale;

    for (var i = 0u; i < pc.dab_count; i++) {
        let d = dabs[i];
        let center = d.geometry.xy;
        let radius = max(d.geometry.z, 0.5);
        let tip_ratio = clamp(d.paint1.w, 0.05, 1.0);
        let half_w = radius;
        let half_h = radius * tip_ratio;

        let local = p - center;
        let angle_rad = radians(-d.paint0.x);
        let ca = cos(angle_rad);
        let sa = sin(angle_rad);
        let rotated = vec2<f32>(local.x * ca - local.y * sa, local.x * sa + local.y * ca);
        if (abs(rotated.x) > half_w || abs(rotated.y) > half_h) { continue; }

        let uv = vec2<f32>(rotated.x / (2.0 * half_w) + 0.5, rotated.y / (2.0 * half_h) + 0.5);
        // Mask texels per draft texel, along the tip's long axis.
        // Never coarser than 3 levels below the 1x1 top of the chain (8x8 for a square mask; this
        // line is the only source of that bound): a 1x1 or 2x2 level averages the falloff to one
        // constant and the dab rectangle then clips it to a solid shape.
        let lod_max = max(pc.mask_levels - 4.0, 0.0);
        let lod = clamp(log2(max(pc.mask_width * pc.scale / (2.0 * half_w), 1.0)), 0.0, lod_max);
        let mask_value = textureSampleLevel(tip_mask, linear_mip_clamp, uv, lod).r;
        var coverage = mask_value;
        if (coverage <= 0.0) { continue; }

        let grain_size = vec2<f32>(textureDimensions(grain_tex, 0));
        let grain_source = select(local, p, pc.grain_canvas_locked > 0.5);
        let grain_scale_safe = max(pc.grain_scale, 0.05);
        let grain_uv = vec2<f32>(
            (grain_source.x / grain_scale_safe + pc.grain_phase_x) / grain_size.x,
            (grain_source.y / grain_scale_safe + pc.grain_phase_y) / grain_size.y
        );
        coverage *= textureSampleLevel(grain_tex, nearest_repeat, grain_uv, 0.0).r;
        if (coverage <= 0.0) { continue; }

        if (pc.has_secondary > 0.5) {
            let sd = secondary_dabs[i];
            let s_center = sd.geometry.xy;
            let s_radius = max(sd.geometry.z, 0.5);
            let s_tip_ratio = clamp(sd.geometry.w, 0.05, 1.0);
            let s_alpha = sd.paint.x;
            let s_angle_rad = radians(-sd.paint.y);
            let s_flow = max(sd.paint.z, 0.0);
            let s_keep_inside = sd.paint.w;
            let s_local = p - s_center;
            let sca = cos(s_angle_rad);
            let ssa = sin(s_angle_rad);
            let s_rotated = vec2<f32>(s_local.x * sca - s_local.y * ssa, s_local.x * ssa + s_local.y * sca);
            let s_half_w = s_radius;
            let s_half_h = s_radius * s_tip_ratio;
            var secondary_coverage = 0.0;
            if (abs(s_rotated.x) <= s_half_w && abs(s_rotated.y) <= s_half_h) {
                let s_uv = vec2<f32>(s_rotated.x / (2.0 * s_half_w) + 0.5, s_rotated.y / (2.0 * s_half_h) + 0.5);
                secondary_coverage = textureSampleLevel(secondary_mask, linear_clamp, s_uv, 0.0).r;
            }
            let secondary_factor = clamp(secondary_coverage * s_alpha * s_flow, 0.0, 1.0);
            coverage *= select(1.0 - secondary_factor, secondary_factor, s_keep_inside > 0.5);
            if (coverage <= 0.0) { continue; }
        }

        coverage *= substrate_deposition(d, p);
        if (coverage <= 0.0) { continue; }

        let src_a = d.paint1.x * d.geometry.w * max(d.paint1.y, 0.0) * coverage;
        if (src_a <= 0.0) { continue; }
        dst = vec4<f32>(d.paint0.yzw * src_a + dst.rgb * (1.0 - src_a), src_a + dst.a * (1.0 - src_a));
        // A mask's falloff is the stamp's own shape and softness; trimming it at 1 - f made soft
        // masked tips draft as flat discs. The draft shows the whole (lower-mip) mask: key 0.
        key = 0.0;
    }
    draft_px[idx] = vec2<u32>(pack4x8unorm(dst), bitcast<u32>(key));
}
