// Impasto v2 (the wet material simulation) on the GPU. Each entry point follows its CPU reference
// in core/engine (Kotlin, f32) step for step:
//
//   transfer         ImpastoEngine.transferMaterialStroke   (height deposit + reservoir pickup)
//   deposit_wetness  ImpastoEngine.depositWetnessStroke
//   level_phase      ImpastoEngine.levelWetHeight           (one edge phase)
//   pigment_phase    WetMaterialTransport.advanceArgb       (one edge phase)
//   wet_delta/apply  PersistentWetnessField.advance          (Jacobi flux, then drying)
//   shade_material   ImpastoRegionShader.shadeMaterial
//
// The two in-place solvers (levelling, pigment transport) visit their edges in four phases --
// horizontal edges from even x, from odd x, vertical from even y, from odd y -- exactly as the CPU
// references do. Edges within a phase share no pixel, so one invocation per edge gives the CPU's
// result regardless of scheduling.
//
// Raw pigment is straight (unpremultiplied) ARGB words, Android's Bitmap.getPixels layout:
// A<<24 | R<<16 | G<<8 | B. The engine layer is premultiplied RGBA8 (R in the low byte).
//
// Every entry point binds at most four storage buffers (the GLES compute minimum). The per-tile
// state shares one control buffer `ctl`: active flags [0, tiles), next-step active flags
// [tiles, 2*tiles), the reservoir load the last transfer ended on at 2*tiles (f32 bits), then one
// flag word per dab from `flags_off` = 2*tiles + 1 (bit 0 = the dab changed height,
// bit 1 = it added wetness). Height and wetness share `fields`: height [0, n), wetness [n, 2n).

struct Params {
    width: i32,
    height: i32,
    tile: i32,
    cols: i32,
    rx: i32,
    ry: i32,
    rw: i32,
    rh: i32,
    phase: i32,
    dab_count: u32,
    use_cov: u32,
    use_wet: u32,
    tiles: u32,
    flags_off: u32,
    n: u32,
    mode: u32,
    f0: f32,
    f1: f32,
    f2: f32,
    f3: f32,
    f4: f32,
    f5: f32,
    f6: f32,
    f7: f32,
};

// One dab of the stroke: centre, radius, alpha, flow multiplier, contact depth (raw values; the
// shaders clamp them as the CPU does).
struct MaterialDab {
    x: f32,
    y: f32,
    radius: f32,
    alpha: f32,
    flow: f32,
    contact: f32,
    _p0: f32,
    _p1: f32,
};

const MEDIA: u32 = 8u;
// Per-tile medium layout (ImpastoMaterialReplayState.mediumAt, sanitized).
const M_VISCOSITY: u32 = 0u;
const M_YIELD: u32 = 1u;
const M_LEVELING: u32 = 2u;
const M_DRYING: u32 = 3u;
const M_ROUGHNESS: u32 = 4u;
const M_SPECULAR: u32 = 5u;

const CONTACT_LOAD_EXCHANGE: f32 = 0.12;
const MIN_TRANSFER_RATE: f32 = 1e-4;
const YIELD_HEIGHT_THRESHOLD: f32 = 0.08;
const MAX_LEVEL_EDGE_STEP: f32 = 0.25;
const MAX_PIGMENT_EDGE_STEP: f32 = 0.25;
const MIN_WET_ROUGHNESS: f32 = 0.08;

@group(0) @binding(0) var<uniform> p: Params;
@group(0) @binding(1) var<storage, read_write> raw: array<u32>;
@group(0) @binding(2) var<storage, read_write> raw_before: array<u32>;
@group(0) @binding(3) var<storage, read_write> fields: array<f32>;
@group(0) @binding(4) var<storage, read_write> delta: array<f32>;
@group(0) @binding(5) var<storage, read_write> ctl: array<atomic<u32>>;
@group(0) @binding(6) var<storage, read> media: array<f32>;
@group(0) @binding(7) var<storage, read> coverage_mask: array<f32>;
@group(0) @binding(8) var<storage, read> dabs: array<MaterialDab>;
@group(0) @binding(9) var<storage, read_write> layer_px: array<u32>;

// ---- helpers -------------------------------------------------------------------------------

fn tile_of(x: i32, y: i32) -> u32 {
    return u32((y / p.tile) * p.cols + (x / p.tile));
}

