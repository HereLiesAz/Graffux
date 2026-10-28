// The Detail tab's blurred luma (core/wgpu-engine/src/adjust.rs): the source's luma, box-blurred
// separably at two radii. Runs once per source upload, never per slider move.

struct BlurParams {
    width: u32,
    height: u32,
    radius: u32,
    // 0: luma of `src` into work[0..n]; 1: horizontal, work[in..] -> work[out..];
    // 2: vertical, work[in..] -> blur[out..].
    mode: u32,
    in_offset: u32,
    out_offset: u32,
    _p0: u32,
    _p1: u32,
};

@group(0) @binding(0) var<uniform> b: BlurParams;
@group(0) @binding(1) var<storage, read> src: array<u32>;
@group(0) @binding(2) var<storage, read_write> work: array<f32>;
@group(0) @binding(3) var<storage, read_write> blurred: array<f32>;

fn luma_of(v: u32) -> f32 {
    let c = vec4<f32>(
        f32(v & 0xffu),
        f32((v >> 8u) & 0xffu),
        f32((v >> 16u) & 0xffu),
        f32((v >> 24u) & 0xffu),
    ) / 255.0;
    if (c.a <= 0.0) {
        return 0.0;
    }
    return dot(c.rgb / c.a, vec3<f32>(0.2126, 0.7152, 0.0722));
}

@compute @workgroup_size(8, 8, 1)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
    if (id.x >= b.width || id.y >= b.height) {
        return;
    }
    let i = id.y * b.width + id.x;
    if (b.mode == 0u) {
        work[i] = luma_of(src[i]);
        return;
    }
    let r = i32(b.radius);
    var sum = 0.0;
    var count = 0.0;
    for (var k = -r; k <= r; k = k + 1) {
        if (b.mode == 1u) {
            let x = i32(id.x) + k;
            if (x >= 0 && x < i32(b.width)) {
                sum = sum + work[b.in_offset + id.y * b.width + u32(x)];
                count = count + 1.0;
            }
        } else {
            let y = i32(id.y) + k;
            if (y >= 0 && y < i32(b.height)) {
                sum = sum + work[b.in_offset + u32(y) * b.width + id.x];
                count = count + 1.0;
            }
        }
    }
    let v = sum / max(count, 1.0);
    if (b.mode == 1u) {
        work[b.out_offset + i] = v;
    } else {
        blurred[b.out_offset + i] = v;
    }
}
