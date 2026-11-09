#!/usr/bin/env bash
# Fails when a test is switched off (@Ignore / @Disabled) without a linked issue on the same line.
#
# A disabled test is a finding someone decided to live with; without a reference it is also a
# finding nobody can find again. Accepted references on the annotation's own line: `#123`,
# `.../issues/123` or `ABC-123`. Prose in a comment that merely mentions the annotation is not
# matched (only lines that start with the annotation are).
#
# Usage: check-ignored-tests.sh [repository-root]
set -euo pipefail

root="${1:-.}"
cd "$root"

ANNOTATION='^[[:space:]]*@(org\.junit(\.jupiter\.api)?\.)?(Ignore|Disabled)([^[:alnum:]_]|$)'
REFERENCE='(#[0-9]+|/issues/[0-9]+|\b[A-Z][A-Z0-9]+-[0-9]+\b)'

bad=0
while IFS= read -r hit; do
  [ -n "$hit" ] || continue
  line="${hit#*:*:}"
  if ! printf '%s' "$line" | grep -Eq "$REFERENCE"; then
    echo "FAIL  ${hit%%:*}:$(printf '%s' "$hit" | cut -d: -f2): disabled test with no issue reference (#123, .../issues/123 or ABC-123 on the same line)" >&2
    echo "      $line" >&2
    bad=$((bad + 1))
  fi
done < <(git grep -nE "$ANNOTATION" -- '*.kt' '*.java' || true)

if [ "$bad" -ne 0 ]; then
  echo "$bad disabled test(s) without a linked issue." >&2
  exit 1
fi
echo "Every disabled test links an issue."
