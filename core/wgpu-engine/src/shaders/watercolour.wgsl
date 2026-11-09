// Watercolour field passes (spec/painterly.md 3.8): wicking, pigment bleed, edge flow and drying as a
// fixed-timestep per-pixel update, plus granulated deposit. A statement-for-statement port of Kotlin
// `WatercolourField` (core/engine/.../azphalt/WatercolourField.kt), pinned to it by
// tests/fixtures/watercolour_golden.txt and tests/watercolour.rs. Each tick reads the previous tick's
// buffers and writes the other pair (a gather), summing the four neighbours in a fixed order
// (left, right, up, down), so pixel order never matters and a replay repeats.
//
// MEMORY MODEL. No invocation reads a storage location another invocation of the same dispatch writes: `step`
// reads only the "in" pair and writes only the "out" pair, and `deposit` reads and writes the "in" pair at the
// invocation's own index. The ordering that matters (deposit -> tick 1 -> tick 2 ...) is between dispatches, and
// the host runs each in its own compute pass (see watercolour.rs), a pass boundary being what orders storage-buffer
// writes between them. There are no barriers in this shader because none are needed; do not add intra-dispatch
// cross-invocation reads without adding the matching storageBarrier()/workgroupBarrier() (workgroupBarrier alone
// does not order storage writes).

struct Params {
    width: i32,
    height: i32,
    wick: f32,
    bleed: f32,
    edge: f32,
    dry: f32,
    granulation: f32,
    _p0: f32,
};

@group(0) @binding(0) var<storage, read_write> wet_in: array<f32>;
@group(0) @binding(1) var<storage, read_write> pig_in: array<f32>;
@group(0) @binding(2) var<storage, read_write> wet_out: array<f32>;
@group(0) @binding(3) var<storage, read_write> pig_out: array<f32>;
@group(0) @binding(4) var<storage, read> absorb: array<f32>;
@group(0) @binding(5) var<storage, read> tooth: array<f32>;
@group(0) @binding(6) var<storage, read> dep_amount: array<f32>;
@group(0) @binding(7) var<storage, read> dep_water: array<f32>;
@group(0) @binding(8) var<uniform> p: Params;

fn granulate(amount: f32, t: f32, g: f32) -> f32 {
    return amount * max(0.0, 1.0 + g * (0.5 - t) * 2.0);
}

// Adds granulated pigment and water to the "in" pair, in place.
@compute @workgroup_size(64)
fn deposit(@builtin(global_invocation_id) gid: vec3<u32>) {
    let i = gid.x;
    if (i >= u32(p.width * p.height)) { return; }
    if (dep_amount[i] > 0.0) { pig_in[i] = pig_in[i] + granulate(dep_amount[i], tooth[i], p.granulation); }
    if (dep_water[i] > 0.0) { wet_in[i] = min(1.0, wet_in[i] + dep_water[i]); }
}

@compute @workgroup_size(64)
fn step(@builtin(global_invocation_id) gid: vec3<u32>) {
    let n = u32(p.width * p.height);
    let i = gid.x;
    if (i >= n) { return; }
    let x = i32(i) % p.width;
    let y = i32(i) / p.width;
    var wet_sum = 0.0;
    var pig_sum = 0.0;
    for (var k = 0; k < 4; k = k + 1) {
        var nx = x;
        var ny = y;
        if (k == 0) { nx = x - 1; }
        if (k == 1) { nx = x + 1; }
        if (k == 2) { ny = y - 1; }
        if (k == 3) { ny = y + 1; }
        if (nx < 0 || ny < 0 || nx >= p.width || ny >= p.height) { continue; }
        let j = u32(ny * p.width + nx);
        wet_sum = wet_sum + p.wick * (absorb[i] + absorb[j]) / 2.0 * (wet_in[j] - wet_in[i]);
        pig_sum = pig_sum + p.bleed * min(wet_in[i], wet_in[j]) * (pig_in[j] - pig_in[i]);
        pig_sum = pig_sum + p.edge * (max(wet_in[j] - wet_in[i], 0.0) * pig_in[j]
            - max(wet_in[i] - wet_in[j], 0.0) * pig_in[i]);
    }
    wet_out[i] = clamp((wet_in[i] + wet_sum) * (1.0 - p.dry), 0.0, 1.0);
    pig_out[i] = max(pig_in[i] + pig_sum, 0.0);
}
