#!/usr/bin/env bash
# Pixel-for-pixel comparison of the GPU stamp backends on the host (no device needed):
# VulkanStampEngine on Mesa lavapipe, GlesStampEngine on Mesa llvmpipe, and the wgpu engine
# (core/wgpu-engine, through the same WgpuStampEngine adapter Android uses) on both wgpu backends:
# Vulkan/lavapipe and GL/llvmpipe. Same scripted inputs for all of them.
# Needs: g++, glslangValidator, cargo, python3 (+numpy for the paint-height reference),
#        libvulkan-dev mesa-vulkan-drivers libegl-dev libgles-dev.
set -euo pipefail
cd "$(dirname "$0")"
C=../../core/nativebridge/src/main/cpp
mkdir -p gen out_vk out_gl out_wgpu_vk out_wgpu_gl out_gl_ph out_wgpu_ph_vk out_wgpu_ph_gl out_wgpu_mp_vk out_wgpu_mp_gl
spv() {  # source, header, tile, symbol -- same layout CMake's graffux_embed_shader writes
  glslangValidator -V --target-env vulkan1.1 -S comp -DTILE_SIZE="$3" -o "gen/$2.spv" "$C/shaders/$1" >/dev/null
  python3 - "$2" "$4" <<'PY'
import struct, sys
name, sym = sys.argv[1], sys.argv[2]
d = open(f"gen/{name}.spv", "rb").read(); w = struct.unpack(f"<{len(d)//4}I", d)
open(f"gen/{name}", "w").write("#pragma once\n#include <cstdint>\n#include <cstddef>\nnamespace graffux {\ninline constexpr uint32_t %s[] = {%s};\ninline constexpr size_t %sWords = sizeof(%s) / sizeof(%s[0]);\n}\n" % (sym, ",".join(hex(x) for x in w), sym, sym, sym))
PY
}
spv stamp.comp StampSpv.h 16 kStampCompSpv; spv stamp.comp StampSpv8.h 8 kStampComp8Spv
spv stamp_masked.comp StampMaskedSpv.h 16 kStampMaskedCompSpv; spv stamp_masked.comp StampMaskedSpv8.h 8 kStampMaskedComp8Spv
spv color_smudge.comp ColorSmudge8Spv.h 8 kColorSmudge8CompSpv; spv color_smudge.comp ColorSmudge16Spv.h 16 kColorSmudge16CompSpv
python3 - <<'PY'
d = "../../core/nativebridge/src/main/cpp/shaders/gles/"
out = "#pragma once\nnamespace graffux {\n"
for f, sym in [("stamp.comp", "kStampCompSrc"), ("stamp_masked.comp", "kStampMaskedCompSrc"), ("color_smudge.comp", "kColorSmudgeCompSrc")]:
    out += f'inline constexpr const char* {sym} = R"GLSL({open(d + f).read()})GLSL";\n'
open("gen/GlesShaders.h", "w").write(out + "}\n")
PY
# The Vulkan clear() lives beside a JNI entry point; compile just the engine half.
sed -n '1,/^}  \/\/ namespace graffux/p' "$C/VulkanStampEngineReuse.cpp" | sed 's/#include <jni.h>//' > gen/reuse.cpp
g++ -std=c++17 -O2 -I shim -I gen -I "$C" run_vk.cpp "$C/VulkanStampEngine.cpp" "$C/VulkanColorSmudge.cpp" -x c++ gen/reuse.cpp -lvulkan -o gen/run_vk
g++ -std=c++17 -O2 -I gen -I "$C" run_gl.cpp "$C/GlesStampEngine.cpp" -lEGL -lGLESv2 -o gen/run_gl
g++ -std=c++17 -O2 -DWITH_PAINT_HEIGHT -DOUTDIR='"out_gl_ph"' -I gen -I "$C" run_gl.cpp "$C/GlesStampEngine.cpp" -lEGL -lGLESv2 -o gen/run_gl_ph
cargo build --release --quiet --manifest-path ../../core/wgpu-engine/Cargo.toml
WGPU_LIB="$(cd ../../core/wgpu-engine && pwd)/target/release/libgraffux_wgpu.so"
for v in vk gl; do
  g++ -std=c++17 -O2 -DOUTDIR="\"out_wgpu_$v\"" -I shim -I "$C" run_wgpu.cpp "$C/WgpuStampEngine.cpp" -ldl -o "gen/run_wgpu_$v"
