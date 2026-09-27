// WGSL port of core/nativebridge/src/main/cpp/shaders/color_smudge.comp -- identical math, one
// pipeline for every ordered phase. The C++ engines submit phases sequentially with a
// compute->compute barrier between them; here each phase is its own dispatch inside one compute
// pass, and wgpu inserts the storage barrier between dispatches automatically. Per-dispatch
// parameters live in one uniform buffer addressed with a dynamic offset (push constants in Vulkan,
// a UBO rewritten per dispatch in GLES). Layer = packed-RGBA8 storage buffer, as in stamp.wgsl.

struct SmudgeParams {
    phase: i32,          // 0 seed, 1 Smear, 2 Dulling sample, 3 Dulling deposit, 4..7 reservoir stages
    center_x: i32,
    center_y: i32,
    radius: i32,
    sample_radius: i32,
    smear_alpha: i32,
    origin_x: i32,
    origin_y: i32,
    soft: f32,
    smudge_rate: f32,
    color_rate: f32,
    opacity: f32,
    paint_r: f32,
    paint_g: f32,
    paint_b: f32,
    paint_a: f32,
    dilution: f32,
    // Packed feature flags: +1 = Sample Merged, +2 = pigment RYB mixing.
    has_sample_merged: f32,
    reservoir_enabled: f32,
    base_color_rate: f32,
    charge_decay_rate: f32,
    pickup_rate: f32,
    color_rate_multiplier: f32,
    distance_delta_px: f32,
    layer_width: i32,
    layer_height: i32,
    _pad0: u32,
    _pad1: u32,
};

@group(0) @binding(0) var<storage, read_write> layer_px: array<u32>;
@group(0) @binding(1) var<storage, read_write> carrier: array<vec4<f32>>;
@group(0) @binding(2) var sample_source_tex: texture_2d<f32>;
@group(0) @binding(3) var<uniform> pc: SmudgeParams;

fn layer_load(p: vec2<i32>) -> vec4<f32> {
    return unpack4x8unorm(layer_px[p.y * pc.layer_width + p.x]);
}

fn layer_store(p: vec2<i32>, c: vec4<f32>) {
    layer_px[p.y * pc.layer_width + p.x] = pack4x8unorm(c);
}

fn feature_flags() -> i32 { return i32(pc.has_sample_merged + 0.5); }
fn sample_merged_enabled() -> bool { return (feature_flags() & 1) != 0; }
fn pigment_mixing_enabled() -> bool { return (feature_flags() & 2) != 0; }
fn reservoir_enabled() -> bool { return pc.reservoir_enabled > 0.5; }

fn reservoir_base() -> i32 {
    let diameter = pc.radius * 2 + 1;
    return diameter * diameter;
}
fn reservoir_color_index() -> i32 { return reservoir_base(); }
fn reservoir_meta_index() -> i32 { return reservoir_base() + 1; }
fn reservoir_sample_index() -> i32 { return reservoir_base() + 2; }

fn brush_mask(dx: i32, dy: i32) -> f32 {
    let r = f32(max(pc.radius, 1));
    let t = length(vec2<f32>(f32(dx), f32(dy))) / r;
    if (t >= 1.0) { return 0.0; }
    if (t <= 1.0 - pc.soft) { return 1.0; }
    let u = (1.0 - t) / pc.soft;
    return u * u * (3.0 - 2.0 * u);
}

fn rounded8(v: vec4<f32>) -> vec4<f32> {
    return round(clamp(v, vec4<f32>(0.0), vec4<f32>(1.0)) * 255.0) / 255.0;
}

fn rounded_material8(v: vec4<f32>) -> vec4<f32> {
    return floor(clamp(v, vec4<f32>(0.0), vec4<f32>(1.0)) * 255.0 + 0.5) / 255.0;
}

fn lerp_argb(a: vec4<f32>, b: vec4<f32>, t: f32, include_alpha: bool) -> vec4<f32> {
    let f = clamp(t, 0.0, 1.0);
    var out_color = mix(a, b, f);
    if (!include_alpha) { out_color.a = a.a; }
    return rounded8(out_color);
}

