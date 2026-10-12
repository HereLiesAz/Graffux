// A stroke confined to the selection at commit, on the GPU. The CPU reference is the editor's
// commit (GpuStampCommit.keepInside, SelectionMask.feather):
//
//   hard selection   out = lerp(shadow, layer, m)             -- SRC through an antialiased clip
//   feathered        out = layer * m + shadow * (1 - layer.a * m)  -- DST_IN by the blurred mask,
//                                                             then SRC_OVER onto the pre-stroke layer
//
// `layer` is the painted layer, `shadow` the layer as it was when the stroke started, `m` the
// selection's coverage (0..1, one byte per pixel packed four to a word). All premultiplied RGBA8.

struct Params {
    rx: i32,
    ry: i32,
    rw: i32,
    rh: i32,
    width: i32,
    height: i32,
    feather: u32,
    _pad: u32,
};

@group(0) @binding(0) var<uniform> p: Params;
@group(0) @binding(1) var<storage, read_write> layer_px: array<u32>;
@group(0) @binding(2) var<storage, read> shadow_px: array<u32>;
@group(0) @binding(3) var<storage, read> mask_words: array<u32>;

fn coverage(idx: u32) -> f32 {
    return f32((mask_words[idx / 4u] >> ((idx % 4u) * 8u)) & 0xFFu) / 255.0;
}

@compute @workgroup_size(8, 8)
fn confine(@builtin(global_invocation_id) gid: vec3<u32>) {
    if (i32(gid.x) >= p.rw || i32(gid.y) >= p.rh) { return; }
    let x = p.rx + i32(gid.x);
    let y = p.ry + i32(gid.y);
    if (x < 0 || y < 0 || x >= p.width || y >= p.height) { return; }
    let idx = u32(y * p.width + x);
    let m = coverage(idx);
    let painted = unpack4x8unorm(layer_px[idx]);
    let before = unpack4x8unorm(shadow_px[idx]);
    var out: vec4<f32>;
    if (p.feather == 0u) {
        if (m >= 1.0) { return; }
        out = mix(before, painted, m);
    } else {
        let soft = painted * m;
        out = soft + before * (1.0 - soft.a);
    }
    layer_px[idx] = pack4x8unorm(out);
}