done
for v in vk gl; do
  g++ -std=c++17 -O2 -DWITH_PAINT_HEIGHT -DOUTDIR="\"out_wgpu_ph_$v\"" -I shim -I "$C" run_wgpu.cpp "$C/WgpuStampEngine.cpp" -ldl -o "gen/run_wgpu_ph_$v"
done
# Multipass rendering on (drafts now, final layer refined in small budgets): must equal the plain run.
for v in vk gl; do
  g++ -std=c++17 -O2 -DMULTIPASS -DOUTDIR="\"out_wgpu_mp_$v\"" -I shim -I "$C" run_wgpu.cpp "$C/WgpuStampEngine.cpp" -ldl -o "gen/run_wgpu_mp_$v"
done
# Resident layers: multi-stroke sequences with undos, full upload vs resident bind/refresh.
g++ -std=c++17 -O2 -I shim -I "$C" run_wgpu_resident.cpp "$C/WgpuStampEngine.cpp" -ldl -o gen/run_wgpu_resident
export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json EGL_PLATFORM=surfaceless GRAFFUX_WGPU_LIB="$WGPU_LIB"
gen/run_vk
gen/run_gl
gen/run_gl_ph
WGPU_BACKEND=vulkan gen/run_wgpu_vk
WGPU_BACKEND=gl gen/run_wgpu_gl
WGPU_BACKEND=vulkan gen/run_wgpu_ph_vk
WGPU_BACKEND=gl gen/run_wgpu_ph_gl
WGPU_BACKEND=vulkan gen/run_wgpu_mp_vk
WGPU_BACKEND=gl gen/run_wgpu_mp_gl
echo "resident layers vs full upload (wgpu Vulkan):"; WGPU_BACKEND=vulkan gen/run_wgpu_resident
echo "resident layers vs full upload (wgpu GL):"; WGPU_BACKEND=gl gen/run_wgpu_resident
python3 - <<'PY'
import os
def cmp(a, b, f):
    x = open(f"{a}/{f}", "rb").read(); y = open(f"{b}/{f}", "rb").read()
    d = [abs(p - q) for p, q in zip(x, y)]
    return sum(1 for v in d if v), max(d), len(x)
pairs = [("out_vk", "out_gl", "vk-gl"), ("out_wgpu_vk", "out_vk", "wgpu(vk)-vk"),
         ("out_wgpu_gl", "out_gl", "wgpu(gl)-gl"), ("out_wgpu_vk", "out_wgpu_gl", "wgpu vk-gl")]
print(f"{'scenario':28s}" + "".join(f"{label:>18s}" for _, _, label in pairs) + "   (bytes differ / max level)")
worst = {label: 0 for _, _, label in pairs}
for f in sorted(os.listdir("out_vk")):
    row = f"{f[:-4]:28s}"
    for a, b, label in pairs:
        n, m, total = cmp(a, b, f)
        worst[label] = max(worst[label], m)
        row += f"{f'{n}/{m}':>18s}"
    print(row)
# The Vulkan engine cannot run the paint-height build on lavapipe (see README), so those outputs
# are compared against the GLES engine only.
print("paint-height build (-DWITH_PAINT_HEIGHT) vs GLES:")
for f in ("ph_out.raw", "s4_substrate.raw", "s4b_masked_substrate.raw"):
    for v in ("vk", "gl"):
        n, m, total = cmp(f"out_wgpu_ph_{v}", "out_gl_ph", f)
        worst[f"wgpu({v})-gl paint height"] = max(worst.get(f"wgpu({v})-gl paint height", 0), m)
        print(f"  {f[:-4]:24s} wgpu({v}) {n:6d}/{total}  max {m}")
print("multipass on vs off (wgpu, through the C++ adapter; must be 0):")
for v in ("vk", "gl"):
    total_diff = 0
    for f in sorted(os.listdir(f"out_wgpu_{v}")):
        n, m, total = cmp(f"out_wgpu_mp_{v}", f"out_wgpu_{v}", f)
        total_diff += n
        if n:
            print(f"  {f[:-4]:24s} wgpu({v}) {n}/{total} max {m}")
    print(f"  wgpu({v}): {total_diff} bytes differ across all scenarios")
    worst[f"multipass-plain wgpu({v})"] = total_diff
print("worst per pair:", worst)
PY