fn rgb_to_ryb(rgb: vec3<f32>) -> vec3<f32> {
    var r = clamp(rgb.r, 0.0, 1.0);
    var g = clamp(rgb.g, 0.0, 1.0);
    var b = clamp(rgb.b, 0.0, 1.0);
    let white = min(r, min(g, b));
    r -= white;
    g -= white;
    b -= white;

    let max_green = max(r, max(g, b));
    var yellow = min(r, g);
    r -= yellow;
    g -= yellow;

    if (b > 0.0 && g > 0.0) {
        b *= 0.5;
        g *= 0.5;
    }

    yellow += g;
    b += g;
    let max_yellow = max(r, max(yellow, b));
    if (max_yellow > 0.0) {
        let normal = max_green / max_yellow;
        r *= normal;
        yellow *= normal;
        b *= normal;
    }

    return vec3<f32>(r + white, yellow + white, b + white);
}

fn ryb_to_rgb(ryb: vec3<f32>) -> vec3<f32> {
    var r = clamp(ryb.r, 0.0, 1.0);
    var y = clamp(ryb.g, 0.0, 1.0);
    var b = clamp(ryb.b, 0.0, 1.0);
    let white = min(r, min(y, b));
    r -= white;
    y -= white;
    b -= white;

    let max_yellow = max(r, max(y, b));
    var green = min(y, b);
    y -= green;
    b -= green;

    if (b > 0.0 && green > 0.0) {
        b *= 2.0;
        green *= 2.0;
    }

    r += y;
    green += y;
    let max_green = max(r, max(green, b));
    if (max_green > 0.0) {
        let normal = max_yellow / max_green;
        r *= normal;
        green *= normal;
        b *= normal;
    }

    return vec3<f32>(r + white, green + white, b + white);
}

fn pigment_continuous(a: vec4<f32>, b: vec4<f32>, t: f32, include_alpha: bool) -> vec4<f32> {
    let f = clamp(t, 0.0, 1.0);
    let ac = clamp(a, vec4<f32>(0.0), vec4<f32>(1.0));
    var bc = clamp(b, vec4<f32>(0.0), vec4<f32>(1.0));
    if (f <= 0.0) { return ac; }
    if (f >= 1.0) {
        if (!include_alpha) { bc.a = ac.a; }
        return bc;
    }
    let rgb = ryb_to_rgb(mix(rgb_to_ryb(ac.rgb), rgb_to_ryb(bc.rgb), f));
    let alpha = select(ac.a, mix(ac.a, bc.a, f), include_alpha);
    return clamp(vec4<f32>(rgb, alpha), vec4<f32>(0.0), vec4<f32>(1.0));
}

fn pigment_argb(a: vec4<f32>, b: vec4<f32>, t: f32, include_alpha: bool) -> vec4<f32> {
    return rounded_material8(pigment_continuous(a, b, t, include_alpha));
}

fn material_mix_argb(a: vec4<f32>, b: vec4<f32>, t: f32, include_alpha: bool) -> vec4<f32> {
    if (pigment_mixing_enabled()) { return pigment_argb(a, b, t, include_alpha); }
    return lerp_argb(a, b, t, include_alpha);
}

fn material_mix_continuous(a: vec4<f32>, b: vec4<f32>, t: f32, include_alpha: bool) -> vec4<f32> {
    let f = clamp(t, 0.0, 1.0);
    let ac = clamp(a, vec4<f32>(0.0), vec4<f32>(1.0));
    let bc = clamp(b, vec4<f32>(0.0), vec4<f32>(1.0));
    if (pigment_mixing_enabled()) { return pigment_continuous(ac, bc, f, include_alpha); }
    var out_color = mix(ac, bc, f);
    if (!include_alpha) { out_color.a = ac.a; }
    return clamp(out_color, vec4<f32>(0.0), vec4<f32>(1.0));
}

fn effective_color_rate() -> f32 {
    if (!reservoir_enabled()) { return clamp(pc.color_rate, 0.0, 1.0); }
    let load = clamp(carrier[reservoir_meta_index()].x, 0.0, 1.0);
    let charge = clamp(pc.base_color_rate * load, 0.0, 1.0);
    return clamp(charge * max(pc.color_rate_multiplier, 0.0), 0.0, 1.0);
}

fn diluted_pigment(under: vec4<f32>) -> vec4<f32> {
    var paint = vec4<f32>(pc.paint_r, pc.paint_g, pc.paint_b, pc.paint_a);
    if (reservoir_enabled()) { paint = rounded_material8(carrier[reservoir_color_index()]); }
    return material_mix_argb(under, paint, 1.0 - pc.dilution, true);
}

