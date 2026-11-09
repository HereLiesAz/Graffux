#!/usr/bin/env bash
# Builds tiny shared objects with 4 KB and 16 KB LOAD alignment and checks the verdicts.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cd "$tmp"
echo 'int answer(void) { return 42; }' > a.c
cc -shared -fPIC -Wl,-z,max-page-size=4096 -Wl,-z,common-page-size=4096 -o small.so a.c
cc -shared -fPIC -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -o big.so a.c

mkdir -p good/lib/arm64-v8a bad/lib/arm64-v8a bad32/lib/armeabi-v7a third32/lib/armeabi-v7a
cp big.so good/lib/arm64-v8a/libgraffux_wgpu.so
cp small.so bad/lib/arm64-v8a/libgraffux_wgpu.so
cp small.so bad32/lib/armeabi-v7a/libgraffux_wgpu.so       # ours, 32-bit: still checked
cp small.so third32/lib/armeabi-v7a/libthirdparty.so       # theirs, 32-bit: not checked
cp big.so third32/lib/arm64-v8a/libok.so 2>/dev/null || { mkdir -p third32/lib/arm64-v8a; cp big.so third32/lib/arm64-v8a/libok.so; }

expect_pass() { "$here/check-elf-alignment.sh" "$@" >/dev/null || { echo "expected PASS: $*" >&2; exit 1; }; }
expect_fail() { if "$here/check-elf-alignment.sh" "$@" >/dev/null 2>&1; then echo "expected FAIL: $*" >&2; exit 1; fi; }

expect_pass good
expect_fail bad
expect_fail bad32
expect_pass third32
expect_pass big.so good/lib/arm64-v8a/libgraffux_wgpu.so
expect_fail small.so
(cd good && zip -q -r ../good.apk lib) && (cd bad && zip -q -r ../bad.apk lib)
expect_pass good.apk
expect_fail bad.apk
mkdir empty && expect_fail empty          # nothing to check is a failure, not a pass
echo "check-elf-alignment tests passed"
