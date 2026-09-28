// Multipass display composite: one invocation per pixel of each active tile, one dispatch per frame.
//
//   target = base OVER reveal(upsample(draft), edge)
//   shown  = mix(from, target, w_px)          while a landing eases in, else target
//
// * base: the layer as it was when the tile last landed (the final result of everything painted
//   before), or at the start of the stroke.
// * draft: the low-resolution drafts painted since, bilinearly upsampled (never across the tile's
//   own edge, so a neighbour's landing cannot bleed in).
// * reveal: the draft's feather shows only up to `edge`, which grows from the trimmed edge toward the
//   full feather as the final result approaches (progress.rs).
// * landing: when the final result lands the tile's base becomes it and the draft is cleared, so
//   `target` is the final image; `from` is what was on screen at that moment, and the per-pixel
//   weight ramps the strongest changes first, so the soft edge settles outward.
// At weight 1 the output is exactly `target` (mix(a, b, 1) = b), and with an empty draft `target`
// is exactly `base`: a landed tile shows the final layer bit for bit.

struct Tile {
    x: i32,
    y: i32,
    slot: i32,     // snapshot slot of the "from" image; < 0 = no landing
    _pad: i32,
    edge: f32,     // reveal edge on the feather key
    weight: f32,   // landing weight 0..1
    _pad1: f32,
    _pad2: f32,
};

struct Params {
    layer_width: i32,
    layer_height: i32,
    draft_width: i32,
    draft_height: i32,
    scale: f32,
    tile: i32,
    band: f32,     // width of the reveal's soft step, in key units
    spread: f32,   // how much the landing's start is staggered by change magnitude
    // Up to 128 tiles per dispatch (a uniform array keeps this pass at four storage buffers, the
    // downlevel GL limit).
    tiles: array<Tile, 128>,
};

@group(0) @binding(0) var<storage, read_write> display_px: array<u32>;
@group(0) @binding(1) var<storage, read> base_px: array<u32>;
@group(0) @binding(2) var<storage, read> draft_px: array<vec2<u32>>;
@group(0) @binding(3) var<storage, read> snaps: array<u32>;
@group(0) @binding(4) var<uniform> pc: Params;

struct DraftSample {
    color: vec4<f32>,
    key: f32,
};

fn draft_texel(t: vec2<i32>) -> DraftSample {
    let v = draft_px[t.y * pc.draft_width + t.x];
    let c = unpack4x8unorm(v.x);
    var s: DraftSample;
    s.color = c;
    s.key = select(1.0, bitcast<f32>(v.y), c.a > 0.0);
    return s;
}

@compute @workgroup_size(16, 16)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    let tile = pc.tiles[gid.z];
    let local = vec2<i32>(gid.xy);
    if (local.x >= pc.tile || local.y >= pc.tile) { return; }
    let p = vec2<i32>(tile.x, tile.y) + local;
    if (p.x >= pc.layer_width || p.y >= pc.layer_height) { return; }
    let idx = p.y * pc.layer_width + p.x;

    // Bilinear draft sample, clamped to this tile's draft texels.
    let lo = vec2<i32>(tile.x, tile.y) / i32(pc.scale);
    let hi = min((vec2<i32>(tile.x, tile.y) + vec2<i32>(pc.tile)) / i32(pc.scale),
                 vec2<i32>(pc.draft_width, pc.draft_height)) - vec2<i32>(1);
    let dp = (vec2<f32>(p) + vec2<f32>(0.5)) / pc.scale - vec2<f32>(0.5);
    let f0 = floor(dp);
    let fr = dp - f0;
    let t00 = clamp(vec2<i32>(f0), lo, hi);
    let t11 = clamp(vec2<i32>(f0) + vec2<i32>(1), lo, hi);
    let a = draft_texel(t00);
    let b = draft_texel(vec2<i32>(t11.x, t00.y));
    let c = draft_texel(vec2<i32>(t00.x, t11.y));
    let d = draft_texel(t11);
    let color = mix(mix(a.color, b.color, fr.x), mix(c.color, d.color, fr.x), fr.y);
    let key = mix(mix(a.key, b.key, fr.x), mix(c.key, d.key, fr.x), fr.y);

    let reveal = 1.0 - smoothstep(tile.edge - 0.5 * pc.band, tile.edge + 0.5 * pc.band, key);
    let stroke = color * reveal;
    let base = unpack4x8unorm(base_px[idx]);
    let target_px = stroke + base * (1.0 - stroke.a);

    var shown = target_px;
    if (tile.slot >= 0) {
        let shown_before = unpack4x8unorm(snaps[tile.slot * pc.tile * pc.tile + local.y * pc.tile + local.x]);
        let diff = abs(target_px - shown_before);
        let change = max(max(diff.r, diff.g), max(diff.b, diff.a));
        // Big changes (the paint's body next to the draft's edge) start first; faint outer feather
        // last: the soft edge grows outward. All pixels reach weight 1 together at w = 1.
        let start = pc.spread * (1.0 - clamp(change * 4.0, 0.0, 1.0));
        var w = 1.0;
        if (tile.weight < 1.0) {
            w = clamp((tile.weight - start) / max(1.0 - start, 1e-4), 0.0, 1.0);
        }
        shown = mix(shown_before, target_px, w);
    }
    display_px[idx] = pack4x8unorm(shown);
}
