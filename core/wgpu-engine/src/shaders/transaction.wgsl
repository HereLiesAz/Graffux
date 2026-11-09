// The paint transaction (spec/painterly.md 3.4): per dab, lift paint from a surface into a brush load
// column (or discard it), then draw the column's paint onto the surface. A statement-for-statement port
// of Kotlin `PaintTransaction.run` (core/engine/.../azphalt/PaintTransaction.kt), pinned to it by
// tests/fixtures/transaction_golden.txt and tests/transaction.rs.
//
// One workgroup of 64 threads walks the dabs in recorded order (the order-dependent transaction composes
// exactly as the CPU loop does). Sums use a fixed per-thread partial and a fixed tree reduction, never
// float atomics, so a replay on the same device is bit-identical.
//
// conc: 16 concentrations per texel as q/255. snap: the concentrations at touchdown (colour sampling
// ONLY). avail: the stroke-local remaining liftable amount (quantities). Draws never add to avail or
// snap, so a stroke cannot lift its own paint.

struct Params {
    width: i32,
    height: i32,
    dab_count: u32,
    lanes: u32,
    capacity: f32,
    _p0: f32,
    _p1: f32,
    _p2: f32,
};

const CH: u32 = 16u;
const WG: u32 = 64u;
const DAB_FLOATS: u32 = 12u;

@group(0) @binding(0) var<storage, read_write> conc: array<f32>;
@group(0) @binding(1) var<storage, read_write> amount: array<f32>;
@group(0) @binding(2) var<storage, read_write> avail: array<f32>;
@group(0) @binding(3) var<storage, read> snap: array<f32>;
@group(0) @binding(4) var<storage, read_write> take: array<f32>;
@group(0) @binding(5) var<storage, read_write> load_amount: array<f32>;
@group(0) @binding(6) var<storage, read_write> load_conc: array<f32>;
@group(0) @binding(7) var<storage, read> dabs: array<f32>;
@group(0) @binding(8) var<uniform> p: Params;

var<workgroup> partial: array<f32, 64>;
var<workgroup> wg_box: vec4<i32>;      // min_x, min_y, bw, n
var<workgroup> wg_lane: i32;
var<workgroup> wg_flags: vec4<u32>;    // do_lift, do_draw, dispose, has_lane
var<workgroup> wg_scale: f32;
var<workgroup> wg_before: f32;

// Dab fields: x y radius hardness lane draw lift mixWet dispose
struct Dab {
    x: f32, y: f32, radius: f32, hardness: f32,
    lane: i32, draw: f32, lift: f32, mix_wet: f32, dispose: bool,
};

fn dab_at(i: u32) -> Dab {
    let o = i * DAB_FLOATS;
    return Dab(dabs[o], dabs[o + 1u], dabs[o + 2u], dabs[o + 3u], i32(dabs[o + 4u]),
        dabs[o + 5u], dabs[o + 6u], dabs[o + 7u], dabs[o + 8u] != 0.0);
}

fn coverage(r_norm: f32, hardness: f32) -> f32 {
    if (r_norm <= 0.0) { return 1.0; }
    if (r_norm >= 1.0) { return 0.0; }
    let h = clamp(hardness, 0.0, 1.0);
    if (r_norm <= h) { return 1.0; }
    let denom = 1.0 - h;
    if (denom <= 1e-4) { return 1.0; }
    return clamp((1.0 - r_norm) / denom, 0.0, 1.0);
}

fn weight_at(k: i32, box: vec4<i32>, d: Dab) -> f32 {
    let bw = box.z;
    let x = box.x + k % bw;
    let y = box.y + k / bw;
    let dx = f32(x) + 0.5 - d.x;
    let dy = f32(y) + 0.5 - d.y;
    let dist = sqrt(dx * dx + dy * dy);
    if (dist >= d.radius) { return 0.0; }
    return coverage(clamp(dist / d.radius, 0.0, 1.0), d.hardness);
}

