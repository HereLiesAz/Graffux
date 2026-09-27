"""Mechanically ports a Vulkan GLSL 450 compute shader to GLSL ES 3.10 for GpuStampEngine.

rgba8 image2D read+write is not legal in ES 3.1 (only r32 formats may be both loaded and stored),
so the layer becomes an SSBO of packed RGBA8 words (unpack/packUnorm4x8 == rgba8 image unorm
conversion). Push constants become a std140 UBO at binding 0 with the layer size appended.
"""
# Usage (from shaders/): python3 gles/port_from_vulkan.py stamp.comp gles/stamp.comp 1
#                        python3 gles/port_from_vulkan.py stamp_masked.comp gles/stamp_masked.comp 1
#                        python3 gles/port_from_vulkan.py color_smudge.comp gles/color_smudge.comp 3
# (the last argument is the layer SSBO binding; it must not collide with the shader's own SSBOs).
import re, sys
src, dst, layer_binding = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(src).read()
s = s.replace("#version 450", "#version 310 es\n// GENERATED from ../" + src.split("/")[-1] + " by port_from_vulkan.py -- edit the Vulkan source and re-run it,\n// so the two backends can never drift apart. GlesStampEngine inserts TILE_SIZE after line 1.\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;", 1)
s, n = re.subn(r"layout\(binding = \d+, rgba8\) uniform image2D layerImage;",
    f"// Layer pixels: one packed RGBA8 word per pixel, row-major (premultiplied, byte order R,G,B,A --\n"
    f"// identical to android.graphics.Bitmap ARGB_8888 memory). ES 3.1 forbids load+store on an rgba8\n"
    f"// image, so this SSBO + unpack/packUnorm4x8 stands in for it with the same unorm conversion.\n"
    f"layout(std430, binding = {layer_binding}) buffer LayerBuffer {{ uint layerPx[]; }};", s)
assert n == 1, "layer image decl"
s, n = re.subn(r"layout\(push_constant\) uniform (\w+) \{", r"layout(std140, binding = 0) uniform \1 {", s)
assert n == 1, "push constant block"
helpers = """    int layerWidth;
    int layerHeight;
} pc;

ivec2 layerSize() { return ivec2(pc.layerWidth, pc.layerHeight); }
vec4 layerLoad(ivec2 p) { return unpackUnorm4x8(layerPx[p.y * pc.layerWidth + p.x]); }
void layerStore(ivec2 p, vec4 c) { layerPx[p.y * pc.layerWidth + p.x] = packUnorm4x8(c); }
"""
s, n = re.subn(r"\n\} pc;\n", "\n" + helpers, s, count=1)
assert n == 1, "pc end"
s = re.sub(r"imageLoad\(layerImage, ", "layerLoad(", s)
s = re.sub(r"imageStore\(layerImage, ", "layerStore(", s)
s = s.replace("imageSize(layerImage)", "layerSize()")
s = s.replace("// layerImage stores", "// The layer stores"); assert "layerImage" not in s, "leftover layerImage"
# % on negative ints is undefined in GLSL; wrap via floor division instead.
s, n = re.subn(r"cell\.(x|y) = \(\(cell\.\1 % size\.\1\) \+ size\.\1\) % size\.\1;",
               r"cell.\1 = cell.\1 - size.\1 * int(floor(float(cell.\1) / float(size.\1)));", s)
# ES has no implicit int->uint conversion.
s = re.sub(r"(gl_GlobalInvocationID\.[xy]) (!=|==) 0\b", r"\1 \2 0u", s)
open(dst, "w").write(s)
print(dst, "ok", "wrapfix", n)