fn tile_active(x: i32, y: i32) -> bool {
    return atomicLoad(&ctl[tile_of(x, y)]) != 0u;
}

fn wet_at(x: i32, y: i32) -> f32 {
    if (x < 0 || y < 0 || x >= p.width || y >= p.height) { return 0.0; }
    return fields[p.n + u32(y * p.width + x)];
}

fn medium(x: i32, y: i32, k: u32) -> f32 {
    return media[tile_of(x, y) * MEDIA + k];
}

fn sel(idx: u32) -> f32 {
    if (p.use_cov == 0u) { return 1.0; }
    return clamp(coverage_mask[idx], 0.0, 1.0);
}

// BrushStamps.stampCoverage.
fn stamp_coverage(r_norm: f32, hardness: f32) -> f32 {
    if (r_norm <= 0.0) { return 1.0; }
    if (r_norm >= 1.0) { return 0.0; }
    let h = clamp(hardness, 0.0, 1.0);
    if (r_norm <= h) { return 1.0; }
    let denom = 1.0 - h;
    if (denom <= 1e-4) { return 1.0; }
    return clamp((1.0 - r_norm) / denom, 0.0, 1.0);
}

// BrushStamps.buildUp.
fn build_up(current: f32, flow: f32) -> f32 {
    let c = clamp(current, 0.0, 1.0);
    return clamp(c + clamp(flow, 0.0, 1.0) * (1.0 - c), 0.0, 1.0);
}

// The CPU's per-dab pixel box, clamped: (minX, minY, maxX, maxY) inclusive.
fn dab_box(d: MaterialDab) -> vec4<i32> {
    return vec4<i32>(
        max(0, i32(floor(d.x - d.radius))),
        max(0, i32(floor(d.y - d.radius))),
        min(p.width - 1, i32(ceil(d.x + d.radius))),
        min(p.height - 1, i32(ceil(d.y + d.radius))),
    );
}

fn region_pixel(gid: vec3<u32>) -> vec2<i32> {
    if (i32(gid.x) >= p.rw || i32(gid.y) >= p.rh) { return vec2<i32>(-1, -1); }
    let x = p.rx + i32(gid.x);
    let y = p.ry + i32(gid.y);
    if (x < 0 || y < 0 || x >= p.width || y >= p.height) { return vec2<i32>(-1, -1); }
    return vec2<i32>(x, y);
}

fn channel(c: u32, shift: u32) -> i32 {
    return i32((c >> shift) & 0xFFu);
}

fn argb(a: i32, r: i32, g: i32, b: i32) -> u32 {
    return (u32(clamp(a, 0, 255)) << 24u) | (u32(clamp(r, 0, 255)) << 16u) |
        (u32(clamp(g, 0, 255)) << 8u) | u32(clamp(b, 0, 255));
}

// Straight ARGB -> premultiplied RGBA8 (what Bitmap.setPixels stores).
fn premultiply(c: u32) -> u32 {
    let a = f32(channel(c, 24u));
    let rgb = vec3<f32>(f32(channel(c, 16u)), f32(channel(c, 8u)), f32(channel(c, 0u)));
    let pre = round(rgb * a / 255.0);
    return pack4x8unorm(vec4<f32>(pre / 255.0, a / 255.0));
}

// Premultiplied RGBA8 -> straight ARGB (what Bitmap.getPixels returns).
fn unpremultiply(px: u32) -> u32 {
    let c = unpack4x8unorm(px) * 255.0;
    let a = round(c.a);
    if (a <= 0.0) { return 0u; }
    let s = min(round(round(c.rgb) * 255.0 / a), vec3<f32>(255.0));
    return argb(i32(a), i32(s.x), i32(s.y), i32(s.z));
}

// ---- raw pigment <-> layer -----------------------------------------------------------------

@compute @workgroup_size(8, 8)
fn raw_to_layer(@builtin(global_invocation_id) gid: vec3<u32>) {
    let xy = region_pixel(gid);
    if (xy.x < 0) { return; }
    let idx = u32(xy.y * p.width + xy.x);
    layer_px[idx] = premultiply(raw[idx]);
}

@compute @workgroup_size(8, 8)
fn layer_to_raw(@builtin(global_invocation_id) gid: vec3<u32>) {
    let xy = region_pixel(gid);
    if (xy.x < 0) { return; }
    let idx = u32(xy.y * p.width + xy.x);
    raw[idx] = unpremultiply(layer_px[idx]);
}

