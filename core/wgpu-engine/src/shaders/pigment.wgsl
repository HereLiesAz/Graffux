// Kubelka-Munk pigment mixing, a statement-for-statement port of Kotlin `PigmentMixer.mixLinearRgb`
// (core/engine/.../azphalt/PigmentMixer.kt). Pinned to it by tests/fixtures/pigment_golden.txt and
// tests/pigment.rs. Spec: spec/painterly.md 6.2. PLACEHOLDER pigment data (the tables are inputs).
//
// tables: per pigment 14 floats, K0..K6 then S0..S6. mixtures: m rows of n concentrations (any
// non-negative scale; normalised here). out_rgb: m rows of linear sRGB, clamped to 0..1.

struct Params {
    n: u32,
    m: u32,
    _p0: u32,
    _p1: u32,
};

@group(0) @binding(0) var<storage, read> tables: array<f32>;
@group(0) @binding(1) var<storage, read> mixtures: array<f32>;
@group(0) @binding(2) var<storage, read_write> out_rgb: array<f32>;
@group(0) @binding(3) var<uniform> p: Params;

const BANDS: u32 = 7u;

// PigmentModel.BAND_TO_LINEAR_RGB, row per channel (frozen constants shared with Kotlin).
fn band_weight(channel: u32, band: u32) -> f32 {
    var w = array<f32, 21>(
        0.0846821, -0.0513403, -0.1992169, -0.1765789, 0.5181762, 0.6919792, 0.1322987,
        -0.0952128, 0.0143237, 0.2472030, 0.6014336, 0.2953821, -0.0499800, -0.0131496,
        0.5937924, 0.4371635, 0.0707287, -0.0505347, -0.0405309, -0.0092208, -0.0013983
    );
    return w[channel * BANDS + band];
}

@compute @workgroup_size(64)
fn km_mix(@builtin(global_invocation_id) gid: vec3<u32>) {
    let row = gid.x;
    if (row >= p.m) { return; }
    let base = row * p.n;
    var total = 0.0;
    for (var i = 0u; i < p.n; i = i + 1u) { total = total + mixtures[base + i]; }
    if (!(total > 0.0)) {
        out_rgb[row * 3u] = 0.0;
        out_rgb[row * 3u + 1u] = 0.0;
        out_rgb[row * 3u + 2u] = 0.0;
        return;
    }
    var reflectance = array<f32, 7>(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    for (var b = 0u; b < BANDS; b = b + 1u) {
        var k = 0.0;
        var s = 0.0;
        for (var i = 0u; i < p.n; i = i + 1u) {
            let w = mixtures[base + i] / total;
            k = k + w * tables[i * 14u + b];
            s = s + w * tables[i * 14u + BANDS + b];
        }
        let q = k / s;
        reflectance[b] = 1.0 + q - sqrt(q * q + 2.0 * q);
    }
    for (var c = 0u; c < 3u; c = c + 1u) {
        var v = 0.0;
        for (var b = 0u; b < BANDS; b = b + 1u) { v = v + band_weight(c, b) * reflectance[b]; }
        out_rgb[row * 3u + c] = clamp(v, 0.0, 1.0);
    }
}