fn in_layer(p: vec2<i32>) -> bool {
    return p.x >= 0 && p.y >= 0 && p.x < pc.layer_width && p.y < pc.layer_height;
}

fn source_texel(p: vec2<i32>) -> vec4<f32> {
    if (sample_merged_enabled()) {
        return textureLoad(sample_source_tex, p, 0);
    }
    let premultiplied = layer_load(p);
    if (premultiplied.a <= 0.0) { return vec4<f32>(0.0); }
    return vec4<f32>(clamp(premultiplied.rgb / premultiplied.a, vec3<f32>(0.0), vec3<f32>(1.0)), premultiplied.a);
}

fn weighted_source_average(sample_radius: i32) -> vec4<f32> {
    let sr = max(sample_radius, 1);
    var sum_a = 0.0;
    var sum_rgb = vec3<f32>(0.0);
    var sum_w = 0.0;
    var sum_alpha_weight = 0.0;
    for (var dy = -sr; dy <= sr; dy++) {
        for (var dx = -sr; dx <= sr; dx++) {
            let distance_px = length(vec2<f32>(f32(dx), f32(dy)));
            if (distance_px > f32(sr)) { continue; }
            let p = vec2<i32>(pc.center_x + dx, pc.center_y + dy);
            if (!in_layer(p)) { continue; }
            let weight = clamp(1.0 - distance_px / f32(sr), 0.0, 1.0);
            if (weight <= 0.0) { continue; }
            let texel = source_texel(p);
            let alpha_weight = weight * texel.a;
            sum_a += texel.a * weight;
            sum_rgb += texel.rgb * alpha_weight;
            sum_w += weight;
            sum_alpha_weight += alpha_weight;
        }
    }
    let center = vec2<i32>(pc.center_x, pc.center_y);
    var center_texel = vec4<f32>(0.0);
    if (in_layer(center)) { center_texel = source_texel(center); }
    if (sum_w <= 0.0) { return rounded_material8(center_texel); }
    let alpha = sum_a / sum_w;
    if (sum_alpha_weight <= 0.0) {
        return rounded_material8(vec4<f32>(center_texel.rgb, alpha));
    }
    return rounded_material8(vec4<f32>(sum_rgb / sum_alpha_weight, alpha));
}

fn seed_carrier(gid: vec2<i32>) {
    let diameter = pc.radius * 2 + 1;
    if (gid.x >= diameter || gid.y >= diameter) { return; }
    let dx = gid.x - pc.radius;
    let dy = gid.y - pc.radius;
    let src = clamp(vec2<i32>(pc.center_x + dx, pc.center_y + dy), vec2<i32>(0),
                    vec2<i32>(pc.layer_width, pc.layer_height) - vec2<i32>(1));
    carrier[gid.y * diameter + gid.x] = layer_load(src);
}

fn reservoir_init(gid: vec2<i32>) {
    if (gid.x != 0 || gid.y != 0) { return; }
    carrier[reservoir_color_index()] =
        clamp(vec4<f32>(pc.paint_r, pc.paint_g, pc.paint_b, pc.paint_a), vec4<f32>(0.0), vec4<f32>(1.0));
    carrier[reservoir_meta_index()] = vec4<f32>(1.0, clamp(pc.dilution, 0.0, 1.0), 0.0, 0.0);
    carrier[reservoir_sample_index()] = vec4<f32>(0.0);
}

fn reservoir_decay(gid: vec2<i32>) {
    if (gid.x != 0 || gid.y != 0) { return; }
    var meta_v = carrier[reservoir_meta_index()];
    let rate = max(pc.charge_decay_rate, 0.0);
    let distance_px = max(pc.distance_delta_px, 0.0);
    if (rate > 0.0 && distance_px > 0.0 && meta_v.x > 0.0) {
        meta_v.x = clamp(meta_v.x * exp(-rate * distance_px), 0.0, 1.0);
    }
    carrier[reservoir_meta_index()] = meta_v;
}

fn reservoir_sample_smear(gid: vec2<i32>) {
    if (gid.x != 0 || gid.y != 0) { return; }
    carrier[reservoir_sample_index()] = weighted_source_average(pc.radius);
}