// DrawingEngine's feathered-selection blend: the freshly painted raw pigment kept only through the
// selection's coverage, the pre-contact pigment elsewhere (lerpArgb, truncating).
@compute @workgroup_size(8, 8)
fn blend_selection(@builtin(global_invocation_id) gid: vec3<u32>) {
    let xy = region_pixel(gid);
    if (xy.x < 0) { return; }
    let idx = u32(xy.y * p.width + xy.x);
    let f = coverage_mask[idx];
    if (f >= 1.0) { return; }
    let before = raw_before[idx];
    if (f <= 0.0) {
        raw[idx] = before;
        return;
    }
    let after = raw[idx];
    let t = clamp(f, 0.0, 1.0);
    var out = 0u;
    for (var s = 0u; s < 32u; s = s + 8u) {
        let av = f32(channel(before, s));
        let bv = f32(channel(after, s));
        out = out | (u32(clamp(i32(av + (bv - av) * t), 0, 255)) << s);
    }
    raw[idx] = out;
}

// ---- wetness deposit -----------------------------------------------------------------------

// One invocation per pixel of the dabs' union box walks the dabs in order: addWetness's clamp
// composes per pixel exactly as the CPU's dab-major loop does.
@compute @workgroup_size(8, 8)
fn deposit_wetness(@builtin(global_invocation_id) gid: vec3<u32>) {
    let xy = region_pixel(gid);
    if (xy.x < 0) { return; }
    let idx = u32(xy.y * p.width + xy.x);
    let s = sel(idx);
    if (s <= 0.0) { return; }
    let fx = f32(xy.x);
    let fy = f32(xy.y);
    let rate = clamp(p.f0, 0.0, 1.0);
    let hardness = p.f1;
    var w = fields[p.n + idx];
    var changed = false;
    for (var i = 0u; i < p.dab_count; i = i + 1u) {
        let d = dabs[i];
        if (d.radius <= 0.0) { continue; }
        let b = dab_box(d);
        if (xy.x < b.x || xy.x > b.z || xy.y < b.y || xy.y > b.w) { continue; }
        let dx = fx + 0.5 - d.x;
        let dy = fy + 0.5 - d.y;
        let dist = sqrt(dx * dx + dy * dy);
        if (dist >= d.radius) { continue; }
        let c = stamp_coverage(clamp(dist / d.radius, 0.0, 1.0), hardness);
        let amount = rate * c * s * clamp(d.contact, 0.0, 1.0) * clamp(d.alpha, 0.0, 1.0) *
            max(d.flow, 0.0);
        if (amount <= 0.0) { continue; }
        w = clamp(w + amount, 0.0, 1.0);
        changed = true;
        atomicOr(&ctl[p.flags_off + i], 2u);
    }
    if (changed) {
        fields[p.n + idx] = w;
        atomicOr(&ctl[tile_of(xy.x, xy.y)], 1u);
    }
}

// ---- height transfer -----------------------------------------------------------------------

const WG: u32 = 256u;

var<workgroup> red_a: array<f32, 256>;
var<workgroup> red_b: array<f32, 256>;
// Per-dab values computed by invocation 0 and read uniformly by the workgroup.
var<workgroup> wg_box: vec4<i32>;
var<workgroup> wg_load: f32;
var<workgroup> wg_spent: vec4<f32>;
var<workgroup> wg_carry: f32;
var<workgroup> wg_mass: f32;

struct Contact {
    ok: bool,
    idx: u32,
    before: f32,
    contact: f32,
    cov_sel: f32,
};

