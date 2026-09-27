#!/usr/bin/env bash
# Pixel-for-pixel comparison of the two GPU stamp backends on the host (no device needed):
# VulkanStampEngine on Mesa lavapipe vs GlesStampEngine on Mesa llvmpipe, same scripted inputs.
# Needs: g++, glslangValidator, python3 (+numpy for the paint-height reference),
#        libvulkan-dev mesa-vulkan-drivers libegl-dev libgles-dev.
set -euo pipefail
cd "$(dirname "$0")"
C=../../core/nativebridge/src/main/cpp
mkdir -p gen out_vk out_gl
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
VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json gen/run_vk
EGL_PLATFORM=surfaceless gen/run_gl
python3 - <<'PY'
import os
for f in sorted(os.listdir("out_vk")):
    a = open("out_vk/" + f, "rb").read(); b = open("out_gl/" + f, "rb").read()
    diffs = [abs(x - y) for x, y in zip(a, b)]
    print(f"{f:32s} bytes differ {sum(1 for d in diffs if d):6d}/{len(a)}  max {max(diffs)}")
PY