fn idx_at(k: i32, box: vec4<i32>) -> u32 {
    let bw = box.z;
    return u32((box.y + k / bw) * p.width + (box.x + k % bw));
}

// The fixed tree: stride 32, 16, ... 1. All threads call it (it holds barriers).
fn reduce(lid: u32) -> f32 {
    workgroupBarrier();
    var stride = WG / 2u;
    loop {
        if (stride == 0u) { break; }
        if (lid < stride) { partial[lid] = partial[lid] + partial[lid + stride]; }
        workgroupBarrier();
        stride = stride / 2u;
    }
    let total = partial[0];
    workgroupBarrier();
    return total;
}

// PigmentConcentration.quantize over 16 channels: largest remainder, ties to the lower index.
fn quantise(raw: array<f32, 16>) -> array<f32, 16> {
    var out = array<f32, 16>();
    var total = 0.0;
    for (var c = 0u; c < CH; c = c + 1u) { total = total + max(raw[c], 0.0); }
    if (!(total > 0.0)) { return out; }
    var rem = array<f32, 16>();
    var used = 0.0;
    for (var c = 0u; c < CH; c = c + 1u) {
        let scaled = max(raw[c], 0.0) / total * 255.0;
        let whole = clamp(floor(scaled), 0.0, 255.0);
        out[c] = whole;
        rem[c] = scaled - whole;
        used = used + whole;
    }
    var left = i32(255.0 - used);
    loop {
        if (left <= 0) { break; }
        var best = 0u;
        for (var c = 1u; c < CH; c = c + 1u) { if (rem[c] > rem[best]) { best = c; } }
        out[best] = out[best] + 1.0;
        rem[best] = -1.0;
        left = left - 1;
    }
    return out;
}