fn reservoir_pickup(gid: vec2<i32>) {
    if (gid.x != 0 || gid.y != 0) { return; }
    var meta_v = carrier[reservoir_meta_index()];
    let load = clamp(meta_v.x, 0.0, 1.0);
    let capacity = clamp(1.0 - load, 0.0, 1.0);
    if (capacity <= 0.0 || pc.pickup_rate <= 0.0) { return; }

    let sampled = rounded_material8(carrier[reservoir_sample_index()]);
    let material_presence = clamp(sampled.a, 0.0, 1.0);
    if (material_presence <= 0.0) { return; }

    let pickup = min(capacity, capacity * clamp(pc.pickup_rate, 0.0, 1.0) * material_presence);
    let final_load = clamp(load + pickup, 0.0, 1.0);
    if (pickup > 0.0 && final_load > 0.0) {
        let pickup_ratio = clamp(pickup / final_load, 0.0, 1.0);
        carrier[reservoir_color_index()] = material_mix_continuous(
            carrier[reservoir_color_index()], sampled, pickup_ratio, true
        );
        meta_v.x = final_load;
        carrier[reservoir_meta_index()] = meta_v;
    }
}

fn smear_dab(gid: vec2<i32>) {
    let diameter = pc.radius * 2 + 1;
    if (gid.x >= diameter || gid.y >= diameter) { return; }
    let dx = gid.x - pc.radius;
    let dy = gid.y - pc.radius;
    let mask = brush_mask(dx, dy);
    if (mask <= 0.0) { return; }
    let dst_pos = vec2<i32>(pc.center_x + dx, pc.center_y + dy);
    if (!in_layer(dst_pos)) { return; }

    let k = gid.y * diameter + gid.x;
    let under = layer_load(dst_pos);
    var picked_up = under;
    if (sample_merged_enabled()) { picked_up = textureLoad(sample_source_tex, dst_pos, 0); }
    let carried = material_mix_argb(picked_up, carrier[k], pc.smudge_rate, pc.smear_alpha != 0);
    carrier[k] = carried;
    var out_color = material_mix_argb(under, carried, mask * pc.opacity, pc.smear_alpha != 0);
    let color_rate = effective_color_rate();
    if (color_rate > 0.0) {
        let pigment = diluted_pigment(picked_up);
        out_color = material_mix_argb(out_color, pigment, mask * pc.opacity * color_rate, true);
    }
    layer_store(dst_pos, out_color);
}

fn dull_sample(gid: vec2<i32>) {
    if (gid.x != 0 || gid.y != 0) { return; }
    let sampled = weighted_source_average(pc.sample_radius);
    carrier[0] = sampled;
    if (reservoir_enabled()) { carrier[reservoir_sample_index()] = sampled; }
}

fn dull_deposit(gid: vec2<i32>) {
    let diameter = pc.radius * 2 + 1;
    if (gid.x >= diameter || gid.y >= diameter) { return; }
    let dx = gid.x - pc.radius;
    let dy = gid.y - pc.radius;
    let mask = brush_mask(dx, dy);
    if (mask <= 0.0) { return; }
    let dst_pos = vec2<i32>(pc.center_x + dx, pc.center_y + dy);
    if (!in_layer(dst_pos)) { return; }

    let under = layer_load(dst_pos);
    var out_color = material_mix_argb(
        under, carrier[0], mask * pc.opacity * pc.smudge_rate, pc.smear_alpha != 0
    );
    let color_rate = effective_color_rate();
    if (color_rate > 0.0) {
        let pigment = diluted_pigment(carrier[0]);
        out_color = material_mix_argb(out_color, pigment, mask * pc.opacity * color_rate, true);
    }
    layer_store(dst_pos, out_color);
}

@compute @workgroup_size(8, 8)
fn main(@builtin(global_invocation_id) gid3: vec3<u32>) {
    let gid = vec2<i32>(gid3.xy);
    switch pc.phase {
        case 0: { seed_carrier(gid); }
        case 1: { smear_dab(gid); }
        case 2: { dull_sample(gid); }
        case 3: { dull_deposit(gid); }
        case 4: { reservoir_init(gid); }
        case 5: { reservoir_decay(gid); }
        case 6: { reservoir_pickup(gid); }
        case 7: { reservoir_sample_smear(gid); }
        default: {}
    }
}