// Pass 1 and pass 2 share the per-pixel contact (transferMaterialStroke's inner loop head).
fn contact_at(k: i32, b: vec4<i32>, d: MaterialDab) -> Contact {
    var out = Contact(false, 0u, 0.0, 0.0, 0.0);
    let bw = b.z - b.x + 1;
    let x = b.x + k % bw;
    let y = b.y + k / bw;
    let idx = u32(y * p.width + x);
    let s = sel(idx);
    if (s <= 0.0) { return out; }
    let dx = f32(x) + 0.5 - d.x;
    let dy = f32(y) + 0.5 - d.y;
    let dist = sqrt(dx * dx + dy * dy);
    if (dist >= d.radius) { return out; }
    let c = stamp_coverage(clamp(dist / d.radius, 0.0, 1.0), p.f1);
    if (c <= 0.0) { return out; }
    let before = clamp(fields[idx], 0.0, 1.0);
    // SubstrateDepositionModel.coverageMultiplier on the smooth substrate: no tooth barrier, so
    // the gate is (1 - response) + response.
    let response = clamp(p.f5, 0.0, 1.0);
    let gate = clamp((1.0 - response) + response, 0.0, 1.0);
    let alpha_flow = clamp(d.alpha, 0.0, 1.0) * max(d.flow, 0.0);
    let contact = clamp(c * s * clamp(d.contact, 0.0, 1.0) * alpha_flow * gate, 0.0, 1.0);
    if (contact <= 0.0) { return out; }
    out.ok = true;
    out.idx = idx;
    out.before = before;
    out.contact = contact;
    out.cov_sel = c * s;
    return out;
}

// f0 rate, f1 hardness, f2 heightResponse, f3 depositionRate, f4 pickupRate, f5 substrateResponse,
// f6 initial reservoir load. One workgroup walks the dabs in order (the reservoir load is
// sequential); within a dab, pass 1 is a reduction and pass 2's bounded pickup a prefix sum over
// the box in the CPU's raster order, which reproduces its running `pickedThisDab` budget.
@compute @workgroup_size(256)
fn transfer(@builtin(local_invocation_index) lid: u32) {
    let rate = max(p.f0, 0.0);
    let deposit_k = rate * p.f2 * p.f3;
    if (lid == 0u) {
        // A negative initial load continues from the load the previous transfer stored.
        if (p.f6 < 0.0) {
            wg_load = clamp(bitcast<f32>(atomicLoad(&ctl[p.flags_off - 1u])), 0.0, 1.0);
        } else {
            wg_load = clamp(p.f6, 0.0, 1.0);
        }
    }
    workgroupBarrier();
    for (var i = 0u; i < p.dab_count; i = i + 1u) {
        let d = dabs[i];
        if (lid == 0u) {
            var b = dab_box(d);
            if (d.radius <= 0.0) { b = vec4<i32>(0, 0, -1, -1); }
            wg_box = b;
        }
        let b = workgroupUniformLoad(&wg_box);
        if (b.x > b.z || b.y > b.w) { continue; }
        let area = (b.z - b.x + 1) * (b.w - b.y + 1);
        let load_at_start = workgroupUniformLoad(&wg_load);

        // Pass 1: gross deposition against the untouched surface, and the contact mass.
        var dep = 0.0;
        var mass = 0.0;
        for (var k = i32(lid); k < area; k = k + i32(WG)) {
            let c = contact_at(k, b, d);
            if (!c.ok) { continue; }
            mass = mass + c.cov_sel;
            let inc = deposit_k * load_at_start * c.contact;
            dep = dep + max(build_up(c.before, inc) - c.before, 0.0);
        }
        red_a[lid] = dep;
        red_b[lid] = mass;
        workgroupBarrier();
        for (var s = WG / 2u; s > 0u; s = s / 2u) {
            if (lid < s) {
                red_a[lid] = red_a[lid] + red_a[lid + s];
                red_b[lid] = red_b[lid] + red_b[lid + s];
            }
            workgroupBarrier();
        }
        if (lid == 0u) {
            let deposited = red_a[0];
            let coverage_mass = red_b[0];
            let denom = max(coverage_mass * max(rate, MIN_TRANSFER_RATE), MIN_TRANSFER_RATE);
            var spent = 0.0;
            if (coverage_mass > 0.0) {
                spent = clamp((deposited / denom) * CONTACT_LOAD_EXCHANGE, 0.0, load_at_start);
            }
            let load_after = clamp(load_at_start - spent, 0.0, 1.0);
            let capacity = 1.0 - load_after;
            let max_pickup = capacity * denom / CONTACT_LOAD_EXCHANGE;
            wg_spent = vec4<f32>(load_after, capacity, max_pickup, denom);
            wg_carry = 0.0;
            wg_mass = coverage_mass;
        }
        let sp = workgroupUniformLoad(&wg_spent);
        let capacity = sp.y;
        let max_pickup = sp.z;

        // Pass 2: deposit, then pick up within the capacity the deposit just freed.
        let chunks = (area + i32(WG) - 1) / i32(WG);
        var touched = false;
        for (var ch = 0; ch < chunks; ch = ch + 1) {
            let k = ch * i32(WG) + i32(lid);
            var c = Contact(false, 0u, 0.0, 0.0, 0.0);
            if (k < area) { c = contact_at(k, b, d); }
            var local_h = 0.0;
            var q = 0.0;
            if (c.ok) {
                let inc = deposit_k * load_at_start * c.contact;
                local_h = clamp(build_up(c.before, inc), 0.0, 1.0);
                q = min(local_h, rate * p.f4 * capacity * c.contact);
            }
            // Inclusive scan of q over the chunk (Hillis-Steele, double-buffered).
            red_a[lid] = q;
            workgroupBarrier();
            var src_is_a = true;
            for (var off = 1u; off < WG; off = off * 2u) {
                var v = 0.0;
                if (src_is_a) {
                    v = red_a[lid];
                    if (lid >= off) { v = v + red_a[lid - off]; }
                    red_b[lid] = v;
                } else {
                    v = red_b[lid];
                    if (lid >= off) { v = v + red_b[lid - off]; }
                    red_a[lid] = v;
                }
                src_is_a = !src_is_a;
                workgroupBarrier();
            }
            var inclusive = red_a[lid];
            var total = red_a[WG - 1u];
            if (!src_is_a) {
                inclusive = red_b[lid];
                total = red_b[WG - 1u];
            }
            let carry = workgroupUniformLoad(&wg_carry);
            if (c.ok) {
                let picked_before = carry + (inclusive - q);
                let removed = min(q, max(max_pickup - picked_before, 0.0));
                let h = max(local_h - removed, 0.0);
                if (h != c.before) {
                    fields[c.idx] = h;
                    touched = true;
                }
            }
            workgroupBarrier();
            if (lid == 0u) { wg_carry = carry + total; }
            workgroupBarrier();
        }
        if (touched) { atomicOr(&ctl[p.flags_off + i], 1u); }
        storageBarrier();
        if (lid == 0u) {
            let picked = clamp(min(wg_carry, max_pickup), 0.0, max_pickup);
            var refill = 0.0;
            if (wg_mass > 0.0) {
                refill = clamp((picked / sp.w) * CONTACT_LOAD_EXCHANGE, 0.0, capacity);
            }
            wg_load = clamp(sp.x + refill, 0.0, 1.0);
        }
        workgroupBarrier();
    }
    if (lid == 0u) { atomicStore(&ctl[p.flags_off - 1u], bitcast<u32>(wg_load)); }
}

