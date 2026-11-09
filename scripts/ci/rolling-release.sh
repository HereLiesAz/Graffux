#!/usr/bin/env bash
# Publishes the rolling debug release: one prerelease per major.minor whose tag always points at the
# commit that built its APK.
#
# Usage: rolling-release.sh <tag> <sha> <apk-file> <title> <notes>
# Env:   the GitHub CLI's token variable, GITHUB_REPOSITORY (owner/name). Needs `gh` and a token with
#        contents: write.
#
# Order matters, because a half-finished run must never leave the tag ahead of the asset:
#   1. upload the APK (the tag still points at the previous build);
#   2. only after the upload succeeded, move the tag (created if missing) to <sha>;
#   3. then update the release text, publish it if it was created as a draft, and delete stale
#      assets (older *-debug.apk files and the retired build-tools.zip).
# A failed upload therefore changes nothing a downloader can see. The tag moves through the Git
# refs API, so this script needs no checkout, no git credentials and no source code on disk.
set -euo pipefail

tag="${1:?tag}" sha="${2:?sha}" apk="${3:?apk}" title="${4:?title}" notes="${5:?notes}"
repo="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY}"
[ -s "$apk" ] || { echo "rolling-release: $apk is missing or empty" >&2; exit 1; }
asset="$(basename "$apk")"

move_tag() {
  if gh api "repos/$repo/git/ref/tags/$tag" >/dev/null 2>&1; then
    gh api --method PATCH "repos/$repo/git/refs/tags/$tag" -f sha="$sha" -F force=true >/dev/null
  else
    gh api --method POST "repos/$repo/git/refs" -f ref="refs/tags/$tag" -f sha="$sha" >/dev/null
  fi
}

if gh release view "$tag" --repo "$repo" >/dev/null 2>&1; then
  # --clobber: a re-run of the same commit replaces its APK instead of failing.
  gh release upload "$tag" "$apk" --repo "$repo" --clobber
else
  # A draft needs no tag yet, so the asset lands before anything points anywhere.
  gh release create "$tag" "$apk" --repo "$repo" --draft --prerelease --target "$sha" --title "$title" --notes "$notes"
fi

move_tag

# Always clear the draft flag, not only for a draft this run created: a previous run may have created
# the draft and then failed before publishing it, and every later run finds an existing release.
# Publishing an already-published release is a no-op.
gh release edit "$tag" --repo "$repo" --draft=false --prerelease --title "$title" --notes "$notes"

# Housekeeping runs last and never fails the release: the new build is already published.
while IFS= read -r stale; do
  [ -n "$stale" ] || continue
  gh release delete-asset "$tag" "$stale" --repo "$repo" -y || echo "::warning::could not delete stale asset $stale" >&2
done < <(gh release view "$tag" --repo "$repo" --json assets --jq '.assets[].name' 2>/dev/null \
  | grep -E -- '(-debug\.apk|^build-tools\.zip)$' | grep -Fvx -- "$asset" || true)
echo "Published $asset at $tag ($sha)."
