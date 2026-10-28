// The Detail tab's blurred luma (core/wgpu-engine/src/adjust.rs): the source's luma, box-blurred
// separably at two radii, then (mode 3) the Outline effect's Sobel gradients of the small plane.
// Runs once per source upload, never per slider move.

struct BlurParams {
    width: u32,
    height: u32,
    radius: u32,
    // 0: luma of `src` into work[0..n]; 1: horizontal, work[in..] -> work[out..];
    // 2: vertical, work[in..] -> blur[out..]; 3: edge gradients of blur[0..n] -> blur[out..].
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
    if (b.mode == 3u) {
        blurred[b.out_offset + i] = edge_code(i32(id.x), i32(id.y));
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

fn small_at(x: i32, y: i32) -> f32 {
    let cx = clamp(x, 0, i32(b.width) - 1);
    let cy = clamp(y, 0, i32(b.height) - 1);
    return blurred[u32(cy) * b.width + u32(cx)];
}

// The Sobel gradient of the small blur plane at (x, y), as direction + magnitude: the integer part
// is the direction quantised to 0 (horizontal), 1 (down-right diagonal), 2 (vertical) or
// 3 (down-left diagonal); the fraction is the magnitude scaled so a full black-to-white step reads
// 1 (an 8 / (2r + 1) Sobel response), capped below 1. adjust.wgsl's `edge_inline` and
// PhotoAdjustMath.edgeCode are the same function.
fn edge_code(x: i32, y: i32) -> f32 {
    let a = small_at(x - 1, y - 1);
    let bb = small_at(x, y - 1);
    let c = small_at(x + 1, y - 1);
    let d = small_at(x - 1, y);
    let f = small_at(x + 1, y);
    let g = small_at(x - 1, y + 1);
    let h = small_at(x, y + 1);
    let k = small_at(x + 1, y + 1);
    let gx = (c + 2.0 * f + k) - (a + 2.0 * d + g);
    let gy = (g + 2.0 * h + k) - (a + 2.0 * bb + c);
    let mag = min(sqrt(gx * gx + gy * gy) * f32(2u * b.radius + 1u) / 8.0, 0.999);
    let ax = abs(gx);
    let ay = abs(gy);
    var dir = 0.0;
    if (ay <= ax * 0.41421356) {
        dir = 0.0;
    } else if (ax <= ay * 0.41421356) {
        dir = 2.0;
    } else if ((gx > 0.0) == (gy > 0.0)) {
        dir = 1.0;
    } else {
        dir = 3.0;
    }
    return dir + mag;
}