// ---- edge-phase solvers --------------------------------------------------------------------

// The edge (a, b) owned by pixel `a` in this phase, or b = (-1, -1). Phases 0/1: horizontal edges
// from even/odd x; 2/3: vertical edges from even/odd y. `right`/`bottom` bound b (exclusive).
fn phase_neighbour(x: i32, y: i32, right: i32, bottom: i32) -> vec2<i32> {
    if (p.phase < 2) {
        if (x % 2 != p.phase || x + 1 >= right || !tile_active(x + 1, y)) { return vec2<i32>(-1, -1); }
        return vec2<i32>(x + 1, y);
    }
    if (y % 2 != p.phase - 2 || y + 1 >= bottom || !tile_active(x, y + 1)) { return vec2<i32>(-1, -1); }
    return vec2<i32>(x, y + 1);
}

// levelWetHeight's exchange, region [rx, rx+rw) x [ry, ry+rh). f0 dt, f1 iteration count.
// Terrain is zero: the smooth substrate has no height.
@compute @workgroup_size(8, 8)
fn level_phase(@builtin(global_invocation_id) gid: vec3<u32>) {
    let xy = region_pixel(gid);
    if (xy.x < 0 || !tile_active(xy.x, xy.y)) { return; }
    let nb = phase_neighbour(xy.x, xy.y, p.rx + p.rw, p.ry + p.rh);
    if (nb.x < 0) { return; }
    let a = u32(xy.y * p.width + xy.x);
    let b = u32(nb.y * p.width + nb.x);
    let wet = clamp((wet_at(xy.x, xy.y) + wet_at(nb.x, nb.y)) * 0.5, 0.0, 1.0);
    if (wet <= 0.0) { return; }
    let viscosity = (medium(xy.x, xy.y, M_VISCOSITY) + medium(nb.x, nb.y, M_VISCOSITY)) * 0.5;
    let leveling = (medium(xy.x, xy.y, M_LEVELING) + medium(nb.x, nb.y, M_LEVELING)) * 0.5;
    let yield_strength = (medium(xy.x, xy.y, M_YIELD) + medium(nb.x, nb.y, M_YIELD)) * 0.5;
    let fluidity = wet * (1.0 - viscosity);
    if (fluidity <= 0.0 || leveling <= 0.0) { return; }
    let ha = fields[a];
    let hb = fields[b];
    let diff = max(hb, 0.0) - max(ha, 0.0);
    let threshold = yield_strength * (1.0 - wet) * YIELD_HEIGHT_THRESHOLD;
    let excess = abs(diff) - threshold;
    if (excess <= 0.0) { return; }
    let step_base = clamp(leveling * p.f0 / p.f1, 0.0, MAX_LEVEL_EDGE_STEP);
    let amount = excess * step_base * fluidity;
    if (amount <= 0.0) { return; }
    if (diff > 0.0) {
        let moved = min(amount, min(max(hb, 0.0), max(1.0 - ha, 0.0)));
        if (moved <= 0.0) { return; }
        fields[b] = max(hb - moved, 0.0);
        fields[a] = clamp(ha + moved, 0.0, 1.0);
    } else {
        let moved = min(amount, min(max(ha, 0.0), max(1.0 - hb, 0.0)));
        if (moved <= 0.0) { return; }
        fields[a] = max(ha - moved, 0.0);
        fields[b] = clamp(hb + moved, 0.0, 1.0);
    }
}

