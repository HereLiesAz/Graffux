#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cd "$tmp"
git init -q .
git config user.email t@example.com
git config user.name t
mkdir src
run() { git add -A >/dev/null; "$here/check-ignored-tests.sh" . >/dev/null 2>&1; }

printf '%s\n' 'class A { @Test fun ok() {} }' > src/A.kt
run || { echo "clean tree must pass" >&2; exit 1; }

printf '%s\n' '    // Remove @Ignore when fixed' '    @Test fun ok() {}' > src/B.kt
run || { echo "prose mentioning @Ignore must not match" >&2; exit 1; }

printf '%s\n' '    @Ignore("finding: palette load drops capacity")' '    @Test fun x() {}' > src/C.kt
run && { echo "an @Ignore without an issue must fail" >&2; exit 1; }

printf '%s\n' '    @Ignore("finding: palette load drops capacity, see #412")' > src/C.kt
run || { echo "#412 reference must pass" >&2; exit 1; }
printf '%s\n' '    @Ignore("https://github.com/HereLiesAz/Graffux/issues/77")' > src/C.kt
run || { echo "issue URL must pass" >&2; exit 1; }
printf '%s\n' '    @org.junit.Ignore("flaky")' > src/C.kt
run && { echo "qualified @Ignore without reference must fail" >&2; exit 1; }
printf '%s\n' '    @Disabled("GFX-12 flaky on CI")' > src/C.kt
run || { echo "ABC-123 reference must pass" >&2; exit 1; }
printf '%s\n' '    @Disabled' > src/C.kt
run && { echo "bare @Disabled must fail" >&2; exit 1; }
echo "check-ignored-tests tests passed"