@compute @workgroup_size(64)
fn run(@builtin(local_invocation_index) lid: u32) {
    for (var di = 0u; di < p.dab_count; di = di + 1u) {
        let d = dab_at(di);
        if (lid == 0u) {
            var b = vec4<i32>(0, 0, 1, 0);
            if (d.radius > 0.0) {
                let min_x = max(0, i32(floor(d.x - d.radius)));
                let max_x = min(p.width - 1, i32(ceil(d.x + d.radius)));
                let min_y = max(0, i32(floor(d.y - d.radius)));
                let max_y = min(p.height - 1, i32(ceil(d.y + d.radius)));
                if (min_x <= max_x && min_y <= max_y) {
                    let bw = max_x - min_x + 1;
                    b = vec4<i32>(min_x, min_y, bw, bw * (max_y - min_y + 1));
                }
            }
            wg_box = b;
            wg_lane = d.lane;
            let has_lane = d.lane >= 0 && d.lane < i32(p.lanes);
            let live = b.w > 0;
            wg_flags = vec4<u32>(
                select(0u, 1u, live && d.lift > 0.0 && (has_lane || d.dispose)),
                select(0u, 1u, live && d.draw > 0.0 && has_lane && load_amount[u32(max(d.lane, 0))] > 0.0),
                select(0u, 1u, d.dispose),
                select(0u, 1u, has_lane),
            );
        }
        workgroupBarrier();
        let box = workgroupUniformLoad(&wg_box);
        let flags = workgroupUniformLoad(&wg_flags);
        let lane = workgroupUniformLoad(&wg_lane);
        let n = box.w;
        let has_lane = flags.w != 0u;
        let is_dispose = flags.z != 0u;

        // ---- lift ----
        if (flags.x != 0u) {
            partial[lid] = 0.0;
            for (var k = i32(lid); k < n; k = k + i32(WG)) {
                take[k] = 0.0;
                let w = weight_at(k, box, d);
                if (w > 0.0) {
                    take[k] = min(d.lift * w, avail[idx_at(k, box)]);
                    partial[lid] = partial[lid] + take[k];
                }
            }
            let wanted = reduce(lid);
            if (lid == 0u) {
                var scale = 1.0;
                if (!is_dispose && has_lane) {
                    let free = max(p.capacity - load_amount[u32(lane)], 0.0);
                    if (wanted > free) { scale = free / wanted; }
                }
                wg_scale = scale;
                wg_before = select(0.0, load_amount[u32(max(lane, 0))], has_lane);
            }
            workgroupBarrier();
            let scale = workgroupUniformLoad(&wg_scale);
            let before = workgroupUniformLoad(&wg_before);
            for (var k = i32(lid); k < n; k = k + i32(WG)) {
                if (take[k] > 0.0) {
                    take[k] = take[k] * scale;
                    let i = idx_at(k, box);
                    avail[i] = avail[i] - take[k];
                    amount[i] = max(amount[i] - take[k], 0.0);
                }
            }
            workgroupBarrier();
            if (!is_dispose && has_lane) {
                var sums = array<f32, 16>();
                for (var c = 0u; c < CH; c = c + 1u) {
                    partial[lid] = 0.0;
                    for (var k = i32(lid); k < n; k = k + i32(WG)) {
                        if (take[k] > 0.0) {
                            partial[lid] = partial[lid] + take[k] * snap[idx_at(k, box) * CH + c];
                        }
                    }
                    sums[c] = reduce(lid);
                }
                partial[lid] = 0.0;
                for (var k = i32(lid); k < n; k = k + i32(WG)) { partial[lid] = partial[lid] + take[k]; }
                let total = reduce(lid);
                let after = before + total;
                if (lid == 0u && after > 0.0) {
                    for (var c = 0u; c < CH; c = c + 1u) {
                        let at = u32(lane) * CH + c;
                        load_conc[at] = (load_conc[at] * before + sums[c]) / after;
                    }
                    load_amount[u32(lane)] = after;
                }
                workgroupBarrier();
            }
        }

        // ---- draw ---- (the gate re-reads the column the lift just changed)
        var do_draw = 0u;
        if (lid == 0u) {
            let live = box.w > 0;
            wg_flags = vec4<u32>(0u, select(0u, 1u, live && d.draw > 0.0 && has_lane && load_amount[u32(max(lane, 0))] > 0.0), 0u, 0u);
        }
        workgroupBarrier();
        do_draw = workgroupUniformLoad(&wg_flags).y;
        if (do_draw != 0u) {
            partial[lid] = 0.0;
            for (var k = i32(lid); k < n; k = k + i32(WG)) {
                let w = weight_at(k, box, d);
                if (w > 0.0) { partial[lid] = partial[lid] + d.draw * w; }
            }
            let wanted = reduce(lid);
            if (lid == 0u) {
                let have = load_amount[u32(lane)];
                wg_scale = select(1.0, have / wanted, wanted > have);
            }
            workgroupBarrier();
            let scale = workgroupUniformLoad(&wg_scale);
            partial[lid] = 0.0;
            for (var k = i32(lid); k < n; k = k + i32(WG)) {
                let w = weight_at(k, box, d);
                if (w <= 0.0) { continue; }
                let i = idx_at(k, box);
                let dep = d.draw * w * scale;
                let present = amount[i];
                let m = clamp(d.mix_wet, 0.0, 1.0) * min(1.0, present);
                let new_amount = present + dep;
                var raw = array<f32, 16>();
                for (var c = 0u; c < CH; c = c + 1u) {
                    let surf = conc[i * CH + c];
                    let recipe = load_conc[u32(lane) * CH + c] * (1.0 - m) + surf * m;
                    raw[c] = (surf * present + recipe * dep) / new_amount;
                }
                let q = quantise(raw);
                for (var c = 0u; c < CH; c = c + 1u) { conc[i * CH + c] = q[c] / 255.0; }
                amount[i] = new_amount;
                partial[lid] = partial[lid] + dep;
            }
            let spent = reduce(lid);
            if (lid == 0u) { load_amount[u32(lane)] = max(load_amount[u32(lane)] - spent, 0.0); }
            workgroupBarrier();
        }
    }
}