// WetMaterialTransport's exchangeArgb over the whole layer. f0 iteration rate
// (transportRate.clamp(0, 1) * dt / iterations).
@compute @workgroup_size(8, 8)
fn pigment_phase(@builtin(global_invocation_id) gid: vec3<u32>) {
    let xy = region_pixel(gid);
    if (xy.x < 0 || !tile_active(xy.x, xy.y)) { return; }
    let nb = phase_neighbour(xy.x, xy.y, p.width, p.height);
    if (nb.x < 0) { return; }
    let mobility = clamp((wet_at(xy.x, xy.y) + wet_at(nb.x, nb.y)) * 0.5, 0.0, 1.0);
    let step = clamp(p.f0 * mobility, 0.0, MAX_PIGMENT_EDGE_STEP);
    if (step <= 0.0) { return; }
    let ia = u32(xy.y * p.width + xy.x);
    let ib = u32(nb.y * p.width + nb.x);
    let a = raw[ia];
    let b = raw[ib];
    var out_a = 0u;
    var out_b = 0u;
    for (var s = 0u; s < 32u; s = s + 8u) {
        let av = channel(a, s);
        let bv = channel(b, s);
        // Math.round: floor(x + 0.5).
        let mv = i32(floor(f32(bv - av) * step + 0.5));
        out_a = out_a | (u32(clamp(av + mv, 0, 255)) << s);
        out_b = out_b | (u32(clamp(bv - mv, 0, 255)) << s);
    }
    raw[ia] = out_a;
    raw[ib] = out_b;
}

fn edge_flux(ax: i32, ay: i32, bx: i32, by: i32, step: f32) -> f32 {
    // Flux into a along the eligible edge (a, b): both tiles active, b inside the layer.
    if (bx >= p.width || by >= p.height || ax < 0 || ay < 0) { return 0.0; }
    if (!tile_active(ax, ay) || !tile_active(bx, by)) { return 0.0; }
    return (wet_at(bx, by) - wet_at(ax, ay)) * step;
}

// PersistentWetnessField.advance, transport half: each active pixel's summed flux over its four
// edges into `delta`. f0 transport step.
@compute @workgroup_size(8, 8)
fn wet_delta(@builtin(global_invocation_id) gid: vec3<u32>) {
    let xy = region_pixel(gid);
    if (xy.x < 0 || !tile_active(xy.x, xy.y)) { return; }
    let x = xy.x;
    let y = xy.y;
    let s = p.f0;
    var d = 0.0;
    if (s > 0.0) {
        d = d - edge_flux(x, y - 1, x, y, s);
        d = d - edge_flux(x - 1, y, x, y, s);
        d = d + edge_flux(x, y, x + 1, y, s);
        d = d + edge_flux(x, y, x, y + 1, s);
    }
    delta[u32(y * p.width + x)] = d;
}

