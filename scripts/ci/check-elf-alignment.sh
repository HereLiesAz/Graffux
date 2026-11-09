#!/usr/bin/env bash
# Fails when a native library in the given APK/AAB/.so/directory is not 16 KB page-size aligned.
#
# Google Play requires apps that target Android 15+ to run on 16 KB-page devices. A shared object
# is compatible when every PT_LOAD segment is aligned to at least 0x4000. (A library can be
# 4 KB-aligned with no build error and still load fine on today's phones, so nothing else catches it.)
#
# Usage: check-elf-alignment.sh <apk|aab|so|dir>...
#
# Policy: every library in a 64-bit ABI (arm64-v8a, x86_64) must be aligned: only 64-bit devices
# have 16 KB pages. A library of ours (libgraffux*, libgraffitixr*) must be aligned in EVERY ABI, so
# a missing linker flag on the 32-bit build cannot hide. Third-party 32-bit libraries are not checked.
set -euo pipefail

MIN=$((0x4000))
FIRST_PARTY_RE='^lib(graffux|graffitixr)'
failures=0
checked=0

command -v readelf >/dev/null || { echo "check-elf-alignment: readelf is required" >&2; exit 2; }
[ "$#" -gt 0 ] || { echo "usage: $0 <apk|aab|so|dir>..." >&2; exit 2; }

check_so() {
  local file="$1" label="$2" abi="$3" base
  base="$(basename "$file")"
  case "$abi" in
    arm64-v8a|x86_64) ;;
    *) [[ "$base" =~ $FIRST_PARTY_RE ]] || return 0 ;;
  esac
  local aligns
  aligns="$(readelf -lW "$file" | awk '$1 == "LOAD" { print $NF }')"
  if [ -z "$aligns" ]; then
    echo "FAIL  $label: no LOAD segments (not an ELF shared object?)" >&2
    failures=$((failures + 1))
    return 0
  fi
  checked=$((checked + 1))
  local min_seen="" a
  for a in $aligns; do
    if [ -z "$min_seen" ] || [ "$((a))" -lt "$min_seen" ]; then min_seen=$((a)); fi
  done
  if [ "$min_seen" -lt "$MIN" ]; then
    printf 'FAIL  %s: LOAD alignment 0x%x < 0x%x\n' "$label" "$min_seen" "$MIN" >&2
    failures=$((failures + 1))
  else
    printf 'ok    %s: LOAD alignment 0x%x\n' "$label" "$min_seen"
  fi
}

check_tree() {
  local root="$1" prefix="$2" so abi
  while IFS= read -r so; do
    abi="$(basename "$(dirname "$so")")"
    check_so "$so" "$prefix${so#"$root"/}" "$abi"
  done < <(find "$root" -type f -name '*.so' | sort)
}

for input in "$@"; do
  if [ -d "$input" ]; then
    check_tree "$input" ""
  elif [[ "$input" == *.apk || "$input" == *.aab ]]; then
    tmp="$(mktemp -d)"
    unzip -q -o "$input" '*.so' -d "$tmp" 2>/dev/null || true
    check_tree "$tmp" "$(basename "$input"):"
    rm -rf "$tmp"
  elif [ -f "$input" ]; then
    check_so "$input" "$input" arm64-v8a   # an explicitly named library is always checked
  else
    echo "check-elf-alignment: no such file or directory: $input" >&2
    exit 2
  fi
done

if [ "$checked" -eq 0 ]; then
  echo "FAIL  no native libraries were found to check in: $*" >&2
  exit 1
fi
if [ "$failures" -ne 0 ]; then
  echo "$failures native librar$([ "$failures" -eq 1 ] && echo y || echo ies) are not 16 KB page-size aligned." >&2
  exit 1
fi
echo "All $checked checked native libraries are 16 KB aligned."
