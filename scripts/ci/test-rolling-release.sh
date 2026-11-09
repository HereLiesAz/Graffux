#!/usr/bin/env bash
# Runs rolling-release.sh against a fake `gh` and checks the ORDER of the calls.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/bin"
cat > "$tmp/bin/gh" <<'FAKE'
#!/usr/bin/env bash
echo "gh $*" >> "$GH_LOG"
case "$1 $2" in
  "release view")
    if printf '%s ' "$@" | grep -q -- '--json'; then printf '%s\n' $STALE_ASSETS; exit 0; fi
    [ "${RELEASE_EXISTS:-1}" = 1 ] ;;
  "release upload") [ "${UPLOAD_FAILS:-0}" = 0 ] ;;
  "api repos/o/r/git/ref/tags/t") [ "${TAG_EXISTS:-1}" = 1 ] ;;
esac
FAKE
chmod +x "$tmp/bin/gh"
echo apk > "$tmp/App-1.2.3.4-debug.apk"
run() {
  : > "$tmp/log"
  PATH="$tmp/bin:$PATH" GH_LOG="$tmp/log" GITHUB_REPOSITORY=o/r \
    bash "$here/rolling-release.sh" t abc123 "$tmp/App-1.2.3.4-debug.apk" "Title" "Notes" >/dev/null 2>&1
}
line() { grep -n -- "$1" "$tmp/log" | head -1 | cut -d: -f1; }

# Existing release: upload, then move the tag, then edit text.
STALE_ASSETS="App-1.2.3.3-debug.apk build-tools.zip App-1.2.3.4-debug.apk" run
up=$(line 'release upload'); mv_=$(line 'PATCH'); ed=$(line 'release edit')
if ! { [ -n "$up" ] && [ -n "$mv_" ] && [ -n "$ed" ] && [ "$up" -lt "$mv_" ] && [ "$mv_" -lt "$ed" ]; }; then
  cat "$tmp/log"; echo "order must be upload < move tag < edit" >&2; exit 1
fi
# A draft left behind by an earlier failed run must be published even though the release already exists.
grep -q 'release edit t .*--draft=false' "$tmp/log" || { cat "$tmp/log"; echo "an existing (draft) release must be published" >&2; exit 1; }
grep -q 'delete-asset t App-1.2.3.3-debug.apk' "$tmp/log" || { echo "stale APK must be deleted" >&2; exit 1; }
grep -q 'delete-asset t build-tools.zip' "$tmp/log" || { echo "retired build-tools.zip must be deleted" >&2; exit 1; }
if grep -q 'delete-asset t App-1.2.3.4-debug.apk' "$tmp/log"; then echo "the new APK must not be deleted" >&2; exit 1; fi
if grep -q 'git push\|git tag' "$tmp/log"; then echo "no git push: the tag moves through the refs API" >&2; exit 1; fi

# A failed upload must not touch the tag or the release text.
if UPLOAD_FAILS=1 STALE_ASSETS="" run; then echo "failed upload must fail the script" >&2; exit 1; fi
if grep -q 'PATCH\|POST\|release edit' "$tmp/log"; then cat "$tmp/log"; echo "nothing may move after a failed upload" >&2; exit 1; fi

# New release: draft with the asset first, tag after, publish last.
RELEASE_EXISTS=0 TAG_EXISTS=0 STALE_ASSETS="" run
cr=$(line 'release create'); po=$(line 'POST'); pub=$(line 'draft=false')
if ! { [ -n "$cr" ] && [ -n "$po" ] && [ -n "$pub" ] && [ "$cr" -lt "$po" ] && [ "$po" -lt "$pub" ]; }; then
  cat "$tmp/log"; echo "new release: create(draft) < create tag < publish" >&2; exit 1
fi
grep -q -- '--draft' "$tmp/log"
echo "rolling-release tests passed"