// PersistentWetnessField.advance, drying half. mode 0: uniform multiplier f0; mode 1: the tile
// medium's drying rate over f1 seconds. f2 dry epsilon. Still-wet tiles are flagged in the
// next-step set.
@compute @workgroup_size(8, 8)
fn wet_apply(@builtin(global_invocation_id) gid: vec3<u32>) {
    let xy = region_pixel(gid);
    if (xy.x < 0 || !tile_active(xy.x, xy.y)) { return; }
    let idx = u32(xy.y * p.width + xy.x);
    var mul = p.f0;
    if (p.mode == 1u) {
        mul = exp(-clamp(medium(xy.x, xy.y, M_DRYING), 0.0, 1.0) * p.f1);
    }
    let next = clamp(clamp(fields[p.n + idx] + delta[idx], 0.0, 1.0) * mul, 0.0, 1.0);
    var w = next;
    if (next <= p.f2) { w = 0.0; }
    fields[p.n + idx] = w;
    if (w > p.f2) { atomicOr(&ctl[p.tiles + tile_of(xy.x, xy.y)], 1u); }
}

// After wet_apply: a dry tile leaves the simulation; clears the next-step set.
@compute @workgroup_size(64)
fn wet_commit(@builtin(global_invocation_id) gid: vec3<u32>) {
    let t = gid.x;
    if (t >= p.tiles) { return; }
    let still = atomicExchange(&ctl[p.tiles + t], 0u);
    if (atomicLoad(&ctl[t]) != 0u && still == 0u) { atomicStore(&ctl[t], 0u); }
}

// ---- presentation --------------------------------------------------------------------------

// ImpastoRegionShader.shadeMaterial: the raw pigment relief-lit with wet specular, written
// premultiplied into the layer over the region. f0..f2 light, f3 relief strength, f4..f6 the Blinn
// half vector.
@compute @workgroup_size(8, 8)
fn shade_material(@builtin(global_invocation_id) gid: vec3<u32>) {
    let xy = region_pixel(gid);
    if (xy.x < 0) { return; }
    let x = xy.x;
    let y = xy.y;
    let xl = u32(y * p.width + clamp(x - 1, 0, p.width - 1));
    let xr = u32(y * p.width + clamp(x + 1, 0, p.width - 1));
    let yu = u32(clamp(y - 1, 0, p.height - 1) * p.width + x);
    let yd = u32(clamp(y + 1, 0, p.height - 1) * p.width + x);
    let dhdx = (fields[xr] - fields[xl]) / 2.0;
    let dhdy = (fields[yd] - fields[yu]) / 2.0;
    let nx0 = -dhdx;
    let ny0 = -dhdy;
    let inv_len = 1.0 / sqrt(nx0 * nx0 + ny0 * ny0 + 1.0);
    let nx = nx0 * inv_len;
    let ny = ny0 * inv_len;
    let nz = inv_len;
    let diffuse = nx * p.f0 + ny * p.f1 + nz * p.f2;
    let m = clamp(1.0 + max(p.f3, 0.0) * (diffuse - p.f2), 0.0, 3.0);
    var wet = 0.0;
    if (p.use_wet != 0u) { wet = clamp(wet_at(x, y), 0.0, 1.0); }
    let roughness = clamp(
        medium(x, y, M_ROUGHNESS) * (1.0 - wet) + MIN_WET_ROUGHNESS * wet, MIN_WET_ROUGHNESS, 1.0,
    );
    let shininess = 4.0 + (1.0 - roughness) * 60.0;
    let n_dot_h = clamp(nx * p.f4 + ny * p.f5 + nz * p.f6, 0.0, 1.0);
    var lobe = 0.0;
    if (n_dot_h > 0.0) { lobe = pow(n_dot_h, shininess); }
    let specular = medium(x, y, M_SPECULAR) * wet * lobe;
    let idx = u32(y * p.width + x);
    let c = raw[idx];
    let add = 255.0 * clamp(specular, 0.0, 1.0);
    let shaded = argb(
        channel(c, 24u),
        i32(f32(channel(c, 16u)) * m + add),
        i32(f32(channel(c, 8u)) * m + add),
        i32(f32(channel(c, 0u)) * m + add),
    );
    layer_px[idx] = premultiply(shaded);
}
