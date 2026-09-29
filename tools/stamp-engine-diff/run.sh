#!/usr/bin/env bash
# Pixel-for-pixel checks of the wgpu stamp engine on the host (no device needed): core/wgpu-engine,
# through the same WgpuStampEngine adapter Android uses, on both wgpu backends (Vulkan/lavapipe and
# GL/llvmpipe), with the same scripted inputs. The Vulkan and GLES engines this used to compare
# against were retired (docs/Native Rendering Engine Design.md, "Retired backends").
# Needs: g++, cargo, python3 (+numpy for the paint-height reference),
#        mesa-vulkan-drivers libegl-dev libgles-dev.
set -euo pipefail
cd "$(dirname "$0")"
C=../../core/nativebridge/src/main/cpp
mkdir -p gen out_wgpu_vk out_wgpu_gl out_wgpu_ph_vk out_wgpu_ph_gl out_wgpu_mp_vk out_wgpu_mp_gl out_wgpu_dd_vk out_wgpu_dd_gl
cargo build --release --quiet --manifest-path ../../core/wgpu-engine/Cargo.toml
WGPU_LIB="$(cd ../../core/wgpu-engine && pwd)/target/release/libgraffux_wgpu.so"
build() {  # output name, extra flags...
  local out="$1"; shift
  g++ -std=c++17 -O2 "$@" -I "$C" stamp_tuning.cpp run_wgpu.cpp "$C/WgpuStampEngine.cpp" -ldl -o "gen/$out"
}
for v in vk gl; do
  build "run_wgpu_$v" -DOUTDIR="\"out_wgpu_$v\""
  build "run_wgpu_ph_$v" -DWITH_PAINT_HEIGHT -DOUTDIR="\"out_wgpu_ph_$v\""
  # Multipass rendering on (drafts now, final layer refined in small budgets): must equal the plain run.
  build "run_wgpu_mp_$v" -DMULTIPASS -DOUTDIR="\"out_wgpu_mp_$v\""
  # Direct display entry points (inert without a window on a host): must equal the plain run.
  build "run_wgpu_dd_$v" -DDIRECT -DOUTDIR="\"out_wgpu_dd_$v\""
done
# Resident layers: multi-stroke sequences with undos, full upload vs resident bind/refresh.
g++ -std=c++17 -O2 -I "$C" stamp_tuning.cpp run_wgpu_resident.cpp "$C/WgpuStampEngine.cpp" -ldl -o gen/run_wgpu_resident
export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json EGL_PLATFORM=surfaceless GRAFFUX_WGPU_LIB="$WGPU_LIB"
for v in vk gl; do
  backend=$([ "$v" = vk ] && echo vulkan || echo gl)
  for kind in "" _ph _mp _dd; do WGPU_BACKEND=$backend "gen/run_wgpu${kind}_$v"; done
done
echo "resident layers vs full upload (wgpu Vulkan):"; WGPU_BACKEND=vulkan gen/run_wgpu_resident
echo "resident layers vs full upload (wgpu GL):"; WGPU_BACKEND=gl gen/run_wgpu_resident
echo "paint-height build vs the NumPy reference:"
python3 paint_height_reference.py out_wgpu_ph_vk
python3 paint_height_reference.py out_wgpu_ph_gl
python3 - <<'PY'
import os
def cmp(a, b, f):
    x = open(f"{a}/{f}", "rb").read(); y = open(f"{b}/{f}", "rb").read()
    d = [abs(p - q) for p, q in zip(x, y)]
    return sum(1 for v in d if v), max(d), len(x)
worst = {}
print(f"{'scenario':28s}{'wgpu vk-gl':>18s}   (bytes differ / max level)")
for f in sorted(os.listdir("out_wgpu_vk")):
    n, m, total = cmp("out_wgpu_vk", "out_wgpu_gl", f)
    worst["wgpu vk-gl"] = max(worst.get("wgpu vk-gl", 0), m)
    print(f"{f[:-4]:28s}{f'{n}/{m}':>18s}")
for label, prefix in (("multipass", "out_wgpu_mp"), ("direct display entry points", "out_wgpu_dd")):
    print(f"{label} vs plain (wgpu, through the C++ adapter; must be 0):")
    for v in ("vk", "gl"):
        total_diff = 0
        for f in sorted(os.listdir(f"out_wgpu_{v}")):
            n, m, total = cmp(f"{prefix}_{v}", f"out_wgpu_{v}", f)
            total_diff += n
            if n:
                print(f"  {f[:-4]:24s} wgpu({v}) {n}/{total} max {m}")
        print(f"  wgpu({v}): {total_diff} bytes differ across all scenarios")
        worst[f"{label}-plain wgpu({v})"] = total_diff
print("worst per pair:", worst)
PY
